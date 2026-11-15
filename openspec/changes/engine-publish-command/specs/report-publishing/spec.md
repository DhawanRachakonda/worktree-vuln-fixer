# Spec Delta

## Purpose

Lets an operator hand an issue report to the engine from its own console, without a separate publisher program and without
configuring NATS a second time.

## ADDED Requirements

### Requirement: The console publishes a report
The system SHALL provide the console command `publish <report path> [--subject <subject>]`. It SHALL publish the absolute path of the
report as the message body, on the named subject (default: the first configured subject), over a connection made for the command from
the engine's own NATS settings (URL and credentials) and closed when the command ends. It SHALL publish only on a subject the engine is
configured for, SHALL make a relative path absolute, and SHALL publish nothing unless the path names an existing regular file. It SHALL
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

### Requirement: No Node publisher
The system SHALL NOT need Node.js or npm to hand a report to the engine. The repository SHALL NOT contain the Node publisher, and the
documentation SHALL describe the console command, or the NATS command-line client for a machine without the engine.

#### Scenario: Documentation
- **WHEN** the engine documentation and the harness readme describe how to publish
- **THEN** none of them tells the reader to run `npm`
