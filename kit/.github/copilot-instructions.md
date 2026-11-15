# Repository instructions for GitHub Copilot

This repository uses the **AppMod kit** for modernization work (Java/Spring upgrades, CVE fixes, Azure migration,
dependency security, SQL Server database code).

- **Start every modernization task with the `appmod-harness` agent** (`copilot --agent=appmod-harness`, or pick it in
  VS Code). It is the single entry point: it delegates the migration to GitHub Copilot app modernization
  (`github-copilot-modernization:modernize`) and other work to internal specialist agents.
- Ground rules: `.github/instructions/appmod-harness.instructions.md`.
- Planning policies for app modernization: `.github/modernize/rulebook/`.
- Skills: `openrewrite-recipes`, `code-knowledge-graph`, `dependency-security-advisor`, `tsql-migration`,
  `harness-reporting` (`.github/skills/`). Tools: `.github/appmod/tools/*.java` (run with `java <File>.java`, JDK 25+).

<!-- Merge this file with your existing copilot-instructions.md if you have one. -->
