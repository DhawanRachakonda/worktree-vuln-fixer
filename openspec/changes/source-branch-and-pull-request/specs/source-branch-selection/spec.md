# Spec Delta

## Purpose

Lets the person choose which branch a fix session starts from, optionally including uncommitted local work, with a detected default and a
saved per-repository choice, so that fixes are based on the branch the person means and work for many repositories side by side.

## ADDED Requirements

### Requirement: The source branch is chosen per publish or per repository
The system SHALL decide the branch a session's worktree starts from, in this order: the source branch given when the report was
published; the source branch saved for the repository; the commit named by the report's `commit` column when it exists in the
repository; the repository's default branch; the repository's `HEAD`. A source branch SHALL be a branch name made of letters, digits and
`._/-`, that exists as a local branch of the repository or as a remote-tracking branch of `origin`, and the system SHALL create the
worktree from its current tip. When a given or saved source branch is unusable the session SHALL end as `ERROR` with that reason and
SHALL NOT fall back to a lower choice. The commit used, the source branch, and which choice applied SHALL be recorded with the session.
When the report names a commit that differs from the one used, an event SHALL say so.

#### Scenario: Branch given when publishing
- **WHEN** a report is published with the source branch `release/1.2`
- **THEN** the worktree starts from the tip of `release/1.2`, and the session records that branch, that commit and that the choice came
  from the publish

#### Scenario: Saved branch beats the report's commit
- **WHEN** no branch is given, the repository has the saved source branch `develop`, and the report names a different commit
- **THEN** the worktree starts from `develop`, and an event says that the report's commit was not used

#### Scenario: No choice made
- **WHEN** nothing is given or saved and the report names no usable commit
- **THEN** the worktree starts from the repository's default branch, and the session records that it was the detected default

#### Scenario: Unusable branch
- **WHEN** the given or saved source branch does not exist or is not a valid name
- **THEN** the session ends as `ERROR` naming the branch and why, and no worktree is created

### Requirement: The default branch is detected
The system SHALL determine a repository's default branch without asking the person: the branch `origin/HEAD` points to; otherwise the
branch the remote reports for its `HEAD`; otherwise a local `main`, then `master`; otherwise, with no remote, the checked-out branch.
It SHALL say how the answer was found. It SHALL NOT fetch from the remote to find it.

#### Scenario: Remote default known locally
- **WHEN** `origin/HEAD` points to `develop`
- **THEN** the default branch is `develop`, described as the remote's default

#### Scenario: No remote
- **WHEN** the repository has no remote and `main` exists locally
- **THEN** the default branch is `main`, described as a local guess

### Requirement: Local changes can be included on request
When a report is published with the option to include local changes, the system SHALL base the worktree on a commit that holds the
current branch's tip plus the checkout's tracked and untracked changes, made without changing the checkout's branch, files or index.
The snapshot commit SHALL be the base commit of the session, SHALL carry a message that says it holds uncommitted local work, and the
session SHALL record that local changes were included and the branch they came from. Without the option the worktree SHALL NOT contain
uncommitted changes. When there is nothing to include, the worktree SHALL start from the current branch's tip and an event SHALL say so.

#### Scenario: Changes included
- **WHEN** the person has an edited tracked file and a new untracked file and publishes with the option
- **THEN** the worktree contains both, the checkout is unchanged in branch, files and index, and the session records the snapshot
  commit and the branch

#### Scenario: Option not used
- **WHEN** the same changes exist and the option is not given
- **THEN** the worktree contains neither of them

#### Scenario: Nothing to include
- **WHEN** the option is given and the checkout is clean
- **THEN** the worktree starts from the current branch's tip and an event says there was nothing to include

#### Scenario: Ignored files stay out
- **WHEN** the checkout holds files that git ignores
- **THEN** they are not part of the snapshot

### Requirement: Source branch settings are kept per repository
The system SHALL read a repository's saved source branch and its publish mode from a setting inside that repository's `.appmod/`
folder, so that several repositories served by one engine can differ. The engine-wide publish mode and pull-request command SHALL be
the defaults that a repository's setting overrides. A setting that is unreadable or names an invalid branch SHALL be reported by the
readiness check and SHALL make a session for that repository end as `ERROR` with that reason.

#### Scenario: Two repositories
- **WHEN** repository A saves `develop` and repository B saves `main`
- **THEN** sessions for A start from `develop` and sessions for B start from `main`

#### Scenario: Invalid setting
- **WHEN** a repository's saved setting names a branch that does not exist
- **THEN** the readiness check fails for that repository naming the branch, and a session for it ends as `ERROR` with the same reason
