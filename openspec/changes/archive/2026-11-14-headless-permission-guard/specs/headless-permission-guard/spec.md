# Spec Delta

## Purpose

Makes sure that an agent runs unattended with a blanket permission only when an operator said so, and that `ask` is never quietly
turned into "allow everything".

## ADDED Requirements

### Requirement: Headless sessions need an explicit permission decision
When the effective runner is headless, the system SHALL refuse to start unless the permission mode is the allow-list, or is
`allow-all-tools` and was set explicitly (by the setting or its environment variable, not by the default). The permission mode `ask`
SHALL stop the start with headless, because nobody can answer. The message SHALL name the settings involved and the choices
(allow-list, or explicit `allow-all-tools`), and SHALL NOT print any tool rule. With the window runner the system SHALL start
whatever the mode, and when headless starts with an explicit `allow-all-tools` the system SHALL log one warning that the agent runs
with every tool allowed and nobody to approve.

#### Scenario: Headless with the default mode
- **WHEN** the effective runner is headless and the permission mode was not set
- **THEN** the start is refused with a message that names `subscriber.copilot.permission-mode` and the choices

#### Scenario: Headless with ask
- **WHEN** the effective runner is headless and the permission mode is `ask`
- **THEN** the start is refused and the message says that `ask` needs a person

#### Scenario: Headless with an explicit allow-all-tools
- **WHEN** the effective runner is headless and the permission mode is explicitly `allow-all-tools`
- **THEN** the engine starts and logs one warning that unattended agents run with every tool allowed

#### Scenario: Headless with the allow-list
- **WHEN** the effective runner is headless and the permission mode is the allow-list with at least one allow rule
- **THEN** the engine starts and no warning about blanket permission is logged

#### Scenario: Window runner
- **WHEN** the effective runner is the window runner
- **THEN** the engine starts with any permission mode, set or not
