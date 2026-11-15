# OpenRewrite on top of app modernization

**Why**: many migration changes are mechanical (Java language level, Spring Boot property renames, `javax`→`jakarta`,
dependency version bumps). OpenRewrite recipes do them deterministically, reproducibly and cheaply. The AI is then
spent on what recipes cannot do (compile errors, behavioural changes, judgement), and app modernization's planner is
told – via the rulebook `20-openrewrite-first.md` – to treat recipe output as done.

## How OpenRewrite is used

The kit's skill `openrewrite-recipes` and agent `appmod-openrewrite`: a developer or an agent runs the Maven or Gradle command below, builds, and commits as
`appmod(openrewrite): <recipe>`. The Black Duck flow of the engine reaches the same recipes when the front-door agent hands a mechanical change to `appmod-openrewrite`.

## How recipes run without touching build files

Maven:
```
mvn -B -U -DskipTests org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run \
  -Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-migrate-java:RELEASE \
  -Drewrite.activeRecipes=org.openrewrite.java.migrate.UpgradeToJava21 \
  -Drewrite.exportDatatables=true [-Drewrite.configLocation=.appmod/rewrite.yml]
```
Gradle: `gradle --init-script .appmod/rewrite-init.gradle rewriteRun -Dappmod.rewriteRecipes=… -Dappmod.rewriteArtifacts=…`
(the init script applies the plugin, adds artifacts and an optional repository).

## Recipe catalog

The skill carries a short catalog of the recipes the kit uses (`.github/skills/openrewrite-recipes/recipe-catalog.md`): Java 17 and 21 upgrades, `javax` to `jakarta`,
Spring Boot upgrades, and the dependency-version recipes `org.openrewrite.maven.UpgradeDependencyVersion`, `UpgradeParentVersion` and `org.openrewrite.gradle.UpgradeDependencyVersion`.
A run was checked on 2026-11-15: `rewrite-maven-plugin:6.46.1` with a declarative `UpgradeDependencyVersion` recipe changed three versions in a `pom.xml` (BUILD SUCCESS, 29 s).

## Artifact access – check before rollout

Recipe artifacts are resolved by Maven/Gradle from your repositories. The current OpenRewrite documentation notes
that recipe artifacts are served from a credentialed repository for some editions; in enterprises the usual setup is
an Artifactory/Nexus remote repository. Configure:

- Maven: the build user's `~/.m2/settings.xml` (mirror and credentials).
- Gradle: a repository URL in the init script `.github/appmod/tools/rewrite-init.gradle`.

Licensing: some recipes are not in the open-source catalog (for example `DependencyVulnerabilityCheck`). The kit does not need them: vulnerability detection comes from app modernization and the dependency advisor.
