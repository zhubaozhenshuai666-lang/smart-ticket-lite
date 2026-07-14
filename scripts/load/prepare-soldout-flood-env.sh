#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-${SMART_TICKET_DB_PASSWORD:-}}"
DB_NAME="${DB_NAME:-smart_ticket_lite}"

STOCK_QUANTITY="${STOCK_QUANTITY:-2000}"
USER_MULTIPLIER="${USER_MULTIPLIER:-4}"
USER_COUNT="${USER_COUNT:-8249}"
ROWS="${ROWS:-8249}"
QUANTITY="${QUANTITY:-2}"
TICKET_CATEGORY_ID="${TICKET_CATEGORY_ID:-2}"
USER_PHONE_PREFIX="${USER_PHONE_PREFIX:-139010}"
USER_PHONE_WIDTH="${USER_PHONE_WIDTH:-5}"
SKIP_USER_SYNC="${SKIP_USER_SYNC:-true}"

if [[ -z "$DB_PASSWORD" ]]; then
  echo "缺少数据库密码。先执行：read -s SMART_TICKET_DB_PASSWORD && export SMART_TICKET_DB_PASSWORD"
  exit 1
fi

if [[ -z "${SMART_TICKET_ADMIN_PASSWORD:-}" ]]; then
  echo "缺少后台密码。先执行：read -s SMART_TICKET_ADMIN_PASSWORD && export SMART_TICKET_ADMIN_PASSWORD"
  exit 1
fi

if [[ "$QUANTITY" -gt 2 ]]; then
  echo "QUANTITY 不能大于 2。当前业务约束是一单最多两张票。"
  exit 1
fi

if [[ "$USER_MULTIPLIER" -lt 3 || "$USER_MULTIPLIER" -gt 5 ]]; then
  echo "USER_MULTIPLIER 必须在 3-5 之间。当前值=$USER_MULTIPLIER"
  exit 1
fi

EXPECTED_SUCCESS_ORDERS=$((STOCK_QUANTITY / QUANTITY))

cat <<CONFIG
Smart Ticket 售罄洪峰环境准备
STOCK_QUANTITY=$STOCK_QUANTITY
QUANTITY=$QUANTITY
EXPECTED_SUCCESS_ORDERS=$EXPECTED_SUCCESS_ORDERS
USER_MULTIPLIER=$USER_MULTIPLIER
USER_COUNT=$USER_COUNT
ROWS=$ROWS
TICKET_CATEGORY_ID=$TICKET_CATEGORY_ID
USER_PHONE_PREFIX=$USER_PHONE_PREFIX
USER_PHONE_WIDTH=$USER_PHONE_WIDTH
SKIP_USER_SYNC=$SKIP_USER_SYNC
CONFIG

echo
echo "1. 重置 MySQL 交易表、库存表、库存 bucket，并预热 Redis..."
CONFIRM_RESET=YES \
RESET_STOCK_QUANTITY="$STOCK_QUANTITY" \
TICKET_CATEGORY_ID="$TICKET_CATEGORY_ID" \
"$ROOT_DIR/scripts/load/reset-load-test-env.sh"

echo
echo "2. 同步压测用户到 MySQL user_account..."
if [[ "$SKIP_USER_SYNC" == "true" ]]; then
  echo "已设置 SKIP_USER_SYNC=true，跳过用户创建，直接复用 MySQL 里已有的 ${USER_COUNT} 个压测用户。"
else
  USER_COUNT="$USER_COUNT" \
  USER_PHONE_PREFIX="$USER_PHONE_PREFIX" \
  USER_PHONE_WIDTH="$USER_PHONE_WIDTH" \
  "$ROOT_DIR/scripts/load/ensure-load-users.sh"
fi

echo
echo "3. 生成 JMeter CSV，并为每行写入 Redis admissionToken..."
ROWS="$ROWS" \
STOCK_QUANTITY="$STOCK_QUANTITY" \
USER_MULTIPLIER="$USER_MULTIPLIER" \
USER_COUNT="$USER_COUNT" \
USER_PHONE_PREFIX="$USER_PHONE_PREFIX" \
USER_PHONE_WIDTH="$USER_PHONE_WIDTH" \
QUANTITY="$QUANTITY" \
TICKET_CATEGORY_ID="$TICKET_CATEGORY_ID" \
"$ROOT_DIR/scripts/load/prepare-async-order-jmeter-data.sh"

echo
echo "4. MySQL 同步结果核验..."
mysql --protocol=TCP \
  -h "$DB_HOST" \
  -P "$DB_PORT" \
  -u "$DB_USER" \
  "-p${DB_PASSWORD}" \
  -D "$DB_NAME" <<SQL
SELECT COUNT(*) AS load_user_count
FROM user_account
WHERE phone >= CONCAT('${USER_PHONE_PREFIX}', LPAD(1, ${USER_PHONE_WIDTH}, '0'))
  AND phone <= CONCAT('${USER_PHONE_PREFIX}', LPAD(${USER_COUNT}, ${USER_PHONE_WIDTH}, '0'))
  AND role_code = 'USER';

SELECT ticket_category_id, total_stock, available_stock, locked_stock, sold_stock
FROM ticket_stock
WHERE ticket_category_id = ${TICKET_CATEGORY_ID};

SELECT bucket_version,
       COUNT(*) AS bucket_count,
       SUM(total_stock) AS total_stock,
       SUM(available_stock) AS available_stock,
       SUM(locked_stock) AS locked_stock,
       SUM(sold_stock) AS sold_stock
FROM ticket_stock_bucket
WHERE ticket_category_id = ${TICKET_CATEGORY_ID}
GROUP BY bucket_version;
SQL

echo
echo "环境准备完成。下一步运行："
echo "REQUESTS=$ROWS STOCK_QUANTITY=$STOCK_QUANTITY ORDER_QUANTITY=$QUANTITY USER_MULTIPLIER=$USER_MULTIPLIER ./scripts/load/run-soldout-flood-jmeter.sh"
