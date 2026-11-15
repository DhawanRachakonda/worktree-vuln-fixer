# Design

## Context

`WorktreeService` (in `worktree/`) creates a worktree with `git worktree add` through `GitRunner` (argument lists, no shell, hooks switched
off, time limit) and only from validated pieces. `console/ReopenService` is the model for an operator action on a finished session: it finds
the session by UUID, checks the state, refuses with a message, and adds an event. `ConsoleReports.session` prints a hand-typed
`git worktree remove --force` line; the agent leaves untracked files under `.appmod/` in every worktree, so a plain `worktree remove` always
refuses, and `--force` also drops anything else uncommitted.

## Goals / Non-Goals

**Goals:** a person removes a finished session's worktree with one safe command; uncommitted work is never discarded; the engine never
removes anything on its own; the branch always survives.
**Non-Goals:** removing branches, removing worktrees automatically (age or count based), cleanup of sessions that ran with isolation `none`,
a read-API endpoint (the API is read-only by requirement).

## Decisions

- **A console command with a confirmation option, not a prompt.** `cleanup <id>` is a dry run that says what it would do; `cleanup <id> --yes`
  does it. Spring Shell commands here are one line each and are tested through the parser, so an interactive question would add a state to
  test and could hang a scripted console; an option is explicit in the line that was typed.
- **Safety checks come before `git worktree remove --force`, and they are the engine's own, not git's.** Git's refusal would also trigger on
  `.appmod/` (the agent's reports), so the engine runs `git status --porcelain --untracked-files=all` in the worktree and allows only paths
  under `.appmod/`; anything else means refusal. Then `--force` removes the folder.
- **The folder must be proven a linked worktree of the session's repository before anything is removed:** it must appear in
  `git worktree list --porcelain` run in the repository (compared by real path), its name must end in `-fix-<id8>` and its branch must be
  `appmod/fix-<id8>` with the same `<id8>`, and it must lie inside an allowed root. A primary checkout is never in that list as a linked
  worktree of itself in a way that matches these names, and the name check is a second guard.
- **The branch is kept**, since its commits are the product of the session; deleting it is a separate human decision, and the output says
  how many commits it holds when that is known.
- **No schema change.** The removal is one more event (`worktree removed`); "the folder is gone" is read from the file system by
  `session <id>`, as `reopen` already does.
- **Refuse while another session is active in the repository**, as `reopen` does, so no git command races with a worktree being created.
- Alternatives considered: a setting to remove worktrees after N days (rejected: the original rule, that they are the person's review
  material, stands and a timer would remove it unasked); `git worktree remove` without `--force` (rejected: refuses on the agent's own
  reports).

## Risks / Trade-offs

- A worktree in use by an open editor or window can keep its folder on Windows; git then fails and the answer carries git's reason. The
  session is left unchanged and no event is added.
- Ignored build output (for example `node_modules`) is not listed by `git status` and is removed with the folder: intended, it is
  reproducible.
- The agent's reports under `.appmod/` in the worktree are lost; the engine's copy of the audit CSV and the session folder stay.
