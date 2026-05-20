# Forgejo MCP

This is a simple MCP server for forgejo, at this time it focuses primarily on
read-only context gathering during local dev (code search, file reads at a
ref, issues and PRs with comments and diffs, repo/branch/commit/release
metadata).

See `CLAUDE.md` for the architecture and `PLAN.md` for the build plan + current
status.

## Developer setup

### JDK

The project uses Java 25.

### Build & test

```sh
./mvnw test                  # fast: tests only, no quality gate
./mvnw -Pquality verify      # full: Error Prone, NullAway, Spotless, ArchUnit, JaCoCo (70% line)
./mvnw -Pquality spotless:apply   # auto-format all sources
```
