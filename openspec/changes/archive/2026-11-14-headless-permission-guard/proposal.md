# Proposal

## Why

When the engine runs the agent headless, nobody can approve a tool. The headless launcher therefore adds `--allow-all-tools` for every
permission mode except the allow-list, and `ask` is no exception: a headless engine configured with `ask` silently runs the agent with
every tool allowed, which is the opposite of what `ask` says. The default mode is also `allow-all-tools`, so a headless engine that
nobody configured (`SUBSCRIBER_RUNNER=headless`, or any platform without windows, where `auto` means headless) gives an unattended agent
the whole machine without anyone having decided it. Starting the agent with a blanket permission should be a decision that someone made.

## What Changes

- The engine **refuses to start** when the effective runner is headless and the permission mode is `ask`: `ask` cannot be honoured
  without a person, and the message tells the operator to choose the allow-list or to set `allow-all-tools` explicitly.
- The engine **refuses to start** when the effective runner is headless and the permission mode is `allow-all-tools` only by default,
  that is, `subscriber.copilot.permission-mode` (`SUBSCRIBER_COPILOT_PERMISSION_MODE`) was not set. Setting it to `allow-all-tools` is
  accepted, and the start logs one warning that unattended agents run with every tool allowed.
- The window runner and the allow-list are unchanged. The default value of the setting stays `allow-all-tools` for the window runner,
  where a person watches.
- **BREAKING**: a headless engine that relied on the default or on `ask` no longer starts until the operator chooses.

## Capabilities

### New Capabilities
- `headless-permission-guard`: an unattended (headless) engine starts only with an explicit permission decision.

### Modified Capabilities

None as a delta: the capability `issue-fix-subscriber` is a main spec now, but this change adds its requirement as a capability of
its own, as `fix-worktrees` did, to keep the permission rules of the headless runner in one place.

## Impact

- `subscriber-engine`: a startup check in `config`, `application.yml` (the setting has no default value in the file, so that "not set"
  can be seen), the `README.md` settings table, `docs/operations.md`, `docs/spec-coverage.md`, the tests of the contexts that run
  headless with `ask` or the default.
- No change to the launcher scripts, the window runner, the allow-list rendering, the NATS subjects or the agents.
