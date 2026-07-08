# PLAN v2: box-branch reconciliation — grounded in the 2026-06-30 live-box verification

**Supersedes** `PLAN-box-branch-reconciliation.md` (v1, 2026-06-16). Read v1 for the
original Group A/B/C framing; this doc corrects it against what is *actually running on
prod* and what `main` *actually contains* after PR #8 merged.

**Verification performed 2026-06-30** (read-only SSH to the box `ubuntu@54.174.228.229`,
`git`/`docker` inspection only — nothing changed). PR #8 (upstream sync + npm) is **merged**;
`main` @ `b35aea0`.

---

## 1. Did the live box still match the 2026-06-16 capture?

**Committed state: YES, exactly.**
- Box checkout `/home/ubuntu/helium-chirpstack-community` is on branch
  `buoy/mqtt-auth-and-expose` @ **`02e9865`** — identical to the capture / to
  `origin/buoy/mqtt-auth-and-expose` (0 ahead / 0 behind).
- Working tree is clean except one untracked pre-capture backup
  (`chirpstack/docker-compose.yml.bak.20260612T074822Z`). No un-pushed commits.

So the captured branch (PR #9) **is a faithful basis** to reconcile from. Good.

**Runtime state: NO — the bind-mounted `/helium/` config has drifted from the repo**, in
specific, now-catalogued ways (§3). This is expected — `/helium/` is hand-maintained and was
never fully repo-tracked — but it is exactly where the 2026-03-10 outage lived, so it drives
the plan.

## 2. What changed since v1 — most of the v1 fears are now moot

`main` advanced (PR #8 upstream sync + npm, PRs #5/#6/#7 Access-shim slices), so it now
already contains most of what v1 wanted to port:

| v1 concern | Reality on 2026-06-30 |
|---|---|
| `68607ad` port eclipse-temurin base | **Moot** — `main`'s `Dockerfile` is `eclipse-temurin:24-jre` (newer). `make back` not broken. |
| `85db91f` mosquitto bind-mount must survive | **On `main`** (as `9861086`). `main` and box `chirpstack/docker-compose.yml` are now **byte-identical**. |
| Group A Access-shim commits | **On `main`** via slices #1–6 (`--cherry-pick` confirms patch-equivalents present). |
| "main would downgrade ChirpStack 4.15→4.11" (my earlier claim) | **Wrong / nuanced** — see §3. `main`'s pins (4.11.0/4.14.1) *match the running containers*; the 4.15.0 is an **un-applied** edit sitting only in the box's runtime compose file. |

**Net: the reconciliation is far smaller than v1 assumed.** The compose file is already
reconciled between branches; the remaining work is a short port list (§4), one human decision
(§5), and three runtime drifts to settle (§3).

## 3. Three-way state — running vs deployed-file vs repo (the real drift)

There are **three** distinct states, not two:

| Item | Running container | `/helium/docker-compose.yml` (on-box file) | Repo (`main` == box tip) |
|---|---|---|---|
| chirpstack | **4.11.0** (created 2026-03-03) | 4.15.0 | 4.11.0 |
| chirpstack-rest-api | **4.14.1** | 4.15.0 | 4.14.1 |
| `mosquitto_exporter` creds | (running w/ real creds) | `chirpstack_internal` + **real 24-hex secret** | **`gateway` / `buoy`** ← wrong |
| mosquitto `acl.conf` bind-mount | present | present | present |
| `acl.conf` contents | — | 8 lines | 7 lines (diff = 1 trailing newline; effectively identical) |

**Three drifts to resolve:**

1. **⚠️ PENDING, UN-APPLIED CHIRPSTACK UPGRADE — DECIDED 2026-06-30: stay on 4.11.0.** The
   on-box compose file was hand-bumped 4.11→4.15 on 2026-03-08 but the container (created
   2026-03-03) was never recreated, so prod still *runs* 4.11.0 (= repo pin). **jameson's call:
   keep 4.11.0** (git already pins it) and **revert the box's un-applied 4.15.0 one-off** in
   `/helium/docker-compose.yml` (+ rest-api 4.15.0→4.14.1) so file == running == git. This is
   part of the "box is git-orchestrated, no hard one-offs" principle. Guard the deploy so no
   `docker compose up -d` silently pulls 4.15.

2. **`mosquitto_exporter` creds are wrong in the repo.** Both branch tips hardcode
   `--user gateway --pass buoy`; deployed reality is `chirpstack_internal` + a real secret held
   only in `/helium/docker-compose.yml`. Deploying the repo as-is would break the exporter's
   mosquitto connection. **Fix:** template the creds in the repo (e.g. `@MQTT_INTERNAL_PASSWORD@`)
   and inject the real value at deploy from the box's current file — never commit the secret.
   *Impact if missed: monitoring-only (no LoRa/uplink impact), but a real regression.*

3. **`acl.conf`** — trivial (trailing-newline only). Normalise and move on.

## 4. Real remaining port list (box → `main`)

From `git log --cherry-pick --right-only main...origin/buoy/mqtt-auth-and-expose`, minus the
patch-equivalents already on `main`:

- `82327b1` **Grafana/Prometheus data-dir ownership fix** (touches `makeiteasy.sh`) — port.
- `7ec304d` + `625688a` **MQTT-auth setup + placeholders / Redis stream tuning in
  `makeiteasy.sh`** — `main`'s `makeiteasy.sh` already has *some* MQTT-auth wiring; reconcile
  by hand rather than blind cherry-pick. Confirm the Redis stream tuning actually landed
  somewhere (not visible in `main`'s compose redis block — check `application.properties`).
- `0974204` **exporter creds** — supersede with the templating fix in §3.2 (don't port the
  `gateway/buoy` value).

Everything else in `main..box` is either Group A (already on `main`) or the upstream-sync
delta where **`main` is ahead of the box** (npm/package-lock, gradle wrapper, etc.) — those
flow *to* the box when we deploy `main`, nothing to port.

## 5. Group C — DECIDED 2026-06-30: drop `83b4ab2`, keep `main` (but fix a LoRa-outage gap)

**Decision (jameson): DROP box `83b4ab2`; keep `main`'s implementation.** `main` already
subsumes the feature — it binds mosquitto `0.0.0.0:1883` (external exposure), enforces auth
(`allow_anonymous false` + `password_file` + `acl_file`, i.e. upstream `8ff3c4f` + bind-mount),
and its `MqttLoRaListener.java` is the newer 1208-line version (box's is 1086).

**⚠️ CRITICAL GAP found while confirming — `main` is NOT deployable as-is.** With
`allow_anonymous false`, every internal MQTT client needs valid creds. Three-way state:

| client | `main` (repo) | box `83b4ab2` | deployed (live, working) |
|---|---|---|---|
| gateway bridges ×9 | `chirpstack_internal`/`@MQTT_INTERNAL_PASSWORD@` ✅ | `gateway`/`buoy` | `chirpstack_internal`/secret |
| **region backends ×39** | **`""`/`""` EMPTY** ❌ | `gateway`/`buoy` (wrong) | `chirpstack_internal`/secret |
| mosquitto_exporter | `gateway`/`buoy` ❌ | `gateway`/`buoy` | `chirpstack_internal`/secret |

**Deploying `main` today → ChirpStack's 39 region backends auth to mosquitto with empty creds →
broker rejects (anonymous off) → total LoRa outage.** `main` is internally inconsistent (bridges
templated, region backends blank, exporter hardcoded). Both branches have *also* drifted from the
hand-patched deployed reality — the exact "hard one-off" pattern we are eliminating.

**Reconciliation fix (git-orchestration):** normalise ALL internal MQTT creds in the repo to
`username="chirpstack_internal"` / `password="@MQTT_INTERNAL_PASSWORD@"` — region backends (×39)
AND the exporter — matching how `main` already does the bridges and matching the deployed *user*.
The real secret is injected at deploy from env (`makeiteasy.sh` placeholder substitution, cf.
`625688a`); **never commit the literal secret.** Repo becomes source of truth; `/helium/` stops
being hand-edited.

## 6. Execution

### 6a. Branch built — `reconcile/box-into-main` (2026-06-30) ✅ steps 1–3 done
Worktree `../hcc-reconcile` off `main` @ `b35aea0`. `83b4ab2` dropped (subsumed). Two commits:
- `c4f39fb` — all 39 `region_*.toml` gateway backends + `mosquitto_exporter` →
  `chirpstack_internal` / `@MQTT_INTERNAL_PASSWORD@`.
- `84b631a` — `configuration.properties` (console `MqttLoRaListener`: `mqtt.login`/`mqtt.password`
  were EMPTY on main → also a LoRa-outage gap, now templated) + Redis stream tuning (port of
  `625688a`); `makeiteasy.sh` substitution widened to `*.properties` + `/helium/docker-compose.yml`;
  recursive grafana/prom chown (port of `82327b1`).

Validated: `bash -n` clean; all 40 region/chirpstack TOMLs parse; every `@MQTT_INTERNAL_PASSWORD@`
site is reachable by the widened substitution; no `gateway/buoy` or empty-cred leftovers.
No Java changed (build state == main). `withforwarder.yml` intentionally NOT modified (unused
localhost-only topology; flagged — if ever adopted, apply MQTT auth there too).

### 6b. ⚠️⚠️ DEPLOY TRAP — the substitution is guarded off on the already-provisioned box
`makeiteasy.sh:324` only runs the whole MQTT block `if egrep "@MQTT_INTERNAL_PASSWORD@"
chirpstack.toml`. On the live box that placeholder was substituted long ago, so **the block is
SKIPPED** — deploying the newly-templated files verbatim leaves the literal `@MQTT_INTERNAL_PASSWORD@`
in the region backends / configuration.properties / compose → **ChirpStack auths with the literal
string → total LoRa outage.** The auto-substitution only helps a *fresh* install.
**At deploy you MUST substitute with the box's EXISTING secret** (readable in plaintext from a
deployed file, e.g. `/helium/configuration/helium/configuration.properties` `mqtt.password=`):
```
PW=$(grep '^mqtt.password=' /helium/configuration/helium/configuration.properties | cut -d= -f2)
find /helium/configuration \( -name '*.toml' -o -name '*.properties' \) -exec sed -i "s/@MQTT_INTERNAL_PASSWORD@/$PW/g" {} +
sed -i "s/@MQTT_INTERNAL_PASSWORD@/$PW/g" /helium/docker-compose.yml
```
Then restart. (Longer-term git-orchestration TODO: drive the exporter/compose secret from a
`.env` file so no in-place sed is needed.)

### 6c. Remaining before merge/deploy
1. **Green check — DONE 2026-07-01 ✅.** In the worktree, isolated (never touched the running
   stack): `make front` npm build green (`npm run generate` → `dist/`); backend `./gradlew build`
   on **JDK 21** (temurin:21-jdk container, local host is JDK 8) `BUILD SUCCESSFUL`, boot jar
   produced; **Access-shim suite 23/23 pass** (AccessShimService 3, AccessShimUsers 4,
   AccessJwtVerifier 5, ConsoleBearerMinter 8, ChirpstackBearerMinter 3 — 0 fail/0 error). `make
   back`'s docker step is a trivial `COPY` of the verified jar, not re-run. (Diff is config-only,
   so this confirms `main`+reconcile builds/tests clean; no regression possible from the diff.)
2. **Runtime parity check** vs the box after the §6b substitution (compose/mosquitto/exporter/
   region creds all resolve to `chirpstack_internal` + the real secret).
3. PR `reconcile/box-into-main` → `main`, `/code-review`, merge. **Close PR #9** (capture fulfilled).
4. **Deploy to box**: revert the box's un-applied 4.15.0 one-off first (§3.1 → stay 4.11.0);
   tag rollback image; back up `/helium/front`; apply §6b substitution; build detached;
   `docker compose -f /helium/docker-compose.yml up -d --no-deps helium` (+ recreate mosquitto/
   chirpstack only as intended — **do not** let a blanket `up -d` pull 4.15); poll log (~1 min
   console blip). **Repoint the box checkout** off `buoy/mqtt-auth-and-expose` onto merged `main`
   only after green.

## 7. Decisions (jameson, 2026-06-30) — all three resolved

- **Q1 (Group C): DROP `83b4ab2`, keep `main`.** See §5 — but the empty region-backend creds
  gap MUST be fixed as part of the reconcile (LoRa-outage risk).
- **Q2 (chirpstack version): 4.11.0 → REOPENED by the upstream V1.11.1 notes (see §8a).** `main`
  already carries V1.11.1's full config surface; only the engine image lags. Recommendation is now
  **adopt 4.14.1** (sanctioned + fixes buoy-relevant bugs: bad gateway clocks → TOO_EARLY, non-helium
  gateway NPE → external RAK gateways). 4.15.0 rejected regardless. **Awaiting jameson's final call.**
- **Q3 (exporter/creds): standardise on `chirpstack_internal` + `@MQTT_INTERNAL_PASSWORD@`
  (templated), inject secret at deploy.** Applies to region backends AND exporter. See §5.

**Governing principle (jameson): the box must be git-orchestrated — stop hand-editing
`/helium/`.** The repo is the source of truth; runtime config is rendered from git at deploy
(placeholders → real secrets via env/`makeiteasy.sh`). Every current `/helium/` hand-edit
(4.15 bump, literal exporter secret) is drift to be pulled back into git or injected at deploy,
not perpetuated. This is the root cause the reconciliation exists to fix.

## 8. Deployment runbook — reconcile secrets + upstream V1.11.1 (2026-06-30)

### 8a. Version state (measured)
`main` already carries the **entire V1.11.1 config surface** (came in via PR #8): `shm_size: 512m`
on postgres, adr profiles `configuration/chirpstack/adr/{no_adr,force_dr1_adr}.js`, the
`chirpstack.toml` `adr_plugins=[...]` line, and rest-api `4.14.1`. The **only** thing held back
is the ChirpStack **engine image: `main` pins 4.11.0; V1.11.1 targets 4.14.1**. The box's
`/helium` compose says `4.15.0` — a rogue, un-sourced, never-applied value (running container is
still 4.11.0). The **live box is also missing the `adr/` dir** (deploying `main`'s config via
`make init` creates it).

**⇒ Version DECIDED (jameson, 2026-06-30): adopt 4.14.1** (upstream-sanctioned V1.11.1; `main`
already config-aligned; backend fixes — *bad-gateway-clock protection* (→ TOO_EARLY floods) and
*non-helium-gateway NPE* (→ external RAK gateways) — address live buoy issues). Done on the branch:
commit `0ad59d5` bumps the engine 4.11.0→4.14.1 in both composes and purges the rogue 4.15.0
entirely from the repo.

### 8b. The deploy = TWO overlaid change-sets
1. **Upstream V1.11.1 engine bump** (4.11.0 → 4.14.1): only the ChirpStack image tag + ensuring the
   adr dir/config land. All *other* V1.11.1 config changes are already in `main`.
2. **Our secrets reconciliation** (this PR): every internal MQTT client on `chirpstack_internal` +
   templated secret — which requires the §6b manual substitution on the already-provisioned box.

### 8c. Procedure (on the box; adapts the release-notes upgrade steps)
Pre: PR #10 merged to `main`; box checkout still on `buoy/mqtt-auth-and-expose`.
```
# 0. capture the EXISTING mqtt secret before overwriting configs (needed for §6b)
PW=$(grep '^mqtt.password=' /helium/configuration/helium/configuration.properties | cut -d= -f2)

# 1. bring the checkout to merged main
cd ~/helium-chirpstack-community && git fetch origin && git checkout main && git pull origin main

# 2. rollback safety
docker tag chirpstack/chirpstack:4.11.0 chirpstack/chirpstack:rollback-pre-v1111 2>/dev/null || true
cp -a /helium /helium.bak.$(date +%Y%m%dT%H%M%SZ)      # or at least docker-compose.yml + configuration/

# 3. set the ChirpStack engine tag to the DECIDED version in the repo compose, then render /helium
#    (make init copies chirpstack/* -> /helium/, INCLUDING the adr/ dir the box lacks)
#    -> pin chirpstack image to 4.14.1 (or 4.11.0 fallback) in chirpstack/docker-compose.yml first
make init            # NOTE: prompts "Are you really sure?" because /helium exists — answer intentionally

# 4. §6b — substitute the templated secret with the EXISTING one (makeiteasy's auto-subst is guarded OFF here)
find /helium/configuration \( -name '*.toml' -o -name '*.properties' \) -exec sed -i "s/@MQTT_INTERNAL_PASSWORD@/$PW/g" {} +
sed -i "s/@MQTT_INTERNAL_PASSWORD@/$PW/g" /helium/docker-compose.yml
grep -rl '@MQTT_INTERNAL_PASSWORD@' /helium && echo "STOP: unsubstituted placeholder remains" || echo "secrets rendered OK"

# 5. build back if needed (only if backend changed; this PR is config-only, so usually skip)
#    make back

# 6. pull new images + restart (this is the ~packet-loss window the release notes warn about)
cd /helium && docker compose pull --ignore-pull-failures
docker compose stop && docker compose up -d      # recreates chirpstack@4.14.1 + mosquitto/exporter w/ new creds

# 7. verify
docker logs -f helium-helium-1        # console app connects to MQTT (no auth errors) + processes uplinks
docker compose ps                     # chirpstack shows 4.14.1; all up
#   confirm real uplinks flowing + no mosquitto auth failures in `docker logs helium-mosquitto-1`
```
Rollback: restore `/helium.bak.*`, retag chirpstack back to `rollback-pre-v1111`, `up -d`.

### 8d. Post-deploy
Repoint the box checkout onto merged `main` (done in step 1). Going forward the box is git-
orchestrated: config changes flow repo → `make init` → §6b secret render → restart. The `/helium`
hand-edits (4.15 bump, literal secret) are gone. TODO: move the exporter/compose secret to a
`.env` so even step 4's compose `sed` disappears.

## Cross-references
- v1 plan; `docs/drift-matrix.md` (2026-03-10 outage); `docs/HANDOFF-access-jwks-vs-iss.md`.
- Box: `ssh -i ~/.ssh/jameson_macbook.pem ubuntu@54.174.228.229`, checkout
  `/home/ubuntu/helium-chirpstack-community`, runtime `docker compose` at `/helium`.
- Verified read-only 2026-06-30; the real exporter secret lives only in
  `/helium/docker-compose.yml` — **do not commit it**.
