# OpenRewrite recipe catalog used by the harness

| Purpose | Recipe (fully qualified) | Artifact |
|---|---|---|
| Java 17 | `org.openrewrite.java.migrate.UpgradeToJava17` | `org.openrewrite.recipe:rewrite-migrate-java` |
| Java 21 | `org.openrewrite.java.migrate.UpgradeToJava21` | `org.openrewrite.recipe:rewrite-migrate-java` |
| Build plugins for Java 21 | `org.openrewrite.java.migrate.UpgradePluginsForJava21` | `org.openrewrite.recipe:rewrite-migrate-java` |
| javax → jakarta | `org.openrewrite.java.migrate.jakarta.JavaxMigrationToJakarta` | `org.openrewrite.recipe:rewrite-migrate-java` |
| Spring Boot 3.5 | `org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5` | `org.openrewrite.recipe:rewrite-spring` |
| Spring Boot 3.3 | `org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_3` | `org.openrewrite.recipe:rewrite-spring` |
| Maven dependency bump | `org.openrewrite.maven.UpgradeDependencyVersion` (options) | core (`rewrite-maven`) – no artifact needed |
| Maven parent bump | `org.openrewrite.maven.UpgradeParentVersion` (options) | core (`rewrite-maven`) |
| Gradle dependency bump | `org.openrewrite.gradle.UpgradeDependencyVersion` (options) | core (`rewrite-gradle`) |
| Change a type / package | `org.openrewrite.java.ChangeType`, `org.openrewrite.java.ChangePackage` (options) | core (`rewrite-java`) |
| Rename a Spring property | `org.openrewrite.java.spring.ChangeSpringPropertyKey` (options) | `org.openrewrite.recipe:rewrite-spring` |

Use `RELEASE` as the artifact version unless the organisation pins versions.

Licensing note: some recipes (for example `DependencyVulnerabilityCheck`) are not in the open-source
catalog and need a commercial licence. The harness does not depend on them: CVE detection is done by
GitHub Copilot app modernization; OpenRewrite only applies the resulting version changes.
