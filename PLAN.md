# Build plan & progress

Read this first when resuming work. CLAUDE.md has the architecture; this file
has the phased plan and where we are in it.

## Design brief

Quarkus 3.35 / Java 25 MCP server wrapping Forgejo's REST API for read-only
context gathering. OAuth broker pattern (confidential client to Forgejo
upstream, AS to Claude downstream). CIMD-only client identification. Redis-backed
session store. Read-only tool scope (search, browse, read).

See CLAUDE.md for the full architecture and OAuth flow.

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

### Phase 1 — OAuth broker

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

#### 1.1 Redis-backed broker stores — DONE

- Records under `broker/model/`: `ForgejoTokens`, `ForgejoUser`, `PendingAuth`,
  `AuthCodeEntry`, `AccessTokenEntry`, `RefreshTokenEntry`, `TokenIds`.
- Stores under `broker/store/`: `PendingAuthStore` (10-min hardcoded TTL,
  single-use via `getdel`), `AuthCodeStore` (TTL from `BrokerConfig`,
  single-use), `AccessTokenStore` (long TTL, non-destructive `get`,
  `replacePreservingTtl` using SET XX KEEPTTL for silent upstream refreshes),
  `RefreshTokenStore` (single-use rotation).
- `BrokerStoresTest` (5 tests, all green): per-store round-trip + KEEPTTL
  behavior verified by reading PTTL before/after.

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

#### 1.4 `/token` (auth_code + refresh_token grants)

`POST /token` — validate code, verify PKCE `code_verifier`, mint opaque
`mcp_access_token` + `mcp_refresh_token`, persist, return per RFC 6749. Refresh
grant rotates refresh + mints a new access. Tests for both grants + error cases
(bad code, expired code, bad PKCE, replayed code).

#### 1.5 Bearer auth filter + Forgejo bearer producer

JAX-RS `ContainerRequestFilter` on `/mcp/*` validates `Authorization: Bearer
mcp_at_...`, loads `AccessTokenEntry` from Redis, populates `SecurityContext`,
exposes a request-scoped CDI bean returning the current Forgejo bearer
(refreshing transparently via `replacePreservingTtl` when expired). Tests:
fresh / garbage / expired-refreshable tokens; a probe MCP tool that returns the
resolved Forgejo user proves end-to-end propagation.

#### 1.6 End-to-end happy-path test

One integration test driving the entire dance against the Forgejo container:
discover metadata → `/authorize` → log in via Forgejo's API as the test user
(POST consent form) → follow redirect to `/oauth/callback` → `/token` → call
probe MCP endpoint with bearer → assert resolved Forgejo user matches.

### Phase 2 — Forgejo REST clients + per-request bearer producer

Typed REST clients for Forgejo (DTOs only for fields we use) + a
`ForgejoBearerProducer` that pulls the per-request token from the session.
Thin layer between MCP and Forgejo so tool code stays trivial.

### Phase 3 — MCP tools

In order: repo + code search + file read (the headline trio), then issues/PRs
with comments and diffs, then commits/releases.

### Phase 4 — Health + observability

`/q/health` (readiness checks for Forgejo + Redis), structured logging,
basic metrics.

### Phase 5 — Operator README

Forgejo OAuth-app registration steps, env vars, Docker deploy notes.

## Current test count

21/21 green as of end of Phase 1.2.
- `ConfigLoadingTest`: 3
- `ForgejoTestResourceTest`: 4
- `BrokerStoresTest`: 5
- `MetadataEndpointsTest`: 2
- `CimdResolverTest`: 6
- `CimdResolverAllowlistTest`: 1

## Open decisions / things to pin down later

- MCP endpoint path is currently the quarkus-mcp-server-http default (`/mcp`).
  If we want it configurable, plumb through `BrokerConfig` and update
  `ProtectedResourceMetadataResource.metadata()` accordingly.
- DCR is intentionally not implemented (CIMD only). If a non-Claude MCP host
  needs DCR later, add a `/register` endpoint and corresponding store.
