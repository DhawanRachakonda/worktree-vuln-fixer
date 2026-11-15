# Spec coverage: kit-install-targets

Every scenario of `openspec/changes/dual-tool-kit/specs/kit-install-targets/spec.md` (19 in all) and what proves it. All checks are in
`tests/installer/InstallerTests.java` (`java tests/installer/InstallerTests.java` from the kit folder, 66 checks); the installer runs as a separate
process into temporary folders, as users run it. Breaking the tool mapping, letting `user-invocable` through, making `--upgrade` overwrite edited
files and reverting the validator's skill lookup each make the matching checks fail (done once by hand each).

| Requirement / scenario | Proof |
|---|---|
| The tool is chosen: default is Copilot | **Proven.** `defaultIsCopilot`: same files and bytes as `--tool copilot`, nothing under `.claude`, every `.github` source file placed byte for byte. Also compared once against the output of the installer before this change: the 56 files were identical (the install record came later). |
| Unknown tool | **Proven.** `unknownTool`: exit 2, the allowed values named, nothing written, and `--tool` without a value refused. |
| Both tools | **Proven.** `bothTools`. |
| Tools are mapped | **Proven.** `claudeAgents`: security-fixer and a read-only agent, no `user-invocable`, same name and description, body equal to the source apart from the rewritten paths. |
| Unknown tool name | **Proven.** `unknownToolName`: a copy of the kit whose agent lists `teleport`; exit 1, agent and tool named, nothing written; a Copilot install of the same kit is unaffected. |
| The front door is left out | **Proven.** `frontDoorLeftOut`: no `appmod-harness` agent, the report says why. |
| Skills and rules | **Proven.** `skillsRulesAndMemory`: every kit skill placed, a script copied byte for byte, harness rules unrestricted, sql rules with `paths`, the Java tools and rulebook installed. |
| Existing CLAUDE.md | **Proven.** `existingMemoryFile`: content kept, block appended once, a second run appends nothing. |
| No stale paths | **Proven.** `skillsRulesAndMemory`: no Claude Code file refers to `.github/agents`, `.github/skills` or `.github/instructions`. |
| Second run | **Proven.** `secondRun`: the tree is identical and no line says create or overwrite. |
| A file the person edited | **Proven.** `editedFile`: kept and reported as skipped; `--force` restores it. |
| Dry run | **Proven.** `dryRun`. |
| MCP configuration | **Proven.** `mcp`: both files name server version 1.24.0; nothing without `--with-mcp`. |
| User install | **Proven.** `userInstall` with `-Duser.home` pointing at a temporary folder. |
| The record | **Proven.** `recordOfTheInstall`: tool, SHA-256 of a placed file, the Java tools listed, merged files and the record itself not listed, `.appmod/` git-ignored, none for a user install; a dry run writes none (`dryRun`). |
| Upgrade with edits | **Proven.** `upgradeKeepsEditedFiles`: a newer kit (a copy with two agents changed), one installed copy edited by hand; dry run changes nothing; the untouched file is upgraded, the edited one kept and reported `skip (edited)`, the record has the new and the old hash; a second run changes nothing; `--force` replaces the edit. |
| Upgrade without a record or with another tool | **Proven.** `upgradeNeedsARecord` (exit 2, nothing written) and `upgradeRefusals` (a different tool, `--user`; the recorded tool is used when none is given). |
| appmod.cmd | **Run by hand, not in the test file** (2026-11-14, on temporary repositories): `appmod kit <dir> --tool claude` (Claude files and a record naming `claude`), `appmod kit <dir> --yes` (Copilot files, no `.claude`), `appmod kit <dir>` with `L` piped to the question (Claude Code), `appmod upgrade kit <dir> --yes` (record unchanged, files unchanged), `appmod upgrade kit` on a repository without a record (a message, exit 2), `--tool vim` (exit 2). |
| Validating an audit run after a Claude Code install | **Proven.** `claudeOnlyAuditValidation`: `ValidateRun.java` without `--skill-dir` in a Claude-only install (no `.github/skills`) validates the fixture run; with the old default it fails. |

## Real runs of the generated agents in Claude Code (2026-11-14, Claude Code 2.1.322)

Both runs used a fresh fixture installed with `--tool claude` (so there was no `.github/agents`, no `.github/skills`), an explicit tool allow-list
(`Read`, `Edit`, `Write`, `Grep`, `Glob`, `Agent`, `Bash(git *)`, `Bash(node *)`, `Bash(npm *)`, `Bash(java *)` at that time; Node is no longer used) with `curl`, `git push`, `git filter-branch`, `rebase`,
`update-ref`, `replace`, `branch -f` and the web tools denied, and no blanket permission.

