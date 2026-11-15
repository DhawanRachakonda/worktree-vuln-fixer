# OpenRewrite-first strategy (global rulebook)

Deterministic refactoring is cheaper, reviewable and repeatable. When running inside the AppMod harness an
**OpenRewrite pre-pass** has already been applied before planning (`.appmod/prepass-summary.md`, commit
`appmod(openrewrite): ...`). When running by hand, run the recipe yourself first (skill `openrewrite-recipes`).

Planning rules:

1. Treat OpenRewrite changes as **already done**. Do not create plan tasks that repeat them.
2. Plan tasks for what OpenRewrite could **not** do: compile errors left behind, behavioural changes, removed APIs
   without a recipe, test failures, configuration migrations needing judgement, CVEs without a clean version bump.
3. Remaining **mechanical** changes (dependency bumps, package/type renames, property renames) are executed with an
   OpenRewrite recipe (tool `openrewrite_run` if present, else the skill's Maven/Gradle command) – not by hand.
4. If the pre-pass failed, the first task is "make the build compile on the current toolchain".
