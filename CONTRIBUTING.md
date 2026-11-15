# Contributing

Thanks for looking at this. The project is small enough to understand in an afternoon, and every behaviour has a spec and a test or a recorded run. Read [README.md](README.md) first, then this file.

## What is where

| Folder | What | Build and test |
|---|---|---|
| `engine/` | The engine: NATS intake, git worktrees, the agent launcher (Copilot or Claude Code), session records (H2), the Spring Shell console, a read API. Java 25, Spring Boot 4. Package `dev.subscriber`. | `cd engine` then `gradlew.bat build` (`./gradlew build` on Linux). Opt-in: `-Pnats-tests` (the tests start a NATS server in a Docker container, image `nats:2.10-alpine`, so a running Docker daemon is all they need; `NATS_SERVER_EXE` pointing at a `nats-server` program is used instead when set), `-Pwindow-tests` (opens real windows). |
| `kit/` | What is installed into the repository to be fixed: agents, skills, rules, single-file Java tools, the installer (`install/KitInstaller.java`) and `appmod.cmd`. | `java tests/installer/InstallerTests.java`, `java tests/installer/GitStepTests.java`, `java tests/security-audit/ToolTests.java`, `java tests/security-audit/ValidatorTests.java`, `java tests/security-fixer/ToolTests.java` (from `kit/`; each exits 0 when everything passes). |
| `intel-service/` | The vulnerability-intelligence service: daily feed ingestion, one MCP tool (`listVulnerabilities`), a JobRunr dashboard. Java 25, Spring Boot. Package `dev.intel`. | `cd intel-service` then `gradlew.bat build`. |
| `openspec/` | The specs (`specs/`) and every change that made them (`changes/`, archived ones under `changes/archive/`). | `openspec validate --specs --strict` |
| `docs/` | Features with their proof, evidence of the real runs, posts. | |

You need a JDK 25 (Gradle builds with a Java 25 toolchain; tell Gradle where it is in your user-level `~/.gradle/gradle.properties`, never in the project) and Git. Node is not needed for anything here.

## How a change is made: OpenSpec

Behaviour is specified before it is written, in [OpenSpec](https://github.com/Fission-AI/OpenSpec). A change is a folder `openspec/changes/<name>/` with `proposal.md` (why and what), `design.md` (how), a spec delta under
`specs/<capability>/spec.md` (requirements with scenarios) and `tasks.md` (each task says how it is verified). The usual loop, with the OpenSpec CLI or its slash commands:

1. `openspec new change <name>`, then write the four artifacts; `openspec validate <name> --strict` must pass.
2. Implement the tasks; tick each one only when its verification has run.
3. Record the proof: a test name or a recorded run in the matching `spec-coverage.md` (`engine/docs/spec-coverage.md`, `kit/tests/*/spec-coverage.md`, `intel-service/docs/spec-coverage.md`).
4. `openspec archive <name> --yes` merges the delta into `openspec/specs/`.

Look at `openspec/changes/archive/` for finished examples: `2026-11-14-engine-claude-code` (a feature across the engine), `2026-11-14-dual-tool-kit` (the installer), the `engine-publish-command` and `kit-java-only` changes (a console command; removing a runtime).

## Rules we hold ourselves to

- **A requirement without a proof is not done.** Prefer a test; for anything about a real agent or a real tool, run it for real and record what happened, including what failed. Say plainly what was not run.
- **Mutation-check new tests:** break the code, see the test fail, restore it.
- **Modern Java 25 style:** text blocks with `formatted()`, `_` for unused variables, records and sealed types, no string concatenation chains where a text block reads better. Match the surrounding code.
- **The kit's tools are single source files** run with `java File.java` and no dependencies (Java 25 runs the other source files of the same folder, as `ValidateFindings.java` does with `Json.java`). Do not add a `package` line to one of them: the launcher of newer JDKs refuses it.
- **No new runtime.** The engine, the kit and the validators need a JDK and nothing else. (The Microsoft Copilot app modernization plugin needs Node; that is the plugin's requirement, not ours.)
- **Security defaults stay safe:** agents run on an allow-list, reports are data, a headless engine refuses to start without a permission decision, credentials never reach a log. A change that loosens one of these needs its own spec scenario.
- **Never edit the vendored security-audit skill silently.** It is pinned to an upstream commit; every local edit is listed in `kit/.github/skills/security-audit/UPSTREAM.md`.

## Where to add things

- **A scanner or a subject** (a new kind of report): add an entry to `subscriber.subjects` in `engine/src/main/resources/application.yml` (`subject`, `source`, `agent`, and a fixed `repository` or a `report-location`); the intake needs no code. Document it in `engine/docs/integrations.md` and run it for real.
- **A console command:** a method annotated `@Command` in `engine/src/main/java/dev/subscriber/console/ConsoleCommands.java`, with its logic in a service (see `CleanupService`, `ReportPublisher`), and a test that runs it through Spring Shell's own parser (`ConsoleCommandsTest.run`).
- **A coding agent besides Copilot and Claude Code:** the `AgentTool` enum in `SubscriberProperties`, the launch templates in `engine/src/main/resources/launcher/` (one set per tool), `LauncherScripts`, and the golden files in `engine/src/test/resources/golden/`.
- **An agent or skill for the kit:** `kit/.github/agents/<name>.agent.md` or `kit/.github/skills/<name>/SKILL.md`; the installer renders the Claude Code versions from them (`KitInstaller.mapTools` maps the tool names; an MCP tool is written `server/tool`). Add it to the tests that count agents and skills.
- **A kit tool:** a single Java file in `kit/.github/appmod/tools/` and a test in `kit/tests/`.

## Checks before you commit or push

One command runs everything a change must not break: `java scripts/Verify.java` (see [AGENTS.md](AGENTS.md) for what each flag does). It runs the kit's five test programs, the engine's
build and tests, the intel-service's build and tests, and `openspec validate --specs --strict`, prints a summary table, and exits non-zero if anything failed. Install the git hooks once with
`git config core.hooksPath .githooks`: `pre-commit` runs `--quick` and `pre-push` runs everything. CI (`.github/workflows/ci.yml`) runs the same command on Linux and Windows for every push
and pull request. The engine's live NATS tests are opt-in (`--live`). They start their own NATS server in a Docker container on a free loopback port and remove it afterwards (`NatsTestServer` in the engine's tests), so you only need a running Docker daemon; set `NATS_SERVER_EXE` to use a local `nats-server` program instead, and `NATS_DOCKER_IMAGE` to use another image. CI runs them in a separate Linux job (`live` in `.github/workflows/ci.yml`), because the Windows runners cannot run Linux containers; run them yourself when you touch the NATS code.

## Pull requests

1. Fork, branch, and keep the change to one OpenSpec change.
2. `java scripts/Verify.java` reads `READY`, and `openspec validate <your change> --strict` is green.
3. In the PR description: the change name, what you ran for real and what you did not, and any defect the real run found.
4. Do not commit generated output (`build/`, `.gradle/`, `data/`, `.appmod/`).
