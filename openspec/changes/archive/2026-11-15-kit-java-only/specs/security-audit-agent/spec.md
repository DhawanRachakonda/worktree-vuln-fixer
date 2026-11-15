# Spec Delta

## MODIFIED Requirements

### Requirement: Behaviour when the skill cannot run fully
If the coding tool cannot run the skill's parallel sub-agents or its validators, the audit SHALL still complete as far as
it can, SHALL record in the summary which phase was reduced and why, and SHALL keep unproven issues as
`needs_validation`. The skill's validators are Java programs that read files without following links on every operating system, so there is no
reduced way of reading them; a real validation error SHALL never be treated as a limitation. When no JDK 25 is available the audit SHALL say that the
validators were skipped and SHALL mark the run as not validated.

#### Scenario: No sub-agent support
- **WHEN** the coding tool cannot start sub-agents
- **THEN** the audit runs its phases itself in sequence, notes the limitation in the summary, and still produces the CSV

#### Scenario: No sandbox to run target code
- **WHEN** the machine cannot enforce the isolation the skill requires before running any code of the audited repository
- **THEN** no target code is run, issues that depend on running it stay `needs_validation`, and the summary says so

#### Scenario: Validation error is not a limitation
- **WHEN** the run's findings or coverage ledger break a validation rule
- **THEN** validation fails with the rule's message and the run is not reported as validated

#### Scenario: No JDK 25
- **WHEN** no JDK 25 or later is available
- **THEN** the audit notes that the validators were skipped and marks the run as not validated, and the CSV conversion
  still validates every row itself

#### Scenario: Validators cannot read files on this operating system
- **WHEN** the audit runs on native Windows, where the Node validators used to refuse to read
- **THEN** the Java validators read the files without following links as on any other system, the run is validated with the skill's own rules, and nothing in the summary says that a weaker read was used

#### Scenario: Node is not installed
- **WHEN** Node is not available at all
- **THEN** the audit is not affected: the validators are Java, and the run is validated
