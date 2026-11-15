# Tasks

## 1. The shared JSON reader and the validators

- [ ] 1.1 `Json.java` (strict reader like `JSON.parse`, lone surrogates kept, `NULL`, key order like `Object.keys`, number printing like `String(number)`,
      `stringify` of strings, `jsTrim`, the Unicode class helpers and the Default_Ignorable table); verify `ValidatorTests` for each helper
- [ ] 1.2 `ValidateFindings.java`: the schema interpreter, the semantic checks, the safe read, the text limits and the messages of the original;
      verify against the Node original on every case of `validate-findings.test.cjs`, the audit fixtures and mutated inputs (same exit code, stdout, stderr)
- [ ] 1.3 `ValidateCoverageLedger.java`: the same for the ledger (preflight text and document limits, canonical ids, attempts, state invariants);
      verify against the Node original on every case of `validate-coverage-ledger.test.cjs`, the fixtures and mutated inputs
- [ ] 1.4 `ValidateRun.java` replacing `validate-run.cjs` (stock validators as separate processes, the same summary line, no `weakened` mode,
      the skill folder found in `.github/skills` or `.claude/skills`); verify the installer tests and `tests/security-audit` with it

## 2. Removing the rest of Node

- [ ] 2.1 Port the fixer's fixture scripts (`make-findings.js`, `make-fixture.js`, `check-acceptance.js`, `build-fixture.sh`) to Java and make the
      sample project's own tests run without `npm`; verify the fixture builds and its acceptance check passes against a known-good run
- [ ] 2.2 Edit the vendored skill text and the agents/skills that say `node ...validate-*.cjs`, update `UPSTREAM.md` (local edits, original and new
      checksum) and the installer so it copies the `.java` files; delete every `.js`/`.cjs` file; verify `InstallerTests` and that a search finds none
- [ ] 2.3 Port the upstream test cases to `tests/security-audit/ValidatorTests.java`; verify they pass, and that each fails when its rule is broken in the validator

## 3. JDK 25 and no Node in `appmod.cmd`

- [ ] 3.1 JDK 25 floor in `KitInstaller`, the tools' headers, `appmod.cmd` (detection, portable Temurin 25, no Volta or Node, Copilot CLI by WinGet, `--installer npm`
      refused) and every doc that says JDK 21 or Node; verify `appmod doctor` on this machine and the installer tests

## 4. Proof

- [ ] 4.1 `openspec validate kit-java-only --strict`, the kit's three test programs, the engine build, and a real Claude Code audit run validated with
      the Java validators, with no Node on the PATH
