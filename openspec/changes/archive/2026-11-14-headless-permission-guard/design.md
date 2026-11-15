# Design

## Context

`LauncherScripts.permissionFlags` gives headless sessions `--allow-all-tools` for `allow-all-tools` and for `ask` (nobody can approve),
and the rules only for the allow-list. `SubscriberProperties.Copilot` defaults the mode to `allow-all-tools`, and `application.yml`
repeats that default in the placeholder `${SUBSCRIBER_COPILOT_PERMISSION_MODE:allow-all-tools}`, so a record cannot tell a mode that
was set from one that was not. `SubscriberProperties.effectiveRunner()` resolves `auto` (window on Windows, headless elsewhere).

## Goals / Non-Goals

**Goals:** a headless engine never runs the agent with a blanket permission by accident; the decision is visible in the start.
**Non-Goals:** changing the window runner, the default of the setting, the allow-list rendering, or adding a new setting.

## Decisions

- **Detect "set" from the Spring `Environment`, not from the record.** The file's default is removed
  (`${SUBSCRIBER_COPILOT_PERMISSION_MODE:}`, empty, like the two rule lists) and the record keeps its own default. A small component,
  `HeadlessPermissionGuard`, reads `subscriber.copilot.permission-mode` from the `Environment`; a non-blank value is "set". No new
  setting is needed and every way of setting it (file, environment variable, command line) counts.
- **Fail at the start, as the other settings do** (an `IllegalStateException` from a bean created at start, with a message that names the
  setting). `ask` is refused outright with headless, since honouring it is impossible and the old behaviour was a silent allow-all.
- **A warning, once**, for the explicit blanket permission, so the choice shows in the log of every unattended run.
- Alternative considered: a new flag `subscriber.copilot.headless-allow-all`. Rejected: a second switch next to the mode that says the
  same thing, and an operator who already wrote `allow-all-tools` would have to say it twice.

## Risks / Trade-offs

- A headless deployment that worked by default stops starting: intended, and the message says what to set.
- The check needs the Spring `Environment`, so it is not part of the record's own validation; unit tests build the guard directly.

## Migration

Set `SUBSCRIBER_COPILOT_PERMISSION_MODE=allow-list` with rules (recommended), or `allow-all-tools` to keep the old behaviour. `ask`
users on a headless engine must pick one of the two.
