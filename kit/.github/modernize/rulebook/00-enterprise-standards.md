# Enterprise standards (global rulebook)

> Read by the GitHub Copilot app-modernization **planner** from `.github/modernize/rulebook/`.
> Rulebook policies override assessment recommendations.

## Target platform

- **Java:** 21 (LTS) is the default target. Java 17 is acceptable only if a dependency blocks 21
  and the plan states which one.
- **Spring Boot:** latest 3.x minor supported by the organisation (default `3.5`).
- **Jakarta EE:** `jakarta.*` namespaces only after migration; no mixed `javax`/`jakarta`
  servlet, persistence, or validation APIs.
- **Build:** keep the project's existing build tool (Maven or Gradle). Use the wrapper if present.
  Do not convert Maven ↔ Gradle.

## Plan shape

- Order: **build/tooling → language level → framework → namespace (javax→jakarta) → dependencies/CVEs → code cleanup**.
- Each task must be independently buildable and committed separately.
- Any task that changes public API, DB schema, or wire formats must be flagged
  `requiresHumanReview: true` in `tasks.json`.

## Out of scope unless the job asks

- Cloud/Azure re-platforming.
- Re-architecture (monolith decomposition, UI rewrites).
- Formatting-only or style-only changes across the codebase.
