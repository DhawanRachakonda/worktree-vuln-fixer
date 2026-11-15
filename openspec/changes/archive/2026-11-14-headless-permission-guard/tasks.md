# Tasks

## 1. The guard

- [x] 1.1 Add `HeadlessPermissionGuard` (config package): refuses headless with `ask`, refuses headless with an unset mode, warns once for
      an explicit `allow-all-tools`, accepts the allow-list and every mode with the window runner; remove the file default of the setting
      in `application.yml`; verify a unit test per scenario of the spec (the message names the settings and prints no rule)
- [x] 1.2 Adjust the tests that start a context headless with `ask` or the default (`ContextLoadsTest` and any other); add a context test
      that a headless start without the setting fails and one that it starts with the setting; verify `./gradlew build` is green

## 2. Documentation

- [x] 2.1 Update the README settings table, `docs/operations.md` (headless needs a decision; the migration), and `docs/spec-coverage.md`
      (the five scenarios and their proofs); verify `openspec validate headless-permission-guard --strict` passes
