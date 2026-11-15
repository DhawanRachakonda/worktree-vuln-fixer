---
agent: appmod-harness
description: "Fix vulnerable dependencies (advisor for safe versions, app modernization / OpenRewrite to apply them)"
---

Fix vulnerable dependencies in this repository.

- CVE IDs (empty = all with severity ≥ ${input:minimumSeverity:HIGH}): ${input:cveIds:}
- Allow major-version upgrades: ${input:allowMajor:false}

Use the dependency advisor's recommended versions; Java/.NET fixes go through GitHub Copilot app modernization,
npm/PyPI fixes through the advisor. Verify and report what is fixed and what remains.
