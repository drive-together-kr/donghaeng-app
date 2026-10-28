#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""동행 지연 측정 (로컬 vs 클라우드) — docs/09 §4.2, docs/05 §3.4.

같은 그룹에 든 두 차(차 A·차 B, debug 빌드)와 서버로 아래를 재서 out/latency_<label>_<시각>.json + .md 를 만든다.
로컬 서버와 클라우드 서버에서 한 번씩 돌린 뒤 compare 로 비교표를 낸다.

  python tools/demo/latency.py run --server http://127.0.0.1:8080 --label local --car-a emulator-5556 --car-b emulator-5558 --email <dev 이메일>
  python tools/demo/latency.py run --server https://<VM-IP>.sslip.io --label cloud --car-a emulator-5556 --car-b emulator-5558 --email <dev 이메일>
  python tools/demo/latency.py compare tools/demo/out/latency_local_<시각>.json tools/demo/out/latency_cloud_<시각>.json

순서
  ① PC → 서버 HTTP 왕복: http.client keep-alive 한 연결로 GET /v1/time n 번(첫 표본 = 연결 수립 포함, 따로)
  ② 차마다 latency_reset → net_rtt(앱 OkHttp, 첫 표본 따로) → net_mqtt_rtt(앱 HiveMQ QoS 1 PUBLISH → PUBACK = 브로커 왕복, 서버 앱 미경유)
  ③ 그룹 메시지(PASSENGER_MESSAGE) K 개를 REST 로 보내며(keep-alive, 요청 직전 호스트 시각 기록) 두 차의 수신 줄
     `latency: event …ms … type=PASSENGER_MESSAGE id=…<eventId 끝 6자> ts=<서버 발행> rx=<기기 수신>` 과 eventId 끝 6자로 맞춰 'PC 송신 → 차 수신'
  ④ latency_dump — reset 이후 누적 EVENT·STATE 의 서버 발행 → 차 수신(한 방향, 앱 ClockSync 보정) P50·P95
  ⑤ 결과 JSON + 마크다운 표(같은 이름 .md) + 두 차의 DongHaeng 로그(_carA.log·_carB.log)

시계 보정('PC 송신 → 차 수신'은 호스트 시계와 기기 시계를 잇는다 — 에뮬레이터 시계는 호스트보다 1~2 s 늦게 실측됐다):
  기본 = adb 기기 시계: 상주 `adb shell -T` 세션에서 `date +%s%N` 을 여러 번, 왕복이 가장 짧은 표본으로 오프셋(오차 ±왕복/2), ③ 앞뒤로 재서 평균(오차에 표류/2 더함).
  세션이 중간에 끊기면 그때까지 받은 표본으로 잡고, 유효 표본이 하나도 없을 때만 adb 를 매번 띄운다(왕복이 길어 오차 ±15~50 ms).
  교차 확인 = 앱 ClockSync 오프셋(latency_dump offset, ±clockRtt/2) + PC → 서버 오프셋(① 최소 왕복 표본, ±왕복/2).
  수신 시각은 앱 로그의 rx=(HiveMQ 수신 콜백의 기기 epoch ms)를 쓰고, 없으면 `logcat -v epoch` 시각을 쓴다.

- dev 로그인: --email(또는 DH_DEMO_EMAIL). 클라우드는 접속 키 DH_DEV_LOGIN_KEY 환경 변수(api.py 와 같은 방식 — 출력·파일에 쓰지 않는다).
  사용자 토큰 수명이 1시간이라 ③ 앞에서 새로 받고(캐시 토큰을 그대로 쓰지 않는다), 그래도 401 이면 메시지마다 한 번 다시 로그인해 다시 보낸다.
