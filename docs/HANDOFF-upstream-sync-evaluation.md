# Handoff: should we incorporate disk91's upstream changes into this fork?

**For:** a fresh agent evaluating the `RepositoryBehindUpstream` monitoring alert.
**From:** the agent that triaged the buoy.fish monitoring alert backlog (2026-06-16).
**Status:** evaluation only — NO merge attempted. This is a *decision* task, then optionally execute.

## The question
buoy.fish runs a fork of disk91's `helium-chirpstack-community` (the LoRaWAN console). A monitor alerts when the fork falls behind upstream. Decide whether to (a) merge/cherry-pick upstream, (b) intentionally diverge and tune the alert, or (c) a mix — then execute.

## Divergence facts (measured 2026-06-16, `git fetch upstream`)
- `origin/main` (buoy-fish) is **6 commits BEHIND** and **18 commits AHEAD** of `upstream/main` (disk91).
- Fork point: **2026-01-29**. So ~4.5 months of divergence; upstream has only moved 6 commits in that time.
- Remotes: `origin` = `git@github.com:buoy-fish/helium-chirpstack-community.git`; `upstream`/`community` = `disk91/helium-chirpstack-community`. **Never push to upstream.**

## The 6 upstream commits — and a value read
All tagged "Common - …", all low-stakes maintenance:
| Commit | What | Value to buoy |
|---|---|---|
| `6ba44af` | gradle upgrade; **nginx gzip + cache-control for JS** (faster console load); rx2 preferred; eu868 region toml | **Highest** — the nginx JS caching is a real perf win. ⚠️ but buoy heavily customizes nginx during the Access cutover; check these `chirpstack/configuration/nginx/*.conf` changes don't fight the buoy deploy. |
| `8f08f0d` | core.js upgrade, drop polyfill | minor dep hygiene; touches `nuxt.config.js` (see conflict below) |
| `6d0f2de` / `3bf69f8` | version bumps; **completes a yarn→npm migration** (deletes `yarn.lock`, standardizes on `package-lock.json`) | mostly mechanical, but the tooling switch is the real decision (below) |
| `5225b9e` / `adbcf75` | captcha fixes on `signupValidation.vue` (disable-captcha bug, double error message) | **Possibly irrelevant** — buoy replaced public signup with the Access shim (match-only, non-public signup per `sign.buoy.fish/docs/adr/0003`). Confirm buoy even uses this signup flow before valuing these. |

## Conflict surface — smaller than it looks
A `git merge-tree` dry-run of `origin/main` vs `upstream/main`:
- **Only ONE source file changed on both sides:** `nuxt/console/pages/.. nuxt/console/nuxt.config.js`. That's the single real merge conflict to resolve by hand.
- `package-lock.json` / `yarn.lock` show as "changed in both" — that's the **yarn→npm migration**, not logic; resolve by picking one tooling and regenerating the lockfile, not by hand-merging 22k lines.
- `gradle-wrapper.properties` differs trivially.
- Buoy's 18-ahead commits are almost entirely **additive new files** (the Access shim — see below), so upstream's 6 commits do NOT touch them. `login.vue` (buoy-modified) is NOT among upstream's changes here, so no conflict there despite earlier worry.

## What MUST survive any merge (buoy's crown jewels = the 18 ahead)
The entire Cloudflare Access SSO shim, none of which exists upstream:
`AccessJwtVerifier`, `AccessShimService`, `AccessShimSetup`, `AccessShimUsers`, `ChirpstackBearerMinter`, `ConsoleBearerMinter`, the `cf.access.*` block in `ConsoleConfig.java` + `application.properties`, `SignInUpApi`/`UserService` hooks, `login.vue` Access handling, and their tests. See **`docs/HANDOFF-access-jwks-vs-iss.md`** (same dir) for how this subsystem works — do NOT duplicate or re-derive it; just ensure a merge preserves it.

## The actual strategic decision: yarn vs npm
Upstream standardized on **npm/`package-lock.json`** and deleted `yarn.lock`. The fork still builds with **yarn**. Any upstream merge forces a choice:
- **Adopt npm** (follow upstream) — eases all future merges, but re-verify buoy's build/CI/Docker that invoke yarn.
- **Stay on yarn** — keep regenerating `yarn.lock` and dropping upstream's `package-lock.json` on every sync; permanent friction.
This decision matters more than the 6 commits themselves.

## Decision options (recommend picking per-item, not all-or-nothing)
1. **Cherry-pick the worthwhile** (`6ba44af` nginx-perf + gradle; skip captcha if signup is shimmed out). Lowest risk, keeps history clean, but does NOT fully clear the alert (still "behind").
2. **Full merge `upstream/main`** into a branch → resolve `nuxt.config.js` + the lockfile/tooling → PR. Clears the alert; forces the yarn/npm decision now.
3. **Declare intentional divergence** and make the monitor informational. Valid for a fork that deliberately leads upstream on auth. See "the alert" below.

A reasonable default: **option 1 + 3** — cherry-pick the nginx/gradle perf, then downgrade the alert to a low-frequency informational nudge, since buoy intentionally diverges and upstream moves slowly (6 commits in 4.5 months).

## The alert (how to actually make `RepositoryBehindUpstream` stop)
`monitoring.buoy.fish` runs `scripts/github-repo-sync-monitor.py` (container `github-repo-sync-monitor:9116`). It calls the GitHub **compare API** (`disk91:main...buoy-fish:main`) and fires `RepositoryBehindUpstream` when the fork lacks upstream commits. It clears only when `origin/main` actually contains upstream's tip (option 1 partial won't fully clear it) — OR by tuning the rule/threshold in `monitoring.buoy.fish` (`prometheus/rules/`) to treat "a few commits behind" as informational. Editing that rule is a `monitoring.buoy.fish` change (PR + deploy), not a change in this repo.

## Cautions / repo state
- **Do NOT push to `upstream`/`community` (disk91).** PRs go to `origin` (buoy-fish) only.
- **Untracked files from other agents are in this tree** (`TODO.md`, `docs/HPR_RECONCILIATION_PLAN.md`, `docs/drift-matrix.md`, `docs/join-captures/`, `lorawan-analyzer/`, `scripts/`, plus `docs/HANDOFF-access-jwks-vs-iss.md`). Branch + add only your files; **work in a git worktree** to isolate (the buoy.fish house style — see CLAUDE.md). Multiple agents touch this repo; if your evaluation overlaps another agent's area, hand off rather than edit in parallel.
- The console shim is currently **match-only** (signature validation deferred — ADR-0003); keep that in mind when judging whether upstream auth/signup changes matter.
- Re-run the divergence numbers yourself (`git fetch upstream && git rev-list --count origin/main..upstream/main`) — they drift.

## Suggested skills
- `diagnose` / general research first to score each upstream commit's relevance to buoy's deployment.
- `tdd` — if you execute a merge, the Access-shim tests are the regression net; run them green before/after.
- `code-review` — before any sync PR.
- `handoff` — if you decide a merge is non-trivial (the yarn/npm switch), hand the execution to a focused agent.

## Cross-references (read, don't duplicate)
- `docs/HANDOFF-access-jwks-vs-iss.md` — the Access shim this fork must preserve.
- `sign.buoy.fish/docs/adr/0002`, `0003` — Access identity / console shim decisions (match-only, non-public signup).
- Monitor: `monitoring.buoy.fish/scripts/github-repo-sync-monitor.py` + its rule in `prometheus/rules/`.
