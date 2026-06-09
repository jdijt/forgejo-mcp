# Forgejo MCP — guidance for Claude Code

## What this is

A Quarkus 3.35 / Java 25 MCP server that wraps a Forgejo instance's REST API for
**read-only context gathering during local dev** (code search, file reads at a
ref, issues + PRs with comments and diffs, repo/branch/commit/release metadata).
Plain JSON output, no MCP UI widgets.

Distributable: one deployment per Forgejo instance. The operator registers a
single confidential OAuth app on Forgejo and supplies `client_id` /
`client_secret` via env vars; all end users share that app.

## Module layout

This is a multi-module Maven reactor. The OAuth broker is a **standalone,
publishable Quarkus extension** (generic OAuth machinery, zero Forgejo
references); the Forgejo MCP server is a separate app that consumes it.

```
forgejo/                       parent aggregator (groupId eu.derfniw.mcp.forgejo)
├── oauth-broker/              extension parent (groupId eu.derfniw.oauthbroker)
│   ├── runtime/               artifactId oauth-broker          (the extension)
│   └── deployment/            artifactId oauth-broker-deployment (build steps + the broker tests)
└── forgejo-mcp/               artifactId forgejo-mcp           (deployable app)
```

- Extension code lives under `eu.derfniw.oauthbroker.runtime.*` /
  `…deployment.*`. **No "forgejo" anywhere in the extension.**
- App code lives under `eu.derfniw.mcp.forgejo.*` (the Forgejo REST clients,
  the `UpstreamUserResolver` Forgejo impl, and — later — the MCP tools).

The extension's runtime jar **must be Jandex-indexed** (`jandex-maven-plugin`)
or the consuming app won't discover its CDI beans / JAX-RS resources /
`@ConfigMapping`. The `deployment` module must declare the `-deployment`
counterpart of every runtime Quarkus extension. Beyond the feature marker, the
deployment processor registers the envelope records for native reflection (they
are Jackson-serialized inside `TokenCrypto`, never via JAX-RS) and fails the
build if the consuming app supplies no `UpstreamUserResolver`.

The extension uses the JDK `HttpClient` for its two outbound calls (upstream
token endpoint, CIMD fetch), so it does **not** depend on `quarkus-rest-client`.
An app that needs REST clients (e.g. Forgejo's API) declares
`quarkus-rest-client-jackson` itself.

## Architecture

The server is a **generic OAuth broker** (the extension) wired to Forgejo (the
app):

- **Confidential OAuth client** to the upstream (holds `client_secret`,
  performs the auth-code exchange, refreshes tokens server-side). Authorize /
  token / refresh are standard OAuth2, driven entirely by configured upstream
  endpoints (`broker.upstream.*`) — nothing Forgejo-specific.
- **OAuth Authorization Server** to Claude downstream (exposes `/authorize`,
  `/token`, well-known metadata; identifies clients via Client ID Metadata
  Documents — CIMD only, no DCR).
- **One SPI** — `UpstreamUserResolver` (the only thing OAuth2 doesn't
  standardize: user-identity resolution). The app implements it
  (`ForgejoUserResolver` → `GET /api/v1/user`); the broker calls it in the
  callback to set the `SecurityIdentity` principal.

**Stateless tokens.** No durable session store. Every broker token is a
self-contained AES-256-GCM envelope whose plaintext carries the upstream
access + refresh tokens (`UpstreamTokens`), the resolved user (`UpstreamUser`),
scope, and an `expiresAt`.
`runtime/crypto/TokenCrypto` mints and parses them; the key comes from
`BROKER_TOKEN_ENCRYPTION_KEY` (base64url, 32 bytes). Token prefixes:

- `mcp_at_*` — access token (`AccessTokenEntry`)
- `mcp_rt_*` — refresh token (`RefreshTokenEntry`)
- `mcp_ac_*` — broker auth code (`AuthCodeEntry`)
- `mcp_pa_*` — in-flight authorize state, passed to Forgejo as `state=`
  (`PendingAuth`)

The prefix is bound as AEAD additional data, so a token of one kind can't be
decrypted as another even under the same key. Expiry is checked post-decrypt.
Tradeoffs: no server-side revocation, no single-use enforcement on codes /
refresh tokens — fine for the internal-network deployment model in `README.md`.

OAuth flow at a glance:

1. Claude → `GET /.well-known/oauth-protected-resource/mcp` → points at our AS.
2. Claude → `GET /.well-known/oauth-authorization-server` → metadata.
3. Claude → `GET /authorize?...` (PKCE + CIMD client_id) → encrypt PendingAuth
   into `state=`, 302 to Forgejo.
4. Forgejo → `GET /oauth/callback?code=...&state=mcp_pa_...` → we decrypt
   state, exchange the upstream code for tokens, resolve the user via the
   `UpstreamUserResolver` SPI, encrypt an `AuthCodeEntry` as our `code=`,
   302 back to Claude.
5. Claude → `POST /token` → decrypt broker code, mint `mcp_at_*` + `mcp_rt_*`.
6. Claude → `/mcp/*` with `Authorization: Bearer mcp_at_...` → decrypt,
   forward the embedded upstream bearer upstream (identity attribute
   `upstream.bearer`).

## Package layout (every class lives in a leaf package)

Extension runtime (`eu.derfniw.oauthbroker.runtime`):

```
runtime
├── api/          public data contract: UpstreamUser, UpstreamTokens
├── spi/          UpstreamUserResolver (the one app-implemented seam)
├── config/       @ConfigMapping BrokerConfig (incl. nested upstream config)
├── crypto/       TokenCrypto (AES-GCM envelopes) + Expirable + TokenCryptoException
├── envelope/     internal AES-GCM payloads: Access/Refresh/AuthCode entries, PendingAuth
├── dto/          wire DTOs: AuthServerMetadata, ProtectedResourceMetadata, CimdDocument, TokenResponse
├── error/        sealed BrokerException + its 4 leaves
├── security/     BearerAuthenticationMechanism + UpstreamBearer producer
├── endpoint/     JAX-RS resources (/.well-known, /authorize, /token, callback) + the exception mapper
└── service/      CimdResolver, UpstreamOAuthClient, BrokerUris, …
```

`api` + `spi` are the surface an application touches; `envelope` / `dto` / `error`
are internal. The exception model is a flat sealed hierarchy whose leaves map
one-to-one to renderings in `BrokerExceptionMapper`:

```
BrokerException (sealed) → BadRequest (400 text) | OAuthRedirectError (302)
                         | TokenError (400 JSON) | UpstreamFailure (502)
```

`TokenCryptoException` is **not** a `BrokerException` — it's a technical failure of
`TokenCrypto` (in the `crypto` package), translated at each call site (401 on
`/mcp/*`, `invalid_grant` on `/token`, `BadRequest` for an undecryptable
callback `state`).

App (`eu.derfniw.mcp.forgejo`):

```
forgejo/          ForgejoReposApi, ForgejoUserApi, ForgejoUserResolver (SPI impl)
```

**Rule:** never put a class in a package that also contains subpackages. Add a
new leaf if needed.

**Tests.** The broker's own `@QuarkusTest`s live in `deployment/src/test` (the
standard place for extension tests — full Quarkus runs against the extension).
They use a dummy `broker.upstream.*` config + a local `FakeUpstreamUserResolver`
and never touch Forgejo or Docker. The Forgejo-coupled end-to-end + REST-client
tests live in `forgejo-mcp` (real container + Playwright).

