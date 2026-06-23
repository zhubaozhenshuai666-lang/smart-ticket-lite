#!/usr/bin/env bash
set -euo pipefail

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-${SMART_TICKET_DB_PASSWORD:-}}"
DB_NAME="${DB_NAME:-smart_ticket_lite}"
TICKET_CATEGORY_ID="${TICKET_CATEGORY_ID:-2}"
RESET_STOCK="${RESET_STOCK:-true}"
RESET_STOCK_QUANTITY="${RESET_STOCK_QUANTITY:-1000}"
CONFIRM_RESET="${CONFIRM_RESET:-NO}"
ADMIN_PHONE="${ADMIN_PHONE:-13800000001}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-${SMART_TICKET_ADMIN_PASSWORD:-}}"

mysql_cmd=(
  mysql
  --protocol=TCP
  -h "$DB_HOST"
  -P "$DB_PORT"
  -u "$DB_USER"
  "-p${DB_PASSWORD}"
  -D "$DB_NAME"
)

echo "Smart Ticket 压测环境恢复"
echo "DB=${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "TICKET_CATEGORY_ID=${TICKET_CATEGORY_ID}"
echo "RESET_STOCK=${RESET_STOCK}"
echo "RESET_STOCK_QUANTITY=${RESET_STOCK_QUANTITY}"
echo

if [[ "$CONFIRM_RESET" != "YES" ]]; then
  cat <<'EOF'
当前是预览模式，不会删除任何数据。

真正执行请加：

CONFIRM_RESET=YES ./scripts/load/reset-load-test-env.sh

会清理：
- ticket_order_request
- ticket_order
- local_message
- dead_letter_message
- payment_order / payment_callback_log / payment_flow_log
- stock_consistency_record / stock_compensation_record
- Redis 等待室、幂等 token、异步结果、限流、风控、库存预扣标记
- /tmp 里的 JMeter 临时结果

会保留：
- user_account
- venue / show_info / performance_session / ticket_category
- admin_operation_log
EOF
  exit 0
fi

if [[ -z "$DB_PASSWORD" ]]; then
  echo "缺少数据库密码。先执行：read -s SMART_TICKET_DB_PASSWORD && export SMART_TICKET_DB_PASSWORD"
  exit 1
fi

echo "1. 清理 MySQL 压测交易数据..."
"${mysql_cmd[@]}" <<SQL
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE payment_flow_log;
TRUNCATE TABLE payment_callback_log;
TRUNCATE TABLE payment_order;
TRUNCATE TABLE dead_letter_message;
TRUNCATE TABLE local_message;
TRUNCATE TABLE ticket_order_request;
TRUNCATE TABLE ticket_order;
TRUNCATE TABLE stock_compensation_record;
TRUNCATE TABLE stock_consistency_record;
SET FOREIGN_KEY_CHECKS = 1;
SQL

if [[ "$RESET_STOCK" == "true" ]]; then
  echo "2. 重置 MySQL 库存和 bucket..."
  "${mysql_cmd[@]}" <<SQL
UPDATE ticket_stock
SET total_stock = ${RESET_STOCK_QUANTITY},
    available_stock = ${RESET_STOCK_QUANTITY},
    locked_stock = 0,
    sold_stock = 0,
    version = version + 1,
    updated_at = NOW()
WHERE ticket_category_id = ${TICKET_CATEGORY_ID};

SET @bucket_count := (SELECT COUNT(*) FROM ticket_stock_bucket WHERE ticket_category_id = ${TICKET_CATEGORY_ID} AND bucket_version = 1);
SET @base := FLOOR(${RESET_STOCK_QUANTITY} / @bucket_count);
SET @remain := MOD(${RESET_STOCK_QUANTITY}, @bucket_count);

UPDATE ticket_stock_bucket
SET total_stock = @base + IF(bucket_no < @remain, 1, 0),
    available_stock = @base + IF(bucket_no < @remain, 1, 0),
    locked_stock = 0,
    sold_stock = 0,
    version = version + 1,
    updated_at = NOW()
WHERE ticket_category_id = ${TICKET_CATEGORY_ID}
  AND bucket_version = 1;
SQL
fi

echo "3. 清理 Redis 压测 key..."
redis_patterns=(
  "waiting-room:admission:*"
  "waiting-room:queue:*"
  "waiting-room:sequence:*"
  "order:idempotency:*"
  "order:async:result:*"
  "order:async:inflight:*"
  "rate:*"
  "rate:limit:*"
  "risk:order:*"
  "ticket:stock:deducted:*"
  "ticket:stock:compensated:*"
  "ticket:soldout:*"
)

for pattern in "${redis_patterns[@]}"; do
  while IFS= read -r key; do
    [[ -n "$key" ]] && redis-cli DEL "$key" >/dev/null
  done < <(redis-cli --scan --pattern "$pattern")
done

if [[ "$RESET_STOCK" == "true" ]]; then
  echo "4. 重新预热 Redis bucket 库存..."
  if [[ -z "$ADMIN_PASSWORD" ]]; then
    echo "未设置 SMART_TICKET_ADMIN_PASSWORD，跳过 Redis 库存预热。"
    echo "需要自动预热时先执行：read -s SMART_TICKET_ADMIN_PASSWORD && export SMART_TICKET_ADMIN_PASSWORD"
  else
    admin_token=$(curl -sS -X POST http://127.0.0.1:8081/api/auth/login \
      -H 'Content-Type: application/json' \
      -d "{\"phone\":\"${ADMIN_PHONE}\",\"password\":\"${ADMIN_PASSWORD}\"}" | /usr/bin/jq -r '.data.token')
    curl -sS -X POST "http://127.0.0.1:8081/api/admin/ticket-categories/${TICKET_CATEGORY_ID}/stock/preheat" \
      -H "Authorization: Bearer ${admin_token}" | /usr/bin/jq .
  fi
fi

echo "5. 清理 JMeter 临时文件..."
rm -f /tmp/async-order-users-formal.csv \
      /tmp/async-order-users.csv \
      /tmp/smart-ticket-jmeter-gui-result.jtl \
      /tmp/jmeter-fixcheck*.out \
      /tmp/jmeter-path-check.* \
      /tmp/manual-idem.json \
      /tmp/manual-submit.json

echo
echo "恢复完成。当前库存："
"${mysql_cmd[@]}" -e "
SELECT * FROM ticket_stock WHERE ticket_category_id = ${TICKET_CATEGORY_ID};
SELECT bucket_version, COUNT(*) bucket_count, SUM(total_stock) total_stock, SUM(available_stock) available_stock, SUM(locked_stock) locked_stock, SUM(sold_stock) sold_stock
FROM ticket_stock_bucket
WHERE ticket_category_id = ${TICKET_CATEGORY_ID}
GROUP BY bucket_version;
"
