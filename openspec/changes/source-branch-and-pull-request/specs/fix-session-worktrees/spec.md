# Spec Delta

## MODIFIED Requirements

### Requirement: A worktree for each fix session
Unless a subject is configured with isolation `none`, the system SHALL create a git worktree for each session before it opens the
session's window, and SHALL run the agent with that worktree as its working directory. The worktree SHALL be named
`<repository folder name>-fix-<first 8 characters of the session id>`, SHALL be a new folder next to the repository, SHALL lie
inside an allowed root, and SHALL be on a new branch named `appmod/fix-<first 8 characters of the session id>`. The system SHALL NOT
switch the branch of the repository's own checkout and SHALL NOT change its files. The system SHALL NOT push, except that after a
session has ended it pushes the session's own branch when the capability `branch-publishing` says so, SHALL NOT delete a
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

#### Scenario: Nothing is pushed unless asked
- **WHEN** the publish mode is `none`
- **THEN** no session pushes anything, and the agent is never given permission to push

### Requirement: Base commit of the worktree
The worktree SHALL start from the commit chosen as the capability `source-branch-selection` describes: the source branch given at
publish, the repository's saved source branch, the commit named by the report's `commit` column when the report is a CSV and the commit
exists in the repository, the repository's default branch, or its `HEAD`, in that order. The cell is read as the audit tool writes it:
the commit id alone, or `commit=<id>, dirty=<true|false>`. The commit used SHALL be recorded with the session, and when a commit the
report names could not be used the session's events SHALL say why. Uncommitted changes of the person's checkout SHALL NOT be part of
the worktree unless the person asked for them when publishing.

#### Scenario: Audited commit used
- **WHEN** an audit report names a commit that exists in the repository and no source branch is given or saved
- **THEN** the worktree starts from that commit and the session records it as the base commit

#### Scenario: Audit ran on a modified tree
- **WHEN** the report's commit cell says `dirty=true` and the commit exists in the repository and is the one used
- **THEN** the worktree still starts from that commit and an event says that the audited working tree had uncommitted changes, so
  line numbers may differ

#### Scenario: Audited commit unusable
- **WHEN** the report names a commit that is not in the repository, or has no commit, and no source branch is given or saved
- **THEN** the worktree starts from the repository's default branch, and an event states that the report's commit was not used and why

#### Scenario: A source branch overrides the report
- **WHEN** a source branch is given or saved and the report names another commit
- **THEN** the worktree starts from the source branch and an event states that the report's commit was not used