- 메시지는 음성으로도 읽히므로 ③ 동안 두 차 음성을 잠시 끄고 끝나면 원래대로 돌린다(--keep-voice 면 그대로). 그룹 소식에 '지연 측정 i/K' 가 남는다 — 시험용 그룹에서.
- 서버의 동승자 메시지에는 빈도 제한이 없다(GroupService.postMessage — 429 는 무전 3 s·페어링 폴링뿐). 기본 간격 1.5 s 는 수신·음성 처리가 겹치지 않게 둔 것.
- 출력·파일에 토큰·이메일·좌표·닉네임·메시지 원문 이외의 사용자 값은 남기지 않는다(서버 주소는 scheme://host[:port] 만).
"""
import argparse
import datetime as dt
import http.client
import json
import os
import queue
import re
import secrets
import ssl
import subprocess
import sys
import threading
import time
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from api import DhApi  # noqa: E402
from demo import ADB, PKG, LogWatcher, adb, adb_devices, broadcast, device_connected, sh, status  # noqa: E402

OUT_DIR = os.path.join(HERE, "out")
MSG_TYPE = "PASSENGER_MESSAGE"
CARS = ("A", "B")


# ---------------------------------------------------------------- 순수 도우미(단위 테스트 대상)
def percentile(values, p: int):
    """최근접 순위법 — 앱 `LatencyPercentiles.nearestRank` 와 같은 정의.
    오름차순 표본의 ⌈p·n/100⌉ 번째(1부터, 1 ≤ r ≤ n). 보간하지 않으므로 값은 언제나 실제 표본이다. 순위는 정수 산술로 계산한다."""
    if not values:
        raise ValueError("표본이 없다")
    if not 1 <= p <= 100:
        raise ValueError(f"p={p}")
    s = sorted(values)
    rank = min(max((p * len(s) + 99) // 100, 1), len(s))
    return s[rank - 1]


def summarize(values):
    """None 을 뺀 표본의 {n, p50, p95, min, max}. 표본이 없으면 None"""
    vals = sorted(v for v in values if v is not None)
    if not vals:
        return None
    return {"n": len(vals), "p50": percentile(vals, 50), "p95": percentile(vals, 95), "min": vals[0], "max": vals[-1]}


def first_and_rest(attempts):
    """시도 순서대로의 왕복(실패 None) → (첫 표본, 두 번째부터의 요약, 실패 수) — 앱 net_rtt 와 같은 규칙"""
    first = attempts[0] if attempts else None
    return first, summarize(attempts[1:]), sum(1 for v in attempts if v is None)


KV_RX = re.compile(r"(\w+)=(\S+)")


def ms_value(v):
    """'12.3ms'·'12'·'12ms' → float, '-'·'fail'·None → None"""
    if v is None:
        return None
    m = re.fullmatch(r"(-?\d+(?:\.\d+)?)(?:ms)?", v.strip())
    return float(m.group(1)) if m else None


def parse_net_line(line: str):
    """앱 `net: http_rtt|mqtt_rtt n=… first=…ms p50=…ms p95=…ms min=…ms max=…ms base=… fail=… …` → dict (시작 줄·다른 줄은 None)"""
    m = re.search(r"net: (http_rtt|mqtt_rtt) (n=.*)$", line)
    if not m:
        return None
    kv = dict(KV_RX.findall(m.group(2)))
    if "first" not in kv:
        return None
    core = ("n", "first", "p50", "p95", "min", "max", "base", "fail")
    n = int(kv["n"])
    return {
        "kind": m.group(1),
        "first_ms": ms_value(kv["first"]),
        "summary": None if n == 0 else {"n": n, "p50": ms_value(kv.get("p50")), "p95": ms_value(kv.get("p95")),
                                         "min": ms_value(kv.get("min")), "max": ms_value(kv.get("max"))},
        "base": kv.get("base"),
        "fail": int(kv.get("fail", "0")),
        "extra": {k: v for k, v in kv.items() if k not in core},
    }


def parse_summary_line(line: str):
    """앱 `latency: summary event n=… p50=…ms … | state n=… … | clockRtt=…ms offset=…ms clamped=… unsynced=…` → dict"""
    i = line.find("latency: summary ")
    if i < 0:
        return None
    out = {"event": None, "state": None, "clock_rtt_ms": None, "clock_offset_ms": None, "clamped": 0, "unsynced": 0}
    for part in (p.strip() for p in line[i + len("latency: summary "):].split("|")):
        name = part.split(" ", 1)[0]
        kv = dict(KV_RX.findall(part))
        if name in ("event", "state"):
            n = int(kv.get("n", "0"))
            out[name] = None if n == 0 else {"n": n, "p50": ms_value(kv.get("p50")), "p95": ms_value(kv.get("p95")),
                                             "min": ms_value(kv.get("min")), "max": ms_value(kv.get("max"))}
        else:
            out["clock_rtt_ms"] = ms_value(kv.get("clockRtt"))
            out["clock_offset_ms"] = ms_value(kv.get("offset"))
            out["clamped"] = int(kv.get("clamped", "0"))
            out["unsynced"] = int(kv.get("unsynced", "0"))
    return out


EVENT_RX = re.compile(r"latency: event (?:(\d+)ms|unsynced)(?=\s|$)")


def parse_event_line(line: str):
    """앱 `latency: event 123ms (avg … n=…)[ clamped(raw=-7)] type=… id=…<끝 6자> ts=<서버 ms> rx=<기기 ms>` 또는 `latency: event unsynced type=… …` → dict.
    꼬리(rx=)가 없는 옛 줄은 None"""
    m = EVENT_RX.search(line)
    if not m:
        return None
    kv = dict(KV_RX.findall(line[m.end():]))
    if "rx" not in kv or "ts" not in kv:
        return None
    tail = re.sub(r"^[^0-9A-Za-z]+", "", kv.get("id", ""))
    return {
        "latency_ms": int(m.group(1)) if m.group(1) else None,
        "type": kv.get("type"),
        "id": tail or None,
        "ts": int(kv["ts"]),
        "rx": int(kv["rx"]),
        "clamped": "clamped(" in line,
    }


LOGCAT_EPOCH_RX = re.compile(r"^\s*(\d{9,}\.\d+)\s")


def logcat_epoch_ms(line: str):
    """`logcat -v epoch` 줄 머리의 기기 시각(초.밀리초) → epoch ms. 없으면 None"""
    m = LOGCAT_EPOCH_RX.match(line)
    return round(float(m.group(1)) * 1000) if m else None


def device_ms(text: str):
    """기기 `date +%s%N` 출력(ns) → epoch ms(float). %N 을 모르는 date 면 None"""
    t = (text or "").strip()
    return int(t) / 1e6 if re.fullmatch(r"\d{16,20}", t) else None


def clock_offset(samples):
    """[(호스트 t0 ms, 상대 시각 ms, 호스트 t1 ms)] → 왕복이 가장 짧은 표본으로 {offset_ms = 상대 − (t0+t1)/2, err_ms = (t1−t0)/2, rtt_ms, samples}.
    상대 시각(기기 date·서버 /v1/time)은 반드시 [t0, t1] 안에서 읽혔으므로 ±왕복/2 가 확실한 오차 상한이다. 표본이 없으면 None"""
    good = [s for s in samples if s and s[1] is not None and s[2] >= s[0]]
    if not good:
        return None
    t0, other, t1 = min(good, key=lambda s: s[2] - s[0])
    rtt = t1 - t0
    return {"offset_ms": other - (t0 + t1) / 2, "err_ms": rtt / 2, "rtt_ms": rtt, "samples": len(good)}


def combine_offsets(before, after):
    """③ 앞·뒤로 잰 기기 시계 오프셋 → 평균. 오차 = 둘 중 큰 오차 + |표류|/2 (선형 표류를 평균 하나로 쓰는 몫)"""
    if before is None and after is None:
        return None
    if before is None or after is None:
        one = dict(before or after)
        one["drift_ms"] = None
        return one
    drift = after["offset_ms"] - before["offset_ms"]
    return {"offset_ms": (before["offset_ms"] + after["offset_ms"]) / 2, "err_ms": max(before["err_ms"], after["err_ms"]) + abs(drift) / 2,
            "drift_ms": drift, "method": before.get("method") or after.get("method"), "before": before, "after": after}


def match_messages(messages, events, dev_offset):
    """보낸 메시지와 한 차의 수신 줄을 eventId 끝 6자로 맞춘다.
    messages: [{i, id, send_ms}] (send_ms = 호스트 시각), events: parse_event_line(+logcat_ms), dev_offset: 기기 − 호스트 오프셋(없으면 e2e 없음).
    → (행 목록 [{i, id, rx_dev_ms, ts_srv_ms, srv_to_car_ms, e2e_ms}], 못 받은 메시지 번호)"""
    by_id = {}
    for e in events:
        if e.get("type") == MSG_TYPE and e.get("id") and e["id"] not in by_id:
            by_id[e["id"]] = e
    rows, missing = [], []
    for m in messages:
        if not m.get("id"):
            continue
        e = by_id.get(m["id"])
        if e is None:
            missing.append(m["i"])
            continue
        rx = e["rx"] if e.get("rx") is not None else e.get("logcat_ms")
        row = {"i": m["i"], "id": m["id"], "rx_dev_ms": rx, "ts_srv_ms": e.get("ts"), "srv_to_car_ms": e.get("latency_ms")}
        if dev_offset is not None and rx is not None:
            row["e2e_ms"] = (rx - dev_offset["offset_ms"]) - m["send_ms"]
        rows.append(row)
    return rows, missing


def clocksync_cross(rows, messages, app_offset_ms, pc_offset_ms):
    """교차 확인: 차 수신을 앱 ClockSync 로, PC 송신을 PC→서버 오프셋으로 둘 다 서버 시계에 옮겨 뺀다.
    e2e_cs = (rx + app_offset) − (send + pc_offset), pc_to_publish = ts(서버 발행) − (send + pc_offset). 행에 덧붙인다"""
    if pc_offset_ms is None:
        return rows
    send = {m["i"]: m["send_ms"] for m in messages}
    for r in rows:
        s = send.get(r["i"])
        if s is None:
            continue
        if app_offset_ms is not None and r.get("rx_dev_ms") is not None:
            r["e2e_cs_ms"] = (r["rx_dev_ms"] + app_offset_ms) - (s + pc_offset_ms)
        if r.get("ts_srv_ms") is not None:
            r["pc_to_publish_ms"] = r["ts_srv_ms"] - (s + pc_offset_ms)
    return rows


def origin(url: str | None) -> str:
    """scheme://host[:port] — 경로·쿼리·사용자 정보 없이"""
    if not url:
        return "-"
    u = urllib.parse.urlsplit(url)
    if not u.scheme or not u.hostname:
        return "-"
    return f"{u.scheme}://{u.hostname}" + (f":{u.port}" if u.port else "")


LOOPBACK = {"127.0.0.1", "localhost", "10.0.2.2"}   # 에뮬레이터의 10.0.2.2 = 호스트의 127.0.0.1


def same_server(car_base: str | None, server: str) -> bool:
    """차가 보는 서버(net_rtt base)와 --server 가 같은 서버인가 — 에뮬레이터 10.0.2.2 ↔ 호스트 127.0.0.1 은 같다고 본다"""
    if not car_base or car_base == "-":
        return False
    a, b = urllib.parse.urlsplit(car_base), urllib.parse.urlsplit(server)
    if a.hostname in LOOPBACK and b.hostname in LOOPBACK:
        return (a.port or 80) == (b.port or 80)
    return a.hostname == b.hostname


def default_label(server: str) -> str:
    return "local" if urllib.parse.urlsplit(server).hostname in LOOPBACK else "cloud"


def mask_id(s: str | None) -> str:
    """앱 TextMasking.maskVehicleId 와 같은 모양(앞 4 + … + 뒤 2, 6자 이하 전부 가림) — 차 status 의 group= 과 비교용"""
    if not s:
        return "?"
    return "*" * len(s) if len(s) <= 6 else s[:4] + "…" + s[-2:]


def fmt(v, digits: int = 1) -> str:
    return "-" if v is None else f"{v:.{digits}f}"


def metric(key, label, span, summary, first=None, note=""):
    return {"key": key, "label": label, "span": span, "summary": summary, "first": first, "note": note}


def metric_rows(r: dict) -> list:
    """결과 JSON → 표 행(지표 키는 compare 가 맞춘다). summary = {n,p50,p95,min,max} 또는 None"""
    rows = []
    pc = r.get("pc_http") or {}
    rows.append(metric("pc_http", "PC → 서버 HTTP 왕복", "PC ↔ 서버, keep-alive GET /v1/time", pc.get("summary"),
                       first=pc.get("first_ms"), note=f"첫 표본 {pc.get('first_conn', '?')} 연결"))
    for car in CARS:
        c = (r.get("cars") or {}).get(car) or {}
        h = c.get("http") or {}
        rows.append(metric(f"car{car}_http", f"차 {car} → 서버 HTTP 왕복", "앱 OkHttp(같은 연결 풀) ↔ 서버, GET /v1/time", h.get("summary"),
                           first=h.get("first_ms"), note=f"첫 표본 {(h.get('extra') or {}).get('firstConn', '?')} 연결"))
    for car in CARS:
        q = ((r.get("cars") or {}).get(car) or {}).get("mqtt") or {}
        rows.append(metric(f"car{car}_mqtt", f"차 {car} MQTT PUBLISH → PUBACK", "앱 HiveMQ ↔ 브로커(EMQX) — 서버 앱 미경유", q.get("summary"),
                           first=q.get("first_ms"), note=f"transport={(q.get('extra') or {}).get('transport', '?')}"))
    for car in CARS:
        d = ((r.get("cars") or {}).get(car) or {}).get("dump") or {}
        err = d.get("clock_rtt_ms")
        rows.append(metric(f"car{car}_event", f"서버 발행 → 차 {car} EVENT 수신", "한 방향(브로커 경유), 앱 ClockSync 보정", d.get("event"),
                           note=f"±{fmt(err / 2 if err is not None else None)} ms, reset 이후 누적"))
        rows.append(metric(f"car{car}_state", f"서버 발행 → 차 {car} STATE 수신", "한 방향, 그룹 스냅샷(retained)", d.get("state"),
                           note=f"clamped={d.get('clamped', 0)} unsynced={d.get('unsynced', 0)}"))
    for car in CARS:
        e = ((r.get("cars") or {}).get(car) or {}).get("e2e") or {}
        rows.append(metric(f"car{car}_e2e", f"PC 송신 → 차 {car} 수신(메시지)", "REST 업로드 + 서버 처리·커밋 + MQTT 팬아웃 + 수신", e.get("summary"),
                           note=f"{e.get('method', '-')} ±{fmt(e.get('err_ms'))} ms, 못 받음 {len(e.get('missing', []))}"))
    p = r.get("pc_to_publish") or {}
    rows.append(metric("pc_to_publish", "PC 송신 → 서버 발행(봉투 ts)", "REST 업로드 + 서버 처리·커밋까지", p.get("summary"), note=f"±{fmt(p.get('err_ms'))} ms(PC→서버 오프셋)"))
    rows.append(metric("post_rtt", "메시지 POST 응답(PC)", "PC ↔ 서버 왕복 + 처리", (r.get("post") or {}).get("summary")))
    return rows


def markdown_report(r: dict) -> str:
    p = r.get("params", {})
    lines = [f"# 지연 측정 — {r.get('label')} ({r.get('started')})", ""]
    lines.append(f"서버 {r.get('server')} · 백분위 = 최근접 순위법(⌈p·n/100⌉ 번째, 보간 없음) · 단위 ms")
    lines.append(f"왕복 프로브 n={p.get('rtt_n')}(간격 {p.get('gap_ms')} ms, **첫 표본은 따로** — n·P50·P95 는 두 번째부터) · 메시지 {p.get('messages')}개(간격 {p.get('msg_gap_s')} s)")
    for car in CARS:
        c = (r.get("cars") or {}).get(car) or {}
        q = c.get("mqtt") or {}
        lines.append(f"- 차 {car} `{c.get('serial')}`: REST base {(c.get('http') or {}).get('base', '-')} · MQTT {q.get('base', '-')} "
                     f"({(q.get('extra') or {}).get('transport', '-')})")
    lines += ["", "| 지표 | 구간 | n | 첫 표본 | P50 | P95 | min | max | 비고 |", "|---|---|---|---|---|---|---|---|---|"]
    for m in metric_rows(r):
        s = m["summary"] or {}
        lines.append(f"| {m['label']} | {m['span']} | {s.get('n', 0)} | {fmt(m['first'])} | {fmt(s.get('p50'))} | {fmt(s.get('p95'))} | "
                     f"{fmt(s.get('min'))} | {fmt(s.get('max'))} | {m['note']} |")
    lines += ["", "## 시계 보정", ""]
    pc = r.get("pc_offset") or {}
    lines.append(f"- PC → 서버 오프셋(서버 − PC) {fmt(pc.get('offset_ms'))} ms ±{fmt(pc.get('err_ms'))} (GET /v1/time 최소 왕복 {fmt(pc.get('rtt_ms'))} ms, 표본 {pc.get('samples', 0)})")
    for car in CARS:
        c = (r.get("cars") or {}).get(car) or {}
        k = c.get("clock") or {}
        d = c.get("dump") or {}
        lines.append(f"- 차 {car} 기기 시계(기기 − PC) {fmt(k.get('offset_ms'))} ms ±{fmt(k.get('err_ms'))} — {k.get('method', '측정 실패')}, "
                     f"앞뒤 표류 {fmt(k.get('drift_ms'))} ms / 앱 ClockSync(서버 − 기기) {fmt(d.get('clock_offset_ms'))} ms ±{fmt((d.get('clock_rtt_ms') or 0) / 2 if d.get('clock_rtt_ms') is not None else None)}")
        cs = (c.get("e2e") or {}).get("clocksync")
        if cs:
            lines.append(f"  - 교차 확인(ClockSync 경로) PC 송신 → 차 {car} 수신 P50 {fmt(cs.get('p50'))} / P95 {fmt(cs.get('p95'))} ms (n={cs.get('n')}, ±{fmt((c.get('e2e') or {}).get('cs_err_ms'))})")
    lines += ["", "PC 송신 → 차 수신 = (차 rx − 기기 시계 오프셋) − PC 송신. 기기 오프셋 오차 ±(adb 최소 왕복/2 + 표류/2), 수신 시각은 HiveMQ 수신 콜백(rx=)."]
    if r.get("messages"):
        lines += ["", "## 메시지별 (ms)", "", "| # | id | POST | 차 A e2e | 차 B e2e | 서버→A | 서버→B |", "|---|---|---|---|---|---|---|"]
        rows = {car: {x["i"]: x for x in (((r.get("cars") or {}).get(car) or {}).get("e2e") or {}).get("rows", [])} for car in CARS}
        for m in r["messages"]:
            a, b = rows["A"].get(m["i"], {}), rows["B"].get(m["i"], {})
            lines.append(f"| {m['i']} | {m.get('id') or '-'} | {fmt(m.get('post_ms'))} | {fmt(a.get('e2e_ms'))} | {fmt(b.get('e2e_ms'))} | "
                         f"{fmt(a.get('srv_to_car_ms'), 0)} | {fmt(b.get('srv_to_car_ms'), 0)} |")
    if r.get("warnings"):
        lines += ["", "## 주의", ""] + [f"- {w}" for w in r["warnings"]]
    return "\n".join(lines) + "\n"


def compare_table(a: dict, b: dict) -> str:
    """두 결과(JSON) → 지표별 P50·P95 와 차이(b − a) 표"""
    la, lb = a.get("label", "a"), b.get("label", "b")
    rb = {m["key"]: m for m in metric_rows(b)}
    lines = [f"# 지연 비교 — {la} vs {lb}", "",
             f"{la}: {a.get('server')} ({a.get('started')}) / {lb}: {b.get('server')} ({b.get('started')}) · 단위 ms · 백분위 = 최근접 순위법", "",
             f"| 지표 | {la} P50 | {la} P95 | {lb} P50 | {lb} P95 | Δ P50 | Δ P95 |", "|---|---|---|---|---|---|---|"]
    for m in metric_rows(a):
        sa = m["summary"] or {}
        sb = (rb.get(m["key"]) or {}).get("summary") or {}

        def delta(k):
            return None if sa.get(k) is None or sb.get(k) is None else sb[k] - sa[k]
        d50, d95 = delta("p50"), delta("p95")
        lines.append(f"| {m['label']} | {fmt(sa.get('p50'))} | {fmt(sa.get('p95'))} | {fmt(sb.get('p50'))} | {fmt(sb.get('p95'))} | "
                     f"{'-' if d50 is None else f'{d50:+.1f}'} | {'-' if d95 is None else f'{d95:+.1f}'} |")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------- 호스트 시계·HTTP·기기 시계
class HostClock:
    """호스트 시각(ns) — 시작 때 벽시계에 한 번 맞춘 perf_counter. 차이 계산이 벽시계 해상도·조정에 흔들리지 않는다
    (기준 오차는 송신 시각과 기기 오프셋 양쪽에 똑같이 들어가 'PC 송신 → 차 수신' 에서 지워진다)"""

    def __init__(self):
        self.wall0 = time.time_ns()
        self.perf0 = time.perf_counter_ns()

    def now_ns(self) -> int:
        return self.wall0 + (time.perf_counter_ns() - self.perf0)

    def now_ms(self) -> float:
        return self.now_ns() / 1e6


CLOCK = HostClock()


class KeepAliveHttp:
    """한 연결(keep-alive)로 요청을 이어 보낸다 — 연결 수립(TCP·TLS)은 새로 붙을 때만. 끊겨 있으면 한 번 다시 붙어 보낸다(new=True)"""

    def __init__(self, server: str, timeout: float = 15):
        u = urllib.parse.urlsplit(server)
        self.https = u.scheme == "https"
        self.host, self.port = u.hostname, u.port or (443 if u.scheme == "https" else 80)
        self.timeout = timeout
        self.conn = None

    def _open(self):
        if self.https:
            self.conn = http.client.HTTPSConnection(self.host, self.port, timeout=self.timeout, context=ssl.create_default_context())
        else:
            self.conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)

    def close(self):
        if self.conn is not None:
            try:
                self.conn.close()
            except Exception:
                pass
        self.conn = None

    def request(self, method: str, path: str, body: bytes | None = None, headers: dict | None = None):
        """→ (status, 본문 bytes, 요청 직전 호스트 ms, 응답 다 읽은 뒤 호스트 ms, 새 연결이었나)"""
        for attempt in (0, 1):
            if self.conn is None:
                self._open()
            new = self.conn.sock is None
            t0 = CLOCK.now_ms()
            try:
                self.conn.request(method, path, body=body, headers=headers or {})
                resp = self.conn.getresponse()
                data = resp.read()
            except (http.client.HTTPException, OSError):
                self.close()
                if attempt == 0 and not new:
                    continue   # 서버가 유휴 연결을 닫았다 — 새로 붙어 한 번 더
                raise
            t1 = CLOCK.now_ms()
            if resp.will_close:
                self.close()
            return resp.status, data, t0, t1, new
        raise OSError("unreachable")


