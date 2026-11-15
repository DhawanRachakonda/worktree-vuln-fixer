# Posts for X (Twitter) and LinkedIn

Copy the block you need. Every number comes from a real run recorded in this repository ([docs/evidence.md](evidence.md)); the reports and fixtures are small hand-made samples and the posts say so.

Links: repository https://github.com/DhawanRachakonda/worktree-vuln-fixer, write-up https://dhawanrachakonda.github.io/post.html?p=scanner-report-to-fix-branch. Attach `docs/media/demo-source-branch.mp4` directly (X and LinkedIn play an uploaded file better than a link).

---

## Short description (repo About, bio, any "what is this" box)

> Worktree Vuln Fixer turns security-audit, Coverity, Black Duck and CVE issue lists into reviewed fix branches. Publish a report and GitHub Copilot or Claude Code fixes each issue in its own git worktree: one tested commit per issue, the agent never pushes (the engine pushes only if you turn that on), your checkout untouched. A built-in intel-service feeds the latest CVE intelligence to the audit. Java 25 only.

---

## X (Twitter): one post (about 250 characters)

```
Worktree Vuln Fixer: publish a Coverity / Black Duck / audit report, Copilot or Claude Code fixes it in its own git worktree. One commit per issue, no push unless you enable it. Live CVE-intel tool included. Proof + videos 👇
https://github.com/DhawanRachakonda/worktree-vuln-fixer #Java #AppSec
```

Attach: `docs/media/demo-source-branch.mp4`.

---

## X (Twitter): a thread of 5

**1/5**
```
Scanner reports pile up. Agents can fix code. Nobody wants an agent writing into their working copy.

Worktree Vuln Fixer sits between them: report in, reviewed fix branch out. Real runs, honest limits 🧵
```

**2/5**
```
Publish a security-audit, Coverity or Black Duck report -> the engine makes a git worktree -> GitHub Copilot or Claude Code fixes one issue per commit -> you review a branch. By default nothing is pushed (an opt-in mode pushes the branch) and your checkout is never touched.
```

**3/5**
```
Real runs on hand-made fixtures: audit CSV -> 5 commits, Coverity CSV -> 5 commits (tests 11/11), Black Duck report -> 2 commits through Copilot app modernization, Java 17 -> 21, OpenRewrite. Logs are in the repo.
```

**4/5**
```
intel-service keeps the latest CVE intelligence one MCP tool away (daily Cloudflare Radar feed, with freshness on every answer). A curated feed, not a full CVE database; caveats are in the README. Real runs found real bugs, and we fixed them.
```

**5/5**
```
One command gates every commit and push, for people and agents: java scripts/Verify.java. Fork it, add a scanner, send a PR.
https://github.com/DhawanRachakonda/worktree-vuln-fixer
https://dhawanrachakonda.github.io/post.html?p=scanner-report-to-fix-branch
```

---

## LinkedIn

```
I explored a way to turn scanner findings into branches I can actually review.

Worktree Vuln Fixer is a small Java 25 engine. You publish a report (a security audit, a Coverity CSV, a Black Duck report) and an AI coding agent (GitHub Copilot or Claude Code, one setting) fixes the issues in its own git worktree: one tested commit per issue, no push unless I turn it on, my checkout untouched. I review a branch.

What I ran, on small hand-made fixtures:
- security-audit CSV: 5 commits (Claude Code)
- Coverity-style CSV: 5 commits, tests 11/11 (Claude Code)
- Black Duck style report: both CVEs fixed in 2 commits through GitHub Copilot app modernization
- Java 17 to 21 upgrade, and an OpenRewrite recipe

It also has an intel-service: a daily vulnerability feed served as one MCP tool, so the audit starts from what is new. It is a curated feed, not a full CVE database, and the README says what to check before using it beyond a trial.

Running it for real found things tests did not: a pinned OpenRewrite version that does not exist, and an agent that could not see its MCP tool. Both are fixed, and the logs are in the repository. Not proven: real vendor exports, macOS.

Every commit and push, by a person or an agent, goes through one command (java scripts/Verify.java), so existing features keep working.

Code, setup guide, evidence and two demo videos (Claude Code, and Copilot with OpenRewrite):
https://github.com/DhawanRachakonda/worktree-vuln-fixer
Write-up: https://dhawanrachakonda.github.io/post.html?p=scanner-report-to-fix-branch

If you work on appsec automation or developer tooling, I would like your feedback or a pull request.

#Java #AppSec #DevSecOps #GitHubCopilot #ClaudeCode
```

Attach: `docs/media/demo-source-branch.mp4`.

---

## New: choose the branch the fixes start from (X post and LinkedIn paragraph)

Proof: `SourceBranchTest` (22 tests) and `SampleRepositoriesTest` (13 tests, small Spring Boot, Node.js and Python repositories with a bare `origin`), real git, in `engine/src/test`. The opt-in push and the pull-request command were also tested (`BranchPublisherTest`, 16 tests), but only against a local bare repository and a stand-in program. Not proven, so not claimed here: any hosted git service (GitHub, GitLab, Bitbucket), `gh`, `glab`. Details: [engine/docs/publishing.md](../engine/docs/publishing.md).

```
Worktree Vuln Fixer now lets you pick the base: publish with --source-branch release/1.2, or save one per repo, or let it use the remote's default branch. --unsafe-local-changes bases the fix on a snapshot of your uncommitted work, without touching your checkout. Tested on Spring, Node and Python sample repos.
```

LinkedIn paragraph:

> A fix session used to start from whatever your checkout had at HEAD. Now you choose: `publish <report> --source-branch release/1.2`, a branch saved per repository in `.appmod/publish.json`, or, with no choice, the repository's default branch (the remote's, then main or master), not the feature branch you happen to be on. `--unsafe-local-changes` bases the worktree on a snapshot commit of your tracked and untracked changes, made with a temporary index so your branch, files and index stay exactly as they were. A wrong or missing branch is refused when you publish, not after the agent has worked. One behaviour changed: a saved branch now beats the commit a report names; an event says when the report's commit was not used.
