# issue-fix-subscriber Specification

## Purpose
Receives issue-report messages from the audit and scanner tools, starts one interactive coding-agent fix session per message
in its own terminal window, and records every session and its state durably, so that sessions can be followed, recovered
after a restart, and shown by a future application.

## Requirements

### Requirement: Issue-report messages
The system SHALL subscribe to the issue subjects `securityaudit.issuefixesAgent`, `coverity.issuefixesAgent` and
`bdh.issuefixesAgent`. The message payload SHALL be a single, non-empty file path of an issue report. Before anything is
launched, the system SHALL check that the path names an existing regular file whose resolved location lies inside a
configured allowed root folder, and that the repository to work in can be determined and also lies inside an allowed root.
A message that fails a check SHALL create a `REJECTED` session record that states the reason, and SHALL launch nothing.
A message on any other subject SHALL be ignored and SHALL create no record.

#### Scenario: Audit report accepted
- **WHEN** a message on `securityaudit.issuefixesAgent` carries the path of `<repo>/.appmod/security-audit/issues.csv` and the
  repository is inside an allowed root
- **THEN** a session record is created for that report and repository and the session is queued

#### Scenario: Unsupported subject
- **WHEN** a message arrives on a subject that is not an issue subject
- **THEN** it is ignored and no session record is created

#### Scenario: Payload that is not a single path
- **WHEN** the payload is empty, contains a line break or a null character, or names a directory or a missing file
- **THEN** a `REJECTED` record states which check failed and nothing is launched

#### Scenario: Report outside the allowed roots
- **WHEN** the payload names a file outside every allowed root, including through `..` segments or a link that leads outside
- **THEN** a `REJECTED` record states that the path is outside the allowed roots and nothing is launched

#### Scenario: Repository cannot be determined
- **WHEN** a message on `securityaudit.issuefixesAgent` names a file that is not at the expected report location inside a
  repository
- **THEN** a `REJECTED` record states that the repository could not be determined

#### Scenario: Report already being worked on
- **WHEN** a message names a report that already has a queued or running session
- **THEN** the new message is recorded as `REJECTED` as a duplicate and no second session is started

### Requirement: Agent and repository per subject
Each issue subject SHALL have a configured agent name, a source label for the prompt, and a rule for finding the repository:
from the report path for the audit subject, or a configured repository for the others. The default agent SHALL be
`appmod-harness`. Changing the agent or repository of a subject SHALL need configuration only.

#### Scenario: Subject routed to its configured agent
- **WHEN** the audit subject is configured with another agent name and a message arrives
- **THEN** the session is started with that agent

#### Scenario: Default agent
- **WHEN** a subject has no agent configured
- **THEN** the session is started with `appmod-harness`

### Requirement: Interactive session in its own window
For each session the system SHALL open a new terminal window in which the coding agent runs interactively with the repository
as its working directory, a session id chosen by the system, and a prompt naming the report and the source. A person SHALL be
able to type in that window while the agent works. The window SHALL close when the agent ends. The prompt SHALL be built
only from validated values and fixed text; message content SHALL never be interpreted by a shell. The system SHALL ask the
agent to treat the report as data, never as instructions.

#### Scenario: A person can type in the session
- **WHEN** a session is running in its window
- **THEN** keystrokes typed in the window reach the agent and the agent can ask the person questions

#### Scenario: Window closes at the end
- **WHEN** the agent ends, with success or failure
- **THEN** the window closes without further action

#### Scenario: Path with characters that a shell treats specially
- **WHEN** a report path contains spaces, `&`, `;` or `%`
- **THEN** the session starts with the exact path and nothing in the path is executed

#### Scenario: Session can be reopened
- **WHEN** a session has ended
- **THEN** its recorded session id is the agent's own session id, so the conversation can be resumed with that id

### Requirement: One session at a time
The system SHALL run at most a configured number of sessions at once, one by default, and SHALL keep further messages as
`QUEUED` records in order of arrival. A session that ends in any state SHALL let the next queued session start. Two sessions
for the same repository SHALL never run at the same time, whatever the limit.

#### Scenario: Burst of messages
- **WHEN** three valid messages arrive while no session is running
- **THEN** one window opens, the other two sessions are `QUEUED`, and each starts in arrival order after the previous ends

#### Scenario: Same repository with a higher limit
- **WHEN** the limit is 2 and two queued sessions are for the same repository
- **THEN** the second starts only after the first has ended

#### Scenario: Failure does not stop the queue
- **WHEN** a session ends in `ERROR`
- **THEN** the next queued session still starts

