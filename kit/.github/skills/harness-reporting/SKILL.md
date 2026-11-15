---
name: harness-reporting
description: Write the modernization job report (.appmod/result.json and .appmod/summary.md) at the end of a modernization, CVE or dependency job, and the needs-input file when blocked on a human decision without a question tool.
---

# Reporting contract

The AppMod harness (and reviewers using the kit by hand) read these files from the working directory. Write them at
the **end** of the task and update them after follow-up work.

## `.appmod/result.json`

```json
{
  "status": "succeeded | partially_succeeded | failed | needs_input",
  "jobType": "java-upgrade",
  "build": { "status": "passed | failed | not_run", "command": "./mvnw -B verify" },
  "tests": { "passed": 0, "failed": 0, "skipped": 0 },
  "commits": ["<sha> <subject>"],
  "prepass": { "kept": true, "notes": "" },
  "cvesFixed": ["CVE-..."],
  "cvesRemaining": [{ "id": "CVE-...", "reason": "no fixed version published" }],
  "issuesFixed": [{ "fingerprint": "sql:search-concat", "commit": "<sha>" }],
  "issuesLeft": [{ "fingerprint": "xss:profile", "reason": "unproven: could not trace the sanitizer" }],
  "requiresHumanReview": ["<file or topic>"],
  "assumptions": ["<decision taken without asking>"],
  "planPath": ".github/modernize/<plan-name>/plan.md"
}
```

`issuesFixed` and `issuesLeft` are for **security fix jobs** (the `security-fixer` agent); leave them out of other jobs. Each
entry names the issue by the `fingerprint` of the audit CSV: `issuesFixed` with the commit that fixed it, `issuesLeft` with the
reason it was not fixed (unproven and not shown to be real, no safe fix, a fix that broke the tests and was discarded, a decision
that was not the agent's to take).

### What `status` means in a security fix job

| `status` | When |
|---|---|
| `succeeded` | every `confirmed` issue is fixed and the build and the tests pass (as the verifier ran them) |
| `partially_succeeded` | some `confirmed` issue is left, or the final build or the tests fail |
| `needs_input` | the agent stopped on a decision it may not take (headless: see the needs-input file below) |
| `failed` | nothing could be fixed: an unreadable issue list, a baseline that cannot be built, every fix discarded |

The subscriber-engine turns these four into `DONE`, `PARTIAL`, `NEEDS_INPUT` and `ERROR`, and a clean exit without a fresh report
is an `ERROR`, so write the report before the session ends.

## `.appmod/summary.md`

Short, for a reviewer: **What changed**, **Build & tests**, **CVEs** (if any), **Assumptions**, **Needs human review**,
**Next steps**. Factual; no marketing language.

## Needs input (no question tool available)

Write `.appmod/needs-input.json`, set `status` to `needs_input` in `result.json`, and stop:

```json
{
  "message": "Which Spring Boot version should we target?",
  "requestedSchema": {
    "type": "object",
    "properties": {
      "springBootVersion": { "type": "string", "title": "Spring Boot version", "enum": ["3.4", "3.5"], "default": "3.5" },
      "keepJavaxValidation": { "type": "boolean", "title": "Keep javax.validation shim?", "default": false }
    },
    "required": ["springBootVersion"]
  }
}
```

`requestedSchema` follows the MCP (Model Context Protocol) elicitation schema: a flat object whose properties are
`string` (optionally `enum`, `format`), `number`, `integer` or `boolean`. The harness renders it as a form; when run
by hand, the developer answers in chat.
