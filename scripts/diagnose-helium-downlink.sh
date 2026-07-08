#!/usr/bin/env bash
#
# diagnose-helium-downlink.sh
#
# Demonstrates that the Helium Packet Router (HPR) acknowledges downlinks
# (JoinAccept) via TX_ACK but fails to deliver them to the hotspot for
# radio transmission. The device never completes OTAA join.
#
# Usage: ./diagnose-helium-downlink.sh <dev_eui> [deduplication_id]
#
# Requirements: SSH access to console.buoy.fish as ubuntu
#
# OUI: 2109
# Route ID: 0b87a1c6-ea56-11ef-a434-37ed6a7f036d
# Tenant: e1d293fb-6dc5-4214-a23a-94696f17f82f (buoy)
#

set -euo pipefail

SERVER="ubuntu@console.buoy.fish"
DEV_EUI="${1:?Usage: $0 <dev_eui> [deduplication_id]}"
DEDUP_ID="${2:-}"
ROUTE_ID="0b87a1c6-ea56-11ef-a434-37ed6a7f036d"

echo "=============================================="
echo "Helium HPR Downlink Delivery Diagnostic"
echo "=============================================="
echo "DevEUI: $DEV_EUI"
echo "Date:   $(date -u '+%Y-%m-%d %H:%M UTC')"
echo ""

# --- Step 1: Verify route configuration ---
echo "--- Step 1: Route Configuration ---"
echo "Command: helium-config-service-cli route get -r $ROUTE_ID"
echo ""
ssh "$SERVER" "cd /helium/cli && source env.sh && helium-config-service-cli route get -r $ROUTE_ID" 2>/dev/null
echo ""
echo "KEY: max_copies and ignore_empty_skf should rule out SKF/copy issues."
echo ""

# --- Step 2: Verify device is registered in HPR ---
echo "--- Step 2: Verify DevEUI in HPR Route ---"
echo "Command: helium-config-service-cli route euis list -r $ROUTE_ID | grep -i $DEV_EUI"
echo ""
MATCH=$(ssh "$SERVER" "cd /helium/cli && source env.sh && helium-config-service-cli route euis list -r $ROUTE_ID" 2>/dev/null | grep -ci "$DEV_EUI" || true)
if [ "$MATCH" -gt 0 ]; then
    echo "RESULT: ✓ DevEUI $DEV_EUI is registered in HPR route"
else
    echo "RESULT: ✗ DevEUI $DEV_EUI is NOT registered in HPR route"
fi
echo ""

# --- Step 3: Get recent device events from ChirpStack Redis ---
echo "--- Step 3: Recent Device Events (ChirpStack Redis) ---"
echo "Command: redis-cli XREVRANGE 'device:{$DEV_EUI}:stream:event' + - COUNT 10"
echo ""
echo "Extracting event timestamps and types..."
RAW=$(ssh "$SERVER" "docker exec helium-redis-1 redis-cli XREVRANGE 'device:{$DEV_EUI}:stream:event' + - COUNT 10" 2>/dev/null)

# Extract stream IDs (timestamps) and look for event types
echo "$RAW" | strings | grep -E '^[0-9]{13}-[0-9]$|^(up|join|txack)$' | while read -r line; do
    if [[ "$line" =~ ^[0-9]{13} ]]; then
        TS_MS="${line%-*}"
        TS_HUMAN=$(python3 -c "from datetime import datetime,timezone; print(datetime.fromtimestamp($TS_MS/1000,tz=timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC'))")
        printf "  %s  (%s)  " "$line" "$TS_HUMAN"
    else
        echo "→ $line"
    fi
done
echo ""
echo "KEY: If you see repeated 'join' events with no 'up' events after them,"
echo "     the device is sending JoinRequests but never receiving JoinAccepts."
echo ""

# --- Step 4: ChirpStack logs — trace the join + downlink ---
echo "--- Step 4: ChirpStack Join Processing Logs ---"
echo "Command: docker logs helium-chirpstack-1 | grep $DEV_EUI (filtered)"
echo ""
ssh "$SERVER" "docker logs helium-chirpstack-1 2>&1" \
    | sed 's/\x1b\[[0-9;]*m//g' \
    | grep "$DEV_EUI" \
    | grep -E 'join_request|join_accept|Sending downlink|tx_ack' \
    | tail -20
