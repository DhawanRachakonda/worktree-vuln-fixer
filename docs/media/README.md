# Demo videos

| Video | Length | What it shows |
|---|---|---|
| [demo-source-branch.mp4](demo-source-branch.mp4) | 1 min 8 s | Choosing the base branch, local changes, and the opt-in push. `appmod init`'s git step (checks, detected default branch, branch list, the saved choice), then the **real engine jar driven by the real `nats` command-line client** with the headers `source-branch:release/1.0` (the branch is pushed, a pull-request link is recorded) and `unsafe-local-changes:true` (the session waits for confirmation, nothing is pushed), and a slide on what was real and what was not |

Two earlier videos (a live engine console with Claude Code, and one with GitHub Copilot and OpenRewrite) were removed on 2026-11-15, because they showed `doctor`, `config` and `session` screens from before the source-branch and push features. The runs they were made from are still recorded as text in [docs/evidence](../evidence/). A new video of a live agent session would need new live Claude Code and Copilot sessions.

Setup instructions: [engine/docs/setup.md](../../engine/docs/setup.md). The features in the video: [engine/docs/publishing.md](../../engine/docs/publishing.md). Proof for every claim: [docs/evidence.md](../evidence.md).

## What is real in it, and what is not

- The text of its terminal scenes is the output of real runs on 2026-11-15, taken from [evidence/source-branch-real-engine-run.txt](../evidence/source-branch-real-engine-run.txt) and a real `appmod init` run; only the command lines are shortened (the report path is shown as `<report>`).
- The engine ran with its console off (it needs a terminal), so the results are shown through its read API and the git remote, not through the console: `doctor`, `config`, `publish` and `publish-branch` are therefore not shown in the video (they are proven by the tests listed in [engine/docs/spec-coverage.md](../../engine/docs/spec-coverage.md)).
- In the `appmod init` scene the answer `release/1.0` was read from a file, not typed.
- The coding agent and the pull-request command were stand-ins (a program that commits one file, and one that prints a link). No hosted git service, `gh` or `glab` was used.
- The reveal speed of the text is chosen for reading, not the original timing. The frames are drawn by [scripts/media/RenderFrames.java](../../scripts/media/RenderFrames.java) and encoded with FFmpeg in Docker (see `AGENTS.md`).
- **Slides** are plain text slides that name what each part proves and its caveats.
- **Not in the video:** a real Black Duck or Coverity export (the reports are hand-made samples), a hosted git service, macOS. They are listed in [docs/evidence.md](../evidence.md) under "Not proven".
- The video has no narration; every step is captioned.
