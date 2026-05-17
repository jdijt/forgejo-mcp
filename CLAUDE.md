# Forgejo MCP — guidance for Claude Code

## What this is

A Quarkus 3.35 / Java 25 MCP server that wraps a Forgejo instance's REST API for
**read-only context gathering during local dev** (code search, file reads at a
ref, issues + PRs with comments and diffs, repo/branch/commit/release metadata).
Plain JSON output, no MCP UI widgets.

Distributable: one deployment per Forgejo instance. The operator registers a
single confidential OAuth app on Forgejo and supplies `client_id` /
`client_secret` via env vars; all end users share that app.

## Architecture

The server is an **OAuth broker**:

- **Confidential OAuth client** to Forgejo upstream (holds `client_secret`,
  performs the auth-code exchange, refreshes tokens server-side).
- **OAuth Authorization Server** to Claude downstream (exposes `/authorize`,
  `/token`, well-known metadata; identifies clients via Client ID Metadata
  Documents — CIMD only, no DCR).

**Stateless tokens.** No durable session store. Every broker token is a
self-contained AES-256-GCM envelope whose plaintext carries the Forgejo
access + refresh tokens, the resolved user, scope, and an `expiresAt`.
`broker/crypto/TokenCrypto` mints and parses them; the key comes from
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
   state, exchange Forgejo code for tokens, fetch user, encrypt an
   `AuthCodeEntry` as our `code=`, 302 back to Claude.
5. Claude → `POST /token` → decrypt broker code, mint `mcp_at_*` + `mcp_rt_*`.
6. Claude → `/mcp/*` with `Authorization: Bearer mcp_at_...` → decrypt,
   forward the embedded Forgejo bearer upstream.

## Package layout (every class lives in a leaf package)

```
eu.derfniw.mcp.forgejo
├── config/        @ConfigMapping interfaces (ForgejoConfig, BrokerConfig)
└── broker/
    ├── model/     records (DTOs, envelope payloads, metadata docs)
    ├── crypto/    TokenCrypto (AES-GCM envelopes) + Expirable
    ├── forgejo/   @RegisterRestClient interfaces for upstream Forgejo
    ├── endpoint/  JAX-RS resources (/.well-known, /authorize, /token, callback)
    └── service/   non-endpoint services (CimdResolver, ForgejoOAuthClient, …)
```

**Rule:** never put a class in a package that also contains subpackages. Add a
new leaf if needed.

## Build & test

- `./mvnw test` — unit + integration tests. Requires Docker (testcontainers).
- `./mvnw quarkus:dev` — dev mode with live reload.
- Java 25, Maven wrapper pinned to 3.9.9 in `.mvn/wrapper/maven-wrapper.properties`.

The only test infra dependency is Docker (for the Forgejo testcontainer); the
broker itself is stateless and needs no external store.

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

Optional knobs in `BrokerConfig`: token/code TTLs, CIMD allowed-hosts and fetch
timeout. Defaults are in `application.properties`.

## Where to find more

- `PLAN.md` — phased build plan + current status (read this first when resuming).
- Memory directory `~/.claude/projects/<project-hash>/memory/` (when present)
  contains additional workflow preferences captured during prior sessions.
