# Proposal

## Why

The NATS subscriber that turns an issue report into a Copilot fix session (`copilot-mordernize-harness/nats/javascript-int/
MessageSubscriber.js`) has a bug and three gaps:

- **The CLI cannot be typed into.** It starts Copilot with `stdio: ["pipe","inherit","inherit"]`, writes the prompt to stdin and
  closes stdin, so the interactive CLI draws on the subscriber's console but receives no keystrokes.
- **Nothing is remembered.** Whether a session started, is running, finished or failed exists only in the subscriber's memory
  and console, and NATS core keeps no history, so a restart of any process loses it. A web view later has nothing to read.
- **The repository is a placeholder constant** (`C:\<user_name>\<path_to_repo>\src\app`), and anyone who can publish to NATS can
  make the subscriber run Copilot with `--allow-all-tools` in whatever that directory is.
- **Audit findings have no subject.** The security audit now produces `.appmod/security-audit/issues.csv`; there is no way to
  hand it to a fixer.

## What Changes

- Add **`subscriber-engine/`**, a Spring Boot application (Java 25, Gradle, H2 file database, Flyway; the same stack as
  `intel-service`) that replaces the Node subscriber. The Node **publisher stays** and gains the new subject.
- Fix the typing bug: for each message the engine opens **a new terminal window** running
  `copilot --session-id <uuid> -i <prompt>` with a real terminal, so the user can watch and type. The window **closes when
  Copilot ends**. Only **one session runs at a time** by default, so windows cannot pile up; further messages wait as `QUEUED`.
- Persist every session in an **H2 file database**: its id, state, source subject, report, repository, engine, runner, times,
  exit code, start and end commit, the agent's reported outcome, a reason, and an append-only event list.
- Track a **state** per session: `QUEUED`, `IN_PROGRESS`, `DONE`, `PARTIAL`, `NEEDS_INPUT`, `ERROR`, `INTERRUPTED`, `REJECTED`.
  The final state combines the exit code with the repository's own `.appmod/result.json` (a clean exit is not proof of a fix).
- Make the engine **restart-safe**: queued messages are requeued, a running session is re-attached to or marked interrupted.
- Add the subject **`securityaudit.issuefixesAgent`** and keep `coverity.issuefixesAgent` and `bdh.issuefixesAgent`. Each
  subject names its agent (default `appmod-harness`) and how its repository is found (from the report path, or configured).
- Replace the hard-coded directory with an **allowlist of root folders**: reports and repositories outside it are
  rejected, and the engine refuses to start without one.
- Offer a small **read-only HTTP API** on loopback (list sessions, one session, its events) so a future web application is
  not blocked: a second process cannot open the same H2 file, so the engine is the one reader.
- Offer an **operator console** (Spring Shell) inside the engine when it runs in a terminal: list and show sessions and their events,
  show the settings without secrets, a readiness check, and reopen a finished session's conversation in a window.
- Offer a **headless runner** (`copilot -p`) for machines without a window system, and reserve an `engine` field so Claude
  can be added later.
- **Not in this change:** the web application, SSE, JetStream, the `security-fixer` agent, the audit-then-publish step and the
  Claude engine.

## Capabilities

### New Capabilities
- `issue-fix-subscriber`: receiving issue-report messages, starting one interactive fix session per message in its own
  window, recording each session and its state durably, recovering after a restart, restricting what may be run, and
  exposing the records read-only.

### Modified Capabilities
<!-- None: the project has no specs under openspec/specs/ yet. The change replaces the Node subscriber's behaviour; its tests are
     replaced, not carried over. -->

## Impact

- **New project:** `subscriber-engine/` at the repository root (Gradle with Kotlin DSL, wrapper, Java 25 toolchain), next to
  `intel-service/`.
- **Removed:** `MessageSubscriber.js` and the subscriber tests in `MessageHandlers.test.js` (the publisher tests stay).
  `package.json` loses the `subscribe` script.
- **Edited:** `MessagePublisher.js` (accepts and validates the new subject), the NATS docs.
- **Adds a dependency:** Spring Shell 4.0.3 (GA) for the operator console.
- **Depends on:** a running NATS server (`NATS_URL`), GitHub Copilot CLI on the PATH and signed in, PowerShell on Windows.
- **Risk:** the window launch cannot be tested without a window system; tests use a stand-in `copilot` and one manual
  acceptance run records the real behaviour.
- **Related, not included:** the `security-fixer` agent is the next change; until it exists, the audit subject's agent
  defaults to `appmod-harness`, which has no route for code-level findings.
