# Spec Delta

## MODIFIED Requirements

### Requirement: A worktree for each fix session
Unless a subject is configured with isolation `none`, the system SHALL create a git worktree for each session before it opens the
session's window, and SHALL run the agent with that worktree as its working directory. The worktree SHALL be named
`<repository folder name>-fix-<first 8 characters of the session id>`, SHALL be a new folder next to the repository, SHALL lie
inside an allowed root, and SHALL be on a new branch named `appmod/fix-<first 8 characters of the session id>`. The system SHALL NOT
switch the branch of the repository's own checkout and SHALL NOT change its files. The system SHALL NOT push, SHALL NOT delete a
worktree on its own, and SHALL NOT delete a branch. A worktree folder is removed only when an operator asks for it for a finished
session.

#### Scenario: Worktree created before the window opens
- **WHEN** a queued session is started
- **THEN** the worktree and its branch exist and are recorded before the window opens, and the agent's working directory is the
  worktree

#### Scenario: The person's checkout is untouched
- **WHEN** a session runs and ends while the person has uncommitted changes in the repository's checkout
- **THEN** the checkout is on the same branch, at the same commit, with the same uncommitted changes as before

#### Scenario: Worktree outside the allowed roots
- **WHEN** the folder the worktree would be created in lies outside every allowed root
- **THEN** no worktree is created, the session ends as `ERROR` with that reason, and the next queued session still starts

#### Scenario: Isolation turned off
- **WHEN** a subject is configured with isolation `none`
- **THEN** its sessions run in the repository's own checkout, and no worktree is created or recorded

#### Scenario: Worktree cannot be created
- **WHEN** git refuses to create the worktree (for example the folder or the branch already exists)
- **THEN** the session ends as `ERROR` with the reason, nothing is left behind from the attempt, and the next queued session still
  starts

#### Scenario: Nothing is removed on its own
- **WHEN** sessions end, the engine restarts, or a long time passes
- **THEN** no worktree folder and no branch is removed unless an operator asked for the cleanup of that session

## ADDED Requirements

### Requirement: Operator cleanup of a finished session's worktree
The system SHALL provide an operator console command, `cleanup <session id>`, that removes the worktree folder of a finished
session. Without the confirmation option `--yes` it SHALL remove nothing and SHALL say which folder it would remove and that the branch
is kept. With the confirmation it SHALL remove only a folder that git lists as a worktree of the session's repository, that has the
name and branch the system made, and that holds no uncommitted work other than the agent's reports under `.appmod/`; otherwise it
SHALL refuse and say why. It SHALL refuse for a session that is unknown, not finished, without a worktree, or whose folder is already
gone, and while another session is queued or running in the same repository. It SHALL keep the branch and its commits. After a removal
it SHALL add one event, `worktree removed`, to the session's timeline and SHALL leave the state and every other record unchanged.

#### Scenario: Asking without confirming
- **WHEN** the operator runs `cleanup <id>` for a finished session whose worktree exists
- **THEN** nothing is removed, and the answer names the folder, says that the branch and its commits stay, and says to add `--yes`

#### Scenario: Confirmed cleanup
- **WHEN** the operator runs `cleanup <id> --yes` for a finished session whose worktree holds only committed work and the agent's
  `.appmod/` reports
- **THEN** the folder is gone, git no longer lists it, the branch and its commits are still there, the session's timeline has one
  `worktree removed` event, and the session's state is unchanged

#### Scenario: Uncommitted work is not thrown away
- **WHEN** the worktree holds a modified or new file outside `.appmod/` that is not committed
- **THEN** nothing is removed and the answer names that the worktree has uncommitted work

#### Scenario: Not a worktree of this repository
- **WHEN** the recorded folder is not listed by git as a worktree of the session's repository, or is the repository's own checkout
- **THEN** nothing is removed and the answer says so

#### Scenario: Session not finished, unknown or without a worktree
- **WHEN** the session is queued or in progress, does not exist, or ran in the repository's own checkout
- **THEN** nothing is removed and the answer says which of these it is

#### Scenario: Already removed
- **WHEN** the worktree folder no longer exists
- **THEN** nothing is removed and the answer says that the folder is already gone

#### Scenario: Another session in the repository
- **WHEN** another session is queued or in progress in the same repository
- **THEN** nothing is removed until that session has ended

#### Scenario: Reopening after cleanup
- **WHEN** the operator reopens a session whose worktree was removed by the cleanup
- **THEN** the reopen is refused because the worktree no longer exists
