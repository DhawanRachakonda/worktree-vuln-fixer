---
agent: appmod-harness
description: "Upgrade Java / Spring Boot through the AppMod front door (GitHub Copilot app modernization underneath)"
---

Modernize this application.

- Target Java version: ${input:targetJavaVersion:21}
- Target Spring Boot version (empty = keep current): ${input:targetSpringBootVersion:3.5}
- Scope / constraints: ${input:constraints:whole application}

Run the full flow: knowledge graph → OpenRewrite pre-pass → GitHub Copilot app modernization (assess, plan with the
rulebook, execute) → verification → report.
