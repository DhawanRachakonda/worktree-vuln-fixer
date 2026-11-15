# Security guardrails (global rulebook)

## CVE remediation policy

- Fix **Critical** and **High** CVEs in every remediation job. Fix **Medium** when the fix is a
  patch/minor upgrade with no API change.
- Prefer, in order: (1) upgrade the direct dependency, (2) upgrade the managing BOM/parent,
  (3) pin the transitive version in `dependencyManagement` / Gradle `constraints` with a
  comment `// appmod: CVE-XXXX-YYYY`, (4) exclude the transitive dependency only if unused.
- Never downgrade a dependency to remove a CVE.
- Never add a suppression file entry (`dependency-check-suppressions.xml`, etc.) without a
  `requiresHumanReview: true` task.
- Major-version upgrades for CVE reasons require a separate plan task with a migration note.

## Code guardrails

- No new dependencies from outside the organisation's approved repositories
  (Maven Central via the internal mirror).
- No `--add-opens` / `--illegal-access` JVM flags as a "fix".
- No disabling of TLS verification, CSRF, or authentication filters.
