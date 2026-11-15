# Spec Delta

## MODIFIED Requirements

### Requirement: The kit's tools work wherever the skills are installed
The Java tools of the kit that need the vendored skill SHALL find it in `.github/skills` or in `.claude/skills`, whichever the install placed, without an
option.

#### Scenario: Validating an audit run after a Claude Code install
- **WHEN** `ValidateRun.java` runs in a repository installed for Claude Code only, without `--skill-dir`
- **THEN** it finds the skill in `.claude/skills/security-audit` and validates the run