def measure_server_time(http_: KeepAliveHttp, n: int, gap_ms: int):
    """GET /v1/time n 번 → (왕복 목록 ms(실패 None), 첫 시도가 새 연결이었나, 오프셋 표본 [(t0, 서버 ms, t1)])"""
    attempts, samples, first_new = [], [], None
    for i in range(n):
        new = True
        try:
            st, data, t0, t1, new = http_.request("GET", "/v1/time", headers={"Accept": "application/json"})
            if st == 200:
                attempts.append(t1 - t0)
                samples.append((t0, float(json.loads(data)["serverTimeMs"]), t1))
            else:
                attempts.append(None)
        except Exception:
            attempts.append(None)
        if i == 0:
            first_new = new
        if i < n - 1 and gap_ms > 0:
            time.sleep(gap_ms / 1000)
    return attempts, first_new, samples


class DeviceShell:
    """상주 `adb shell -T` 세션(가상 터미널 없음) — 명령마다 adb.exe 를 새로 띄우지 않아 왕복이 짧다(기기 시계 오프셋 오차 ↓).
    이 세션은 이 스크립트가 띄운 프로세스라 끝나면 직접 닫는다."""

    def __init__(self, serial: str):
        self.p = subprocess.Popen([ADB, "-s", serial, "shell", "-T"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.q = queue.Queue()
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for raw in iter(self.p.stdout.readline, b""):
            self.q.put(raw.decode("utf-8", errors="replace").strip())
        self.q.put(None)

    def ask(self, cmd: str, marker: str, timeout: float = 5.0) -> str:
        """명령을 보내고 `marker` 로 시작하는 줄의 나머지를 돌려준다. 남아 있던 줄은 먼저 버리고, 표지가 없는 줄(배너·프롬프트)은 건너뛴다 —
        답이 한 줄씩 밀리면 이미 큐에 있던 이전 답이 '왕복이 가장 짧은 표본'으로 뽑혀 오프셋이 틀어지기 때문"""
        while True:
            try:
                self.q.get_nowait()
            except queue.Empty:
                break
        self.p.stdin.write((cmd + "\n").encode("utf-8"))
        self.p.stdin.flush()
        deadline = time.monotonic() + timeout
        while True:
            line = self.q.get(timeout=max(0.01, deadline - time.monotonic()))
            if line is None:
                raise EOFError("adb shell 종료")
            if line.startswith(marker):
                return line[len(marker):].strip()

    def close(self):
        try:
            self.p.stdin.write(b"exit\n")
            self.p.stdin.flush()
            self.p.wait(timeout=3)
        except Exception:
            self.p.kill()


def device_clock(serial: str, samples: int = 15):
    """기기 시계 − 호스트 시계(ms). 상주 세션이 안 되면 adb 를 매번 띄운다(왕복이 길어 오차가 크다). 둘 다 안 되면 None.
    상주 세션이 중간에 끊기면(시간 초과·EOF) 그때까지 받은 표본은 그대로 쓴다 — 답마다 표지로 짝을 맞추므로 받은 표본은 유효하다.
    유효 표본이 하나도 없을 때만 '매번 실행' 으로 넘어간다(한 번의 시간 초과로 ±1~3 ms 표본을 버리고 ±15~50 ms 로 떨어지지 않게)"""
    res, method, broken = [], "adb shell 상주 세션 date +%s%N", None
    s = None
    try:
        s = DeviceShell(serial)
        s.ask("echo DHready", "DHready", timeout=10)
        for i in range(samples):
            tag = f"DH{i} "   # 답마다 표지 — 줄이 밀려도 짝이 맞는다
            t0 = CLOCK.now_ms()
            out = s.ask(f'date +"{tag}%s%N"', tag)
            t1 = CLOCK.now_ms()
            res.append((t0, device_ms(out), t1))
    except Exception as e:
        broken = type(e).__name__
    finally:
        if s is not None:
            s.close()
    valid = sum(1 for r in res if r[1] is not None)
    if valid and broken:
        method += f" ({valid}/{samples} 표본 — {broken} 로 중단)"
    if not valid:
        method, res = "adb shell 매번 실행 date +%s%N", []
        for _ in range(min(samples, 7)):
            t0 = CLOCK.now_ms()
            out = adb(serial, "shell", "date +%s%N")
            t1 = CLOCK.now_ms()
            res.append((t0, device_ms(out), t1))
    off = clock_offset(res)
    if off:
        off["method"] = method
    return off


class EpochLogWatcher(LogWatcher):
    """demo.LogWatcher 와 같은 시작점(-T 기기 시각)으로 새 줄만 보되, 줄 머리에 기기 epoch 초(`logcat -v epoch`)를 붙여 받는다"""

    def new(self):
        out = sh(self.serial, f"logcat -d -v epoch -T '{self.since}' -s DongHaeng", timeout=60)
        return [line for line in out.splitlines() if "DongHaeng" in line]


def http_probe_warnings(car: str, http_probe) -> list:
    """`net_rtt --ez fresh true` 결과의 연결 확인. fresh 는 유휴 연결만 닫는다(OkHttp `evictAll`) — 앱의 다른 요청이 진행 중인
    연결(HTTP/2 다중화)은 남아 첫 호출이 거기 붙을 수 있다. 그러면 '첫 표본' 은 연결 수립을 빠뜨린 값이고,
    `newConnAfterFirst>0` 이면 P50·P95 표본 일부에 연결 수립이 섞였다"""
    if not http_probe:
        return []
    extra = http_probe.get("extra") or {}
    out = []
    first_conn = extra.get("firstConn")
    if first_conn != "new":
        out.append(f"차 {car} net_rtt 첫 표본이 새 연결이 아니다(firstConn={first_conn or '?'}) — 진행 중인 앱 요청의 연결에 붙어 "
                   f"'첫 표본' 에 연결 수립(DNS·TCP·TLS)이 빠졌다. 다시 재면 보통 new 가 된다")
    after = extra.get("newConnAfterFirst")
    if after is not None and after != "0":
        out.append(f"차 {car} net_rtt 두 번째 이후 표본 {after}개가 새 연결(keep-alive 끊김) — P50·P95 에 연결 수립이 섞였다")
    return out


def car_events(lines):
    """로그 줄 → 수신 이벤트(parse_event_line + logcat_ms)"""
    out = []
    for line in lines:
        e = parse_event_line(line)
        if e:
            e["logcat_ms"] = logcat_epoch_ms(line)
            out.append(e)
    return out


# ---------------------------------------------------------------- 실행
class Runner:
    def __init__(self, a):
        self.a = a
        self.label = a.label or default_label(a.server)
        self.serials = {"A": a.car_a, "B": a.car_b}
        self.started = dt.datetime.now()
        self.stamp = self.started.strftime("%Y%m%d_%H%M%S")
        self.base = os.path.join(a.out, f"latency_{self.label}_{self.stamp}")
        self.api = None
        self.group_id = None
        self.warnings = []
        self.voice_before = {}
        self.watchers = {}

    def say(self, msg: str):
        print(f"[{dt.datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)

    # ---- 0 ----
    def preflight(self) -> bool:
        rows = []
        devices = adb_devices()
        if devices is None:
            rows.append((f"adb 실행 파일 없음({ADB})", False))
        for car, s in self.serials.items():
            rows.append((f"{car} {s} 연결", device_connected(devices, s)))
            rows.append((f"{car} 앱 설치", "package:" in sh(s, f"pm path {PKG}")))
        try:
            http_ = KeepAliveHttp(self.a.server, timeout=8)
            st, _, _, _, _ = http_.request("GET", "/v1/time")
            http_.close()
            rows.append((f"서버 {origin(self.a.server)} GET /v1/time", st == 200))
        except Exception as e:
            rows.append((f"서버 {origin(self.a.server)} 닿지 않음({type(e).__name__})", False))
        st_cars = {}
        for car, s in self.serials.items():
            st_cars[car] = status(s)
            g = st_cars[car].get("group")
            rows.append((f"{car} 디버그 리시버 응답 + 그룹 있음(group={g or '-'})", bool(st_cars[car]) and g not in (None, "없음")))
        if not self.a.email:
            rows.append(("dev 로그인 — --email 또는 DH_DEMO_EMAIL 필요", False))
        else:
            try:
                self.api = DhApi(self.a.server, self.a.email)
                self.api.login()
                groups = self.api.active_groups()
                g = next((x for x in groups if x.get("groupId") == self.a.group), None) if self.a.group else \
                    next((x for x in groups if (x.get("memberCount") or 0) >= 2), groups[0] if groups else None)
                self.group_id = g.get("groupId") if g else None
                rows.append((f"활성 그룹 {mask_id(self.group_id)} 멤버 {g.get('memberCount') if g else 0}대", self.group_id is not None))
                if self.group_id:
                    for car in CARS:
                        cg = st_cars[car].get("group")
                        if cg and cg != mask_id(self.group_id):
                            rows.append((f"{car} 의 그룹({cg})이 메시지를 보낼 그룹({mask_id(self.group_id)})과 다름 — --group 으로 지정", None))
            except SystemExit as e:
                rows.append((f"dev 로그인·그룹 조회 실패: {str(e)[:120]}", False))
        for car, s in self.serials.items():
            sh(s, "logcat -G 8M")   # 1 Hz 로그가 링 버퍼를 밀어내지 않게(demo.py preflight 와 같다)
        print("\n== preflight ==")
        bad = 0
        for k, ok in rows:
            print(f"  {'WARN' if ok is None else 'OK  ' if ok else 'FAIL'} {k}")
            bad += 0 if ok or ok is None else 1
        return bad == 0

    # ---- ① ----
    def pc_http(self):
        self.say(f"① PC → 서버 HTTP 왕복 n={self.a.rtt_n} (keep-alive)")
        http_ = KeepAliveHttp(self.a.server)
        try:
            attempts, first_new, samples = measure_server_time(http_, self.a.rtt_n, self.a.gap_ms)
        finally:
            http_.close()
        first, summary, fail = first_and_rest(attempts)
        self.pc_samples = samples
        res = {"first_ms": first, "first_conn": "new" if first_new else "reused", "summary": summary, "fail": fail}
        self.say(f"   first={fmt(first)}ms p50={fmt((summary or {}).get('p50'))} p95={fmt((summary or {}).get('p95'))} fail={fail}")
        return res

    # ---- ② ----
    def wait_line(self, serial: str, action: str, pattern: str, timeout_s: float, **extras):
        w = EpochLogWatcher(serial)
        broadcast(serial, action, **extras)
        return w.wait(pattern, timeout_s, poll_s=1.0)

    def car_probes(self, car: str) -> dict:
        s = self.serials[car]
        out = {"serial": s}
        line = self.wait_line(s, "latency_reset", r"latency: reset ", 15)
        self.say(f"② 차 {car} latency_reset {'OK' if line else '응답 없음'}")
        per_probe_s = self.a.gap_ms / 1000 + 1.5
        timeout = 30 + self.a.rtt_n * per_probe_s
        line = self.wait_line(s, "net_rtt", r"net: http_rtt (n=|busy|실패)", timeout, n=self.a.rtt_n, gap_ms=self.a.gap_ms, fresh=True)
        out["http"] = parse_net_line(line or "")
        self.say(f"   차 {car} net_rtt → {(line or '응답 없음').split('DongHaeng', 1)[-1][:160]}")
        self.warnings.extend(http_probe_warnings(car, out["http"]))
        if out["http"] and not same_server(out["http"].get("base"), self.a.server):
            self.warnings.append(f"차 {car} 가 다른 서버를 본다: base={out['http'].get('base')} (--server {origin(self.a.server)}) — 숨은 메뉴 서버 주소·빌드 기본값 확인")
        if self.a.no_mqtt:
            out["mqtt"] = None
        else:
            line = self.wait_line(s, "net_mqtt_rtt", r"net: mqtt_rtt (n=|busy|실패)", timeout + self.a.rtt_n * 5, n=self.a.rtt_n, gap_ms=self.a.gap_ms)
            out["mqtt"] = parse_net_line(line or "")
            self.say(f"   차 {car} net_mqtt_rtt → {(line or '응답 없음').split('DongHaeng', 1)[-1][:160]}")
        return out

    # ---- ③ ----
    def set_voice(self, mute: bool):
        """③ 동안 두 차 음성을 끄고 되돌린다 — 원래 꺼져 있던 차(status voice=false)만 끈 채로 두고, 모르면 켠다(시연 차가 무음으로 남지 않게)"""
        if self.a.keep_voice:
            return
        for car, s in self.serials.items():
            if mute:
                self.voice_before[car] = status(s).get("voice")
                broadcast(s, "voice", on=False)
            elif self.voice_before.get(car) != "false":
                broadcast(s, "voice", on=True)

    def fresh_token(self) -> bool:
        """사용자 JWT 를 새로 받는다. preflight 의 login() 은 out/.token 캐시가 아직 통하면 그대로 쓰는데 사용자 토큰 수명은 1시간
        (서버 JwtIssuer.USER_TOKEN_TTL)이라 ①·② 프로브 동안 만료될 수 있다. 실패하면 경고만 남기고 지금 토큰을 쓴다"""
        try:
            self.api.login(force=True)
            return True
        except SystemExit as e:
            self.warnings.append(f"dev 로그인 갱신 실패: {str(e)[:120]}")
            return False

    def send_messages(self):
        """K 개를 keep-alive 로 보낸다 → (메시지 목록, PC→서버 오프셋 표본).
        보내기 전에 토큰을 새로 받고(fresh_token), 그래도 401 이면 메시지마다 한 번만 다시 로그인해 같은 메시지를 다시 보낸다
        (송신 시각은 다시 보낸 요청의 것 — 401 왕복은 지연에 들어가지 않는다)"""
        tag = secrets.token_hex(2)
        self.fresh_token()
        http_ = KeepAliveHttp(self.a.server)
        headers = lambda: {"Content-Type": "application/json; charset=utf-8", "Accept": "application/json",  # noqa: E731
                           "Authorization": f"Bearer {self.api.token}"}
        path = f"/v1/groups/{urllib.parse.quote(self.group_id)}/messages"
        msgs = []
        try:
            # 연결을 미리 붙이고(첫 메시지가 TCP·TLS 수립을 떠안지 않게) PC→서버 오프셋 표본도 더 모은다
            _, _, warm = measure_server_time(http_, 5, 100)
            self.pc_samples += warm
            k = self.a.messages
            for i in range(1, k + 1):
                body = json.dumps({"text": f"지연 측정 {i}/{k} #{tag}"}, ensure_ascii=False).encode("utf-8")
                relogged = False
                while True:
                    try:
                        st, data, t0, t1, new = http_.request("POST", path, body=body, headers=headers())
                        eid = json.loads(data).get("eventId") if st == 201 else None
                    except Exception as e:
                        st, eid, t0, t1, new = 0, None, CLOCK.now_ms(), None, True
                        self.warnings.append(f"메시지 {i} 전송 실패 {type(e).__name__}")
                    if st == 401 and not relogged:
                        relogged = True
                        if self.fresh_token():
                            self.warnings.append(f"메시지 {i} HTTP 401 → 다시 로그인해 한 번 더 보냄")
                            continue
                    break
                if st not in (0, 201):
                    self.warnings.append(f"메시지 {i} HTTP {st}")
                msgs.append({"i": i, "status": st, "id": eid[-6:] if eid else None, "send_ms": t0,
                             "post_ms": (t1 - t0) if t1 is not None and st == 201 else None, "new_conn": new})
                if i < k:
                    time.sleep(self.a.msg_gap_s)
        finally:
            http_.close()
        ok = sum(1 for m in msgs if m["id"])
        self.say(f"③ 메시지 {ok}/{len(msgs)} 보냄(201) — 수신 대기 {self.a.settle_s}s")
        return msgs

    # ---- 전체 ----
    def run(self) -> int:
        os.makedirs(self.a.out, exist_ok=True)
        result = {"label": self.label, "server": origin(self.a.server), "started": self.started.isoformat(timespec="seconds"),
                  "params": {"rtt_n": self.a.rtt_n, "gap_ms": self.a.gap_ms, "messages": self.a.messages, "msg_gap_s": self.a.msg_gap_s},
                  "cars": {}}
        for car, s in self.serials.items():
            self.watchers[car] = EpochLogWatcher(s)   # ⑤ 의 차 로그·③ 수신 줄은 여기부터
        result["pc_http"] = self.pc_http()
        for car in CARS:
            result["cars"][car] = self.car_probes(car)
        clock_before = {car: device_clock(s) for car, s in self.serials.items()}
        self.set_voice(mute=True)
        try:
            msgs = self.send_messages()
            time.sleep(self.a.settle_s)
        finally:
            self.set_voice(mute=False)
        clock_after = {car: device_clock(s) for car, s in self.serials.items()}
        pc_off = clock_offset(self.pc_samples)
        result["pc_offset"] = pc_off
        result["messages"] = [{k: v for k, v in m.items()} for m in msgs]
        result["post"] = {"summary": summarize([m["post_ms"] for m in msgs])}
        publish = {}
        for car, s in self.serials.items():
            c = result["cars"][car]
            line = self.wait_line(s, "latency_dump", r"latency: summary ", 15)
            c["dump"] = parse_summary_line(line or "") or {}
            self.say(f"④ 차 {car} {(line or 'latency_dump 응답 없음').split('DongHaeng', 1)[-1].strip()[:200]}")
            lines = self.watchers[car].new()
            with open(f"{self.base}_car{car}.log", "w", encoding="utf-8") as f:
                f.write("\n".join(lines) + "\n")
            off = combine_offsets(clock_before[car], clock_after[car])
            c["clock"] = off
            rows, missing = match_messages(msgs, car_events(lines), off)
            clocksync_cross(rows, msgs, c["dump"].get("clock_offset_ms"), pc_off["offset_ms"] if pc_off else None)
            app_err = (c["dump"].get("clock_rtt_ms") or 0) / 2
            c["e2e"] = {
                "method": "adb 기기 시계" if off else "기기 시계 측정 실패",
                "err_ms": (off["err_ms"] + 1) if off else None,   # + rx ms 절사 1 ms
                "summary": summarize([x.get("e2e_ms") for x in rows]),
                "clocksync": summarize([x.get("e2e_cs_ms") for x in rows]),
                "cs_err_ms": app_err + (pc_off["err_ms"] if pc_off else 0) + 1,
                "srv_to_car": summarize([x.get("srv_to_car_ms") for x in rows]),
                "missing": missing,
                "rows": rows,
            }
            if missing:
                self.warnings.append(f"차 {car} 가 못 받은 메시지 {len(missing)}개: {missing[:10]}")
            for x in rows:
                if x.get("pc_to_publish_ms") is not None:
                    publish.setdefault(x["i"], x["pc_to_publish_ms"])
        result["pc_to_publish"] = {"summary": summarize(list(publish.values())), "err_ms": pc_off["err_ms"] if pc_off else None}
        result["warnings"] = self.warnings
        with open(f"{self.base}.json", "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        md = markdown_report(result)
        with open(f"{self.base}.md", "w", encoding="utf-8") as f:
            f.write(md)
        print("\n" + md)
        print(f"결과: {self.base}.json / .md / _carA.log / _carB.log")
        complete = all(result["cars"][c].get("http") and (result["cars"][c]["e2e"]["summary"] is not None) for c in CARS)
        return 0 if complete else 1


def cmd_run(a) -> int:
    if a.label and not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", a.label):
        print("--label 은 영숫자·_·- 32자까지")
        return 2
    r = Runner(a)
    ok = r.preflight()
    if a.dry_run:
        print(f"\ndry-run: label={r.label} → {r.base}.json — ① PC HTTP n={a.rtt_n} ② 차마다 latency_reset·net_rtt"
              f"{'' if a.no_mqtt else '·net_mqtt_rtt'} ③ 메시지 {a.messages}개(간격 {a.msg_gap_s}s) ④ latency_dump ⑤ 결과")
        return 0 if ok else 2
    if not ok and not a.skip_preflight:
        print("preflight 실패 항목이 있다. --skip-preflight 로 무시할 수 있다.")
        return 2
    if r.api is None or r.group_id is None:
        print("메시지를 보낼 그룹이 없다(dev 로그인·활성 그룹) — 멈춘다")
        return 2
    return r.run()


def cmd_compare(a) -> int:
    with open(a.first, encoding="utf-8") as f:
        ra = json.load(f)
    with open(a.second, encoding="utf-8") as f:
        rb = json.load(f)
    md = compare_table(ra, rb)
    out = a.out_file or os.path.join(OUT_DIR, f"latency_compare_{ra.get('label', 'a')}_{rb.get('label', 'b')}_{dt.datetime.now().strftime('%Y%m%d_%H%M%S')}.md")
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write(md)
    print(md)
    print(f"비교표: {out}")
    return 0


def main():
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("run", help="두 차 + 서버 지연 측정 → out/latency_<label>_<시각>.json·.md")
    p.add_argument("--server", default=os.environ.get("DH_SERVER", "http://127.0.0.1:8080"), help="https://<VM-IP>.sslip.io 또는 http://127.0.0.1:8080")
    p.add_argument("--label", help="local|cloud 등(기본: 루프백 주소면 local, 아니면 cloud)")
    p.add_argument("--car-a", default="emulator-5556")
    p.add_argument("--car-b", default="emulator-5558")
    p.add_argument("--email", default=os.environ.get("DH_DEMO_EMAIL"), help="dev 로그인 이메일(클라우드는 DH_DEV_LOGIN_KEY 환경 변수도)")
    p.add_argument("--group", help="메시지를 보낼 그룹 ID(기본: 내 활성 그룹 중 멤버 2대 이상인 첫 그룹)")
    p.add_argument("--messages", type=int, default=20, help="그룹 메시지 수 K")
    p.add_argument("--msg-gap-s", type=float, default=1.5, help="메시지 간격(서버 빈도 제한 없음 — 수신·음성 처리가 겹치지 않게)")
    p.add_argument("--rtt-n", type=int, default=30, help="왕복 프로브 횟수(첫 표본 포함)")
    p.add_argument("--gap-ms", type=int, default=300, help="왕복 프로브 간격")
    p.add_argument("--settle-s", type=float, default=5.0, help="마지막 메시지 뒤 수신 대기")
    p.add_argument("--no-mqtt", action="store_true", help="net_mqtt_rtt 를 건너뛴다")
    p.add_argument("--keep-voice", action="store_true", help="③ 동안 음성을 끄지 않는다")
    p.add_argument("--out", default=OUT_DIR)
    p.add_argument("--dry-run", action="store_true", help="preflight + 계획만")
    p.add_argument("--skip-preflight", action="store_true")
    p.set_defaults(fn=cmd_run)

    p = sub.add_parser("compare", help="두 결과 JSON 의 지표별 P50·P95 비교표(두 번째 − 첫 번째)")
    p.add_argument("first", help="예: out/latency_local_….json")
    p.add_argument("second", help="예: out/latency_cloud_….json")
    p.add_argument("--out-file", help="비교표 .md 경로(기본 out/latency_compare_<a>_<b>_<시각>.md)")
    p.set_defaults(fn=cmd_compare)

    a = ap.parse_args()
    if a.cmd == "run" and (a.messages < 1 or a.rtt_n < 2):
        print("--messages ≥ 1, --rtt-n ≥ 2")
        sys.exit(2)
    sys.exit(a.fn(a))


if __name__ == "__main__":
    main()
