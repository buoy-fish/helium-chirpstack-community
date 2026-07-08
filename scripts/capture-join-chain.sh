#!/usr/bin/env bash
#
# capture-join-chain.sh -- Watches ChirpStack + gateway-bridge for the next
# JoinRequest from a specific DevEUI and captures the entire chain:
#   JoinRequest → dedup → JoinAccept → downlink → tx_ack
#
# Usage: ./scripts/capture-join-chain.sh <dev_eui> [duration_minutes]
#
# Requires: SSH access to console.buoy.fish as ubuntu

set -euo pipefail

SERVER="ubuntu@console.buoy.fish"
DEV_EUI="${1:?Usage: $0 <dev_eui> [duration_minutes]}"
DURATION="${2:-15}"
OUTDIR="$(dirname "$0")/../docs/join-captures"
mkdir -p "$OUTDIR"
TIMESTAMP="$(date -u +%Y%m%d_%H%M%S)"
OUTFILE="$OUTDIR/${DEV_EUI}_${TIMESTAMP}.txt"

echo "========================================" | tee "$OUTFILE"
echo "Join Chain Capture" | tee -a "$OUTFILE"
echo "DevEUI:   $DEV_EUI" | tee -a "$OUTFILE"
echo "Start:    $(date -u '+%Y-%m-%d %H:%M:%S UTC')" | tee -a "$OUTFILE"
echo "Duration: ${DURATION}m" | tee -a "$OUTFILE"
echo "========================================" | tee -a "$OUTFILE"
echo "" | tee -a "$OUTFILE"

echo "--- Pre-capture: latest Redis events ---" | tee -a "$OUTFILE"
ssh "$SERVER" "docker exec helium-redis-1 redis-cli XREVRANGE 'device:{$DEV_EUI}:stream:event' + - COUNT 3" 2>/dev/null \
  | strings | grep -E '^[0-9]{13}|^(up|join|txack)$' | tee -a "$OUTFILE"
echo "" | tee -a "$OUTFILE"

echo "--- Pre-capture: gateway list (last_seen) ---" | tee -a "$OUTFILE"
ssh "$SERVER" "docker exec helium-postgres-1 psql -U chirpstack -d chirpstack -t -A \
  -c \"SELECT encode(gateway_id,'hex'),name,last_seen_at FROM gateway ORDER BY last_seen_at DESC NULLS LAST LIMIT 10\"" 2>/dev/null \
  | tee -a "$OUTFILE"
echo "" | tee -a "$OUTFILE"

echo "--- Starting live capture (${DURATION}m) ---" | tee -a "$OUTFILE"
echo "Watching ChirpStack and gateway-bridge for $DEV_EUI..." | tee -a "$OUTFILE"
echo "" | tee -a "$OUTFILE"

TMPDIR=$(mktemp -d)
trap 'kill $(jobs -p) 2>/dev/null; rm -rf "$TMPDIR"' EXIT

# Stream 1: ChirpStack logs filtered for DevEUI + join + downlink activity
ssh "$SERVER" "timeout ${DURATION}m docker logs -f --since 1s helium-chirpstack-1 2>&1" \
  | sed 's/\x1b\[[0-9;]*m//g' \
  | awk "BEGIN{IGNORECASE=1} /$DEV_EUI|join_request|join_accept/" \
  | while IFS= read -r line; do echo "[CS]  $line"; done \
  >> "$TMPDIR/combined.log" 2>/dev/null &

# Stream 2: Gateway-bridge US915 logs filtered for downlink + ack
ssh "$SERVER" "timeout ${DURATION}m docker logs -f --since 1s helium-chirpstack-gateway-bridge-us915-1 2>&1" \
  | awk 'BEGIN{IGNORECASE=1} /downlink frame received|event\/ack/' \
  | while IFS= read -r line; do echo "[GWB] $line"; done \
  >> "$TMPDIR/combined.log" 2>/dev/null &

# Stream 3: Helium integration logs
ssh "$SERVER" "timeout ${DURATION}m docker logs -f --since 1s helium-helium-1 2>&1" \
  | awk "BEGIN{IGNORECASE=1} /$DEV_EUI|join|downlink/" \
  | while IFS= read -r line; do echo "[HEL] $line"; done \
  >> "$TMPDIR/combined.log" 2>/dev/null &

# Tail the combined log to console and file
tail -f "$TMPDIR/combined.log" 2>/dev/null | tee -a "$OUTFILE" &
TAIL_PID=$!

# Wait for duration
echo "(Capture running for ${DURATION} minutes, press Ctrl+C to stop early)" >&2
sleep "${DURATION}m" 2>/dev/null || true
kill $TAIL_PID 2>/dev/null || true

echo "" | tee -a "$OUTFILE"
echo "--- Post-capture: latest Redis events ---" | tee -a "$OUTFILE"
ssh "$SERVER" "docker exec helium-redis-1 redis-cli XREVRANGE 'device:{$DEV_EUI}:stream:event' + - COUNT 5" 2>/dev/null \
  | strings | grep -E '^[0-9]{13}|^(up|join|txack)$' | tee -a "$OUTFILE"

echo "" | tee -a "$OUTFILE"
echo "--- Capture complete ---" | tee -a "$OUTFILE"
echo "Output saved to: $OUTFILE"
