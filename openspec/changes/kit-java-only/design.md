# Design

## Context

The two vendored validators are about 1,650 lines of dependency-free JavaScript with security-minded limits (input size, nesting depth,
array sizes, no symlinks, strict UTF-8, Unicode checks, a capped and escaped error list). `validate-run.cjs` runs them and, on native Windows
where Node has no `O_NOFOLLOW`, falls back to its own read. Everything else in the kit is single-file Java run with `java File.java`.

## Goals / Non-Goals

**Goals:** one runtime (JDK 25); the same verdicts and the same messages from the validators; no weaker mode on Windows.
**Non-Goals:** changing a validation rule; changing the Maven `java-harness`; a build step (the tools stay source files).

## Decisions

- **Same file names in the skill folder, Java 25's multi-file source launch.** `java .../ValidateFindings.java file` compiles the other
  `.java` files of the same folder in memory, so the two validators share one `Json.java` (a strict JSON reader that behaves like
  `JSON.parse`: duplicate keys keep the last value, lone surrogates are kept, numbers are doubles) without a build.
- **JavaScript behaviours that matter are reproduced on purpose**, each in a small named helper: `trim()` strips the ECMAScript whitespace set
  (not Java's `strip`), property order puts integer-like keys first, `Number.isInteger` on a double, `String(number)`, `JSON.stringify` of
  strings, the Unicode classes `Cc Cf Cs Zl Zp`, `White_Space` and `Default_Ignorable_Code_Point` (an explicit range table, because Java has no
  such class), NFC normalisation, and a regular expression test that is an unanchored search.
- **Reading a file** uses `NOFOLLOW_LINKS` attributes and a limited read on every OS: a symlink, a junction or a non-regular file is
  refused with the original messages, the size limit applies while reading, and decoding is strict UTF-8 that keeps a byte order mark (which then
  fails as invalid JSON, as in Node). `ValidateRun` therefore has no "wrapper" mode.
- **The schema file is found next to the validator** through the launcher's `sun.java.command` (the second word names the source file), with
  an explicit `--schema` option for a path that cannot be read that way.
- **Proof by comparison before deletion.** A throwaway differential run feeds every case of the upstream tests, the audit fixtures and a set of
  mutated inputs to the Node original and the Java port and requires the same exit code, standard output and standard error. Only then are the
  Node files removed. The Java test run keeps the upstream cases afterwards.
- **Messages that name the program change** (`Usage: java ValidateFindings.java <file>`); every other message is identical.
- **JDK 25 floor** appears in one place per tool: the file header, the installer's check, `appmod.cmd`'s detection and the docs. The
  portable download in `appmod.cmd` becomes Temurin 25.
- **`appmod.cmd` stops installing Node for the kit, but keeps it for the Copilot plugin.** The Copilot CLI is installed with WinGet by default
  (the `--installer npm` option stays, and then needs Node). The installed plugin (v1.24.0) was read for this: `hooks.json` runs `node
  ...guardModernizeDelegation.mjs` before and after every tool call, `mcp.json` starts `npx -y @microsoft/github-copilot-app-modernization-mcp-server`,
  and the assessment skill is `.mjs` scripts. Node is therefore a prerequisite of the plugin, not of the kit, and is installed (Volta, Node 22) only in
  the plugin step, which only the Copilot tool runs. With `--tool claude` none of Volta, Node, npm, the Copilot CLI or the plugin is touched.

## Risks / Trade-offs

- The Default_Ignorable_Code_Point table and the Unicode version of Java 25 may differ from the Node version in a few code points. The table is
  the one of Unicode 16 and is listed in the source; the tests include the characters the upstream tests use.
- A regular expression in a schema `pattern` is compiled by Java, not by JavaScript; the schema in the kit has one simple pattern, and a
  pattern that Java cannot compile is reported like an invalid one.
- The vendored skill is no longer byte-for-byte the upstream one. `UPSTREAM.md` lists every local edit and the original checksum, so an update is
  still a reviewed diff.
