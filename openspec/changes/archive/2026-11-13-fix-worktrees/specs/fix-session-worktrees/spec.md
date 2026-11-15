# Spec Delta

## Purpose

Lets every fix session work on its own copy of the repository, a git worktree on its own branch, so that a session never disturbs
the person's checkout, and makes that copy findable afterwards by recording it with the session and showing it to the person.

## ADDED Requirements

### Requirement: A worktree for each fix session
Unless a subject is configured with isolation `none`, the system SHALL create a git worktree for each session before it opens the
session's window, and SHALL run the agent with that worktree as its working directory. The worktree SHALL be named
`<repository folder name>-fix-<first 8 characters of the session id>`, SHALL be a new folder next to the repository, SHALL lie
inside an allowed root, and SHALL be on a new branch named `appmod/fix-<first 8 characters of the session id>`. The system SHALL NOT
switch the branch of the repository's own checkout and SHALL NOT change its files. The system SHALL NOT push, and SHALL NOT delete a
worktree or its branch.

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

### Requirement: Base commit of the worktree
The worktree SHALL start from the commit named by the report's `commit` column when the report has one, the value is a commit that
exists in the repository, and the report is a CSV; otherwise it SHALL start from the repository's `HEAD` at that moment. The cell is read
as the audit tool writes it: the commit id alone, or `commit=<id>, dirty=<true|false>`. The commit
used SHALL be recorded with the session, and when the commit the report names could not be used the session's events SHALL say why.
Uncommitted changes of the person's checkout SHALL NOT be part of the worktree.

#### Scenario: Audited commit used
- **WHEN** an audit report names a commit that exists in the repository
- **THEN** the worktree starts from that commit and the session records it as the base commit

#### Scenario: Audit ran on a modified tree
- **WHEN** the report's commit cell says `dirty=true` and the commit exists in the repository
- **THEN** the worktree still starts from that commit and an event says that the audited working tree had uncommitted changes, so
  line numbers may differ

#### Scenario: Audited commit unusable
- **WHEN** the report names a commit that is not in the repository, or has no commit
- **THEN** the worktree starts from `HEAD`, and an event states that the report's commit was not used and why

### Requirement: The worktree is recorded with the session
The system SHALL store, in the session record in the database, the worktree's name, its folder, its branch, its base commit and the
number of commits made on the branch since the base commit. The first four SHALL be stored when the worktree is created and the
count SHALL be stored when the session reaches a final state, whatever that state is. The session's events SHALL include one event
when the worktree is created and one when the session ends that states the number of commits. For a session without a worktree
these values SHALL be empty.

#### Scenario: Recorded at creation
- **WHEN** the worktree of a session has been created
- **THEN** the record holds its name, folder, branch and base commit, and an event states that the worktree was created

#### Scenario: Commit count at the end
- **WHEN** a session ends after the agent made two commits on the branch
- **THEN** the record holds the count 2 and an event states "2 commits on branch `appmod/fix-<id>`"

#### Scenario: Count kept for sessions that did not succeed
- **WHEN** a session ends as `ERROR` or `INTERRUPTED` after the agent made commits
- **THEN** the count of those commits is recorded all the same

#### Scenario: Records survive a restart
- **WHEN** the system is stopped and started again
- **THEN** the worktree fields of earlier sessions are still there

### Requirement: Results are read from the worktree
The system SHALL look for the agent's report `.appmod/result.json` and take the commit at the end of the session from the session's
worktree, not from the repository's checkout. This SHALL also hold for a session that is finalized or watched again after a restart.
The rule that two sessions for the same repository never run at the same time SHALL still be decided by the repository, not by the
worktree.

#### Scenario: Report in the worktree
- **WHEN** the agent ends and wrote its report in the worktree
- **THEN** the state is derived from that report, and a report left in the checkout is not considered

#### Scenario: Restart while a window is open
- **WHEN** the system restarts while a session's window is still running in its worktree
- **THEN** the session is followed again and, when the window ends, finalized from the worktree

#### Scenario: Same repository
- **WHEN** two queued sessions are for the same repository
- **THEN** the second starts only after the first has ended, although each would have its own worktree

### Requirement: The person is told where the fixes are
The console SHALL show, for a session with a worktree, its name, folder, branch, base commit and commit count, and the git commands
to review and to remove it, and SHALL mark in the session list the sessions that have commits to review. The read API SHALL return
the worktree's name, folder, branch, base commit and commit count with each session. Neither SHALL change a session record or a
worktree.

#### Scenario: Session shown in the console
- **WHEN** an operator shows a session that made commits in its worktree
- **THEN** the output names the worktree, its folder and branch, the number of commits, and how to review and remove it

#### Scenario: List marks sessions with fixes
- **WHEN** an operator lists sessions
- **THEN** a session whose worktree holds commits is marked, and sessions without a worktree or without commits are not

#### Scenario: Read API
- **WHEN** a client asks for a session that has a worktree
- **THEN** the response contains the worktree's name, folder, branch, base commit and commit count

### Requirement: Reopening in the worktree
When a finished session has a worktree, reopening it SHALL open the window in the worktree. It SHALL be refused, saying why, when the
worktree's folder no longer exists.

#### Scenario: Reopen in the worktree
- **WHEN** an operator reopens a finished session whose worktree still exists
- **THEN** the window opens in the worktree with the session's conversation resumed

#### Scenario: Worktree removed by the person
- **WHEN** an operator reopens a session whose worktree folder has been removed
- **THEN** nothing is opened and the console says that the worktree no longer exists
