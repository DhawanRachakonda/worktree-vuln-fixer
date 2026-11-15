# Keeping an installed repository up to date

Four things can change, and each is updated differently. Mixing them up is the usual mistake.

| What changed | Where it lives | How it reaches a repository that has the kit | Command |
|---|---|---|---|
| **The GitHub Copilot app modernization plugin** (Microsoft's: its tools, agents, MCP server) | On the machine, in the Copilot CLI (not in the repository) | You update the plugin on each machine | `appmod upgrade plugin` (or `copilot plugin ...`); `appmod plugin status` shows the version |
| **The kit** (our agents, skills, rulebook, single-file Java tools, rules) | Copied into each repository: `.github/`, `.claude/`, `CLAUDE.md`, `.appmod/kit.json` | You run the installer's upgrade on each repository, then commit the result there | `appmod upgrade kit <dir>` or `java kit/install/KitInstaller.java --target <dir> --upgrade` |
| **The engine** | A jar you build and run | Pull, rebuild, restart (it keeps its sessions in its data directory) | `cd engine`, `gradlew.bat build`, restart the jar |
| **The intel-service** | A jar you build and run | Pull, rebuild, restart (it keeps the feed in its own database) | `cd intel-service`, `gradlew.bat bootJar`, restart the jar |

The plugin is shared by every repository on a machine, so updating it needs no change in any repository. The kit is per repository, so a change to it needs a rollout.

## When the Copilot app modernization plugin was updated and the kit has to follow

This is the maintainer's job, done once in this repository, before anyone upgrades their repositories.

1. **Update the plugin on your machine:** `appmod upgrade plugin`. It reports "updated from vX to vY" or "already at latest".
2. **Find what the kit says about the plugin.** The kit names plugin tools and agents in a few places only:
   `kit/.github/agents/appmod-harness.agent.md` (the front door, which delegates to `github-copilot-modernization:modernize`),
   `kit/.github/agents/dependency-security-advisor.agent.md` and `kit/.github/skills/dependency-security-advisor/SKILL.md` (the plugin's CVE, build and test tools,
   named like `appmod-validate-cves-for-java`), `kit/.github/copilot-instructions.md`, `kit/.github/instructions/appmod-harness.instructions.md`,
   `kit/appmod.cmd` (the minimum plugin version, `APPMOD_PLUGIN_MIN`) and `kit/docs/`.
   Search for `github-copilot-modernization`, `appmod-mcp-server` and the tool names you use.
3. **Compare with what the plugin now offers.** List its tools and agents in a Copilot session, and read the plugin's own changelog. If a tool was renamed, removed or
   changed its arguments, change the kit's agents and skills to match. If the plugin now needs a newer Node or Copilot CLI, change `appmod.cmd` and the docs.
4. **Prove it.** Run `java scripts/Verify.java` (the installer tests check that the agents' tool lists are well formed), then run a real Black Duck or CVE job through the plugin
   as in `docs/evidence/bdh-run.txt` and record what happened, including anything the new version broke.
5. **Raise the minimum only if you must.** `APPMOD_PLUGIN_MIN` in `kit/appmod.cmd` says which plugin version the kit was tested with; `appmod doctor` warns below it.
6. **Commit and push.** The kit has no version number; `.appmod/kit.json` in each repository records a short id of the kit content that was installed.

## Applying a newer kit to a repository that has it installed

Run from a clone of this repository, after `git pull`. The repository you are upgrading must already have been installed once (it has `.appmod/kit.json`).

```
# 1. see what would change, and nothing is written
java kit/install/KitInstaller.java --target C:\src\my-repo --upgrade --dry-run

# 2. apply it (on Windows, "appmod upgrade kit C:\src\my-repo" does the same after asking)
java kit/install/KitInstaller.java --target C:\src\my-repo --upgrade

# 3. review and commit it in that repository
git -C C:\src\my-repo status
git -C C:\src\my-repo diff
```

How to read the report:

| Line | Meaning |
|---|---|
| `upgrade` | The kit has a newer version of this file and nobody edited the installed one: it was replaced |
| `unchanged` | Same content, nothing to do |
| `create` | A file that is new in the kit |
| `skip (edited)` | You changed this file after the install: it is kept as it is, and the newer kit version is **not** applied |
| `skip (exists)` | The file exists but the install record does not know it (for example it was there before the kit): kept |
| `skip (merged)` | `copilot-instructions.md` and `CLAUDE.md` get a marked block between `<!-- appmod-kit:start -->` and `<!-- appmod-kit:end -->`; the rest is yours |

- **Your edits are never overwritten unless you say so.** To take the kit's version of one of those files, compare it with `git diff` after a `--force` run, or copy your change out first:
  `java kit/install/KitInstaller.java --target C:\src\my-repo --upgrade --force` replaces every file that differs, edited or not.
- **The tool is remembered.** `--upgrade` reuses the tool recorded at install time (copilot, claude or both). To add the other tool, run a plain install with `--tool both`; passing a different
  `--tool` together with `--upgrade` is refused.
- **Re-running the plain install over an existing one is safe now:** it keeps the record of the files it skips, so a later `--upgrade` still knows which files you edited. (Before this was fixed,
  such a re-run lost that record and `--upgrade` then reported `skip (exists)` for files nobody had edited; the fix is covered by `InstallerTests`.)
- **What is not touched:** your own agents, skills and instructions, and anything outside the kit's file list.
- **Commit the result in the repository that was upgraded**, so everyone who clones it, and every engine session that works on it, uses the same kit. The engine copies nothing; it starts the
  agent in the repository (or its worktree), so the agent sees whatever kit is committed there.

## Upgrading many repositories

For each repository: step 1 (`--dry-run`), step 2, then commit. A repository that was never installed needs `--target <repo> --tool <tool>` first (or `appmod init <repo>` on Windows, which also
installs the tools). The kit is plain files, so a pull request in each repository is the natural way to roll it out and review it.

## What `appmod upgrade` does and does not

`appmod upgrade plugin` updates only the Copilot plugin. `appmod upgrade kit <dir>` previews, asks, and copies kit files. `appmod upgrade copilot`, `node` and `all` update the tools. None of them
touches the engine or the intel-service: those are rebuilt from this repository.
