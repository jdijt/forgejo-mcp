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

Per-user state lives in Redis: `mcp_access_token` →
`{ forgejo_access_token, forgejo_refresh_token, expiry, user_info }`. Forgejo
tokens are refreshed transparently inside the broker; Claude only ever sees
opaque `mcp_at_*` / `mcp_rt_*` strings.

OAuth flow at a glance:

1. Claude → `GET /.well-known/oauth-protected-resource/mcp` → points at our AS.
2. Claude → `GET /.well-known/oauth-authorization-server` → metadata.
3. Claude → `GET /authorize?...` (PKCE + CIMD client_id) → 302 to Forgejo.
4. Forgejo → `GET /oauth/callback?code=...` → we exchange + persist session,
   mint our own auth code, 302 back to Claude.
5. Claude → `POST /token` → opaque access token + refresh token.
6. Claude → `/mcp/*` with `Authorization: Bearer mcp_at_...` → looked up in
   Redis, upstream Forgejo bearer attached for the call.

## Package layout (every class lives in a leaf package)

```
eu.derfniw.mcp.forgejo
├── config/        @ConfigMapping interfaces (ForgejoConfig, BrokerConfig)
└── broker/
    ├── model/     records (DTOs, entries, metadata docs)
    ├── store/     Redis-backed stores (PendingAuth/AuthCode/AccessToken/Refresh)
    ├── endpoint/  JAX-RS resources (/.well-known, /authorize, /token, callback)
    └── service/   non-endpoint services (CimdResolver, etc.)
```

**Rule:** never put a class in a package that also contains subpackages. Add a
new leaf if needed.

## Build & test

- `./mvnw test` — unit + integration tests. Requires Docker (testcontainers).
- `./mvnw quarkus:dev` — dev mode with live reload.
- Java 25, Maven wrapper pinned to 3.9.9 in `.mvn/wrapper/maven-wrapper.properties`.

Quarkus dev services automatically start Redis (and any test resource starts
Forgejo) — no manual setup.

## Conventions

- **Tests ship with every phase**, never deferred. Skip a test only if the phase
  produced no externally observable behavior (e.g., pure config classes). No
  placeholder smoke tests like "GET / returns < 500".
- **Real upstreams in containers, not mocks.** Forgejo runs as a testcontainer
  via `ForgejoTestResource` (admin + test user + OAuth app are bootstrapped
  during container startup). Redis runs via Quarkus dev services. Use Mockito
  only for in-process logic.
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
- `REDIS_URL`

Optional knobs in `BrokerConfig`: token/code TTLs, CIMD allowed-hosts and fetch
timeout. Defaults are in `application.properties`.

## Where to find more

- `PLAN.md` — phased build plan + current status (read this first when resuming).
- Memory directory `~/.claude/projects/<project-hash>/memory/` (when present)
  contains additional workflow preferences captured during prior sessions.
