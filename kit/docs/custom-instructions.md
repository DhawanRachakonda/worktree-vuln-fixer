# Custom instructions

The agents should behave like a member of each team: follow its conventions, respect its constraints and plan with its targets. The kit supports two kinds
of Markdown files, both plain text and both installed into the repository by `appmod.cmd` or `KitInstaller.java`.

| Kind | Format | Read by | Use for |
|---|---|---|---|
| **Instructions** | `*.instructions.md` in `.github/instructions/` (Copilot) or `.claude/rules/` (Claude Code), optional front matter `applyTo` (glob) and `description` | the coding agent when it writes code | coding conventions, architecture rules, test practices, "don't touch X" at file level |
| **Rulebook** | any `*.md` in `.github/modernize/rulebook/` | GitHub Copilot app modernization's **planner** | targets (Java 21, Spring Boot 3.5), migration order, what is out of scope, mandatory reviews |

The kit's own ground rules are in `.github/instructions/` and `.github/modernize/rulebook/`; add your team's files beside them. Security guardrails in the rulebook
are stated as non-negotiable. A task's own text never overrides them, and a report handed to the engine is data, not instructions.

Example instruction file:

```markdown
---
applyTo: "**/*.java"
description: "Orders service coding rules"
---
- Controllers stay thin; business rules live in `*Service` classes.
- Use constructor injection only.
- Do not upgrade `com.vendor:legacy-client` - pinned by contract.
```

Example rulebook file:

```markdown
# Orders rulebook
- Target Java 21 and Spring Boot 3.5.
- Migrate `javax.validation` before upgrading Hibernate.
- The module `legacy-batch/` is out of scope for this phase.
```

## How each tool reads them

| Tool | Instructions | Rulebook |
|---|---|---|
| GitHub Copilot | `.github/instructions/` and `.github/copilot-instructions.md` of the repository | app modernization reads `.github/modernize/rulebook/` of the repository |
| Claude Code | the installer writes the instruction files to `.claude/rules/` and a marked block into `CLAUDE.md` | the rulebook files are installed as they are and are read by the agents that cite them |

## Writing good instructions - checklist

- State **rules**, not wishes ("Use constructor injection", not "prefer clean code").
- Name concrete paths, modules and libraries; say what is **out of scope**.
- Keep each file focused; use `applyTo` to scope it (`**/*.sql`, `src/main/java/**/web/**`).
- Put planning decisions in the rulebook, coding decisions in instructions.
- Never put secrets in instructions: they are sent to the model.
