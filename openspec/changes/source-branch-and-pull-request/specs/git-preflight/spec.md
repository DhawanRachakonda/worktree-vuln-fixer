# Spec Delta

## Purpose

Tells the person, before any agent work starts, whether git can do what the chosen publish mode needs, in the installer, the readiness
check, the settings view and at publish time, instead of failing after a session has run.

## ADDED Requirements

### Requirement: Git readiness is checked without changing anything
The system SHALL provide one set of checks for a repository and a publish mode: git is installed; the repository has an `origin`
remote; the remote answers a read of its `HEAD` with the credentials git provides (without prompting, within a time limit); a push
of the source branch would be accepted, as far as a dry run can tell; the source branch exists; and the program of the configured
pull-request command is found on the PATH. Each check SHALL be reported as passed, warning or failed, with the reason and, for a failure,
what is lost (for example "pull requests will be skipped; sessions still produce a local branch"). The checks SHALL NOT push, create a
branch, fetch, or change any file, and a check that waits SHALL stop within its time limit. A dry run that passes SHALL be described as
"likely", never as proof that writing is permitted. The remote address shown SHALL have any password or token removed. With the mode
`none`, only the git-installed and source-branch checks SHALL apply.

#### Scenario: Everything works
- **WHEN** the mode is `push`, the remote answers and the source branch exists
- **THEN** each check is reported passed, and the push check says "likely"

#### Scenario: No remote
- **WHEN** the repository has no `origin`
- **THEN** the remote check fails, says that push and pull requests will be skipped and that sessions still produce a local branch

#### Scenario: Credentials refused
- **WHEN** the remote rejects the credentials
- **THEN** the check fails naming the remote (without secrets) and says which credential source git used when it can tell

#### Scenario: Unreachable host
- **WHEN** the remote does not answer within the time limit
- **THEN** the check fails with "unreachable" and the other checks are still reported

#### Scenario: Mode none
- **WHEN** the mode is `none`
- **THEN** no remote check is run and the report says why

### Requirement: The installer reports git readiness and records the source branch
`appmod init` SHALL run the git checks for the target repository, show their results, and SHALL still install the kit when a check
fails or warns. It SHALL show the detected default branch and how it was found, list the repository's branches, and, when it runs in an
interactive terminal, let the person confirm the default or choose another and SHALL save the choice as the repository's source
branch setting; without a terminal it SHALL save nothing and SHALL say which branch would be used. It SHALL NOT change any file other than the
kit's and the `.appmod/` setting.

#### Scenario: Interactive init
- **WHEN** `appmod init` runs in a terminal in a repository whose remote default is `develop`
- **THEN** it shows `develop` as the detected default, accepts the person's choice, and saves it

#### Scenario: Non-interactive init
- **WHEN** it runs without a terminal
- **THEN** it saves no branch, prints the branch that would be used, and installs the kit

#### Scenario: Git problem at init
- **WHEN** the remote cannot be reached
- **THEN** the kit is installed, the warning names what is lost, and the exit status is success

### Requirement: Readiness and settings views, and a cheap check at publish
The system's readiness check SHALL report the git checks for each allowed repository that has a worktree-isolated subject, one block per
repository, without hiding other items. The settings view SHALL show the publish mode, the pull-request command (without secret values),
the default source branch setting and the include-local-changes default, SHALL read only settings, and SHALL NOT use the network. At
publish, before a session is queued, the system SHALL check that the git program works, the source branch exists and the repository's
saved setting is valid, and SHALL refuse with the reason otherwise.

#### Scenario: Doctor with two repositories
- **WHEN** two allowed repositories are configured and one has no remote
- **THEN** the readiness check shows one block for each, a failure for the repository without a remote, and the other block unaffected

#### Scenario: Config shows settings only
- **WHEN** the operator shows the settings while the remote is unreachable
- **THEN** the output appears at once and contains the mode, the command and the branch settings

#### Scenario: Publish with a missing branch
- **WHEN** a report is published with a source branch that does not exist
- **THEN** the console refuses with that reason and no session is queued
