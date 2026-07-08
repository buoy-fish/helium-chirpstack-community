# LNS Drift Audit: Root Cause Found and Fixed

**Date:** 2026-03-10
**Issue:** Oyster3 devices (and all OTAA devices) could complete JoinRequest/JoinAccept but never pass data uplinks. Endless join-retry loop.

**Root cause:** When Mosquitto was hardened from `allow_anonymous true` to `allow_anonymous false` (with password auth), the `helium-helium-1` container (disk91 Helium integration) was not restarted. It continued running with empty MQTT credentials from its original Sept 2025 boot. After Mosquitto restarted with auth enforced, the integration's three MQTT clients silently failed to reconnect. Without MQTT, the integration never received ChirpStack join events, never updated the Session Key Filter (SKF) on the Helium Packet Router (HPR), and the HPR dropped all post-join data uplinks because `helium.route.reject.empty.skf=true`.

**Fix:** `cd /helium && docker compose restart helium` on console.buoy.fish. Verified all three MQTT clients (`MqttSender`, `MqttLoRaListener`, `MqttHeliumListener`) now connect as `chirpstack_internal`. First data uplink from test device `70b3d57050012b6e` arrived at 23:50:43 UTC, 5 seconds after join.

**Post-fix audit -- all MQTT clients verified:**

| Service | Config Source | Credentials | Status |
|---------|-------------|-------------|--------|
| chirpstack | chirpstack.toml (mounted) | chirpstack_internal | OK (Up 3d) |
| gateway-bridge-us915 | bridge.toml (mounted) | chirpstack_internal | OK (Up 3d) |
| gateway-bridge-eu868 | bridge.toml (mounted) | chirpstack_internal | OK (Up 3d) |
| gateway-bridge-au915-1 | bridge.toml (mounted) | chirpstack_internal | OK (Up 3d) |
| gateway-bridge-as923 | bridge.toml (mounted) | chirpstack_internal | OK (Up 3d) |
| gateway-bridge-as923-2 | bridge.toml (mounted) | chirpstack_internal | OK (Up 3d) |
| gateway-bridge-as923-3 | bridge.toml (mounted) | chirpstack_internal | OK (Up 3d) |
| gateway-bridge-as923-4 | bridge.toml (mounted) | chirpstack_internal | OK (Up 3d) |
| gateway-bridge-in865 | bridge.toml (mounted) | chirpstack_internal | OK (Up 3d) |
| gateway-bridge-kr920 | bridge.toml (mounted) | chirpstack_internal | OK (Up 3d) |
| helium-helium-1 | configuration.properties (mounted) | chirpstack_internal | OK (restarted) |
| mosquitto-exporter | docker-compose args | chirpstack_internal | OK (Up 2d) |

No other services require MQTT access. All 12 MQTT clients are authenticated.

---

# LNS Drift Matrix: upstream (disk91) vs production (console.buoy.fish)

Generated: 2026-03-10

Base commits:
- **upstream/main**: `16b598c` (disk91 v1.12.0, ChirpStack 4.15.0)
- **origin/main**: `16b598c` (identical)
- **production HEAD**: `8644ade` (2 commits ahead -- MQTT auth changes)

---

## Delta Table