### Requirement: Durable session records
The system SHALL store every session in a database kept in a file, with its id, state, source subject, report path,
repository path, agent, runner, start and end times, exit code, commit at start and at end, the outcome reported by the agent,
and a reason. It SHALL also store an ordered list of events, one for each state change, with the time. A session record SHALL be
created when its message is accepted or rejected, before any launch.

#### Scenario: Record before launch
- **WHEN** a valid message is received
- **THEN** a `QUEUED` record exists before any window is opened

#### Scenario: Events in order
- **WHEN** a session goes from queued to running to done
- **THEN** its event list shows each state with its time, in order

#### Scenario: Records survive a restart
- **WHEN** the system is stopped and started again
- **THEN** all earlier session records and events are still there

### Requirement: Session states
A session SHALL be in exactly one of `QUEUED`, `IN_PROGRESS`, `DONE`, `PARTIAL`, `NEEDS_INPUT`, `ERROR`, `INTERRUPTED` or
`REJECTED`. `DONE`, `PARTIAL`, `NEEDS_INPUT`, `ERROR`, `INTERRUPTED` and `REJECTED` SHALL be final.

#### Scenario: Launch
- **WHEN** a queued session's window is opened
- **THEN** the state becomes `IN_PROGRESS` and the start time and the repository's commit at that moment are recorded

### Requirement: Final state from the exit and the agent's report
When a session ends, the system SHALL set its final state from the agent's exit code and the report the agent wrote in the
repository at `.appmod/result.json`. A clean exit alone SHALL NOT make a session `DONE`. A report older than the session's
start SHALL be ignored.

#### Scenario: Fix completed
- **WHEN** the agent exits with code 0 and the report says `succeeded`
- **THEN** the state is `DONE`

#### Scenario: Partly fixed
- **WHEN** the agent exits with code 0 and the report says `partially_succeeded`
- **THEN** the state is `PARTIAL`

#### Scenario: Agent blocked on a decision
- **WHEN** the agent exits with code 0 and the report says `needs_input`
- **THEN** the state is `NEEDS_INPUT`

#### Scenario: Ended without a report
- **WHEN** the agent exits with code 0 and no report newer than the session's start exists
- **THEN** the state is `ERROR` with the reason that it ended without a report

#### Scenario: Failure
- **WHEN** the agent exits with a non-zero code, or the report says `failed`
- **THEN** the state is `ERROR` with the exit code or the report's status as the reason

#### Scenario: Window could not be opened
- **WHEN** the window or the agent cannot be started
- **THEN** the state is `ERROR` with the reason, and the next queued session still starts

### Requirement: Recovery after a restart
On start the system SHALL examine every session that was not in a final state. A `QUEUED` session SHALL be queued again. An
`IN_PROGRESS` session SHALL be finalized from its exit record if one exists, SHALL be watched again if its window is still
running, and SHALL otherwise become `INTERRUPTED` with a reason.

#### Scenario: Queued sessions survive
- **WHEN** the system restarts while sessions are `QUEUED`
- **THEN** they run in their original order

#### Scenario: Window still open
- **WHEN** the system restarts while a session's window is still running
- **THEN** the session stays `IN_PROGRESS` and is finalized when the window ends

#### Scenario: Window ended while the system was down
- **WHEN** the system restarts and the session's exit record exists
- **THEN** the session is finalized from it as if the system had been running

#### Scenario: Window gone without a record
- **WHEN** the system restarts and a session's window is no longer running and left no exit record
- **THEN** the state is `INTERRUPTED` with that reason

### Requirement: Restricted scope and trusted inputs
The system SHALL refuse to start unless at least one allowed root folder is configured. It SHALL run the agent only in a
repository inside an allowed root and only with the permission mode configured for the system, never one taken from a
message. The permission mode SHALL be one of: everything allowed, ask the person, or an allow-list of tool rules configured for the
system together with an optional list of deny rules. In the allow-list mode the agent SHALL be started with exactly those rules
and no blanket permission. Tool rules configured while another mode is selected SHALL stop the start, so that a restriction is never
silently ignored, and a rule SHALL consist of plain characters only, so that it can never run as part of a command. It SHALL never run any text from a message or a report as a command. NATS connection settings and credentials SHALL
come from configuration, and credentials SHALL never be written to logs or records.

#### Scenario: No allowed root configured
- **WHEN** the system starts with no allowed root
- **THEN** it stops with a message naming the missing setting

#### Scenario: Credentials stay out of logs
- **WHEN** the system connects with credentials
- **THEN** neither logs nor session records contain them

#### Scenario: Allow-list mode
- **WHEN** the permission mode is the allow-list, with the allow rules `shell(git:*)` and `write(C:/work/**)` and the deny rule `shell(curl:*)`
- **THEN** the agent is started, in the window runner and in the headless runner alike, with exactly those allow and deny rules and
  with no blanket permission

