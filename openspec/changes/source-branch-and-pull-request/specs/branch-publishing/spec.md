# Spec Delta

## Purpose

Lets a finished session's branch reach the remote, and optionally become a pull request against the source branch, on any git host, while
the agent itself never pushes and no host-specific code or stored credential is needed.

## ADDED Requirements

### Requirement: The engine pushes the branch when asked
The system SHALL have a publish mode per session, one of `none`, `push` or `pull-request`, chosen by the repository's setting, else the
engine's setting, else `none`. In `none` the system SHALL NOT contact the remote. In `push` and `pull-request` the system SHALL, after a
session ends as `succeeded` or `partially_succeeded` and has at least one commit on its branch, run `git push` of that branch to the
remote `origin` from the session's worktree, using only the credentials git itself provides, without prompting, within a time limit.
The system SHALL never force-push, SHALL push no branch other than the session's, and SHALL NOT push for any other final state. The agent
SHALL remain forbidden to push and to open pull requests.

#### Scenario: Push after a succeeded session
- **WHEN** the mode is `push` and a session ends `succeeded` with two commits
- **THEN** the branch `appmod/fix-<id8>` exists on `origin` with those commits, and the session records the remote, the branch and the push

#### Scenario: Mode none
- **WHEN** the mode is `none`
- **THEN** no connection to the remote is made and the session records that nothing was published

#### Scenario: Session that did not succeed
- **WHEN** a session ends as `ERROR`, `failed` or without commits
- **THEN** nothing is pushed

#### Scenario: Push fails
- **WHEN** the push is refused or cannot connect
- **THEN** the session keeps its final state and its local branch, an event states the reason (without credentials), and the console
  shows it with the command to retry by hand

### Requirement: A pull request through an operator-chosen command
In `pull-request` mode the system SHALL, after a successful push, run the command template configured for the engine or the repository,
once, in the session's worktree, without a shell, with placeholders for the target branch (the session's source branch), the pushed
branch, a title and the path of a file holding the description, and SHALL record the command's outcome and the first line it prints that
looks like a link. The system SHALL contain no code for any particular git host and SHALL NOT store or log credentials. A template that
is empty, whose program is not found, or that has an unknown placeholder SHALL be reported by the readiness check, and in that case
the session SHALL act as in `push` mode and say why. The title and description SHALL say that the branch was produced by an automated
session and, when local changes were included, that it contains a snapshot of uncommitted local work.

#### Scenario: Command succeeds
- **WHEN** the mode is `pull-request`, the template is `gh pr create --base {base} --head {head} --title {title} --body-file {bodyFile}`,
  and the push succeeded
- **THEN** the command runs once with the source branch as base and the session's branch as head, and the session records that it ran
  and the link it printed

#### Scenario: Command fails
- **WHEN** the command exits with an error
- **THEN** the branch stays pushed, an event records the failure, and the session's final state is unchanged

#### Scenario: No command configured
- **WHEN** the mode is `pull-request` and no template is configured
- **THEN** the branch is pushed, the readiness check reported the missing setting beforehand, and the console prints the pushed branch
  and its target branch so that the person can open the request

#### Scenario: Text from a report cannot become part of the command
- **WHEN** a report's text contains shell syntax or placeholders
- **THEN** none of it is substituted into the command; only the engine-made values fill the placeholders

### Requirement: Local-change sessions need a second confirmation to publish
A session that included local changes SHALL NOT be pushed or turned into a pull request automatically, whatever the mode. The system
SHALL leave it unpublished, and say so, until an operator runs the console command `publish-branch <session id> --yes`, which then
pushes the branch and, in `pull-request` mode, runs the command.

#### Scenario: Unsafe session in push mode
- **WHEN** the mode is `push` and a session that included local changes ends `succeeded`
- **THEN** nothing is pushed, the session says it awaits confirmation and why, and `session` shows the command to confirm

#### Scenario: Confirmed
- **WHEN** the operator runs `publish-branch <id> --yes` for that session
- **THEN** the branch is pushed, an event records it, and the pull-request command runs in `pull-request` mode

#### Scenario: Session that is not finished
- **WHEN** the operator runs `publish-branch` for a session that is not finished, has no commits, or does not exist
- **THEN** nothing is pushed and the console says why
