---
name: openrewrite-recipes
description: Run OpenRewrite recipes for deterministic, repeatable code and build changes (Java/Spring upgrades, javax->jakarta, dependency version bumps for CVE fixes). Use instead of hand-editing many files.
---

# OpenRewrite recipes

OpenRewrite is an automated refactoring engine: a **recipe** is a tested, deterministic
transformation over the code's syntax tree (LST = Lossless Semantic Tree). The harness already
ran a pre-pass (see `.appmod/prepass-summary.md`). Use this skill for anything *additional*.

## Run it

Detect the build tool, then run **from the repository root**:

**Maven** (no pom changes):
```
./mvnw -B -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run \
  -Drewrite.recipeArtifactCoordinates=<artifact,...> \
  -Drewrite.activeRecipes=<recipe,...> \
  [-Drewrite.configLocation=.appmod/rewrite.yml] -Drewrite.exportDatatables=true
```
(use `mvn` if there is no `mvnw`)

**Gradle**:
```
./gradlew --init-script .github/appmod/tools/rewrite-init.gradle rewriteRun \
  -Dappmod.rewriteArtifacts=<artifact,...> -Dappmod.rewriteRecipes=<recipe,...> \
  [-Dappmod.rewriteConfig=.appmod/rewrite.yml]
```

Then: build + test, and commit with `appmod(openrewrite): <recipe>`.

## Recipes that need options → declarative `.appmod/rewrite.yml`

```yaml
type: specs.openrewrite.org/v1beta/recipe
name: com.appmod.CveFixes
displayName: AppMod CVE fixes
recipeList:
  - org.openrewrite.maven.UpgradeDependencyVersion:
      groupId: com.fasterxml.jackson.core
      artifactId: jackson-databind
      newVersion: 2.18.2
      overrideManagedVersion: true
  - org.openrewrite.maven.UpgradeParentVersion:
      groupId: org.springframework.boot
      artifactId: spring-boot-starter-parent
      newVersion: 3.5.x
```
Activate it with `activeRecipes=com.appmod.CveFixes`. For Gradle use
`org.openrewrite.gradle.UpgradeDependencyVersion` (options `groupId`, `artifactId`, `newVersion`).

## Catalog

See [recipe-catalog.md](recipe-catalog.md).
