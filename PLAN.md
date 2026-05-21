# Build plan & progress

Read this first when resuming work. CLAUDE.md has the architecture; this file
has the phased plan and where we are in it.

## Design brief

Quarkus 3.35 / Java 25 MCP server wrapping Forgejo's REST API for read-only
context gathering. OAuth broker pattern (confidential client to Forgejo
upstream, AS to Claude downstream). CIMD-only client identification. Stateless
AES-GCM envelope tokens — no session store. Read-only tool scope (search,
browse, read).

See CLAUDE.md for the full architecture and `docs/auth-flow.md` for the
OAuth design rationale + sequence diagram.

## Phases

### Phase 0 — deps + config skeleton — DONE

- Fixed pom.xml (dropped `quarkus-oidc-client`, `quarkus-rest-client-oidc-filter`;
  added `quarkus-rest-jackson`, `quarkus-redis-client`, `quarkus-junit5`,
  `quarkus-junit5-mockito`).
- Added missing `.mvn/wrapper/maven-wrapper.properties` (Maven 3.9.9 + wrapper 3.3.2).
- `config/ForgejoConfig` and `config/BrokerConfig` (`@ConfigMapping`).
- `application.properties` with env-overridable defaults + `%test.` profile;
  Redis hosts scoped to `%prod.` so dev services activate in dev/test.
- Removed scaffold `GreetingResource`.
- `ConfigLoadingTest` (3 tests, all green).

### Phase 1 — OAuth broker — DONE (1.7 parked)

#### 1.0 Testcontainers setup + ForgejoTestResource — DONE

