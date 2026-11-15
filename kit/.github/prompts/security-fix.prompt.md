---
agent: security-fixer
description: "Fix the issues of a security audit CSV, one tested commit per issue, in a git worktree (changes code)"
---

Fix the issues of the security audit report.

- Report (the CSV the security-audit agent wrote): ${input:report:.appmod/security-audit/issues.csv}
- Only these issue fingerprints, comma separated (empty = every issue): ${input:fingerprints:}

Work in a git worktree on your own branch, not in this checkout, unless I tell you otherwise. Fix the confirmed issues first, most
severe first, one tested commit per issue; leave unproven issues alone unless you can show from the code that they are real. Never
push. Tell me where the worktree is, which issues you fixed and which you left, and why.
