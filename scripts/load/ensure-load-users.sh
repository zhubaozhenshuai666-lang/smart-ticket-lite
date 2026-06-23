#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
STOCK_QUANTITY="${STOCK_QUANTITY:-3000}"
USER_MULTIPLIER="${USER_MULTIPLIER:-4}"
USER_COUNT="${USER_COUNT:-$((STOCK_QUANTITY * USER_MULTIPLIER))}"
USER_PHONE_PREFIX="${USER_PHONE_PREFIX:-139010}"
USER_PHONE_WIDTH="${USER_PHONE_WIDTH:-5}"
USER_PASSWORD="${USER_PASSWORD:-Test123456}"
USERNAME_PREFIX="${USERNAME_PREFIX:-load_user_}"

if ! command -v jq >/dev/null 2>&1; then
  echo "缺少 jq：brew install jq"
  exit 1
fi

echo "准备 $USER_COUNT 个压测用户..."
echo "库存票数=$STOCK_QUANTITY，用户倍数=$USER_MULTIPLIER"
echo "phone=${USER_PHONE_PREFIX}<${USER_PHONE_WIDTH}位序号>, password=${USER_PASSWORD}"

created=0
existing=0

for n in $(seq 1 "$USER_COUNT"); do
  phone=$(printf "%s%0${USER_PHONE_WIDTH}d" "$USER_PHONE_PREFIX" "$n")
  username=$(printf "%s%0${USER_PHONE_WIDTH}d" "$USERNAME_PREFIX" "$n")

  login_response=$(curl -sS -X POST "${BASE_URL}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"phone\":\"${phone}\",\"password\":\"${USER_PASSWORD}\"}")
  login_code=$(printf '%s' "$login_response" | jq -r '.code // empty')
  if [[ "$login_code" == "200" ]]; then
    existing=$((existing + 1))
  else
    register_response=$(curl -sS -X POST "${BASE_URL}/api/auth/register" \
      -H 'Content-Type: application/json' \
      -d "{\"username\":\"${username}\",\"phone\":\"${phone}\",\"password\":\"${USER_PASSWORD}\"}")
    register_code=$(printf '%s' "$register_response" | jq -r '.code // empty')
    if [[ "$register_code" == "200" ]]; then
      created=$((created + 1))
    else
      echo "创建压测用户失败：phone=${phone}, response=${register_response}"
      exit 1
    fi
  fi

  if (( n % 100 == 0 )); then
    echo "进度：$n/$USER_COUNT，已存在=$existing，新建=$created"
  fi
done

echo
echo "压测用户准备完成：已存在=$existing，新建=$created，总数=$USER_COUNT"
