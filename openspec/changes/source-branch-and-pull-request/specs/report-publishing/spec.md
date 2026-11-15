# Spec Delta

## MODIFIED Requirements

### Requirement: The console publishes a report
The system SHALL provide the console command `publish <report path> [--subject <subject>] [--source-branch <branch>]
[--unsafe-local-changes]`. It SHALL publish the absolute path of the
report as the message body, on the named subject (default: the first configured subject), over a connection made for the command from
the engine's own NATS settings (URL and credentials) and closed when the command ends. It SHALL publish only on a subject the engine is
configured for, SHALL make a relative path absolute, and SHALL publish nothing unless the path names an existing regular file. When a
source branch is given it SHALL be sent in the message header `source-branch`, and when the option to include local changes is given it
SHALL be sent as the header `unsafe-local-changes` with the value `true`; a message without them SHALL be exactly a path, as before. It
SHALL print the source branch that will be used (given, saved or detected, and how it was found) before it publishes, and SHALL NOT ask
a question. It SHALL
say what was published and where, and SHALL NOT print the credentials. The message SHALL go through NATS, so that the engine records it
as it records a message from any other publisher.

#### Scenario: A report is published
- **WHEN** the operator runs `publish C:\src\shop\.appmod\security-audit\issues.csv` and NATS is reachable
- **THEN** one message whose body is that path is published on the first configured subject, the console says so, and the engine
  records a session for it

#### Scenario: Another configured subject
- **WHEN** the operator runs `publish <path> --subject coverity.issuefixesAgent`
- **THEN** the message is published on that subject

#### Scenario: A subject the engine does not listen to
- **WHEN** the subject is not a configured subject, or contains a wildcard or a space
- **THEN** nothing is published, and the console names the configured subjects

#### Scenario: A report that is not a file
- **WHEN** the path does not exist, is a directory, is empty, or contains a line break
- **THEN** nothing is published, no connection is made, and the console says what is wrong with the path

#### Scenario: A relative path
- **WHEN** the operator gives a path that is relative
- **THEN** the message body is the absolute form of it, resolved against the engine's working folder

#### Scenario: NATS is not reachable
- **WHEN** the connection cannot be made
- **THEN** nothing is recorded, the console says NATS could not be reached at the configured URL (without credentials), and the engine
  keeps running

#### Scenario: Credentials
- **WHEN** the engine is configured with a token, a user and password, or a credentials file
- **THEN** the command connects with them, and neither the console output nor the log shows them

#### Scenario: A source branch is sent as a header
- **WHEN** the operator runs `publish <path> --source-branch release/1.2`
- **THEN** the message body is still only the path, the header `source-branch` is `release/1.2`, and the console printed that branch
  before publishing

#### Scenario: Local changes requested
- **WHEN** the operator runs `publish <path> --unsafe-local-changes`
- **THEN** the header `unsafe-local-changes` is `true`, and the console says that local uncommitted work will be part of the session's
  base and that the branch will not be published without a second confirmation

#### Scenario: A branch name that is not valid
- **WHEN** the given branch contains a character outside letters, digits and `._/-`, or does not exist in the repository
- **THEN** nothing is published and the console says why

#### Scenario: Both options at once
- **WHEN** the operator gives `--source-branch` and `--unsafe-local-changes` together
- **THEN** nothing is published and the console says to choose one, because local changes belong to the checked-out branch
