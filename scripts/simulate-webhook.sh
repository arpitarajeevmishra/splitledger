#!/usr/bin/env bash
# Deliver a Razorpay-shaped webhook to a running SplitLedger, signed the way Razorpay
# signs one (SL-603).
#
# The offline path: it needs no Razorpay account, no tunnel and no dashboard, so the
# webhook handler can be exercised -- including its de-duplication and its refusal of a
# bad signature -- on a laptop or in CI.
#
#   scripts/simulate-webhook.sh --order order_abc --payment pay_xyz
#   scripts/simulate-webhook.sh --order order_abc --payment pay_xyz --repeat 20
#   scripts/simulate-webhook.sh --order order_abc --payment pay_xyz --tamper
#
# The secret must be the one the app is running with (RAZORPAY_WEBHOOK_SECRET).
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
SECRET=${RAZORPAY_WEBHOOK_SECRET:-stub-webhook-secret}
EVENT=payment.captured
ORDER=""
PAYMENT=""
EVENT_ID=""
REPEAT=1
TAMPER=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --order)    ORDER=$2; shift 2 ;;
    --payment)  PAYMENT=$2; shift 2 ;;
    --event)    EVENT=$2; shift 2 ;;
    --event-id) EVENT_ID=$2; shift 2 ;;
    --repeat)   REPEAT=$2; shift 2 ;;
    --tamper)   TAMPER=1; shift ;;
    --base)     BASE=$2; shift 2 ;;
    -h|--help)  sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$ORDER" || -z "$PAYMENT" ]]; then
  echo "need --order and --payment (the gateway order id, and any payment id)" >&2
  exit 2
fi
# One event id for every copy when --repeat is used: that is what makes them duplicates
# rather than twenty different events.
EVENT_ID=${EVENT_ID:-evt_$(date +%s)_$RANDOM}

# Razorpay's shape, trimmed to the fields the handler reads. Written to a file and sent
# with --data-binary so the bytes curl transmits are the bytes that were signed; letting a
# shell re-quote the JSON is how a signature mismatch gets blamed on the code.
BODY=$(mktemp)
trap 'rm -f "$BODY"' EXIT
cat > "$BODY" <<JSON
{"entity":"event","account_id":"acc_simulated","event":"$EVENT","contains":["payment"],"payload":{"payment":{"entity":{"id":"$PAYMENT","entity":"payment","amount":45000,"currency":"INR","status":"captured","order_id":"$ORDER","method":"card","captured":true}}},"created_at":$(date +%s)}
JSON

SIGNATURE=$(openssl dgst -sha256 -hmac "$SECRET" -hex < "$BODY" | sed 's/^.*= *//')
if [[ "$TAMPER" == "1" ]]; then
  # One character, which is all it should take.
  first=${SIGNATURE:0:1}
  [[ "$first" == "a" ]] && SIGNATURE="b${SIGNATURE:1}" || SIGNATURE="a${SIGNATURE:1}"
  echo "sending a tampered signature"
fi

echo "POST $BASE/webhooks/razorpay   event=$EVENT_ID order=$ORDER payment=$PAYMENT repeat=$REPEAT"

deliver() {
  curl -s -o /dev/null -w '%{http_code} ' -X POST "$BASE/webhooks/razorpay" \
    -H 'Content-Type: application/json' \
    -H "X-Razorpay-Signature: $SIGNATURE" \
    -H "X-Razorpay-Event-Id: $EVENT_ID" \
    --data-binary @"$BODY"
}

if [[ "$REPEAT" -gt 1 ]]; then
  # All at once, on purpose: the acceptance criterion is about concurrent duplicates, and
  # delivering them one after another would prove nothing about the race.
  for _ in $(seq "$REPEAT"); do deliver & done
  wait
  echo
else
  deliver
  echo
fi