- testcontainers + junit-jupiter 1.20.4 added to pom.
- `testsupport/ForgejoTestResource` boots `codeberg.org/forgejo/forgejo:15`,
  bootstraps admin (`broker-admin`) + test user (`tester`) via
  `forgejo admin user create` (exec'd as `git` user via `ExecConfig`),
  registers the broker as a confidential OAuth app via Forgejo API,
  exposes `forgejo.base-url` + `oauth.client-id`/`secret` as Quarkus config
  overrides. Use `@WithTestResource` (scoped) so overrides don't leak.
- `ForgejoTestResourceTest` (4 tests, all green): config binds, /api/v1/version
  reachable, test user can authenticate, registered OAuth app present.
- Lessons: Forgejo container exits cleanly if env over-specified — minimal env
  (`USER_UID`, `USER_GID`, `INSTALL_LOCK`, `DISABLE_REGISTRATION`) just works.

#### 1.1 Stateless encrypted-envelope tokens — DONE (replaces Redis stores)

Originally implemented as Redis-backed stores (`PendingAuthStore`,
`AuthCodeStore`, `AccessTokenStore`, `RefreshTokenStore`). Replaced before
Phase 1.4 with stateless AES-256-GCM envelope tokens — see decision notes in
`docs/auth-flow.md` (planned after Phase 1.6).

- Records under `broker/model/`: `ForgejoTokens`, `ForgejoUser`, `PendingAuth`,
  `AuthCodeEntry`, `AccessTokenEntry`, `RefreshTokenEntry`. All four token
  envelopes implement `crypto/Expirable` and carry their own `expiresAt`.
- `broker/crypto/TokenCrypto` (`@ApplicationScoped`): JDK `AES/GCM/NoPadding`,
  256-bit key from `broker.token-encryption-key` (base64url). Token format is
  `<prefix><base64url(nonce(12) || ciphertext+tag(16))>`; prefix is bound as
  AAD so tokens of one kind don't cross-decode. Expiry is enforced after
  decrypt for `Expirable` payloads.
- Prefixes: `mcp_at_` (access), `mcp_rt_` (refresh), `mcp_ac_` (auth code),
  `mcp_pa_` (pending auth — used as Forgejo `state=`).
- Tradeoffs accepted (vs the Redis design): no server-side revocation, no
  single-use enforcement on codes / refresh tokens (TTL-bounded replay possible
  within the window). Suits the internal-network deployment model in README.
- `quarkus-redis-client` dependency removed; `%prod.quarkus.redis.hosts`
  removed from `application.properties`.
- `TokenCryptoTest` (7 tests, all green): round-trip, distinct ciphertexts
  per encode (nonce randomisation), prefix mismatch rejected, cross-prefix
  decode fails (AAD), tampered ciphertext rejected, expiry rejected, garbage
  body rejected.

#### 1.2 OAuth metadata + CIMD resolver — DONE

- Records: `AuthServerMetadata`, `ProtectedResourceMetadata`, `CimdDocument`
  (snake_case via `@JsonNaming`, `NON_ABSENT` includes, CIMD ignores unknown
  fields).
- Endpoints under `broker/endpoint/`:
  `/.well-known/oauth-authorization-server` advertising `code` response type,
  `authorization_code` + `refresh_token` grants, S256-only PKCE, `none`
  endpoint auth, scopes from config, CIMD support flag;
  `/.well-known/oauth-protected-resource/mcp` (RFC 9728) pointing at the AS.
- `broker/service/CimdResolver` + `CimdException`: fetches the CIMD JSON via
  JDK `HttpClient`, validates `client_id == URL`, requires non-empty
  `redirect_uris`, applies optional host allowlist before the network call,
  surfaces timeouts as `CimdException`.
- Tests: `MetadataEndpointsTest` (2), `CimdResolverTest` (6 — happy path +
  5 rejection paths via embedded JDK `HttpServer`), `CimdResolverAllowlistTest`
  (1 with `@TestProfile`). All green.

#### 1.2.5 Quality gate (`quality` Maven profile) — DONE

- `-Pquality` activates Error Prone (`2.49.0`), NullAway (`0.13.4`,
  `OnlyNullMarked` + `JSpecifyMode` so only `@NullMarked` packages are
  checked), and JaCoCo (`0.8.14`, 70% line coverage on `verify`).
- JSpecify (`1.0.0`) added as a runtime dep; `@NullMarked` package-info files
  in every leaf main package (`config`, `broker/{model,store,endpoint,service}`).
- Main compile fails on any compiler warning (`failOnWarning=true`); test
  compile keeps the standard relaxed config (EP + NullAway off, no
  failOnWarning) so test code stays free-form.
- `.sdkmanrc` pins Java 25 (25.0.3-tem) so the IDE's project SDK is consistent
  with what Maven needs for `--release 25`.
- Run via the `mvn forgejo [test-with-quality]` IntelliJ run config (goal:
  `verify`, profile: `quality`). Terminal-based `./mvnw -Pquality verify`
  works only if `JAVA_HOME` is Java 25 — the IDE terminal doesn't auto-pick
  the project SDK from `.sdkmanrc`.
- Current coverage: **0.34 / 0.70** — gate is intentionally red. Will recover
  as Phase 1.3+ adds tested broker logic.

#### 1.3 `/authorize` + `/oauth/callback` — NEXT

What needs to happen:

- `GET /authorize`: validate request (`response_type=code`, PKCE present,
  `redirect_uri` matches one in the resolved CIMD's redirect list, scope is
  subset of advertised), persist a `PendingAuth` keyed by a fresh
  `forgejoState` (random), 302 to Forgejo `/login/oauth/authorize` with our
  state, `client_id`, our `/oauth/callback` redirect URI, scope, etc.
- `GET /oauth/callback`: exchange Forgejo `code` for upstream tokens via
  `POST {forgejo}/login/oauth/access_token` (basic auth with our broker
  client_id/secret), fetch user via `GET /api/v1/user`, build a
  `ForgejoSession` worth of state, mint an `AuthCodeEntry` (single-use),
  302 back to Claude's `redirect_uri` with the broker auth code + Claude's
  echoed state.

Tests should drive these against the real Forgejo container — including
following the redirect to Forgejo and POSTing the consent form as the test
user.

#### 1.4 `/token` (auth_code + refresh_token grants) — DONE

- `POST /token` on `OAuthResource` handles both `authorization_code` and
  `refresh_token` grants. Form-encoded request, JSON `TokenResponse`.
- PKCE S256 verifier check via `MessageDigest.isEqual` (constant-time).
- Auth code grant: decode `mcp_ac_*`, match `client_id` + `redirect_uri`,
  verify PKCE, mint `mcp_at_*` + `mcp_rt_*` from the embedded Forgejo tokens
  (no upstream call needed — tokens were stashed at callback time).
- Refresh grant: decode `mcp_rt_*`, match `client_id`, call
  `ForgejoOAuthClient.refresh(...)` upstream, mint a fresh AT/RT pair.
- `TokenError` (sealed leaf of `ClientError`) added for RFC 6749 §5.2 JSON
  error responses; `BrokerExceptionMapper` renders it as 400 with
  `{error, error_description}`. Upstream refresh failure surfaces as
  `invalid_grant` so the client re-authorizes.
- Broker AT lifetime is capped by Forgejo's AT lifetime so a single decode
  covers the call (no mid-request refresh).
- `TokenEndpointTest` (10): happy path, missing/unknown grant_type, garbage
  code, wrong client_id, wrong redirect_uri, wrong code_verifier, expired
  code, refresh-grant missing token, refresh-grant garbage token. Refresh
  happy-path is deferred to Phase 1.6 where the full Forgejo dance gives us
  a real upstream refresh token.

#### 1.5 Bearer auth on `/mcp/*` via Quarkus `HttpAuthenticationMechanism` — DONE

- Switched from a hand-rolled `ContainerRequestFilter` to a Quarkus
  `HttpAuthenticationMechanism` (`broker/security/BearerAuthenticationMechanism`)
  so the standard `SecurityIdentity` plumbing handles principal injection,
  challenge response, and (later) `@RolesAllowed` on MCP tool methods. Added
  `quarkus-security` to the pom.
- Mechanism decodes `Authorization: Bearer mcp_at_...` via `TokenCrypto`
  (expiry enforced by the envelope), builds a `QuarkusSecurityIdentity` whose
  principal is the Forgejo login, whose roles are the granted OAuth scopes, and
  which carries the decoded `AccessTokenEntry` + embedded Forgejo bearer as
  attributes (`forgejo.accessTokenEntry`, `forgejo.bearer`). No
  `IdentityProvider` is needed because the identity is built directly from the
  decrypted envelope; `getCredentialTypes()` returns an empty set so Quarkus
  skips the IdP-existence check.
- Path scoping is done declaratively in `application.properties`:
  `quarkus.http.auth.permission.mcp.paths=/mcp,/mcp/*` +
  `policy=authenticated`. Everything else stays public; missing/garbage/expired
  bearers on `/mcp/*` get a 401 with a `WWW-Authenticate: Bearer` challenge
  pointing at the protected-resource-metadata document (RFC 9728 §5.1).
- `testsupport/McpProbeResource` (`GET /mcp/_probe/whoami`) returns the
  resolved Forgejo identity from `SecurityIdentity`, used by the filter test
  here and reusable by the Phase 1.6 end-to-end test.
- `BearerAuthMechanismTest` (7): missing Authorization, non-Bearer scheme,
  garbage envelope, wrong-prefix envelope (cross-decode blocked by AAD),
  expired token, fresh-token happy-path (identity propagates through to
  probe), non-`/mcp` paths remain public.

#### 1.6 End-to-end happy-path test — DONE

- `EndToEndOAuthFlowTest` drives the full dance against the real Forgejo
  container in one test: broker `/authorize` → Forgejo `/user/login` →
  consent → broker `/oauth/callback` → client `redirect_uri` (404'd locally,
  we just read the URL) → broker `/token` → `/mcp/_probe/whoami` with bearer
  → refresh-grant round-trip.
- Plain HTTP covers all server-to-server hops; **Playwright**
  (`io.quarkiverse.playwright:quarkus-playwright:2.1.1`, `@WithPlaywright` +
  `@InjectPlaywright Browser`) drives the only UI steps (login form + grant
  button) so we don't hand-roll CSRF scraping. Picks up the deferred
  `/oauth/callback` happy-path and refresh-grant happy-path from Phases 1.3
  and 1.4.
- Surfaced a real architectural bug while wiring this up: the broker was
  forwarding the client-requested scope verbatim to Forgejo, so a client that
  asked for `read:repository read:issue` got a Forgejo token without
  `read:user` — and `/api/v1/user` 403'd in the callback. **Fix:** broker now
  always injects `read:user` into the **upstream** Forgejo scope set
  (`LinkedHashSet` in `OAuthResource.authorize`) regardless of what the
  downstream client requested. The downstream scope (roles on the issued
  bearer, `scope` claim in the token response) still reflects only what was
  requested. Also improved diagnostics: `OAuthResource.oauthCallback` now
  logs the underlying `UpstreamFailure` before rendering the opaque
  `server_error` redirect, so prod ops can debug.
- One follow-on test touched: `AuthorizeEndpointTest.happyPath...` now
  asserts the upstream scope param is `read:repository read:issue read:user`
  (broker-injected) instead of the bare requested pair.
- **Note for CI**: Playwright downloads ~150 MB of Chromium/WebKit on first
  run (cached under `~/.cache/ms-playwright/`). On Fedora the browser
  validator warns about Ubuntu 20.04 libs (`libicudata.so.66`, etc.) being
  missing — these are warnings, not hard errors; the test runs fine on
  fallback WebKit. If a real CI failure traces back to this, install
  `compat-icu`/`libwebp`/`libjpeg-turbo`/`libffi`/`x264-libs` from rpmfusion.

#### 1.7 (parked) — native @QuarkusIntegrationTest path

Scaffolded and reverted in the same session. The `native` profile in pom.xml
(with `quarkus.native.container-build=true`) builds a native binary via the
Mandrel container in ~1 min, and `@QuarkusIntegrationTest`-annotated `*IT`
subclasses of the existing `*Test` classes drive failsafe against it. Blocker:
`AuthorizeEndpointTest` and `EndToEndOAuthFlowTest` start an in-process JDK
`HttpServer` in the test JVM to serve CIMD documents; the broker, now running
as a separate native process, can't consume that cleanly (broker fetches the
URL but receives non-JSON). Path forward when resuming: containerize the CIMD
service the same way `ForgejoTestResource` containerizes Forgejo, then re-add
the `*IT` subclasses + an `mvn forgejo [native-it]` run config. See memory
`project_native_it_blocked_on_cimd_testcontainer.md` for the full plan.

