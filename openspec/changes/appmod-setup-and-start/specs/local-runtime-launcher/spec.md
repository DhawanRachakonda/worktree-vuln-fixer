# Spec Delta

## Purpose

Starts what the engine needs, NATS and the engine itself, with one command and no environment variables, using what `appmod setup` saved.

## ADDED Requirements

### Requirement: `appmod start` runs NATS and the engine
The system SHALL provide the command `appmod start`. It SHALL read the machine configuration and, when the file is missing or invalid, stop
with the reason and the advice to run `appmod setup`. With no option it SHALL make sure a NATS server answers on the configured port, then run
the engine in the foreground of the same terminal with the recorded JDK, with the folders, the tool, the data folder and the NATS address taken from the
configuration and no other setting changed, so that the engine's console works in that terminal. It SHALL NOT depend on the PATH or on environment variables
set by the person, and SHALL return the engine's exit status.

#### Scenario: Start with everything set up
- **WHEN** `appmod start` runs after a successful `appmod setup`
- **THEN** NATS answers on the configured port, the engine starts in this terminal with its console, and the engine's subscriber connects

#### Scenario: Not set up
- **WHEN** the configuration file is missing
- **THEN** nothing is started and the message says to run `appmod setup`

#### Scenario: A file named in the configuration is gone
- **WHEN** the engine program or the `nats-server` program no longer exists
- **THEN** nothing is started and the message names the missing file and says to run `appmod setup` again

### Requirement: NATS is reused when it answers, started when it does not
The system SHALL decide by connecting to the configured port and reading the server's first line. A server that answers as NATS SHALL be reused
and left running when the engine ends. A closed port SHALL start `nats-server` in the background bound to the loopback address only, with its output in a
log file and its process id in a file in the appmod folder, and SHALL wait up to 15 seconds for it to answer. An open port that does not
answer as NATS SHALL stop the command with a message naming the port. A NATS server that `start` began SHALL be stopped when the engine ends, also
when the terminal is closed or interrupted. A process id file whose process is gone, or is not a `nats-server`, SHALL be removed and never used to end a process.

#### Scenario: NATS already running
- **WHEN** a NATS server already answers on the port
- **THEN** no second server is started, the engine uses it, and it is still running when the engine ends

#### Scenario: NATS started by start
- **WHEN** nothing listens on the port
- **THEN** a `nats-server` is started bound to `127.0.0.1`, the log file exists, and when the engine ends that server is stopped

#### Scenario: Something else holds the port
- **WHEN** the port is open and does not answer as NATS
- **THEN** the command stops, names the port, and starts nothing

#### Scenario: NATS does not become ready
- **WHEN** the started server does not answer within 15 seconds
- **THEN** it is stopped, the log file's last lines are shown, and the exit status is not 0

#### Scenario: Stale process id file
- **WHEN** the process id file names a process that is gone
- **THEN** the file is removed and the start continues normally

### Requirement: Each part can be started alone
`appmod start --nats` SHALL run only NATS, in the foreground with its output on the terminal, say the address it listens on, and end it when the person
interrupts the command; it SHALL reuse nothing and refuse when the port is already taken. `appmod start --engine` SHALL run only the engine
and SHALL say when no NATS answers on the configured port, and run the engine anyway, which retries its connection as it does today. The two options
together SHALL be the same as no option. An unknown option SHALL be refused with the list of the known ones.

#### Scenario: Only NATS
- **WHEN** `appmod start --nats` runs
- **THEN** a server listens on the loopback address, its address is printed, no engine is started, and interrupting the command ends the server

#### Scenario: Only the engine, with NATS running elsewhere
- **WHEN** `appmod start --engine` runs while a NATS server answers on the port
- **THEN** only the engine is started and it connects to that server

#### Scenario: Only the engine, without NATS
- **WHEN** `appmod start --engine` runs while nothing answers on the port
- **THEN** the message says that no NATS answers, and the engine starts and keeps retrying

### Requirement: Nothing is exposed and nothing secret is passed
The NATS server SHALL listen on the loopback address only. The engine SHALL receive only the settings named above, and no credential SHALL be written to the configuration, the log or the console by
`start`. The documentation SHALL say that there is no token or TLS and why that is acceptable on the loopback address.

#### Scenario: Not reachable from the network
- **WHEN** NATS was started by `start`
- **THEN** it listens on `127.0.0.1` and not on all interfaces
