# Spec Delta

## ADDED Requirements

### Requirement: Worktree paths that Windows can handle
On Windows the system SHALL refuse to create a worktree, before it runs git and without leaving anything behind, when the worktree folder would be longer than 200
characters, or when the folder git keeps for the worktree inside the repository (`<repository>/.git/worktrees/<worktree name>`) would be longer than 230
characters. The refusal SHALL end the session as `ERROR` with a reason that gives the length and the limit and says to use a repository folder with a
shorter path or name, and the next queued session SHALL still start. When git itself refuses to create or remove a worktree, the reason SHALL be git's own
error line (`fatal:` or `error:`) and not the first line of its output.

#### Scenario: Worktree folder too long
- **WHEN** the worktree folder would be 205 characters long on Windows
- **THEN** no git command is run, no branch or worktree exists afterwards, and the reason names the length, the limit of 200 and a shorter path

#### Scenario: Git's own folder too long
- **WHEN** the repository path and the worktree name together would put git's `.git/worktrees/<name>` folder past 230 characters on Windows, although the worktree folder is within its limit
- **THEN** no git command is run, nothing is left behind, and the reason says that git would keep the worktree's own files in a folder that long

#### Scenario: Git refuses
- **WHEN** git refuses to create the worktree (for example a file stands where the folder should go)
- **THEN** the reason shown is git's `fatal:` or `error:` line and does not contain "Preparing worktree"