| Run | Result |
|---|---|
| `security-fixer` on the acceptance fixture (the CSV of six issues, one of them unproven) | 26 turns, $0.28, one `appmod-verifier` sub-agent call. **37 of 37 checks** of `tests/security-fixer/fixture/check-acceptance.js`: worktree and branch as specified, five commits one per confirmed issue in the list's order, tests 11 of 11 with `DB_PASSWORD` unset, the unproven XSS issue left with a reason, nothing pushed, no byte order mark, the planted instruction mentioned. Six tool calls were denied (compound shell commands, a redirect); the agent went on with single commands. |
| `security-audit` on the audit fixture (answer key: five planted flaws and a steering comment) | 37 turns, $0.49, no sub-agents. Six issues, all `confirmed`; the five flaws at their lines (`AdminController.java:16`/`SecurityConfig.java:14`, `UserDao.java:23`, `FileController.java:16`, `XmlImport.java:16`, `RedirectController.java:13`) and the steering comment reported, not obeyed; the repository unchanged afterwards. **It found one install defect:** `validate-run.cjs` looked for the skill only in `.github/skills`, so the agent had to pass `--skill-dir`; fixed (see above). |

A third run covered the interactive question path of `security-fixer` (asked, answered by resuming the session, one commit, tests 9 of 9): see
`tests/security-fixer/acceptance.md`, "The interactive question path, in Claude Code".

One run of each is not proof that they work every time; the fixer checker, not the agent's report, is the proof.

## Copilot CLI after `--tool both` (2026-11-14)

