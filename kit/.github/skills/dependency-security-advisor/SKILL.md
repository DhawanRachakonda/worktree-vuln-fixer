---
name: dependency-security-advisor
description: Find vulnerable third-party dependencies (Maven, Gradle, npm, PyPI), explain their impact on this codebase, and determine the safest upgrade version that avoids compatibility problems. Uses OSV (GitHub Security Advisories, PyPA, npm, Maven), optional NVD, vendor bulletins, and app modernization's CVE validation for Java.
---

# AI Dependency Security Advisor

Developers use open-source packages from Maven Central, npm and PyPI. Vulnerabilities are published continuously in
NVD, GitHub Security Advisories, OSV and vendor bulletins. This skill answers three questions per project:
**which dependencies are vulnerable, what is the real impact here, and what is the safest version to upgrade to.**

## Step 1 – deterministic scan (always first, never guess versions)

Run the advisor (single-file Java, no dependencies):

```
java .github/appmod/tools/DependencyAdvisor.java scan . --out .appmod/advisory
#   --resolve            full Maven tree via `mvn dependency:list` (includes transitive)
#   --extra-advisories D vendor / internal bulletins in OSV JSON format
#   --nvd                CVSS enrichment from NVD (set NVD_API_KEY)
```

In AppMod harness jobs the report already exists (`.appmod/advisory/advisory.{json,md}`) and the
`dependency_advisor_scan` tool is available. It provides, per vulnerable package: advisories (GHSA/CVE ids, severity,
CVSS), **recommended version** = the smallest published, non-yanked, non-pre-release version that is not affected by
*any* known advisory, preferring the current major line; the upgrade type (patch/minor/major), a compatibility-risk
rating, the latest safe version, and the source files that import the package.

For Java projects also call app modernization's `appmod-validate-cves-for-java` (GitHub advisory database) with the
Maven coordinates as a second opinion; report disagreements.

## Step 2 – impact analysis (your judgement)

For each finding, highest severity first:

1. **Reachability** – where is it used? Use the advisor's usage list and the code knowledge graph
   (`uses-package <package prefix>`). Read the advisory text: is the vulnerable function/feature used here
   (e.g. JNDI lookups for log4j, `template()` for lodash, `Session` redirects for requests)? Classify:
   *reachable*, *possibly reachable*, *not reachable* – and say why.
2. **Exposure** – internet-facing endpoint (see graph `endpoints`), internal batch, test-only scope?
3. **Upgrade risk** – read the release notes / changelog between current and recommended version for breaking
   changes; check the project's usage of changed APIs. For transitive dependencies, decide between upgrading the
   parent dependency vs. pinning (Maven `<dependencyManagement>`, npm `overrides`, pip constraint).
4. **Recommendation** – one of: *upgrade to X* (default), *upgrade to latest safe Y* (if X is EOL or has known
   regressions), *replace the library*, *mitigate* (config change) when no fix exists.

Write `.appmod/advisory/advice.md`: a table (package, severity, reachable?, recommendation, risk, effort) followed by
one short section per package with the reasoning and evidence (file paths, advisory links).

## Step 3 – remediation (only when the task asks for it)

- **Java (Maven/Gradle)**: apply version changes with OpenRewrite (`org.openrewrite.maven.UpgradeDependencyVersion`,
  `UpgradeParentVersion`, `org.openrewrite.gradle.UpgradeDependencyVersion`) – or hand the CVE task to the app
  modernization orchestrator, which fixes and verifies CVEs itself.
- **npm**: `npm install <pkg>@<version> --save-exact` (direct) or an `overrides` entry (transitive); commit the lock file.
- **PyPI**: update the pin in `requirements*.txt` / `pyproject.toml`; regenerate the lock file if one exists.
- Build and run tests after each package. If the recommended version breaks the build and the fix is not trivial,
  try the next safe version in the same major line before proposing a major upgrade.
- Re-run the scan at the end; list what is still vulnerable and why in `.appmod/result.json` (`cvesRemaining`).
