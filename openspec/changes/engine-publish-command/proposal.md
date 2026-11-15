# Proposal

## Why

To hand an issue report to the engine, a person runs a separate Node program (`copilot-mordernize-harness/nats/javascript-int`,
69 lines, one npm dependency). It is the only reason the engine machine needs Node and `npm ci`, and it has to be told the NATS URL
and credentials a second time, although the engine already knows both. The engine already holds a NATS client, the connection
settings (URL, token, user and password, credentials file) and the list of subjects, so it can publish a report itself.

## What Changes

- A new console command `publish <report path> [--subject <subject>]` publishes the absolute path of a report file to NATS, on one of
  the subjects the engine is configured for (default: the first one, the security-audit subject). It uses the engine's own NATS
  settings and a short-lived connection, so the message goes through the same server and the same intake as a message from anywhere
  else: the engine receives it like any other message and records a session.
- It refuses before connecting when the subject is not one of the configured subjects, the path is empty or has a line break, or the
  path is not an existing regular file; a relative path is made absolute first. It says when NATS cannot be reached.
- The Node publisher project (`copilot-mordernize-harness/nats/javascript-int`: script, tests, `package.json`, lock file, README and the
  sample `reports`) is **removed**, and every mention of `npm`/`npm run publish` in the engine docs and the harness `readme.md` is
  replaced by the console command (or by the NATS CLI for a machine without the engine).
- Console `doctor` and `config` need no change; the command is listed in the README, `operations.md`, `connecting.md` and `setup.md`.

## Capabilities

### New Capabilities
- `report-publishing`: how a report is handed to the engine from the console.

### Modified Capabilities

None.

## Impact

- `subscriber-engine`: a `ReportPublisher` class next to the NATS code, the console command, unit tests (a stand-in connection) and a
  live test against a real `nats-server` (opt-in, like the other NATS tests), docs.
- `copilot-mordernize-harness/nats/javascript-int` deleted; `copilot-mordernize-harness/readme.md` updated.
- No new setting, no migration, no change to the subjects, the intake, the read API or the agents. The engine still accepts
  messages from any publisher (the NATS CLI, another program).
