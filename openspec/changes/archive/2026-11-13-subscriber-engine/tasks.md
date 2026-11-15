# Tasks

Assumptions recorded here because the design leaves them open: the project is `subscriber-engine/` at the repository root next to
`intel-service/`, package `dev.subscriber`; it reuses `intel-service`'s Gradle wrapper (9.9.0), Java 25 toolchain setting and the
machine-local JDK registration in `~/.gradle/gradle.properties`; tests that need a window system or a NATS server are opt-in;
the Node publisher stays in `copilot-mordernize-harness/nats/javascript-int/`; the operator console uses Spring Shell and is part of this change (group 7); the kit, the agents and the Copilot CLI are not
changed by this change. "The real CLI" means GitHub Copilot CLI 1.0.108 or newer on the PATH and signed in.

## 1. Spike and skeleton

- [x] 1.1 Spike with the real CLI, on a scratch git repository, through `cmd /c start "t" /wait powershell -NoProfile -ExecutionPolicy Bypass
      -File <script>` where the script runs `copilot --agent appmod-harness --session-id <uuid> --name test
      --add-dir <dir> --log-dir <dir> -i "<short prompt>"`; verify with the person at the keyboard that (a) typing in the window reaches the agent,
      (b) the window closes when the session ends, (c) `copilot --resume <uuid>` reopens it, (d) `-i` combines with `--agent` and
      `--session-id`, (e) the exit code of a normal and of an interrupted session; record the findings in design.md and adapt D6
      if any flag does not combine
- [x] 1.2 Create the Gradle project (Kotlin DSL, wrapper copied from `intel-service`, Java 25 toolchain, Spring Boot 4.1.2 BOM,
      web MVC, JDBC, Flyway, actuator, validation, H2, `io.nats:jnats` at its latest GA, JUnit) with an application class and a
      context-load test on a temporary H2 directory; verify `./gradlew build` passes, `./gradlew dependencies` shows no
      milestone or RC artifacts, and `./gradlew bootRun` starts
- [x] 1.3 Add the typed configuration records (data directory, allowed roots, subjects with source, agent, repository and report
      location, runner, max parallel, Copilot permission mode, NATS connection, access token, bind address) with validation:
      data directory restricted to the safe character set, agent name pattern, no wildcard subjects, no empty allowed roots; verify
      tests for the defaults, environment overrides, and a startup failure naming the setting for each invalid value
- [x] 1.4 Write `subscriber-engine/README.md` (what it does, prerequisites, build, run, configuration table, the one-process-per-data-directory
      rule); verify the commands in it start the engine against a local NATS server or a stated stand-in

## 2. Persistence

- [x] 2.1 Write the Flyway V1 migration (`fix_session`, `fix_session_event`, the state lookup table with foreign keys, indexes on state
      and on `(session_id, occurred_at)`, standard SQL only) and the repositories as typed records on `JdbcClient`; verify tests that the migration
      applies to a fresh in-memory and a fresh file database and re-applies without change, and that inserts, state updates,
      ordered events and the state filter work, and that an invalid state is rejected by the database