| # | File / Surface | Upstream Value | Production Value | Category | Join-Path Impact |
|---|---------------|---------------|-----------------|----------|-----------------|
| 1 | `docker-compose.yml` -- mosquitto port bind | `127.0.0.1:1883:1883` | `0.0.0.0:1883:1883` | **pathing** | **HIGH** -- Exposes MQTT broker to any network client. An external gateway-bridge or packet-forwarder connecting directly via MQTT creates a second downlink path that bypasses Helium HPR. Combined with ACL user `nodered` having `readwrite application/#`, any MQTT client on the internet (with creds) can subscribe to gateway command topics. |
| 2 | `docker-compose.yml` -- mosquitto volumes | 1 volume (conf only) | 3 volumes (conf + acl + password_file) | auth/connectivity | LOW -- Enables MQTT auth enforcement; required by delta #3. |
| 3 | `mosquitto.conf` -- `allow_anonymous` | `true` | `false` | auth/connectivity | LOW in isolation -- Actually *improves* security. But only matters because port is now public (#1). |
| 4 | `mosquitto.conf` -- password_file / acl_file | Not present | Added | auth/connectivity | LOW -- Gate-keeps MQTT access. Correct hardening. |
| 5 | `chirpstack.toml` -- `[integration.mqtt]` credentials | No username/password | `username="chirpstack_internal"` / `password=<generated>` | auth/connectivity | NONE -- Required by #3 so ChirpStack can still publish/subscribe after anonymous access is disabled. |
| 6 | `chirpstack-gateway-bridge-us915.toml` -- MQTT credentials | `username=""` / `password=""` | `username="chirpstack_internal"` / `password=<generated>` | auth/connectivity | NONE -- Same reason as #5. All 9 regional bridge configs have the same change. |
| 7 | `makeiteasy.sh` -- MQTT credential generation block | Not present | 18-line block generating `chirpstack_internal` + `nodered` passwords | auth/connectivity | NONE -- Installer automation for #3-#6. |
| 8 | `docker-compose.yml` -- mosquitto_exporter command | No auth flags | `--user chirpstack_internal --pass <password>` | auth/connectivity | NONE -- Monitoring plumbing. |
| 9 | `.gitignore` -- mosquitto password_file, Notes dir | Not present | Added | unlikely | NONE |
| 10 | `configuration.properties` (deployed) -- `mqtt.login` / `mqtt.password` | Empty | `chirpstack_internal` / `<generated>` | auth/connectivity | NONE -- Helium integration (disk91 Java app) now authenticates to Mosquitto. Required by #3. |
| 11 | `configuration.properties` (deployed) -- Redis stream tuning | Not present | `spring.data.redis.stream.maxlen=1000` / `backoff.ms=1000` / `consumer.enabled=true` | unlikely | NONE -- Limits Redis stream size. Does not affect join path. |

---

## Gateway-Side Deltas (not in this repo, but deployed to Balena gateways)

| # | File / Surface | Expected (Helium-only) | Actual (cs-mux config) | Category | Join-Path Impact |
|---|---------------|----------------------|----------------------|----------|-----------------|
| G1 | `chirpstack-packet-multiplexer.toml` -- Server 2 | Not present | `server = "console.buoy.fish:1701"` / `uplink_only = false` | **pathing** | **CRITICAL** -- Creates a second full-duplex GWMP path directly to the US915 gateway-bridge. JoinRequests arrive at ChirpStack through BOTH Helium HPR AND this direct bridge. ChirpStack sees them as coming from different virtual gateways, may schedule JoinAccepts to both, and the direct-path JoinAccept arrives at the gateway packet-forwarder as a second PULL_RESP that can collide with or replace the Helium-routed one. |
| G2 | `chirpstack-packet-multiplexer.toml` -- Server 3 | Not present | `server = "pkt-logger:1702"` / `uplink_only = true` | unlikely | NONE -- Uplink-only logger, cannot inject downlinks. |

---

## Network Exposure Summary

```
                        Helium HPR
                            |
                     (GWMP over internet)
                            |
                            v
 +-------------------------------------------------+
 |  console.buoy.fish (AWS EC2)                    |
 |                                                 |
 |  :1700/udp  gateway-bridge-eu868                |
 |  :1701/udp  gateway-bridge-us915  <-- DIRECT    |
 |  :1702/udp  gateway-bridge-au915                |
 |  :1703-1708  (other regions)                    |
 |                                                 |
 |  :1883/tcp  mosquitto (0.0.0.0, auth required)  |
 |                                                 |
 |  All bridges --> MQTT --> ChirpStack            |
 +-------------------------------------------------+
          ^                         ^
          |                         |
   Helium HPR GWMP            Direct GWMP from
   (normal path)              Balena cs-mux (G1)
```

Both paths terminate at the **same** `chirpstack-gateway-bridge-us915` container via port 1701/udp. The bridge cannot distinguish Helium-sourced GWMP packets from direct ones -- they both arrive as standard Semtech UDP and get published to the same MQTT topics.

---

## Join-Accept Failure Mechanism (Hypothesis)

```
Timeline for a single Oyster3 JoinRequest:

T+0ms     Oyster3 transmits JoinRequest on US915 channel
T+~10ms   Gateway packet-forwarder receives it
T+~10ms   cs-mux fans out to:
            - gateway-rs:1680 (-> Helium HPR -> console.buoy.fish:1701)
            - console.buoy.fish:1701 (direct)

T+~50ms   DIRECT path: JoinRequest arrives at gateway-bridge-us915
T+~50ms   DIRECT path: Published to MQTT with gateway_id = physical gateway EUI
T+~400ms  DIRECT path: ChirpStack dedup timer fires, generates JoinAccept #1
T+~400ms  DIRECT path: JoinAccept sent back via MQTT -> bridge -> GWMP PULL_RESP
T+~400ms  DIRECT path: Arrives at cs-mux -> packet-forwarder
T+~400ms  DIRECT path: Packet-forwarder schedules for RX1 (T+5s for join)

T+~200ms  HELIUM path: JoinRequest arrives at gateway-bridge-us915
          (routed through HPR with ~150ms additional latency)
T+~200ms  HELIUM path: Published to MQTT with gateway_id = Helium virtual GW EUI
T+~550ms  HELIUM path: ChirpStack dedup timer fires, generates JoinAccept #2
T+~550ms  HELIUM path: JoinAccept sent back via MQTT -> bridge -> GWMP to HPR
T+~700ms  HELIUM path: HPR delivers via hotspot, which is the SAME physical gateway

Result: The packet-forwarder receives TWO PULL_RESP downlinks for the same
JoinAccept window. The second one may:
  a) Collide with the first in the JIT queue ("TX rejected: collision")
  b) Replace the first if timing differs slightly
  c) Cause the gateway-rs to log "rx1 downlink sent with adjusted transmit power"
     because it processes a downlink it wasn't expecting

Meanwhile, ChirpStack has incremented the device nonce / frame counter for
JoinAccept #1. When JoinAccept #2 arrives (or vice versa), the Oyster3 may:
  - Receive a JoinAccept with a nonce it already rejected (nonce reuse)
  - Receive the JoinAccept too late (outside RX1+RX2 windows)
  - Successfully decode it but then fail on the next uplink because
    the LNS used a different DevAddr/session key set
```

---

## Unchanged (Confirmed Identical)

| Surface | Value | Status |
|---------|-------|--------|
| `deduplication_delay` | `350ms` | Matches upstream |
| `allow_unknown_gateways` | `true` | Matches upstream |
| `rx_window` | `0` (RX1/RX2) | Matches upstream |
| `rx1_delay` | `1` (1 second) | Matches upstream |
| `rx2_dr` | `8` (SF12BW500 for US915) | Matches upstream |
| `rx2_frequency` | `923300000` Hz | Matches upstream |
| `net_id` | `00003C` | Correct for Helium |
| `helium.route.regions` | `US915:1701` | Matches bridge port mapping |
| `helium.route.copy.default` | `3` | Upstream default |
| Gateway-bridge port mapping | `1701:1700/udp` | Unchanged |
| Gateway-bridge MQTT topics | `us915_1/gateway/...` | Unchanged |
| ChirpStack version | `4.15.0` | Matches upstream HEAD |

---

## Risk-Ranked Summary

1. **CRITICAL (G1)**: Direct GWMP path `console.buoy.fish:1701` with `uplink_only=false` in cs-mux creates dual-path join-accept delivery. This is the most likely root cause of Oyster3 join failures.

2. **HIGH (#1)**: Mosquitto bound to `0.0.0.0:1883` instead of `127.0.0.1:1883`. While MQTT auth is now enforced, this exposes the broker to the internet. Not directly causing join failures, but enables potential future path-injection if credentials leak. AWS security groups may or may not block this port.

3. **LOW (#2-#8)**: MQTT authentication changes are operationally correct and do not affect join timing. They are prerequisite plumbing for the Node-RED integration use case.

4. **NONE (#9-#11, G2)**: Cosmetic or unrelated changes.

---

## Phase 2 Runtime Findings (2026-03-10)

### Updated Theory: Dual-Path is NOT the Active Cause

The gateway with direct UDP forwarding (`console.buoy.fish:1701`) has been **offline**.
No `balena push` deployed the cs-mux dual-path config to other fleet gateways.
Therefore, dual-path downlink collision is **not** the active failure mode.

### What IS Causing Join Failures

Three join attempts were traced end-to-end for DevEUI `70b3d57050012b6e`:

| Attempt | Time (UTC) | Uplink Virtual GW(s) | JoinAccept Sent To | ACK Latency | Result |
|---------|-----------|---------------------|-------------------|-------------|--------|
| 1 | 19:24:57 | b9bd9d971d348aff | b9bd9d971d348aff | 56ms | Failed |
| 2 | 21:58:18 | b9bd9d971d348aff, b7712252387df4da | b7712252387df4da | 55ms | Failed |
| 3 | 22:08:09 | b7712252387df4da, aa63778ad0ebe0a5 | b7712252387df4da | 55ms | Failed |

**Key observations:**

1. **The 55-56ms tx_ack is from the Helium HPR, not from radio transmission.**
   A real radio tx_ack takes 5-6 seconds (must wait for the RX1/RX2 window).
   The HPR acknowledges receipt of the GWMP downlink packet immediately, but this
   does NOT confirm the physical hotspot transmitted the JoinAccept over the air.

2. **The Oyster3's JoinRequests are heard only by transient community hotspots**
   (virtual GW IDs that appear 1-2 times total), not by the user's own gateways
   (89165f42461e96dc with 926 uplinks, 781082b1d53c1d09 with 605).

3. **The user's gateways listen on US915 Sub-band 2 only** (channels 8-15,
   903.9-905.3 MHz). If the Oyster3 uses all 64 US915 uplink channels (factory
   default for non-Helium-specific firmware), only 1 in 8 JoinRequests will land
   on SB2 and be heard by the user's gateways.

4. **JoinAccepts routed through community hotspots are not being delivered.**
   The HPR ACKs the downlink but the physical hotspot either doesn't transmit it,
   transmits it too late, or the Oyster3 is too far from that hotspot to hear it.

### Root Cause Diagram

```
Oyster3 transmits JoinRequest on random US915 channel
       |
       +-- Channel is in SB2 (1/8 chance) ------> User's gateway hears it
       |                                              -> gateway-rs -> HPR -> LNS
       |                                              -> JoinAccept routed back
       |                                              -> gateway-rs transmits
       |                                              -> Device 8ft away, strong signal
       |                                              -> JOIN SHOULD SUCCEED
       |
       +-- Channel is NOT in SB2 (7/8 chance) --> Only community hotspots hear it
                                                     -> HPR -> LNS
                                                     -> JoinAccept routed to community hotspot
                                                     -> Hotspot may be far away / unreliable
                                                     -> HPR ACKs immediately (fake ACK)
                                                     -> JoinAccept never reaches device
                                                     -> JOIN FAILS
```

### The LNS is Working Correctly

All upstream configuration that affects join timing is **unchanged**:
- `deduplication_delay` = 350ms
- `rx_window` = 0 (RX1/RX2)
- `rx1_delay` = 1 second
- RX2 parameters match upstream
- Region config is identical
- JoinAccept is generated within 11-15ms of dedup completion

## Recommended Next Steps

1. **Verify Oyster3 channel plan**: Check if the Oyster3 is configured for SB2-only
   or all-channel operation. If all-channel, configure it for SB2 (Helium standard).
   This ensures every JoinRequest is heard by the user's own gateways.

2. **Wait for a SB2 hit**: With perfect-ranch online and 8ft away, a JoinRequest on
   SB2 should result in a successful join (proving the LNS works). This may take
   up to ~80 minutes (8 attempts * ~10 min retry interval) if using all channels.

3. **Long-term**: The direct UDP path in cs-mux should still be removed or set to
   `uplink_only=true` before reactivating that gateway, to prevent dual-path issues
   when it comes back online.

4. **Consider**: Restricting Mosquitto back to `127.0.0.1:1883` and using an SSH
   tunnel or Docker network for Node-RED access instead.
