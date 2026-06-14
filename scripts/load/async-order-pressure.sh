#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
TOTAL="${TOTAL:-1000}"
CONCURRENCY="${CONCURRENCY:-100}"
SHOW_ID="${SHOW_ID:-1}"
SESSION_ID="${SESSION_ID:-1}"
TICKET_CATEGORY_ID="${TICKET_CATEGORY_ID:-2}"
QUANTITY="${QUANTITY:-1}"
AUTH_TOKEN="${AUTH_TOKEN:-}"
RISK_DECISION="${RISK_DECISION:-pass}"
SKIP_CAPACITY_GUARD="${SKIP_CAPACITY_GUARD:-false}"
TOKEN_ENDPOINT="${BASE_URL}/api/orders/idempotency-token"
ORDER_ENDPOINT="${BASE_URL}/api/orders/async"
CAPACITY_ENDPOINT="${BASE_URL}/api/admin/ops/capacity/order-pipeline"

if [[ -z "${AUTH_TOKEN}" ]]; then
  echo "AUTH_TOKEN is required" >&2
  exit 1
fi

if [[ "${SKIP_CAPACITY_GUARD}" != "true" ]]; then
  capacity_payload="$(curl -sS -H "Authorization: Bearer ${AUTH_TOKEN}" "${CAPACITY_ENDPOINT}")"
  if [[ "${capacity_payload}" != *'"fastPipelineEnabled":true'* ]]; then
    echo "capacity_guard_failed fastPipelineEnabled is not true; enable flash-sale fast pipeline or set SKIP_CAPACITY_GUARD=true" >&2
    exit 1
  fi
  if [[ "${capacity_payload}" != *'"waitingRoomEnabled":true'* ]]; then
    echo "capacity_guard_failed waitingRoomEnabled is not true; pressure test would hit Redis/MQ without admission control" >&2
    exit 1
  fi
  if [[ "${capacity_payload}" == *'"hardBottleneck":"入口仍通过 Outbox'* ]]; then
    echo "capacity_guard_failed publisher mode is still Outbox" >&2
    exit 1
  fi
fi

run_one() {
  local idx="$1"
  local idem
  idem="$(curl -sS -H "Authorization: Bearer ${AUTH_TOKEN}" "${TOKEN_ENDPOINT}" \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
  if [[ -z "${idem}" ]]; then
    echo "token_failed ${idx}"
    return 0
  fi
  local body
  body="{\"showId\":${SHOW_ID},\"sessionId\":${SESSION_ID},\"ticketCategoryId\":${TICKET_CATEGORY_ID},\"quantity\":${QUANTITY},\"idempotencyToken\":\"${idem}\"}"
  curl -sS -o /dev/null -w "%{http_code}\n" \
    -H "Authorization: Bearer ${AUTH_TOKEN}" \
    -H "Content-Type: application/json" \
    -H "X-Smart-Ticket-Risk-Decision: ${RISK_DECISION}" \
    -d "${body}" \
    "${ORDER_ENDPOINT}"
}

export BASE_URL TOTAL CONCURRENCY SHOW_ID SESSION_ID TICKET_CATEGORY_ID QUANTITY AUTH_TOKEN RISK_DECISION TOKEN_ENDPOINT ORDER_ENDPOINT
export -f run_one

start_epoch="$(date +%s)"
seq 1 "${TOTAL}" | xargs -n 1 -P "${CONCURRENCY}" bash -lc 'run_one "$@"' _
end_epoch="$(date +%s)"
elapsed=$((end_epoch - start_epoch))
if [[ "${elapsed}" -le 0 ]]; then
  elapsed=1
fi
echo "total=${TOTAL} concurrency=${CONCURRENCY} elapsed_seconds=${elapsed} rough_qps=$((TOTAL / elapsed))"
