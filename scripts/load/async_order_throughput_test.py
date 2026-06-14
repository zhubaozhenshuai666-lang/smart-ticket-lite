#!/usr/bin/env python3
"""
Async order submit/create throughput test for smart-ticket-lite.

Only Python standard library is used so the script can run on a clean machine.
It measures two different things:
1. submit_qps: how fast /api/orders/async accepts requests.
2. created_order_tps: how fast accepted requests reach terminal async result.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Any


TERMINAL_STATUSES = {"SUCCESS", "FAILED", "CANCELLED", "COMPENSATED"}


@dataclass
class SubmitResult:
    index: int
    ok: bool
    request_id: str | None
    http_status: int | None
    app_code: int | None
    latency_ms: float
    error: str | None
    started_at: float


@dataclass
class PollResult:
    request_id: str
    terminal: bool
    status: str
    order_id: Any
    fail_reason: str | None
    latency_ms: float
    error: str | None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Pressure test async order submit and async order creation.")
    parser.add_argument("--base-url", default=os.getenv("BASE_URL", "http://127.0.0.1:8081"))
    parser.add_argument("--auth-token", default=os.getenv("AUTH_TOKEN"))
    parser.add_argument("--total", type=int, default=int(os.getenv("TOTAL", "1000")))
    parser.add_argument("--concurrency", type=int, default=int(os.getenv("CONCURRENCY", "100")))
    parser.add_argument("--show-id", type=int, default=int(os.getenv("SHOW_ID", "1")))
    parser.add_argument("--session-id", type=int, default=int(os.getenv("SESSION_ID", "1")))
    parser.add_argument("--ticket-category-id", type=int, default=int(os.getenv("TICKET_CATEGORY_ID", "2")))
    parser.add_argument("--quantity", type=int, default=int(os.getenv("QUANTITY", "1")))
    parser.add_argument("--risk-decision", default=os.getenv("RISK_DECISION", "pass"))
    parser.add_argument("--token-batch-size", type=int, default=int(os.getenv("TOKEN_BATCH_SIZE", "100")))
    parser.add_argument("--admission-token-file", help="One waiting-room admission token per line.")
    parser.add_argument("--skip-capacity-guard", action="store_true")
    parser.add_argument("--skip-result-poll", action="store_true")
    parser.add_argument("--poll-timeout-seconds", type=float, default=60.0)
    parser.add_argument("--poll-interval-seconds", type=float, default=0.2)
    parser.add_argument("--http-timeout-seconds", type=float, default=10.0)
    parser.add_argument("--output-json", help="Write the raw summary JSON to this file.")
    return parser.parse_args()


def api_success(payload: dict[str, Any]) -> bool:
    return payload.get("code") in (0, 200)


def auth_headers(args: argparse.Namespace) -> dict[str, str]:
    headers = {"Content-Type": "application/json"}
    if args.auth_token:
        headers["Authorization"] = f"Bearer {args.auth_token}"
    return headers


def request_json(
    method: str,
    url: str,
    args: argparse.Namespace,
    body: dict[str, Any] | None = None,
    extra_headers: dict[str, str] | None = None,
) -> tuple[int, dict[str, Any]]:
    data = None
    headers = auth_headers(args)
    if extra_headers:
        headers.update(extra_headers)
    if body is not None:
        data = json.dumps(body, separators=(",", ":")).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=args.http_timeout_seconds) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            payload = {"message": raw}
        return exc.code, payload


def endpoint(args: argparse.Namespace, path: str, query: dict[str, Any] | None = None) -> str:
    base = args.base_url.rstrip("/")
    if not query:
        return f"{base}{path}"
    return f"{base}{path}?{urllib.parse.urlencode(query)}"


def unwrap_data(payload: dict[str, Any], label: str) -> Any:
    if not api_success(payload):
        raise RuntimeError(f"{label} failed: code={payload.get('code')} message={payload.get('message')}")
    return payload.get("data")


def fetch_capacity(args: argparse.Namespace) -> dict[str, Any] | None:
    status, payload = request_json("GET", endpoint(args, "/api/admin/ops/capacity/order-pipeline"), args)
    if status >= 400:
        print(f"[WARN] capacity endpoint returned http={status}: {payload.get('message')}", file=sys.stderr)
        return None
    return unwrap_data(payload, "capacity")


def fetch_metrics(args: argparse.Namespace) -> dict[str, Any] | None:
    status, payload = request_json("GET", endpoint(args, "/api/admin/ops/metrics-summary"), args)
    if status >= 400:
        print(f"[WARN] metrics endpoint returned http={status}: {payload.get('message')}", file=sys.stderr)
        return None
    return unwrap_data(payload, "metrics")


def assert_capacity_ready(capacity: dict[str, Any] | None) -> None:
    if capacity is None:
        return
    errors = []
    if not capacity.get("fastPipelineEnabled"):
        errors.append("fastPipelineEnabled=false，压测会走慢链路，数据没有参考价值")
    if not capacity.get("waitingRoomEnabled"):
        errors.append("waitingRoomEnabled=false，没有入场削峰，洪峰测试会直接冲核心链路")
    if capacity.get("directRabbitWaitForConfirm"):
        errors.append("directRabbitWaitForConfirm=true，入口线程等待 MQ confirm 会压低 submit QPS")
    if capacity.get("perOrderTimeoutDelayMessageEnabled"):
        errors.append("perOrderTimeoutDelayMessageEnabled=true，每单超时消息会造成写放大")
    hard_bottleneck = str(capacity.get("hardBottleneck") or "")
    if "Outbox" in hard_bottleneck or "outbox" in hard_bottleneck:
        errors.append(f"hardBottleneck={hard_bottleneck}")
    if errors:
        joined = "\n- ".join(errors)
        raise RuntimeError(f"capacity guard failed:\n- {joined}\n需要修正配置，或本地摸底时加 --skip-capacity-guard。")


def load_admission_tokens(path: str | None, total: int) -> list[str | None]:
    if not path:
        return [None] * total
    with open(path, "r", encoding="utf-8") as file:
        tokens = [line.strip() for line in file if line.strip()]
    if len(tokens) < total:
        raise RuntimeError(f"admission token count is {len(tokens)}, but total is {total}")
    return tokens[:total]


def fetch_idempotency_tokens(args: argparse.Namespace) -> list[str]:
    tokens: list[str] = []
    while len(tokens) < args.total:
        count = min(args.token_batch_size, args.total - len(tokens))
        status, payload = request_json(
            "GET",
            endpoint(args, "/api/orders/idempotency-tokens", {"count": count}),
            args,
        )
        if status >= 400:
            raise RuntimeError(f"idempotency tokens http={status}: {payload.get('message')}")
        data = unwrap_data(payload, "idempotency tokens")
        batch = [item["token"] for item in data or [] if item.get("token")]
        if not batch:
            raise RuntimeError("idempotency token batch is empty")
        tokens.extend(batch)
        print(f"[INFO] prefetched idempotency tokens: {len(tokens)}/{args.total}")
    return tokens[: args.total]


def submit_one(args: argparse.Namespace, index: int, token: str, admission_token: str | None) -> SubmitResult:
    started = time.perf_counter()
    body: dict[str, Any] = {
        "showId": args.show_id,
        "sessionId": args.session_id,
        "ticketCategoryId": args.ticket_category_id,
        "quantity": args.quantity,
        "idempotencyToken": token,
    }
    if admission_token:
        body["admissionToken"] = admission_token
    try:
        status, payload = request_json(
            "POST",
            endpoint(args, "/api/orders/async"),
            args,
            body,
            {"X-Smart-Ticket-Risk-Decision": args.risk_decision},
        )
        latency = (time.perf_counter() - started) * 1000
        data = payload.get("data") if isinstance(payload, dict) else None
        request_id = data.get("requestId") if isinstance(data, dict) else None
        app_code = payload.get("code") if isinstance(payload, dict) else None
        ok = status < 400 and api_success(payload) and bool(request_id)
        error = None if ok else str(payload.get("message") or payload)
        return SubmitResult(index, ok, request_id, status, app_code, latency, error, started)
    except Exception as exc:  # noqa: BLE001 - pressure test must record all client-side failures.
        latency = (time.perf_counter() - started) * 1000
        return SubmitResult(index, False, None, None, None, latency, repr(exc), started)


def poll_one(args: argparse.Namespace, submit_result: SubmitResult) -> PollResult:
    assert submit_result.request_id is not None
    deadline = time.perf_counter() + args.poll_timeout_seconds
    last_status = "UNKNOWN"
    last_order_id = None
    last_fail_reason = None
    last_error = None
    while time.perf_counter() < deadline:
        try:
            status, payload = request_json(
                "GET",
                endpoint(args, f"/api/order-requests/{submit_result.request_id}"),
                args,
            )
            if status >= 400 or not api_success(payload):
                last_error = str(payload.get("message") or payload)
            else:
                data = payload.get("data") or {}
                last_status = str(data.get("status") or "UNKNOWN")
                last_order_id = data.get("orderId")
                last_fail_reason = data.get("failReason")
                if last_status in TERMINAL_STATUSES:
                    return PollResult(
                        submit_result.request_id,
                        True,
                        last_status,
                        last_order_id,
                        last_fail_reason,
                        (time.perf_counter() - submit_result.started_at) * 1000,
                        None,
                    )
        except Exception as exc:  # noqa: BLE001
            last_error = repr(exc)
        time.sleep(args.poll_interval_seconds)
    return PollResult(
        submit_result.request_id,
        False,
        last_status,
        last_order_id,
        last_fail_reason,
        (time.perf_counter() - submit_result.started_at) * 1000,
        last_error or "poll_timeout",
    )


def percentile(values: list[float], percent: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = math.ceil((percent / 100.0) * len(ordered)) - 1
    return ordered[min(max(rank, 0), len(ordered) - 1)]


def latency_stats(values: list[float]) -> dict[str, float | None]:
    return {
        "count": len(values),
        "avg_ms": statistics.fmean(values) if values else None,
        "p50_ms": percentile(values, 50),
        "p95_ms": percentile(values, 95),
        "p99_ms": percentile(values, 99),
        "max_ms": max(values) if values else None,
    }


def metric_delta(before: dict[str, Any] | None, after: dict[str, Any] | None) -> dict[str, Any]:
    if not before or not after:
        return {}
    delta = {}
    for key, value in after.items():
        if isinstance(value, (int, float)) and isinstance(before.get(key), (int, float)):
            delta[key] = value - before[key]
    return delta


def print_counter(title: str, counter: Counter[Any], limit: int = 10) -> None:
    print(f"\n{title}")
    if not counter:
        print("  <empty>")
        return
    for key, value in counter.most_common(limit):
        print(f"  {key}: {value}")


def main() -> int:
    args = parse_args()
    if not args.auth_token:
        print("AUTH_TOKEN is required. Use --auth-token or export AUTH_TOKEN=...", file=sys.stderr)
        return 2
    if args.total <= 0 or args.concurrency <= 0:
        print("--total and --concurrency must be positive", file=sys.stderr)
        return 2

    capacity = fetch_capacity(args)
    if not args.skip_capacity_guard:
        assert_capacity_ready(capacity)

    before_metrics = fetch_metrics(args)
    admission_tokens = load_admission_tokens(args.admission_token_file, args.total)
    idempotency_tokens = fetch_idempotency_tokens(args)

    submit_results: list[SubmitResult] = []
    submit_started = time.perf_counter()
    lock = threading.Lock()
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [
            executor.submit(submit_one, args, index, idempotency_tokens[index], admission_tokens[index])
            for index in range(args.total)
        ]
        for future in as_completed(futures):
            result = future.result()
            with lock:
                submit_results.append(result)
                done = len(submit_results)
                if done % max(1, args.total // 10) == 0 or done == args.total:
                    print(f"[INFO] submitted: {done}/{args.total}")
    submit_elapsed = time.perf_counter() - submit_started

    poll_results: list[PollResult] = []
    poll_started = time.perf_counter()
    successful_submits = [item for item in submit_results if item.ok and item.request_id]
    if not args.skip_result_poll:
        with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
            futures = [executor.submit(poll_one, args, item) for item in successful_submits]
            for future in as_completed(futures):
                result = future.result()
                poll_results.append(result)
                done = len(poll_results)
                if done % max(1, max(len(successful_submits), 1) // 10) == 0 or done == len(successful_submits):
                    print(f"[INFO] polled terminal results: {done}/{len(successful_submits)}")
    poll_elapsed = time.perf_counter() - poll_started
    terminal_wall_elapsed = time.perf_counter() - submit_started
    after_metrics = fetch_metrics(args)

    submit_latency = [item.latency_ms for item in submit_results]
    terminal_latency = [item.latency_ms for item in poll_results if item.terminal]
    terminal_success_count = sum(1 for item in poll_results if item.terminal and item.status == "SUCCESS")
    terminal_failed_count = sum(1 for item in poll_results if item.terminal and item.status != "SUCCESS")
    terminal_timeout_count = sum(1 for item in poll_results if not item.terminal)
    result = {
        "total": args.total,
        "concurrency": args.concurrency,
        "submit_success_count": len(successful_submits),
        "submit_failed_count": args.total - len(successful_submits),
        "submit_elapsed_seconds": submit_elapsed,
        "submit_qps": len(submit_results) / submit_elapsed if submit_elapsed > 0 else None,
        "terminal_success_count": terminal_success_count,
        "terminal_failed_count": terminal_failed_count,
        "terminal_timeout_count": terminal_timeout_count,
        "terminal_poll_elapsed_seconds": poll_elapsed,
        "terminal_wall_elapsed_seconds": terminal_wall_elapsed,
        "created_order_tps": terminal_success_count / terminal_wall_elapsed
        if terminal_wall_elapsed > 0 and not args.skip_result_poll else None,
        "submit_latency": latency_stats(submit_latency),
        "terminal_latency": latency_stats(terminal_latency),
        "http_status": Counter(item.http_status for item in submit_results),
        "app_code": Counter(item.app_code for item in submit_results),
        "submit_errors": Counter(item.error for item in submit_results if item.error),
        "terminal_status": Counter(item.status for item in poll_results),
        "fail_reasons": Counter(item.fail_reason for item in poll_results if item.fail_reason),
        "metrics_delta": metric_delta(before_metrics, after_metrics),
        "capacity": capacity,
    }

    print("\n========== Async Order Throughput Summary ==========")
    print(f"total={result['total']} concurrency={result['concurrency']}")
    print(
        f"submit_success={result['submit_success_count']} submit_failed={result['submit_failed_count']} "
        f"submit_elapsed={result['submit_elapsed_seconds']:.3f}s submit_qps={result['submit_qps']:.2f}"
    )
    if args.skip_result_poll:
        print("result_poll=skipped")
    else:
        print(
            f"terminal_success={terminal_success_count} terminal_failed={terminal_failed_count} "
            f"terminal_timeout={terminal_timeout_count} poll_elapsed={poll_elapsed:.3f}s "
            f"wall_elapsed={terminal_wall_elapsed:.3f}s "
            f"created_order_tps={result['created_order_tps']:.2f}"
        )
    print(f"submit_latency={json.dumps(result['submit_latency'], ensure_ascii=False)}")
    print(f"terminal_latency={json.dumps(result['terminal_latency'], ensure_ascii=False)}")
    print_counter("HTTP status distribution", result["http_status"])
    print_counter("App code distribution", result["app_code"])
    print_counter("Terminal status distribution", result["terminal_status"])
    print_counter("Submit error top10", result["submit_errors"])
    print_counter("Fail reason top10", result["fail_reasons"])
    print(f"\nMetrics delta\n  {json.dumps(result['metrics_delta'], ensure_ascii=False, sort_keys=True)}")

    if args.output_json:
        serializable = {
            key: dict(value) if isinstance(value, Counter) else value
            for key, value in result.items()
        }
        with open(args.output_json, "w", encoding="utf-8") as file:
            json.dump(serializable, file, ensure_ascii=False, indent=2)
        print(f"\n[INFO] wrote json summary: {args.output_json}")

    if result["submit_failed_count"] > 0 or terminal_timeout_count > 0:
        return 1
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\nInterrupted", file=sys.stderr)
        raise SystemExit(130)
