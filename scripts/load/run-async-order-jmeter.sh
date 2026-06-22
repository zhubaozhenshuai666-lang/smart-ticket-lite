#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JMETER_BIN="${JMETER_BIN:-jmeter}"
TEST_PLAN="${TEST_PLAN:-$ROOT_DIR/scripts/jmeter/async-order-load-test.jmx}"
DEFAULT_DATA_FILE="$ROOT_DIR/scripts/jmeter/data/async-order-users.csv"
if [[ -f /tmp/async-order-users.csv ]]; then
  DEFAULT_DATA_FILE="/tmp/async-order-users.csv"
fi
DATA_FILE="${DATA_FILE:-$DEFAULT_DATA_FILE}"
REPORT_ROOT="${REPORT_ROOT:-$ROOT_DIR/reports/jmeter}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RESULT_DIR="$REPORT_ROOT/$RUN_ID"
JTL_FILE="$RESULT_DIR/result.jtl"
LOG_FILE="$RESULT_DIR/jmeter.log"
HTML_DIR="$RESULT_DIR/html"

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
THREADS="${THREADS:-100}"
RAMP_SECONDS="${RAMP_SECONDS:-60}"
DURATION_SECONDS="${DURATION_SECONDS:-300}"
TARGET_QPS="${TARGET_QPS:-200}"
TARGET_QPM="$((TARGET_QPS * 60))"
POLL_RESULT="${POLL_RESULT:-true}"
POLL_MAX_ATTEMPTS="${POLL_MAX_ATTEMPTS:-20}"
POLL_INTERVAL_MS="${POLL_INTERVAL_MS:-300}"
RISK_DECISION="${RISK_DECISION:-pass}"
DO_PREWARM="${DO_PREWARM:-false}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"

if ! command -v "$JMETER_BIN" >/dev/null 2>&1; then
  echo "找不到 JMeter 命令：$JMETER_BIN"
  echo "macOS 可执行：brew install jmeter"
  echo "或设置 JMETER_BIN=/path/to/apache-jmeter/bin/jmeter"
  exit 1
fi

if [[ ! -f "$TEST_PLAN" ]]; then
  echo "找不到测试计划：$TEST_PLAN"
  exit 1
fi

if [[ ! -f "$DATA_FILE" ]]; then
  echo "找不到 CSV 数据文件：$DATA_FILE"
  exit 1
fi

mkdir -p "$RESULT_DIR" "$HTML_DIR"

cat <<CONFIG
JMeter 异步下单压测配置
BASE_URL=$BASE_URL
TEST_PLAN=$TEST_PLAN
DATA_FILE=$DATA_FILE
THREADS=$THREADS
RAMP_SECONDS=$RAMP_SECONDS
DURATION_SECONDS=$DURATION_SECONDS
TARGET_QPS=$TARGET_QPS
TARGET_QPM=$TARGET_QPM
POLL_RESULT=$POLL_RESULT
POLL_MAX_ATTEMPTS=$POLL_MAX_ATTEMPTS
POLL_INTERVAL_MS=$POLL_INTERVAL_MS
DO_PREWARM=$DO_PREWARM
RESULT_DIR=$RESULT_DIR
CONFIG

"$JMETER_BIN" -n \
  -t "$TEST_PLAN" \
  -l "$JTL_FILE" \
  -j "$LOG_FILE" \
  -e -o "$HTML_DIR" \
  -Jbase_url="$BASE_URL" \
  -Jdata_file="$DATA_FILE" \
  -Jthreads="$THREADS" \
  -Jramp_seconds="$RAMP_SECONDS" \
  -Jduration_seconds="$DURATION_SECONDS" \
  -Jtarget_qps="$TARGET_QPS" \
  -Jtarget_qpm="$TARGET_QPM" \
  -Jpoll_result="$POLL_RESULT" \
  -Jpoll_max_attempts="$POLL_MAX_ATTEMPTS" \
  -Jpoll_interval_ms="$POLL_INTERVAL_MS" \
  -Jrisk_decision="$RISK_DECISION" \
  -Jdo_prewarm="$DO_PREWARM" \
  -Jadmin_token="$ADMIN_TOKEN"

echo
echo "压测完成。"
echo "原始结果：$JTL_FILE"
echo "JMeter 日志：$LOG_FILE"
echo "HTML 报告：$HTML_DIR/index.html"
