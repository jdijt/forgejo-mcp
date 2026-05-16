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

### IntelliJ — formatting

The code style is enforced by Spotless using **palantir-java-format**. The
build will fail on format drift, so configure IntelliJ to format with the same
tool to avoid having to run `spotless:apply` after every edit.

1. **Install the plugin**:
   `Settings → Plugins → Marketplace`, search for *palantir-java-format*,
   install, restart.

2. **Enable for this project**:
   `Settings → palantir-java-format → Enable palantir-java-format`.
   IntelliJ's `Reformat Code` (Ctrl+Alt+L / ⌥⌘L) and the on-save reformat
   action will now produce the same output as `mvn spotless:apply`.

3. **(Optional) Enable on-save formatting**:
   `Settings → Tools → Actions on Save → Reformat code (Whole file)`.