The extension ships **no** test-fixtures artifact: a probe resource is inherently
tied to whatever path a consumer protects, so each consumer writes its own
(`McpProbeResource` exists as a local `src/test` class in both the deployment
module and the app — small and intentionally not shared).

## Build & test

- `./mvnw install` — builds all modules + runs tests. The broker tests in
  `oauth-broker/deployment` are pure (dummy upstream config, a fake
  `UpstreamUserResolver`, no Docker); the Forgejo-coupled end-to-end +
  REST-client tests live in `forgejo-mcp` and need Docker (testcontainers).
- `./mvnw -Pquality verify` — spotless + Error Prone/NullAway + JaCoCo. JaCoCo is
  skipped on `oauth-broker/deployment` (its production code is one build-time
  class; the tests there cover the *runtime* module — see the coverage follow-up
  in PLAN.md).
- `./mvnw quarkus:dev -pl forgejo-mcp` — dev mode on the app with live reload.
- Java 25, Maven wrapper pinned to 3.9.9 in `.mvn/wrapper/maven-wrapper.properties`.

The broker itself is stateless and needs no external store. Docker is only
needed by the app's Forgejo-container tests.

## Conventions

- **Tests ship with every phase**, never deferred. Skip a test only if the phase
  produced no externally observable behavior (e.g., pure config classes). No
  placeholder smoke tests like "GET / returns < 500".
- **Real upstreams in containers, not mocks.** Forgejo runs as a testcontainer
  via `ForgejoTestResource` (admin + test user + OAuth app are bootstrapped
  during container startup). Prefer real fakes (testcontainers, embedded
  HttpServer for CIMD-style external HTTP, etc.) over mocks. Mockito is a last
  resort — only when there is no practical way to use a real or fake
  collaborator.
- **Restructure with IntelliJ, never sed.** For class moves between packages or
  cross-project renames the IntelliJ MCP rename-refactor (or hand-off to the
  user in the IDE) is the only acceptable path. Manual Write/Edit is OK for
  isolated edits.
- **Read-only scope only** unless the user explicitly broadens it. No write
  tools (create issue, comment, merge PR) in v1.

## Configuration surface

Required env vars in production:
- `FORGEJO_BASE_URL`
- `FORGEJO_OAUTH_CLIENT_ID`
- `FORGEJO_OAUTH_CLIENT_SECRET`
- `BROKER_PUBLIC_BASE_URL`
- `BROKER_TOKEN_ENCRYPTION_KEY` (base64url, 32 bytes; `openssl rand -base64 32`)

The app's `application.properties` interpolates the generic broker upstream
config from these: `broker.upstream.authorize-url` / `token-url` from
`${forgejo.base-url}`, `broker.upstream.client-id` / `client-secret` from
`forgejo.oauth.*`, and `broker.upstream.scopes` (the full upstream scope set —
the broker no longer injects `read:user`; it's just configured).

Optional knobs in `BrokerConfig`: token/code TTLs, CIMD allowed-hosts and fetch
timeout, and `broker.protected-resource-path` (default `/mcp`) — the resource
identifier advertised in the protected-resource metadata, the suffix of the
`/.well-known/oauth-protected-resource` document, and the `resource_metadata` the
401 challenge points at. Defaults are in `application.properties`. If you change
it, keep the `quarkus.http.auth.permission.*` paths in sync.

## Where to find more

- `PLAN.md` — phased build plan + current status (read this first when resuming).
- Memory directory `~/.claude/projects/<project-hash>/memory/` (when present)
  contains additional workflow preferences captured during prior sessions.
