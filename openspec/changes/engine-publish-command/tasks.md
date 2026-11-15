# Tasks

## 1. The command

- [x] 1.1 Add `ReportPublisher` (package `dev.subscriber.nats`): the checks of the spec (configured subject, absolute path, existing regular file, no
      line break), a short-lived connection built with `NatsOptionsFactory`, `publish`, `flush`, `close`; short error sentences with no credentials;
      verify `ReportPublisherTest` with a stand-in connector (subject and body, default subject, other subject, unknown or wildcard subject,
      missing file, directory, empty, line break, relative path, unreachable server, the credentials never in the message)
- [x] 1.2 Add the console command `publish <path> [--subject <s>]` to `ConsoleCommands`; verify `ConsoleCommandsTest` runs it through Spring Shell's
      parser with and without `--subject`
- [x] 1.3 A live test against a real `nats-server` (opt-in): `ReportPublisher` publishes, `NatsMessageSource` receives it and hands it to the intake, also with a token; verify with `-Pnats-tests`

## 2. Removing the Node publisher

- [x] 2.1 Delete `copilot-mordernize-harness/nats/javascript-int` (and `nats/` when empty); replace the `npm` instructions in
      `copilot-mordernize-harness/readme.md`, `engine/README.md`, `docs/connecting.md`, `docs/setup.md` and
      `tests/security-fixer/acceptance.md` with the console command (and the NATS CLI for another machine); verify no file under the repository
      (outside `openspec/changes/archive`) tells the reader to run `npm run publish`

## 3. Documentation and proof

- [ ] 3.1 Update `docs/operations.md` (console list), `docs/spec-coverage.md` (every scenario of the delta and its proof); verify
      `openspec validate engine-publish-command --strict` and `./gradlew build` are green
