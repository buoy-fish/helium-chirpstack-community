# HPR ↔ ChirpStack reconciliation cron — plan

Status: **draft for review**. Written 2026-04-22 after a successful one-time
manual reconciliation; not yet implemented.

## Where we left off

One-time reconciliation completed on 2026-04-22:

- **EUIs**: CS and HPR now agree, 344 pairs each.
  - Added `Boat Hauling Orca (0003)` to HPR (was genuinely missing).
  - Restored non-zero AppEUIs in CS for 12 Oyster-LR devices that had been
    zeroed out ~1 month earlier by an erroneous app change. The correct
    AppEUIs were recovered from two independent sources (HPR itself and
    Disk91's `helium_devices.application_eui`), which agreed bit-for-bit.
- **DevAddrs**: HPR has range `78000190`–`78000197` registered. All 8 distinct
  DevAddrs CS has ever assigned fall inside that range. Nothing to do.
- **SKFs**: not touched. Separate piece of work (see below).

## Goal of the cron

Keep HPR's EUI list in sync with ChirpStack devices automatically, without
silently clobbering either side when they disagree.

## Where it runs

**console.buoy.fish** (the LNS server). Everything is already there:
ChirpStack postgres (local), `helium-config-service-cli` at `/helium/cli/`,
env at `/helium/cli/env.sh`, keypair at `/etc/helium/pkey.bin`.

## Scope

Only EUI pairs for tenant `e1d293fb-6dc5-4214-a23a-94696f17f82f` on route
`0b87a1c6-ea56-11ef-a434-37ed6a7f036d`. DevAddrs and SKFs are out of scope
for this job.

## What it does each run

1. Read the set of `(dev_eui, join_eui)` pairs from ChirpStack postgres
   (tenant-scoped, same query as 2026-04-22).
2. Read the current EUI list from HPR via
   `helium-config-service-cli route euis list -r <ROUTE>`.
3. Compute three sets:
   - **To add**: `(dev_eui, app_eui)` in CS but not in HPR, where that
     DevEUI has **no existing HPR entry** (i.e. brand-new device).
   - **Desync**: `(dev_eui, app_eui)` in CS but not in HPR, where that
     DevEUI **already has a different AppEUI in HPR**. Do NOT add or remove.
     Surface these for human review.
   - **Orphans**: `(dev_eui, app_eui)` in HPR where DevEUI isn't in CS at all.
     Surface these; don't auto-remove (could be a CS deletion done in error).
4. Execute `add` for every "to add" pair, always doing a dry-run log line
   first, then `--commit`.
5. Emit a structured summary (to stdout / systemd journal / log file):
   counts, any desyncs, any orphans, and any `add` failures.
6. If there are desyncs or orphans, fail loud (non-zero exit and/or
   notification) so a human notices.

### Important behavior — do NOT silently normalize

The reason for the desync/orphan split is the incident on 2026-04-22: a prior
Claude session over-generalized "zero this one AppEUI" into a systematic app
behavior, and a naive reconciler that trusted CS as ground truth would have
propagated the bug into HPR (erasing the real AppEUIs that the devices
actually transmit over the air).

- An AppEUI conflict between CS and HPR for the same DevEUI is never
  auto-resolved.
- An HPR DevEUI not present in CS is never auto-removed. It may be a recent
  CS delete that we want to undo, not a stale entry.
- Both go to a human for adjudication. The third source of truth,
  `helium_devices.application_eui` (Disk91 Console), is a useful
  corroboration — include it in the desync report when available.

## Schedule

Daily is probably enough — device additions in this deployment are batched.
Hourly would be overkill and costs a small amount of Helium config-service
traffic. Start daily; revisit if onboarding volume grows.

`cron` or `systemd timer` are both fine. `systemd timer` gives cleaner logs
via `journalctl`, which is preferable if the monitoring story matures.

## Safety / failure modes

- Always dry-run first in the same run (log the planned commands before
  committing them). Makes post-mortem easy if something goes wrong.
- If the HPR `list` call fails, abort — don't proceed with a stale view.
- If the postgres query fails, abort.
- On any abort, exit non-zero so the systemd timer / cron reports failure.
- Log every `add` (and any future `remove`) with timestamp and DevEUI so
  we have an audit trail.

## Notifications

Open question: where should desync/orphan/failure alerts go? Options the
user already has: Slack webhook, email, dashboard on monitoring.buoy.fish.
Decide before implementation.

## Shape of the implementation

One Python script, ~100 lines, living at
`/helium/cli/reconcile.py` on console.buoy.fish. Dependencies: `psycopg2`
(or use the existing `docker exec psql` pattern for consistency), plus
`subprocess` to call the Helium CLI. A small wrapper shell script sources
`env.sh` before running the Python.

Version-control this in the `console.buoy.fish/scripts/` dir of the buoy
repo alongside `diagnose-helium-downlink.sh`, deploy with the same flow.

## Open questions before implementation

1. Notification channel for desync/orphan alerts?
2. Daily vs hourly schedule?
3. Should the `add` commit be gated behind a feature flag / manual approval
   for the first week, so we can watch it without it running unsupervised?
4. Where exactly in the buoy repo does the script live, and how does it
   get deployed to console.buoy.fish (existing pattern? Ansible? manual)?

## Out of scope for this job (tracked separately)

- **SKF automation** — pushing session keys to HPR when ChirpStack
  completes a join. Different architecture (event-driven, not a cron) and
  touches the ChirpStack HTTP integration or MQTT stream.
- **DevAddr range monitoring** — not needed; range is static and already
  correct. Would be worth a quarterly manual check.
- **Cleanup of stale HPR entries** — any legacy pairs that predate the
  current CS state. Today's one-time reconciliation didn't find any
  orphans, so nothing to clean. Revisit if the cron starts reporting them.