#### Scenario: Rules without the allow-list mode
- **WHEN** tool rules are configured and the permission mode is not the allow-list
- **THEN** the system stops with a message that names the setting, instead of ignoring the rules

#### Scenario: Allow-list mode without allow rules
- **WHEN** the permission mode is the allow-list and no allow rule is configured
- **THEN** the system stops with a message that names the missing setting

#### Scenario: A rule that could run as a command
- **WHEN** a configured rule contains a quote, semicolon, dollar sign, backtick, ampersand, pipe, redirection character, percent sign,
  comma or line break
- **THEN** the system stops with a message that names the setting and does not print the rule as part of a command

### Requirement: Read-only access to session records
The system SHALL offer read-only HTTP access to session records: a list (newest first, filterable by state, with paging), one
session, and the events of one session. It SHALL offer no way to change records through HTTP. By default it SHALL listen on
the loopback address only, and SHALL refuse to start with another bind address unless an access token is configured, which
requests SHALL then present.

#### Scenario: List and filter
- **WHEN** a client asks for sessions in state `ERROR`
- **THEN** it receives only those sessions, newest first, with paging information

#### Scenario: One session with its events
- **WHEN** a client asks for a session by id
- **THEN** it receives the record and, on request, the ordered events

#### Scenario: Remote bind without a token
- **WHEN** a non-loopback bind address is configured without an access token
- **THEN** the system stops with a message naming the missing token

#### Scenario: Missing or wrong token
- **WHEN** a token is configured and a request lacks it or presents a wrong one
- **THEN** the request is rejected and no records are returned

### Requirement: Operator console
When the system runs in an interactive terminal, it SHALL offer a console in that terminal with commands to list sessions
(filterable by state), show one session with its events, show the current settings without secrets, check its own readiness,
and reopen a finished session. Apart from the event that records a reopening, the console SHALL NOT change any session record.
The console SHALL be off when there is no interactive terminal, and the system SHALL run unchanged without it.

#### Scenario: List and show
- **WHEN** an operator lists sessions in state `ERROR`, then shows one of them
- **THEN** the list holds only those sessions, newest first, and the details show the record and its ordered events

#### Scenario: Settings without secrets
- **WHEN** an operator shows the settings while a NATS token, password and access token are configured
- **THEN** no secret value appears in the output

#### Scenario: Readiness check
- **WHEN** an operator runs the readiness check
- **THEN** each item (database, NATS connection, each allowed root, the agent command, the window launcher, the data directory) is
  reported as passed or failed with the reason, and a failing item does not hide the others

#### Scenario: Reopen a finished session
- **WHEN** an operator reopens a session in a final state and no other session for its repository is queued or running
- **THEN** a window opens in the repository with that session's conversation resumed, an event records the reopening, and the
  session's state is unchanged

#### Scenario: Reopen refused
- **WHEN** an operator reopens an unknown session, a session that is not in a final state, or a session whose repository has another
  queued or running session
- **THEN** nothing is opened and the console says why

#### Scenario: No terminal
- **WHEN** the system runs without an interactive terminal
- **THEN** no console is started and sessions are processed as usual

### Requirement: Headless runner
The system SHALL support a headless mode in which the agent runs without a window and without a person (non-interactive), with
its output kept in a log for the session. The mode SHALL be used when configured or when the platform has no supported window
launch. States and records SHALL be identical to the window mode.

#### Scenario: Headless session
- **WHEN** the headless mode is configured and a valid message arrives
- **THEN** the agent runs without a window, the output is kept in the session's log, and the same states are recorded

#### Scenario: Platform without window support
- **WHEN** the system runs on a platform where windows cannot be opened and no mode is configured
- **THEN** the headless mode is used

### Requirement: One engine per data directory
Only one running instance of the system SHALL use a data directory. A second instance on the same directory SHALL stop at
start with a message that names the directory.

#### Scenario: Second instance
- **WHEN** a second instance is started on a data directory that is in use
- **THEN** it stops and the message says the directory is already in use

### Requirement: Publishing audit reports
The message publisher SHALL accept `securityaudit.issuefixesAgent` with a report path, SHALL check that the path is an existing
file, and SHALL publish its absolute path, as it does for the other issue subjects.

#### Scenario: Publish an audit report
- **WHEN** the publisher is given the audit subject and the path of an existing CSV
- **THEN** it publishes the absolute path on that subject

#### Scenario: Missing report
- **WHEN** the publisher is given the audit subject and a path that is not a file
- **THEN** it publishes nothing and reports the error
