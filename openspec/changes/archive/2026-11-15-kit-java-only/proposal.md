# Proposal

## Why

The kit and the engine need two runtimes today: a JDK for the Java tools and the engine, and Node.js for the security-audit validators
(`validate-findings.cjs`, `validate-coverage-ledger.cjs`, `validate-run.cjs`), their tests, the fixer's fixture scripts, and for the Copilot
CLI's npm install route. Node is also the reason a machine needs Volta and `npm`. One runtime is less to download, less to explain and less
to break. The engine already needs JDK 25, while the kit says JDK 21+; one JDK for everything is simpler to set up and to demo.

## What Changes

- **No Node anywhere.** The Node publisher is already gone (change `engine-publish-command`). The vendored validators are ported to Java and the
  Node files are deleted:
  - `validate-findings.cjs` -> `ValidateFindings.java`, `validate-coverage-ledger.cjs` -> `ValidateCoverageLedger.java`, both in
    `.github/skills/security-audit/` beside a shared `Json.java` (Java 25 runs a source file together with the other source files of its
    folder: `java <skill-dir>/ValidateFindings.java <file>`). Same rules, same messages, same exit codes and the same limits.
  - `validate-run.cjs` -> `ValidateRun.java` in `.github/appmod/tools/`. The "weaker read" mode for Windows disappears: Java opens a file
    without following links on every operating system, so the validators run unchanged everywhere.
  - The fixer's fixture scripts (`make-findings.js`, `make-fixture.js`, `check-acceptance.js`, `build-fixture.sh`) become Java, and the
    fixture's own sample project is tested without `npm`.
  - The vendored skill's text that says `node <skill-dir>/validate-*.cjs` says `java <skill-dir>/Validate*.java`, and the upstream tests are
    ported to a Java test run (`tests/security-audit/ValidatorTests.java`). The change is listed under "Local changes" in
    `UPSTREAM.md`, with the new checksum.
- **JDK 25 is the floor** for the kit, the installer, `appmod.cmd` (detect JDK 25, download a portable Temurin 25 when none is found) and
  the docs, replacing "JDK 21+". The engine already needs JDK 25.
- **`appmod.cmd` no longer installs Node for the kit** (Volta, Node 22, npm). The Copilot CLI is installed with WinGet by default.
  **One exception, found by reading the installed plugin (v1.24.0): the GitHub Copilot app modernization plugin is itself built on Node.** Its
  pre- and post-tool hooks run `node ...mjs`, its MCP server is started with `npx`, and its assessment scripts are `.mjs`. That is a third-party
  requirement this repository cannot remove, so `appmod.cmd` installs Node (through Volta) only for that plugin, only when the Copilot tool
  is chosen, and says why. A machine that uses Claude Code, or that never installs the plugin, needs no Node at all.
- The Maven `java-harness` (offerings 2 and 3, Java 21) is **not** changed; offering 3's MCP server needs Node as before.

## Capabilities

### New Capabilities
- `kit-java-runtime`: the kit and the engine need a JDK 25 and nothing else; the Java validators are equivalent to the vendored ones.

### Modified Capabilities
- `security-audit-agent`: the requirement about running the skill with limited tools (the validators are Java, read without following links
  on every system, so the "weaker read" case no longer exists, and a missing JDK replaces a missing Node).
- `kit-install-targets`: the tools that find the vendored skill are all Java.

## Impact

- `kit`: five Java files added, six JavaScript files and the upstream `.cjs` tests
  deleted, the skill text and `UPSTREAM.md` edited, `appmod.cmd`, `KitInstaller.java`, installer and tool tests, docs.
- Risk: the validators are security checks vendored from upstream; the port must behave the same. It is proved by running the upstream
  inputs through the Node original and the Java port and comparing exit code, standard output and standard error, before the Node files
  are deleted.
