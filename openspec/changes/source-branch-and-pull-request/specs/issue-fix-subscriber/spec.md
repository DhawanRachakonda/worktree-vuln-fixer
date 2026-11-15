# Spec Delta

## MODIFIED Requirements

### Requirement: Issue-report messages
The system SHALL subscribe to the issue subjects `securityaudit.issuefixesAgent`, `coverity.issuefixesAgent` and
`bdh.issuefixesAgent`. The message payload SHALL be a single, non-empty file path of an issue report. A message MAY carry the headers
`source-branch` and `unsafe-local-changes`; a header SHALL be treated as data, SHALL be accepted only when its value is a valid branch name
or `true` respectively, and a message with an invalid header, with both headers, or with an unknown value SHALL be `REJECTED`. Before
anything is launched, the system SHALL check that the path names an existing regular file whose resolved location lies inside a
configured allowed root, and that the repository to work in can be determined and also lies inside an allowed root.
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
- **WHEN** a message names a report and a source branch (or none) for which a queued or running session already exists
- **THEN** the new message is recorded as `REJECTED` as a duplicate and no second session is started

#### Scenario: Same report on another source branch
- **WHEN** a message names a report that has a queued or running session, but with a different source branch
- **THEN** it is accepted as a new session, and it starts only when no other session for that repository is running

#### Scenario: Branch header accepted
- **WHEN** a message carries the header `source-branch` with the value `release/1.2` and that branch exists
- **THEN** the session is queued with that source branch recorded

#### Scenario: Invalid header
- **WHEN** a message carries a `source-branch` header with a line break, a space or a shell character, or both headers
- **THEN** a `REJECTED` record states which header was invalid and nothing is launched

### Requirement: Operator console
When the system runs in an interactive terminal, it SHALL offer a console in that terminal with commands to list sessions
(filterable by state), show one session with its events, show the current settings without secrets, check its own readiness,
reopen a finished session, and publish the branch of a finished session that awaits confirmation. Apart from the event that records a
reopening or a publishing, the console SHALL NOT change any session record. The settings it shows SHALL include the publish mode, the
pull-request command and the source-branch settings, and the readiness check SHALL include the git checks of the capability
`git-preflight`. The console SHALL be off when there is no interactive terminal, and the system SHALL run unchanged without it.

#### Scenario: List and show
- **WHEN** an operator lists sessions in state `ERROR`, then shows one of them
- **THEN** the list holds only those sessions, newest first, and the details show the record and its ordered events

#### Scenario: Settings without secrets
- **WHEN** an operator shows the settings while a NATS token, password and access token are configured
- **THEN** no secret value appears in the output

#### Scenario: Readiness check
- **WHEN** an operator runs the readiness check
- **THEN** each item (database, NATS connection, each allowed root, the agent command, the window launcher, the data directory, and the
  git checks of each allowed repository) is reported as passed or failed with the reason, and a failing item does not hide the others

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

#### Scenario: Session shows how its branch was chosen and published
- **WHEN** an operator shows a session that used a source branch and was pushed
- **THEN** the output names the source branch and how it was chosen, whether local changes were included, and the push and pull-request
  outcome
