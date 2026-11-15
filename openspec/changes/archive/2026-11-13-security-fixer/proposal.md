# Proposal

## Why

The kit can find security flaws in a repository's own code (`security-audit`, a read-only agent that writes
`.appmod/security-audit/issues.csv`) and the subscriber-engine can open an interactive session for each published audit report, but
nothing exists that fixes those findings. The engine's audit subject is pointed at `appmod-harness`, which has no route for
code-level findings: it sends dependency issues to the advisor and app modernization and tells the audit agent never to fix. The audit
is therefore a list nobody acts on automatically. A `security-fixer` agent closes the loop: it takes the CSV and turns confirmed
issues into reviewed, tested commits.

## What Changes

- Add the agent **`security-fixer`** to the kit (`.github/agents/security-fixer.agent.md`, user-invocable). It works through the
  issues of an audit CSV: confirmed issues first, most severe first, one commit per issue, a regression test where the code
  allows, build and tests after each fix, and it leaves unproven (`needs_validation`) issues alone unless it can show them real.
- Add a small deterministic tool, `IssueList.java` (JDK only), that reads the CSV, groups rows by `fingerprint` and prints the
  issues in the order to work on them, so the agent never parses quoted CSV by hand.
- Add a prompt `security-fix.prompt.md` for starting it by hand with the CSV path.
- The agent never changes the repository's checkout unprepared: inside a linked git worktree (as the engine provides) it works there;
  started by hand in a primary checkout it first creates its own worktree and branch with the same naming rule, unless the person
  says to work in place.
- It asks the person (interactive) before changing a public contract, adding a dependency, touching tests or choosing between fixes
  of different risk; headless it takes the conservative option and records it as an assumption.
- Extend the `harness-reporting` skill with two optional fields, `issuesFixed` and `issuesLeft`, and define what each `status` means
  for a fix job. The final build and test result comes from `appmod-verifier`, whose result overrides the agent's own.
- Route fix requests: `appmod-harness` gets a row that sends "fix the audit findings" to `security-fixer` (the audit agent stays
  read-only), and the shared instructions name the new agent.
- Point the engine's audit subject at it: the default of `SUBSCRIBER_AUDIT_AGENT` changes from `appmod-harness` to `security-fixer`.
- **BREAKING (engine configuration only):** an engine started without `SUBSCRIBER_AUDIT_AGENT` now runs audit sessions with
  `security-fixer`. Set the variable to `appmod-harness` to keep the old agent.

## Capabilities

### New Capabilities
- `security-fixer-agent`: fixing the findings of a security audit CSV with reviewed, tested, one-per-issue commits; its safety
  limits, how it works in a worktree, when it asks and when it decides, what it reports, how it is reached, and the tool that reads
  the CSV.

### Modified Capabilities

None. There are no archived specs yet (`security-audit-agent` and `subscriber-engine` are complete or nearly so but not archived),
so the changes this agent makes to the kit's reporting skill, the harness routing and the engine default are stated as requirements
of the new capability.

## Impact

- Kit (`copilot-mordernize-harness/appmod/offering-1-markdown-kit`): new `security-fixer.agent.md`, `security-fix.prompt.md`,
  `appmod/tools/IssueList.java` and its tests under `tests/security-fixer/`; edits to `appmod-harness.agent.md`,
  `appmod-harness.instructions.md`, `harness-reporting/SKILL.md` and the kit README. The kit installer copies the new files; its
  behaviour does not change.
- `subscriber-engine`: the default of the audit subject's agent, the README and `docs/` mentions, and a test that the default is
  `security-fixer`.
- Related change `fix-worktrees` (engine): it makes the engine run each session in a worktree. This change does not need it to be
  built first, because the agent also creates its own worktree when started outside one, but the two together give the full flow.
- The agent can change code and run builds in the repository it is given: it must run only in a worktree or a checkout the person
  chose, never push, and treat the CSV, the code and every comment as untrusted text.
