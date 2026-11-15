-- Source branch and publishing of a fix session (standard SQL only). Sessions recorded before this migration keep empty values
-- (local_changes is false and publish_state is NULL, which the engine reads as 'none').

-- the source branch asked for when the report was published (the message header), empty when none was given; used for the duplicate rule
ALTER TABLE fix_session ADD COLUMN source_requested VARCHAR(255);
-- the branch the worktree started from (empty when it started from a commit that is not a branch tip)
ALTER TABLE fix_session ADD COLUMN source_branch VARCHAR(255);
-- which choice applied: publish, saved, report-commit, default-branch or head
ALTER TABLE fix_session ADD COLUMN source_choice VARCHAR(32);
-- true when the worktree started from a snapshot commit that holds the checkout's uncommitted work
ALTER TABLE fix_session ADD COLUMN local_changes BOOLEAN DEFAULT FALSE NOT NULL;
-- none, awaiting_confirmation, pushed or push_failed
ALTER TABLE fix_session ADD COLUMN publish_state VARCHAR(32);
-- the state of the pull-request step: none, created, failed or skipped
ALTER TABLE fix_session ADD COLUMN pull_request_state VARCHAR(32);
-- the first link the pull-request command printed
ALTER TABLE fix_session ADD COLUMN pull_request_link VARCHAR(1024);
