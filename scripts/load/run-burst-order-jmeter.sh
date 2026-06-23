#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

export DATA_FILE="${DATA_FILE:-/tmp/async-order-users-formal.csv}"
export REPORT_ROOT="${REPORT_ROOT:-$ROOT_DIR/reports/jmeter-burst}"
export HEAP="${HEAP:--Xms512m -Xmx2g -XX:MaxMetaspaceSize=256m}"

# 洪峰压测默认不做结果轮询，否则查询接口会把入口洪峰测试污染成“提交 + 查询”混合压力。
# 跑完后通过 MySQL 与 Kafka lag 验证异步创单是否追平。
export POLL_RESULT="${POLL_RESULT:-false}"

BURST_LEVEL="${BURST_LEVEL:-local}"

case "$BURST_LEVEL" in
  smoke)
    DEFAULT_THREADS=50
    DEFAULT_RAMP_SECONDS=2
    DEFAULT_DURATION_SECONDS=15
    DEFAULT_TARGET_QPS=300
    ;;
  local)
    # 适合当前 M4 Air 16GB、JMeter/应用/MySQL/Redis/Kafka 同机的第一档洪峰。
    DEFAULT_THREADS=200
    DEFAULT_RAMP_SECONDS=1
    DEFAULT_DURATION_SECONDS=20
    DEFAULT_TARGET_QPS=2000
    ;;
  strong)
    # 本机强压档，可能开始测到本机资源瓶颈。
    DEFAULT_THREADS=400
    DEFAULT_RAMP_SECONDS=1
    DEFAULT_DURATION_SECONDS=20
    DEFAULT_TARGET_QPS=5000
    ;;
  extreme)
    # 本机极限尝试档，不代表生产容量，失败也可能是压测机先到瓶颈。
    DEFAULT_THREADS=800
    DEFAULT_RAMP_SECONDS=1
    DEFAULT_DURATION_SECONDS=15
    DEFAULT_TARGET_QPS=10000
    ;;
  *)
    echo "未知 BURST_LEVEL=$BURST_LEVEL，可选：smoke/local/strong/extreme"
    exit 1
    ;;
esac

export THREADS="${THREADS:-$DEFAULT_THREADS}"
export RAMP_SECONDS="${RAMP_SECONDS:-$DEFAULT_RAMP_SECONDS}"
export DURATION_SECONDS="${DURATION_SECONDS:-$DEFAULT_DURATION_SECONDS}"
export TARGET_QPS="${TARGET_QPS:-$DEFAULT_TARGET_QPS}"

echo "洪峰压测档位：BURST_LEVEL=$BURST_LEVEL"
echo "当前脚本按 M4 Air 10 核 / 16GB / 全组件同机保守设置；几十万请求请使用分布式压测。"

exec "$ROOT_DIR/scripts/load/run-async-order-jmeter.sh"
