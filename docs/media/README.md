# Demo videos

| Video | Length | What it shows |
|---|---|---|
| [demo-claude.mp4](demo-claude.mp4) | 4 min 49 s | Overview and setup, then the **live engine console** with Claude Code fixing a Coverity-style report (doctor, config, two refusals, publish, sessions, session, events, cleanup), the recorded security-audit run, intel-service, the safety rules, the pre-push check |
| [demo-copilot.mp4](demo-copilot.mp4) | 4 min | Setup for Copilot, then the recorded runs through **GitHub Copilot app modernization**: a Black Duck style report (2 commits, both CVEs fixed), a Java 17 to 21 upgrade, **OpenRewrite**, and the pre-push check |

Setup instructions (also named in both videos): [engine/docs/setup.md](../../engine/docs/setup.md). Proof for every claim: [docs/evidence.md](../evidence.md).

## What is real in them, and what is not

- **Real console, captured as text.** The engine console in `demo-claude.mp4` (the top pane) and the git worktree pane under it are the real screens of a real run on 2026-11-15, read from the console's own screen buffer several times a second and drawn offscreen; the typing in them was done by a script that typed into the real console. The long wait while Claude Code works is sped up (6x) and the caption says so. The same text is in [evidence/live-console-claude-run.txt](../evidence/live-console-claude-run.txt).
- **Recorded runs, shown as replays.** The other terminal scenes show the text of the recorded runs in `docs/evidence/` (security audit, Black Duck, migration, OpenRewrite, intel-service, the verification summary). Their on-screen reveal speed is chosen for readability, so it is not the original timing.
- **Slides** are plain text slides that name what each part proves and its caveats.
- **Not in the videos:** a real Black Duck or Coverity export (the reports are hand-made samples), Copilot with the audit on the newest fixture, macOS. They are listed in [docs/evidence.md](../evidence.md) under "Not proven".
- The videos have no narration; every step is captioned.
