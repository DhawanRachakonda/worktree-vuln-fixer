---
agent: appmod-harness
description: "AI Dependency Security Advisor: vulnerable dependencies, real impact, safest versions (no changes)"
---

Advise on vulnerable dependencies in this repository. Do not change files.

- Analyse in depth from severity: ${input:minimumSeverity:MEDIUM}
- Include transitive Maven dependencies (slower): ${input:resolve:false}

Deliver `.appmod/advisory/advice.md` with reachability evidence, recommended versions and upgrade risk.