- [x] 2.2 Add the single-instance guard (failure analyzer that turns the H2 file-lock error into "data directory <dir> is
      already in use") and a durability test that writes sessions, restarts the application on the same file and finds them;
      verify a second process on the same directory fails fast with that message
- [x] 2.3 Write `subscriber-engine/docs/data-model.md` (tables, columns, states, the standard-SQL rule, backup by copying while
      stopped); verify with a test that every table and column in the document exists in the migration

## 3. Intake

- [x] 3.1 Implement the path checks: real-path resolution against the allowed roots, regular-file check, repository derivation
      from the audit report location (third parent, must hold `.git`) or the configured repository, duplicate detection against
      queued and running sessions; verify unit tests for a normal path, `..` segments, a junction or symlink leading outside
      (skipped where links cannot be created, and said so), a directory, a missing file, a file not at the audit location, a
      repository without `.git`, and a duplicate
- [x] 3.2 Implement the intake service that writes `REJECTED` (with the reason) or `QUEUED` records and ignores other subjects;
      verify tests for every scenario of the spec's "Issue-report messages" requirement, including that nothing is launched and
      that a `QUEUED` row exists before any launch
- [x] 3.3 Implement the report snapshot (copied when the message is accepted) and the `prompt.md` generation from per-source
      templates (source label, snapshot path, original path, treat-as-data rule, the `result.json` contract); verify tests with a
      report path containing spaces, `&`, `%`, `^`, `'` and non-ASCII characters that the prompt holds the exact path, that the
      snapshot is a byte-for-byte copy, and that a later change of the original does not change the snapshot

## 4. Runners

- [x] 4.1 Implement the launcher script generation (`run.ps1`, `run.sh`) from templates with validated values only: agent name,
      uuid, session folder, repository as a quoted literal, permission flags, `try/finally` writing `exit.json` atomically with
      the exit code, end time and end HEAD; verify golden-file tests with hostile repository paths (`'`, `&`, `%`, `^`, spaces,
      non-ASCII) and that the generated PowerShell parses (`[System.Management.Automation.Language.Parser]::ParseFile`)
- [x] 4.2 Build the stand-in `copilot` (a script first on the PATH that records its arguments, reads `prompt.md`, writes
      `.appmod/result.json` with a chosen status and exits with a chosen code) and implement the headless runner; verify
      integration tests that a session runs without a window, `exit.json` appears, and each of exit 0 and non-zero is captured
- [x] 4.3 Implement the window runner (`cmd.exe /c start "<title>" /wait powershell ... -File run.ps1`), the process handle, and the
      liveness token (pid and start instant); verify with the stand-in, opt-in on Windows (`-Pwindow-tests`), that a window opens, the
      script's output reaches it, the window closes when the stand-in ends, `exit.json` appears, and that paths with spaces in the
      session folder work; and without window support the runner choice falls back to headless as the spec requires

## 5. Lifecycle

- [x] 5.1 Implement the state machine and the outcome reader (typed record for `.appmod/result.json`, freshness by file time against
      the start) with a transition and event per change; verify table-driven tests for every row of the design's state table,
      including a stale report, an unreadable report and a missing exit code, and that final states are never changed again
- [x] 5.2 Implement the dispatcher: oldest `QUEUED` first, `max-parallel`, never two sessions in one repository, a watcher per session
      that finalizes and frees the slot, failure to launch recorded as `ERROR` while the queue continues; verify tests with a fake
      runner for the burst of three, the failure that does not stop the queue, the limit of 2 with the same repository, and ordering
- [x] 5.3 Implement recovery on start (requeue, finalize from `exit.json`, re-attach to a live token by polling, otherwise
      `INTERRUPTED`); verify tests that restart the application on a file database in each of the four situations of the spec, using a
      real stand-in process for the re-attach case
- [x] 5.4 Write `subscriber-engine/docs/operations.md` (states and what each means, what happens on a restart, where a session's
      files are, how to reopen a session with `copilot --resume <id>`, the lost-while-down limit, the permission mode, the allowed
      roots); verify each documented step by following it once against a running engine (done after 6.1, because a running engine
      can only receive messages once the NATS source exists)

## 6. NATS and read API

- [x] 6.1 Implement the NATS message source with the jnats client (URL, optional token, user and password, or credentials file;
      unlimited reconnect; one subscription per configured subject; callback only validates and records) and a health indicator for
      the connection and the database; verify tests with a fake source, a test that credentials never appear in logs or records,
      and an opt-in live test against a NATS server in Docker (`NATS_URL`)
- [x] 6.2 Implement the read-only API (`GET /api/sessions` with state filter and paging, `GET /api/sessions/{id}`, `GET
      /api/sessions/{id}/events`) as typed records, the loopback default, and the bearer-token requirement for any other bind with a
      constant-time check before any query; verify tests for listing, filtering, paging, one session with events, no write
      method accepted, start refused for a remote bind without a token, and 401 for a missing or wrong token
- [x] 6.3 Write `subscriber-engine/docs/connecting.md` (how to publish, how to read sessions with `curl`, how a future web application
      should use the API, configuration of the subjects and agents including pointing the audit subject at another agent); verify the
      documented commands against a running engine (the `curl` commands and the Node publisher were run against a live engine and NATS server;
      the standalone `nats pub` command was not run, because no `nats` CLI is installed, and the document says so)

## 7. Operator console

- [x] 7.1 Add Spring Shell (`spring-shell-starter` 4.0.3, GA, built on Boot 4.0.7; this project is on 4.1.2) and let the console start
      only in an interactive terminal (setting `subscriber.console.enabled`: `auto`, `true`, `false`; a terminal is needed, so the
      console runs from `java -jar`, not `bootRun`); verify the engine still starts and `/actuator/health` is UP with the console enabled and without a terminal,
      that the prompt appears in a terminal, and that logs go to `<data dir>/engine.log` while the console is on so the prompt stays
      usable; if Spring Shell 4.0.3 does not work with Boot 4.1.2, stop and report the failure instead of working around it
- [x] 7.2 Implement the read commands `sessions [--state <STATE>]`, `session <id>`, `events <id>` and `config` (settings without
      secrets) on the session repositories; verify command-level tests for listing newest first, the state filter, one session with its
      events, an unknown id, and that no token, password or credentials value appears in the output of `config`
- [x] 7.3 Implement `doctor` (database open, NATS connected, each allowed root exists, the Copilot command found on the PATH, PowerShell
      found on Windows, data directory writable, one line per check with pass or fail and the reason); verify tests for all-passing and
      for each failing check, and that no secret is printed
- [x] 7.4 Implement `reopen <id>`: only for a session in a final state, refused for an unknown id, for a session that is not final, and
      while another session for the same repository is `QUEUED` or `IN_PROGRESS`; otherwise open a window through the safe launcher
      (design D5) running `copilot --resume <id>` in the repository, and append a "reopened" event; verify unit tests for each refusal and
      a window test (opt-in, Windows) with the stand-in that the window opens in the repository, including a repository path with `&`
      and `'`, and that the session's state does not change
- [x] 7.5 Document the console in `subscriber-engine/docs/operations.md` and the README (how to start it, each command with an example,
      when it is disabled); verify by running the documented commands in a scripted console session against a running engine

## 8. Publisher

- [x] 8.1 Add `securityaudit.issuefixesAgent` to `MessagePublisher.js` with the same existing-file check and absolute path; verify
      the publisher tests for the new subject, a missing file and a directory, and that the existing subjects still behave as before
- [x] 8.2 Document the full flow (audit, publish, engine, window, record) in `subscriber-engine/README.md` and the NATS folder; verify
      by publishing a real audit CSV from a scratch repository and watching the engine accept it

## 9. Integration and replacement

- [x] 9.1 End to end with a real NATS server (Docker) and the stand-in: publish three audit reports for two repositories, check
      sequential sessions, the states and events in the database and through the API, then stop and restart the engine in the middle
      and check recovery; verify the recorded results against the expectations written down before the run
- [x] 9.2 Manual acceptance with the real CLI and the person at the keyboard: publish an audit report for a scratch repository, check
      that a window opens, typing works, the agent asks and receives answers, the window closes at the end, the state and outcome are
      recorded, `copilot --resume <id>` reopens the conversation, and the operator console lists the session and `reopen` opens it;
      record the result and any difference from the design (done 2026-11-12, `docs/e2e-run.md`: the agent asked no question, so the
      question path was not exercised)
- [x] 9.3 Remove `MessageSubscriber.js`, the subscriber tests in `MessageHandlers.test.js` and the `subscribe` script, and update the
      NATS docs; verify `npm test` in the NATS folder passes with the publisher tests only, and nothing else in the repository
      references the removed files
- [x] 9.4 Walk every scenario of `specs/issue-fix-subscriber/spec.md` and write `subscriber-engine/docs/spec-coverage.md` (scenario
      to test or recorded run, with gaps stated plainly); verify none is without a proof or an honest gap note, run `./gradlew build`
      and `openspec validate "subscriber-engine" --strict`

## 10. Permission allow-list

- [x] 10.1 Add the permission mode `allow-list` with `subscriber.copilot.allowed-tools` and `denied-tools` (environment
      `SUBSCRIBER_COPILOT_ALLOWED_TOOLS` and `SUBSCRIBER_COPILOT_DENIED_TOOLS`, comma-separated), validated as design D17 says: rules
      with another mode stop the start, the allow-list without an allow rule stops the start, and a rule with a character outside the
      plain set stops the start without echoing the rule; verify `SubscriberPropertiesTest` for each of the four scenarios and for the
      environment names
- [x] 10.2 Render the rules into the generated scripts (window, reopen and both headless flavors) as single-quoted `--allow-tool` and
      `--deny-tool` arguments with no blanket flag, and keep `--allow-all-tools` for the other modes in headless exactly as before;
      verify `LauncherScriptsTest` and the golden files (unchanged for the existing modes, new ones for the allow-list), the PowerShell
      parser and `sh -n` on every flavor with a hostile-looking but allowed rule, and a mutation check that a rule never appears outside
      its quotes
- [x] 10.3 Show the mode and the rules in the console `config`, and document the allow-list (what a good list is, `copilot help
      permissions`, that `COPILOT_MODEL` passes through the environment) in the README and `docs/operations.md`; verify
      `ConsoleCommandsTest` and the documented example list against the CLI's own rule syntax
- [x] 10.4 Run the engine with the real Copilot CLI, the allow-list mode and the security-fixer fixture (`security-fixer` task 6.3):
      verify the session ends `DONE`, the agent is `security-fixer`, the worktree fields and 5 commits are recorded, and the checker of
      the fixture passes. Third run on 2026-11-11: DONE, 5 commits, checker 35 of 35 (two earlier runs led to agent and checker fixes), see
      tests/security-fixer/acceptance.md