Kept from the parked attempt: the `native` profile config and the diagnostic
`Log.warnf` in `CimdResolver` showing status/content-type/body on parse
failure (useful regardless of IT).

### Phase 2 — Forgejo REST clients + per-request bearer producer

Typed REST clients for Forgejo (DTOs only for fields we use) + a
`ForgejoBearerProducer` that pulls the per-request token from the session.
Thin layer between MCP and Forgejo so tool code stays trivial.

### Phase 3 — MCP tools

In order: repo + code search + file read (the headline trio), then issues/PRs
with comments and diffs, then commits/releases.

### Phase 4 — Health + observability

`/q/health` (readiness check for Forgejo upstream — the broker itself is
stateless and needs no external store), structured logging, basic metrics.

### Phase 5 — Operator README

Forgejo OAuth-app registration steps, env vars, Docker deploy notes.

## Current test count

48/48 green as of Phase 1.5 done (`/oauth/callback` and refresh-grant
happy-paths still deferred to Phase 1.6).
- `ConfigLoadingTest`: 3
- `ForgejoTestResourceTest`: 4
- `TokenCryptoTest`: 7
- `MetadataEndpointsTest`: 2
- `AuthorizeEndpointTest`: 7
- `TokenEndpointTest`: 10
- `BearerAuthMechanismTest`: 7
- `CimdResolverTest`: 6
- `CimdResolverAllowlistTest`: 1
- `PackageLayoutTest`: 1

## TODO / cleanup

- Evaluate a Quarkus validation extension (Hibernate Validator /
  `quarkus-hibernate-validator`) for the OAuth endpoints. `OAuthResource`
  currently does a lot of hand-rolled field-level checks (null/blank, enum-like
  string matches on `response_type` / `grant_type` / `code_challenge_method`,
  presence + length on `code`, `code_verifier`, `client_id`, `redirect_uri`).
  Bean Validation annotations on the query/form params would shrink the methods
  and centralise the error rendering — but the OAuth spec wants different error
  shapes for different surfaces (`/authorize` → 302 redirect with
  `error=invalid_request`; `/token` → 400 JSON `{error, ...}`), so the
  `ConstraintViolationException` → domain-exception mapping needs designing
  before we adopt it. Decide before Phase 2.

## Open decisions / things to pin down later

- MCP endpoint path is currently the quarkus-mcp-server-http default (`/mcp`).
  If we want it configurable, plumb through `BrokerConfig` and update
  `ProtectedResourceMetadataResource.metadata()` accordingly.
- DCR is intentionally not implemented (CIMD only). If a non-Claude MCP host
  needs DCR later, add a `/register` endpoint and corresponding store.
