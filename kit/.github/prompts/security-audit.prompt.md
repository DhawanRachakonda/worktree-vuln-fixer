---
agent: security-audit
description: "Security-audit this repository and produce a CSV of issues with file and line numbers (read-only)"
---

Security-audit this repository.

- Scope (paths, empty = the whole repository): ${input:scope:}
- Profile (`quick` = fast first look, `standard`, `deep` = large or high-stakes targets): ${input:profile:standard}

Run the full audit workflow with the vendored security-audit skill, do not change any code, and deliver
`.appmod/security-audit/issues.csv` (every issue with its file and line number) and the standard job report. Tell me where the
CSV is, the counts by verdict and severity, and the three most important issues.
