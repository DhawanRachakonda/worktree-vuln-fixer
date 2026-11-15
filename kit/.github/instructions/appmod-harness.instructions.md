---
applyTo: "**"
description: "Organisation-wide rules for modernization work done with GitHub Copilot app modernization (kit + AppMod harness)"
---

# Modernization ground rules (AppMod kit)

These rules apply to every modernization task in this repository – whether a developer works through the
`appmod-harness` agent (Copilot CLI or VS Code) or the **AppMod harness** web service runs it as a job. You are in a harness job when the folder `.appmod/` exists in the
working directory; then `.appmod/prompt.md` is your task and `.appmod/verify.md` lists what will be checked afterwards.

## Entry point

Users start every modernization task with the **`appmod-harness`** agent. It delegates to GitHub Copilot app
modernization (`github-copilot-modernization:modernize`) and to the kit's internal specialists
(`dependency-security-advisor`, `sqlserver-modernizer`, `appmod-openrewrite`, `appmod-verifier`). If you are one of
those sub-agents, never tell the user to switch agents and never ask the user directly – return the question with
concrete options to the agent that delegated to you. Security audits (CSV list of issues, read-only) are done by the
`security-audit` agent; `appmod-harness` delegates to it, and it can also be started directly. Fixing the findings of an audit
is done by the `security-fixer` agent (it changes code, in a git worktree, one tested commit per issue, never pushing); `appmod-harness`
delegates to it too, and it can also be started directly.

## Always

1. **Use GitHub Copilot app modernization for the migration itself.** Java/Spring upgrades, javax→jakarta, CVE fixes
   and Azure migrations go through the `modernize` orchestrator and its tools (assessment, plan, knowledge base,
   build/test, CVE validation). The planner reads `.github/modernize/rulebook/` – those policies override assessment
   recommendations.
2. **Deterministic first.** Mechanical, repeatable changes (version bumps, renames, framework recipes) are made with
   OpenRewrite (skill `openrewrite-recipes`), not by hand-editing many files. In harness jobs a pre-pass already ran –
   see `.appmod/prepass-summary.md`; do not repeat or revert it unless it broke the build (then say why).
3. **Know the code before changing it.** Use the code knowledge graph (skill `code-knowledge-graph`) for impact
   analysis: who uses this class/package, which endpoints/entities are affected.
4. **Stay inside the repository.** Never read or write outside the working directory. The one exception is the
   `security-audit` agent: the vendored audit skill requires its run directory outside the repository, so that agent
   writes there (and only there, besides `.appmod/`), and it never changes the audited code.
5. **Never push, open PRs, or change git remotes/global config.** Local commits only, one per logical step:
   `appmod(<phase>): <summary>`, e.g. `appmod(upgrade): migrate javax.persistence to jakarta.persistence`.
6. **Keep the build green.** Build with tests after each step (`./mvnw -B verify` / `./gradlew build`, falling back to
   `mvn` / `gradle`). Never delete or `@Disabled` tests to get green – fix the cause or report it.
7. **No secrets** in output, commits or logs. Do not disable TLS verification, CSRF or authentication.
8. **Keep public contracts stable** (REST paths, JSON shapes, DB schema, message formats) unless the task says otherwise.
9. **Report** with skill `harness-reporting`: `.appmod/result.json` and `.appmod/summary.md`.

## Asking questions

- **Interactive** runs: if a decision is genuinely ambiguous (two valid targets, a breaking change), the top-level
  agent asks with the question tool (`ask_user`) and offers concrete choices – the harness web app shows it as a form.
  Sub-agents return the question to their caller instead (see "Entry point").
- **Headless** runs (`mode: headless` in the prompt): never ask. Choose the most conservative option consistent with
  the rulebook, record it under `assumptions`, continue.
- If no question tool is available but you are blocked, follow the "needs input" section of `harness-reporting`.