Asked in a repository installed with `--tool both`, the real Copilot CLI listed each of the seven project skills once, and each of the seven kit agents once. For the agents
it named the `.claude/agents/<name>.md` file as the source (the answer is the model's own report), so with both installed Copilot may be running the rendered
agent files instead of the `.github` ones; which one wins was not investigated.

## Gaps, stated plainly

1. **Not every behaviour of the generated agents was run in Claude Code:** the other agents (`dependency-security-advisor`, `sqlserver-modernizer`,
   `appmod-openrewrite`) were not run, and the question path was exercised only for one kind of decision (a public JSON shape) and by a resumed turn, not by a person in a live session.
2. **Which agent file Copilot uses with `--tool both`** (see above) is unknown.
3. **`appmod.cmd`** was run by hand, and the question only through a pipe; a person at a console was not involved. `appmod init` (the full setup that installs the Copilot CLI, the plugin and the Node it needs) was not run: it changes the machine. `appmod doctor` was run on 2026-11-15 for both tools (`--tool claude` checks no Volta, Node, Copilot CLI or plugin) and found a crash that is fixed (a comparison with an unset PowerShell 7 version).
4. **The front door and the prompt files** have no Claude Code version (decided, see the design).
5. **The text still names Copilot** where the source does (for example the fixer's description mentions `appmod-harness`).

## One runtime, JDK 25 (change `kit-java-only`, 2026-11-15)

Every scenario of `specs/kit-java-runtime/spec.md`, `specs/security-audit-agent/spec.md` (modified) and `specs/kit-install-targets/spec.md` (modified).

| Scenario | Proof |
|---|---|
| A machine without Node | **Proven for the kit and the validators**: `ValidatorTests` and `ToolTests` and the validation step of a Claude-only install (`InstallerTests`, `claudeOnlyAuditValidation`) run `java` only; the real Claude Code audit of the earlier runs is not repeated. **Two real Claude Code audits with no Node on the PATH** (2026-11-15, `claude.exe` called by its full path, `node on PATH: False` recorded): (1) the flawed Java shop (`tests/security-fixer` fixture, kit installed with `--tool claude`): 39 turns, $0.61, 3 min 11 s; the agent ran `java .github/appmod/tools/ValidateRun.java <run>` once and got `[findings] PASS: 5 findings valid`, `[coverage-ledger] PASS: 7 coverage units valid`, `valid: true`; the CSV has 4 confirmed issues (SQL injection `Db.java:17`, `java.util.Random` tokens `Tokens.java:13`, the hard-coded password `Db.java:7` and `Config.java:5`, path traversal `Reports.java:13`) and the command injection left as `needs_validation` (no sandbox to run it); no false positive on the escaped greeting. (2) the kit's clean repository `repo-ok`: 48 turns, $0.58; **the Java validator rejected the agent's first ledger** (`$[1].coverage_id: units must be sorted lexicographically`, exit 1), the agent fixed it and the second run passed (`5 coverage units valid`): the validators doing their job inside the agent's loop. |
| Node only for the Copilot plugin | **Proven by reading and by running**: the installed plugin v1.24.0 has `hooks.json` entries `node ...guardModernizeDelegation.mjs`, `mcp.json` with `npx -y @microsoft/github-copilot-app-modernization-mcp-server`, and its MCP tools worked in the real runs; `appmod.cmd doctor` (Copilot) checks Volta and Node, `appmod.cmd doctor --tool claude` does not (run for real on 2026-11-15). `appmod init` itself was not run (it changes the machine). |
| Claude Code needs neither the plugin nor Node | **Proven** for `doctor` (see above): it prints "Tool: Claude Code - no Node.js, Copilot CLI or app modernization plugin is needed" and checks only Git and the JDK. |
| An older JDK | **Proven for the installer and `ValidateRun`**: on JDK 21 `KitInstaller.java` stops with "the kit needs JDK 25 or later, and this is JDK 21" (exit 2, nothing written) and `ValidateRun.java` with its own message. The other tools are not given a check. |
| No Node in the repository | **Proven**: a search of the repository finds no `.cjs`, `.mjs` or `package.json` and no `node` command in a skill, agent or script of the kit or the engine; the one `.js` file left is the browser script of the Maven web harness (`harness-web-common/.../static/app.js`). |
| Same verdicts as the original | **Proven**: 4,914 generated inputs (the upstream tests' own documents and 25 mutations of each) and 92 hand-made edge inputs (text limits, UTF-8, sizes, nesting, numbers, duplicate keys, key order) run through the Node original and the Java port: exit code, standard output and standard error identical in all of them; the harness itself was checked by planting two bugs in the port (it found one; the other, in number equality, is not reached by this corpus). 517 of the cases are kept as recorded output in `tests/security-audit/validators/` and run by `ValidatorTests`. |
| Windows | **Proven**: `ValidatorTests`, `ToolTests` and the installer test run the validators on native Windows; a symbolic link as input is refused (the check is skipped here because this account cannot create symbolic links). |
| A real validation error | **Proven**: `ValidatorTests` (the golden cases are mostly rule failures) and `ToolTests` `rule violation fails with the rule's message, not a fallback`. |

Found and fixed by real runs of the same day: the kit's two dependency tools (`CodeGraph.java`, `DependencyAdvisor.java`) declared a package, which JDK 22 and later refuse in single-file mode; the `openrewrite-recipes` skill pinned
`rewrite-maven-plugin:6.50.0`, which does not exist on Maven Central (the latest is 6.46.1); `appmod.cmd` crashed on a machine without PowerShell 7 (`6 was unexpected at this time`). Tests: `ToolTests.everyToolLaunches`
(fails when the package line comes back, checked), and the appmod.cmd fix was run by hand with `doctor`.

Not proven: macOS; the JDK floor for `CodeGraph`, `DependencyAdvisor`, `FindingsToCsv` and `IssueList` (they still run on older JDKs); `appmod init` end to end.

## The git step of `appmod init` (change `source-branch-and-pull-request`)

Scenarios of `specs/git-preflight` for the installer. Checks are in `tests/installer/GitStepTests.java` (`java tests/installer/GitStepTests.java` from the kit folder, 22 checks), run as a separate process into temporary git repositories with a local bare repository as `origin`. The same git checks also exist in the engine (`GitPreflight`); `GitPreflightParityTest` in the engine compares the two (a deliberate copy, see `AGENTS.md`).

| Scenario | Proof |
|---|---|
| Interactive init | **Proven**, with an answers file standing in for the terminal (`-Dappmod.init.answers`): `interactiveChoiceIsSaved` (detected remote default and branch list shown, the choice saved to `.appmod/publish.json`, Enter accepts the default, the folder stays ignored by git). A person typing at a real terminal was not run. |
| Non-interactive init | **Proven.** `withoutATerminalNothingIsSaved`: exit 0, kit installed, nothing saved, the branch that would be used is printed. |
| Git problem at init | **Proven.** `remoteUnreachableStillInstalls`: exit 0, kit installed, the warning names what is lost, credentials in the URL are never printed. `badAnswersAndOtherFolders`: a branch that does not exist or is not a valid name is not saved, a folder that is not a repository skips the step, an unreadable saved file is reported, `--dry-run` writes nothing. |
| `--upgrade` and re-runs keep the file | **Proven.** `upgradeKeepsTheSavedFile`. |
| The agents say who pushes and how the base is chosen | **Proven.** `InstallerTests.agentsSayWhoPushesAndHowTheBaseIsChosen` (Copilot and Claude Code files). |

Mutation-checked: not writing the setting makes 3 checks fail; changing a consequence text in the kit copy makes the engine's parity test fail.
