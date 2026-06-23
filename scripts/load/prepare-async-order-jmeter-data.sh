#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
ROWS="${ROWS:-5000}"
USER_COUNT="${USER_COUNT:-50}"
USER_PHONE_PREFIX="${USER_PHONE_PREFIX:-139010000}"
USER_PASSWORD="${USER_PASSWORD:-Test123456}"
SHOW_ID="${SHOW_ID:-1}"
SESSION_ID="${SESSION_ID:-1}"
TICKET_CATEGORY_ID="${TICKET_CATEGORY_ID:-2}"
QUANTITY="${QUANTITY:-1}"
ADMISSION_TTL_SECONDS="${ADMISSION_TTL_SECONDS:-7200}"
OUT_FILE="${OUT_FILE:-/tmp/async-order-users-formal.csv}"

if ! command -v jq >/dev/null 2>&1; then
  echo "缺少 jq：brew install jq"
  exit 1
fi

if ! command -v redis-cli >/dev/null 2>&1; then
  echo "缺少 redis-cli"
  exit 1
fi

if [[ "$ROWS" -lt 1 ]]; then
  echo "ROWS 必须大于 0"
  exit 1
fi

if [[ "$USER_COUNT" -lt 1 ]]; then
  echo "USER_COUNT 必须大于 0"
  exit 1
fi

tmp_file="${OUT_FILE}.tmp"
printf 'authToken,showId,sessionId,ticketCategoryId,quantity,admissionToken\n' > "$tmp_file"

declare -a user_ids
declare -a user_tokens

echo "登录 $USER_COUNT 个压测用户，刷新 JWT..."
for n in $(seq 1 "$USER_COUNT"); do
  phone=$(printf '%s%02d' "$USER_PHONE_PREFIX" "$n")
  redis-cli DEL "auth:login:fail:${phone}" "auth:login:lock:${phone}" >/dev/null
  response=$(curl -sS -X POST "${BASE_URL}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"phone\":\"${phone}\",\"password\":\"${USER_PASSWORD}\"}")
  code=$(printf '%s' "$response" | jq -r '.code')
  if [[ "$code" != "200" ]]; then
    echo "登录失败：phone=${phone}, response=${response}"
    rm -f "$tmp_file"
    exit 1
  fi
  user_ids[$n]=$(printf '%s' "$response" | jq -r '.data.userId')
  user_tokens[$n]=$(printf '%s' "$response" | jq -r '.data.token')
done

echo "生成 $ROWS 行正式压测 CSV：$OUT_FILE"
for row in $(seq 1 "$ROWS"); do
  user_index=$(( ((row - 1) % USER_COUNT) + 1 ))
  user_id="${user_ids[$user_index]}"
  auth_token="${user_tokens[$user_index]}"
  admission_token="admit_$(openssl rand -hex 16)"
  redis_key="waiting-room:admission:ticket:${TICKET_CATEGORY_ID}:user:${user_id}:token:${admission_token}"
  redis-cli SET "$redis_key" 1 EX "$ADMISSION_TTL_SECONDS" >/dev/null
  printf '%s,%s,%s,%s,%s,%s\n' \
    "$auth_token" "$SHOW_ID" "$SESSION_ID" "$TICKET_CATEGORY_ID" "$QUANTITY" "$admission_token" >> "$tmp_file"
done

mv "$tmp_file" "$OUT_FILE"

echo
echo "生成完成。"
wc -l "$OUT_FILE"
awk -F, 'NR==2 {print "columns=" NF ", tokenPrefix=" substr($1,1,10) ", admissionPrefix=" substr($6,1,6)}' "$OUT_FILE"
echo
echo "JMeter DATA_FILE=$OUT_FILE"
