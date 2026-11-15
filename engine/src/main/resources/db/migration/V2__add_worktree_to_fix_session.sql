-- The git worktree a fix session worked in (standard SQL only). All columns are empty for a session without a worktree: isolation
-- "none", a rejected session, or a session recorded before this migration.

-- the worktree's name, the same as its folder name: <repository folder>-fix-<first 8 characters of the session id>
ALTER TABLE fix_session ADD COLUMN work_name VARCHAR(255);
-- the worktree's folder
ALTER TABLE fix_session ADD COLUMN work_path VARCHAR(1024);
-- the branch the agent commits on: appmod/fix-<first 8 characters of the session id>
ALTER TABLE fix_session ADD COLUMN work_branch VARCHAR(255);
-- the commit the worktree started from
ALTER TABLE fix_session ADD COLUMN base_commit VARCHAR(64);
-- commits made on the branch since the base commit, counted when the session reached a final state
ALTER TABLE fix_session ADD COLUMN fix_commits INTEGER;
