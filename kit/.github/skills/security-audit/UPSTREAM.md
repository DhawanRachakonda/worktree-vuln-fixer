# Upstream provenance

**Licence decision (2026-11-10):** MIT, Copyright (c) 2025-2026 Cloudflare, Inc. The MIT licence permits copying and
redistribution provided the copyright notice and licence text are kept, so this skill is vendored here with the
upstream `LICENSE` file beside it. Licence source: <https://github.com/cloudflare/security-audit-skill/blob/c1c8a8c1471069fb0e188eeaff69b8e8db6564a8/LICENSE>.

| | |
|---|---|
| Upstream repository | <https://github.com/cloudflare/security-audit-skill> |
| Upstream path | `skills/security-audit/` (plus the repository's `LICENSE`) |
| Pinned commit | `c1c8a8c1471069fb0e188eeaff69b8e8db6564a8` (head of `main`, last pushed 2026-09-14) |
| Vendored on | 2026-11-10 |
| Files as vendored | 21 (the 20 files of `skills/security-audit/` and `LICENSE`), all byte-for-byte identical to upstream |
| Checksum of the upstream files (SHA-256 over the file list) | `ed56b75617172e7782a40a9fd504a93323ffbd78ffe6b0712cfada91978bcc1e` |
| Local changes | yes, see below (2026-11-15) |
| Checksum of this folder now | `178d32dd7b419d6fb9288a2e744f8b02314f236e29144e311154bb715e50e22e` |

## Local changes (2026-11-15)

The kit needs one runtime (a JDK 25) and no Node, so the two JavaScript validators of the upstream skill were ported to Java. The rules,
limits, messages, output and exit codes are the same; the port was checked against the original on 4,914 inputs (the upstream tests' own
documents and mutations of them) and 92 hand-made edge inputs, comparing exit code, standard output and standard error, with no difference.

| Change | Why |
|---|---|
| Removed `validate-findings.cjs`, `validate-coverage-ledger.cjs`, `validate-findings.test.cjs`, `validate-coverage-ledger.test.cjs` | replaced by the Java files below; the cases of the two test files live on as recorded output in `tests/security-audit/validators/` |
| Added `Json.java`, `ValidateFindings.java`, `ValidateCoverageLedger.java` | the ports (`java <skill-dir>/ValidateFindings.java <file>`; Java 25 compiles `Json.java` with them) |
| `RECONNAISSANCE.md`: the command that runs the ledger validator | `node <skill-dir>/validate-coverage-ledger.cjs` became `java <skill-dir>/ValidateCoverageLedger.java` |
| `SKILL.md`: the names of the two validators in step 4 | `validate-findings.cjs` and `validate-coverage-ledger.cjs` became `ValidateFindings.java` and `ValidateCoverageLedger.java` |
| `VALIDATION-AND-REPORTING.md`: the two commands | the same replacement |

To update the pin, diff a new upstream commit against the upstream files only (the checksum of the upstream files above), re-apply the
rows of this table, and port any change of the two validators to the Java files and to the recorded cases.

## Checksum method

The checksum is the SHA-256 of the output of `sha256sum` run on every file in this folder except `UPSTREAM.md`, in
byte order of the file name. To re-check it from this folder:

```
for f in $(ls | grep -v '^UPSTREAM.md$' | LC_ALL=C sort); do sha256sum "$f"; done | sha256sum
```

## Updating the pin

An upstream update is a reviewed change, never an automatic one: download the new commit into an empty directory,
diff it against this folder, read the changes (the skill's rules decide what the audit agent may do), then replace the
files, update the commit, date and checksum above, and re-run the acceptance checks of the
`security-audit-agent` change. List every local edit under "Local changes".

The vendored files are third-party instructions. The `security-audit` agent follows them for the audit method only; they
never override the agent's own rules (read-only, untrusted audited content, output contract).
