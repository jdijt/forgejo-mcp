# Forgejo MCP

> [!IMPORTANT]
> This project is very much WIP!

This is a simple MCP server for forgejo, at this time it focuses primarily on
read-only context gathering (code search, file reads at a ref, issues and PRs with comments and diffs, repo/branch/commit/release metadata).

This server use oauth to connect to the forgejo instance. 
A this time it uses a bundled oath-broker extension to facilitate compatibility with what MCP requires from oauth (which is significantly more than forgejo implements).

## Developer setup

### JDK

The project uses Java 25.

### Build & test

```sh
./mvnw test                  # fast: tests only, no quality gate
./mvnw -Pquality verify      # full: Error Prone, NullAway, Spotless, ArchUnit, JaCoCo (70% line)
./mvnw -Pquality spotless:apply   # auto-format all sources
```
