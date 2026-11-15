# Design

## Context

`NatsMessageSource` connects with `NatsOptionsFactory.build(properties.nats(), ...)`, which already puts the URL and the credentials
into the options and keeps them out of logs. The intake (`IntakeService.accept`) validates the payload (absolute path, regular
file, allowed roots, repository, duplicate) when a message arrives, so a publisher does not need to repeat those checks, only to avoid
publishing something that cannot be a report.

## Goals / Non-Goals

**Goals:** one command in the console; no second place to set the NATS URL and credentials; no Node.
**Non-Goals:** a way to publish from a machine without the engine (the NATS CLI does that); publishing arbitrary payloads on arbitrary
subjects (the Node script allowed it); the `--issues` pair form; an HTTP endpoint to publish (the read API stays read-only).

## Decisions

- **Through NATS, not a direct call of `IntakeService.accept`.** The console could call the intake and skip NATS, but then it would
  prove nothing about the server, the subjects or the credentials, and a demo of "publish, then watch the session appear" would hide
  the real path. A short-lived connection is a few lines (`Nats.connect(options)`, `publish`, `flush`, `close`) and reuses the options
  factory.
- **`ReportPublisher` takes a connector (a function from options to connection)**, with the real client as the default, so a unit test
  can give it a stand-in and look at the subject and the body. The real server is covered by an opt-in live test, like the other NATS
  tests.
- **The same checks as the old publisher, plus the subject list.** A path is made absolute and must be an existing regular file. The old
  script published any subject it was given when it was not one of the three issue subjects; the console command publishes only
  configured subjects, so a mistyped subject is an error, not a message nobody receives.
- **The default subject is the first configured one**, which is the security-audit subject in the shipped configuration; no new setting.
- **Errors are short sentences, never a stack trace**, and the message for an unreachable server names the URL only (the URL may not
  contain credentials; the settings already forbid it).
- **A machine without the engine** uses the NATS CLI (`nats pub <subject> "<path>"`), which the docs already mention; there is no
  replacement program in this repository.

## Risks / Trade-offs

- Publishing from the engine's console means the producer and the consumer are one program in a demo. The real flow (the audit on one
  machine, the engine on another) needs the NATS CLI or another publisher; the docs say so.
- The check "is a regular file" is made on the engine's machine, so a report on another machine cannot be published from here. That is
  the same limit the intake has.
