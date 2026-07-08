# PLAN: reconcile the prod box branch into `main` (code-review → merge)

**For:** a fresh agent picking this up (drafted 2026-06-16 for tomorrow).
**Goal:** get the prod box's divergent work onto `main` safely, via `/code-review`
then a reconciliation merge — **without** regressing the live LoRaWAN/MQTT path or
clobbering the upstream sync in PR #8.
**Status:** plan only. No reconciliation done yet. Two PRs already exist (below).

## TL;DR
- **PR #8** — `sync/upstream-main` → `main`: upstream (disk91) sync + yarn→npm. Safe,
  prod-untouched. **Merge this FIRST** so `main` is current before reconciling.
- **PR #9** (DRAFT) — `buoy/mqtt-auth-and-expose` → `main`: a **capture** of the exact
  code running on prod (`console.buoy.fish`, EC2 `54.174.228.229`). **DO NOT merge #9
  as-is** — GitHub reports it **13 ahead / 38 behind** `main`; merging would revert
  `main`. It exists so the box state is reviewable and so `85db91f` (an uplink-outage
  fix that had *never been pushed*) is preserved on origin.
- The real work: **port the box's genuinely-unique commits onto `main`**, make **one
  real decision** (which MQTT-external-auth implementation is canonical), prove it green,
  then deploy to the box.

## Why this is a reconciliation, not a merge
`main` and the box branch independently re-did overlapping work. Classify the box's
**13 unique commits** (`git log main..origin/buoy/mqtt-auth-and-expose`):

### Group A — already in `main` as cleaner/squashed commits → **DROP**
Main has these via the TDD Access-shim slices #1–6 and PRs #5/#6. Do not re-port.
- `02e9865` console front default-tenant  (main: `5486021` + PR #6)
- `c14979a` Access Shim: decouple JWKS host from issuer  (main: `b3bd011`/`e5234b5`)
- `f5cf233` Access Shim: mint chirpstack bearer  (main: `754f79d`)
- `1e1421f` Access Shim: remove debug instrumentation  (main: `821985a`)
- `830ca8e` Access Shim: walk-in SSO (ADR-0002)  (main: slices #1–6)
- `aee0aa4` Remove polyfill.io  (main: `32e4ee6`; PR #8 also drops it again)

### Group B — genuinely unique, wanted → **PORT to `main`**
- `85db91f` **mosquitto: bind-mount `password_file`/`acl.conf` (uplink-outage fix)** —
  load-bearing; directly tied to the join-loop outage root-caused in
  `docs/drift-matrix.md`. Must survive.
- `68607ad` **Dockerfile: `eclipse-temurin` base (openjdk:21 tag gone from Docker Hub)** —
  ⚠️ verify whether `main`'s Dockerfile still references `openjdk:21`; if so, **`make
  back` on `main` is currently broken** and this fix is required for any backend build.
- `82327b1` Grafana/Prometheus data-dir ownership fix.
- `7ec304d` MQTT auth setup in `makeiteasy.sh` installer.
- `625688a` MQTT auth placeholders + Redis stream tuning.
- `0974204` MQTT auth creds for `mosquitto_exporter`.

### Group C — competing implementation → **THE decision** (ask jameson)
- `83b4ab2` "expose MQTT broker w/ auth for external gateway access" (buoy's impl, **running
  in prod**) **vs** `main`'s upstream `8ff3c4f` "Common - add MQTT authentication with ACLs
  for external access" (in `main`, **never run on this prod**).
  - Box's version is **battle-tested** (survived the drift-matrix outage + fix). Upstream's
    may have ACL niceties. **Diff them** (`git show 83b4ab2` vs `git show 8ff3c4f`) and pick
    one, or merge the better of each. **This is a human call — surface it, don't guess.**

## Step-by-step

1. **Land PR #8 first.** `/code-review` is already done on it; merge it so `main` carries
   the upstream sync + npm before you reconcile. Re-fetch.
2. **`/code-review` PR #9** — use it as an *inventory/review* of the box-unique changes
   (Group B + C), **not** as a merge candidate. Confirm the Group-A/B/C split above against
   the live diff (`git diff main...origin/buoy/mqtt-auth-and-expose`).
3. **Decide Group C** (MQTT external-auth lineage). Get jameson's call.
4. **Build the reconciliation branch in a worktree** off post-#8 `main`
   (`git worktree add ../hcc-reconcile -b reconcile/box-into-main main`). Cherry-pick / port
   **Group B** (+ the Group-C winner). Resolve overlaps by hand (`makeiteasy.sh`,
   `mosquitto.conf`, `MqttLoRaListener.java` — note `main`'s is newer at 1208 vs box 1086).
5. **Regression net green before & after** (the crown jewels):
   - Access-shim test suite on **JDK 21** (box has it; build in an *isolated* worktree, do
     not disturb the running stack).
   - `make front` (now npm) builds; `make back` (gradle + docker) builds — this is where
     the `eclipse-temurin` fix matters.
6. **Runtime-config parity check.** mosquitto bind-mounts, `mosquitto_exporter` creds, and
   gateway-bridge tomls are **bind-mounted on the box** (`/helium/...`), not all from the
   repo. Confirm the reconciled repo matches what's deployed, or document the deltas — a
   mismatch here is exactly what caused the drift-matrix outage.
7. **PR the reconciliation branch → `main`**, `/code-review`, merge. Then **close PR #9**
   (its purpose — capture/preserve — is fulfilled once its content lands via the recon PR).
8. **Deploy to the box** following the `make front`/`make back` deploy procedure: tag a rollback image
   (`disk91/console:rollback-<reason>`), back up `/helium/front`, build **detached**, deploy
   `docker compose -f /helium/docker-compose.yml up -d --no-deps helium`, poll the log.
   ~1 min console blip. Repoint the box checkout from `buoy/mqtt-auth-and-expose` to the
   merged `main` only after green.

## Hard cautions (read `~/.claude/CLAUDE.md`)
- **Machine traffic stays grey / never break MQTT or LoRa.** The MQTT broker carries
  external-gateway uplinks. A bad mosquitto/auth change = total LoRa outage (see
  `drift-matrix.md`, 2026-03-10). Test MQTT reconnect after any change.
- **`85db91f` must survive** — it was box-local-only until 2026-06-16; it's now on origin
  via PR #9, but make sure the reconciliation actually carries its content into `main`.
- **Work in a git worktree; never force-push a shared branch** (other agents touch this repo;
  memory `no-history-rewrites-in-shared-repos`).
- **Don't deploy `main` to the box until reconciled** (memory `prod-box-on-divergent-branch`).

## Cross-references
- PR #8 (upstream sync + npm); PR #9 (this capture).
- `docs/HANDOFF-upstream-sync-evaluation.md` — how the upstream sync was evaluated.
- `docs/HANDOFF-access-jwks-vs-iss.md` — the Access shim that must stay green.
- `docs/drift-matrix.md` — why the box's MQTT-auth/mosquitto work is load-bearing.
- `sign.buoy.fish/docs/adr/0002`, `0003` — Access identity / console shim decisions.
- Box: `ssh -i ~/.ssh/jameson_macbook.pem ubuntu@54.174.228.229`, checkout
  `/home/ubuntu/helium-chirpstack-community`, runtime `docker compose` at `/helium`.
