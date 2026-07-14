#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
ROWS="${ROWS:-8249}"
STOCK_QUANTITY="${STOCK_QUANTITY:-2000}"
USER_MULTIPLIER="${USER_MULTIPLIER:-4}"
USER_COUNT="${USER_COUNT:-8249}"
USER_PHONE_PREFIX="${USER_PHONE_PREFIX:-139010}"
USER_PHONE_WIDTH="${USER_PHONE_WIDTH:-5}"
USER_PASSWORD="${USER_PASSWORD:-Test123456}"
LOGIN_PARALLELISM="${LOGIN_PARALLELISM:-16}"
SHOW_ID="${SHOW_ID:-1}"
SESSION_ID="${SESSION_ID:-1}"
TICKET_CATEGORY_ID="${TICKET_CATEGORY_ID:-2}"
QUANTITY="${QUANTITY:-2}"
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
login_dir="$(mktemp -d /tmp/smart-ticket-login-tokens.XXXXXX)"
trap 'rm -rf "$login_dir"' EXIT
printf 'authToken,showId,sessionId,ticketCategoryId,quantity,admissionToken\n' > "$tmp_file"

declare -a user_ids
declare -a user_tokens

echo "登录 ${USER_COUNT} 个压测用户，刷新 JWT..."
echo "库存票数=${STOCK_QUANTITY}，用户倍数=${USER_MULTIPLIER}，单请求购票数=${QUANTITY}"
echo "登录并发数=${LOGIN_PARALLELISM}"

login_one_user() {
  local n="$1"
  phone=$(printf "%s%0${USER_PHONE_WIDTH}d" "$USER_PHONE_PREFIX" "$n")
  redis-cli DEL "auth:login:fail:${phone}" "auth:login:lock:${phone}" >/dev/null
  response=$(curl -sS -X POST "${BASE_URL}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"phone\":\"${phone}\",\"password\":\"${USER_PASSWORD}\"}")
  code=$(printf '%s' "$response" | jq -r '.code')
  if [[ "$code" != "200" ]]; then
    printf 'failed,%s,%s\n' "$phone" "$response" > "${login_dir}/${n}.fail"
    exit 1
  fi
  user_id=$(printf '%s' "$response" | jq -r '.data.userId')
  user_token=$(printf '%s' "$response" | jq -r '.data.token')
  printf '%s,%s,%s\n' "$n" "$user_id" "$user_token" > "${login_dir}/${n}.ok"
}

export BASE_URL USER_PHONE_PREFIX USER_PHONE_WIDTH USER_PASSWORD login_dir
export -f login_one_user

set +e
seq 1 "$USER_COUNT" | xargs -n 1 -P "$LOGIN_PARALLELISM" bash -c 'login_one_user "$@"' _
login_status=$?
set -e

failed_count=$(find "$login_dir" -name '*.fail' | wc -l | tr -d ' ')
if [[ "$failed_count" != "0" ]]; then
  echo "有 ${failed_count} 个压测用户登录失败，前 10 条："
  find "$login_dir" -name '*.fail' -print0 | xargs -0 cat | head -10
  rm -f "$tmp_file"
  exit 1
fi

if [[ "$login_status" != "0" ]]; then
  echo "并发登录压测用户失败，login_status=${login_status}"
  rm -f "$tmp_file"
  exit "$login_status"
fi

for n in $(seq 1 "$USER_COUNT"); do
  IFS=, read -r _ user_id user_token < "${login_dir}/${n}.ok"
  user_ids[$n]="$user_id"
  user_tokens[$n]="$user_token"
done

echo "生成 ${ROWS} 行正式压测 CSV：${OUT_FILE}"
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
echo "JMeter DATA_FILE=${OUT_FILE}"