echo ""
echo "KEY: Look for the complete chain:"
echo "  1. 'Device-nonce validated' — LNS accepted the JoinRequest"
echo "  2. 'Downlink-frame saved'   — LNS generated the JoinAccept"
echo "  3. 'Sending downlink frame' — LNS sent it to a Helium virtual gateway"
echo "  4. 'tx_ack...downlink-frame for device' — Gateway bridge got TX_ACK"
echo ""

# --- Step 5: Gateway bridge logs — trace the GWMP exchange ---
echo "--- Step 5: Gateway Bridge GWMP Exchange ---"
echo "Command: docker logs helium-chirpstack-gateway-bridge-us915-1 (filtered)"
echo ""
ssh "$SERVER" "docker logs helium-chirpstack-gateway-bridge-us915-1 2>&1" \
    | grep -E "$(ssh "$SERVER" "docker logs helium-chirpstack-1 2>&1" \
        | sed 's/\x1b\[[0-9;]*m//g' \
        | grep "$DEV_EUI" \
        | grep 'Sending downlink' \
        | tail -3 \
        | grep -oE 'gateway_id=[a-f0-9]+' \
        | sed 's/gateway_id=//' \
        | sort -u \
        | tr '\n' '|' \
        | sed 's/|$//')" \
    | tail -30
echo ""
echo "KEY: Look for:"
echo "  - 'publishing event...event=up'       — uplink arrived via GWMP from HPR"
echo "  - 'downlink frame received'            — bridge got downlink from ChirpStack"
echo "  - 'publishing event...event=ack'       — HPR sent TX_ACK back"
echo "  Compare timestamps: if ack arrives <100ms after downlink, it's the HPR"
echo "  acknowledging receipt — NOT the hotspot confirming radio transmission."
echo ""

# --- Step 6: Verify traffic source ---
echo "--- Step 6: Traffic Source (Helium vs Direct) ---"
echo "Command: redis-cli XREVRANGE ... | strings | grep helium_iotz/gateway EUI"
echo ""
SOURCES=$(ssh "$SERVER" "docker exec helium-redis-1 redis-cli XREVRANGE 'device:{$DEV_EUI}:stream:event' + - COUNT 10 2>/dev/null | strings | grep -E '(helium_iotz|0016c001)'" 2>/dev/null || true)
if echo "$SOURCES" | grep -q 'helium_iotz'; then
    echo "  Found: helium_iotz — traffic routed through Helium HPR"
fi
if echo "$SOURCES" | grep -q '0016c001'; then
    GWIDS=$(echo "$SOURCES" | grep -oE '0016c001[a-f0-9]+' | sort -u)
    echo "  Found: direct gateway(s) — $GWIDS"
fi
if [ -z "$SOURCES" ]; then
    echo "  No gateway identifiers found in recent events"
fi
echo ""

# --- Step 7: Check gateway last-seen ---
echo "--- Step 7: Physical Gateway Status ---"
echo "Command: psql ... SELECT gateway_id, name, last_seen_at FROM gateway"
echo ""
ssh "$SERVER" "docker exec helium-postgres-1 psql -U chirpstack -d chirpstack -t -A -c \"SELECT encode(gateway_id,'hex') || '|' || name || '|' || COALESCE(last_seen_at::text, 'never') FROM gateway ORDER BY last_seen_at DESC NULLS LAST\""
echo ""
echo "KEY: If all physical gateways are offline, the device can ONLY join"
echo "     through Helium — and if Helium can't deliver the JoinAccept,"
echo "     the device is stuck."
echo ""

echo "=============================================="
echo "CONCLUSION"
echo "=============================================="
echo ""
echo "If Steps 4-5 show:"
echo "  - ChirpStack generated a valid JoinAccept"
echo "  - The JoinAccept was sent to a Helium virtual gateway via MQTT"
echo "  - The gateway bridge forwarded it as GWMP PULL_RESP to the HPR"
echo "  - The HPR acknowledged with TX_ACK"
echo "  - But the device never completed the join (no 'up' events after 'join')"
echo ""
echo "Then the Helium Packet Router accepted the downlink but failed to"
echo "deliver it to the hotspot for radio transmission. The LNS did"
echo "everything correctly. The failure is in the HPR → hotspot path."
