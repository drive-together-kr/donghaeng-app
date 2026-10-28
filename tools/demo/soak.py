#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""동행 30분 연속 실행 점검 — T33 완료 기준("30분 연속 실행 무오류", docs/08).

두 차(에뮬레이터)에서 그룹 주행을 켜 둔 채 1분마다 확인한다(앱이 30초마다 남기는 `health …` 줄 + adb):

  1. 앱 pid 불변            — `pidof app.donghaeng.car` 가 시작 때와 같다(크래시·재시작 없음)
  2. MQTT 연결              — 마지막 health 줄 `mqtt=CONNECTED`
  3. 텔레메트리 증가         — health `tel=` 가 직전 분보다 크다(그룹 활성 중 1 Hz 발행)
  4. 그룹 STATE 신선도       — health `stateAgeMs` < 10 000(서버 2초 스냅샷이 계속 온다)
  5. logcat E / FATAL 0     — 시작 이후 앱 pid 의 E 레벨 줄과 crash 버퍼의 FATAL(우리 패키지) 0건

  python tools/demo/soak.py --car-a emulator-5556 --car-b emulator-5558 [--minutes 30] [--interval 60]

준비: 서버 기동, 두 차 페어링 + 같은 그룹(demo.py 장면 2~3 과 같다), 가능하면 주행 중(GPX 재생·VC) — 텔레메트리는 그룹 활성 중에만 나간다.
결과: tools/demo/out/<시각>/soak.md (분 단위 표 + 요약), 각 차의 health 줄·E 줄·logcat(DongHaeng) 덤프.
표준 라이브러리만 쓰고 adb 헬퍼는 demo.py 것을 그대로 쓴다. 이 스크립트는 기기 상태를 바꾸지 않는다(logcat 버퍼 크기만 8 MB 로).
좌표·닉네임·토큰은 앱이 이미 마스킹해 남긴 로그만 다룬다(CLAUDE.md 규칙 8).
"""
import argparse
import datetime as dt
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from demo import ADB, PKG, adb, sh  # noqa: E402  (demo.py 의 adb 헬퍼 재사용)

HEALTH_RX = re.compile(r"health (?P<body>mqtt=\S+.*)$")
KV_RX = re.compile(r"(\w+)=(\S+)")
STATE_AGE_LIMIT_MS = 10_000


def device_time(serial: str) -> str:
    """logcat -T 형식의 기기 시각"""
    return sh(serial, "date '+%m-%d %H:%M:%S.000'").strip()


def pid_of(serial: str) -> str:
    out = sh(serial, f"pidof {PKG}").strip()
    return out.split()[0] if out and not out.startswith("ERR") else ""


def parse_health(line: str) -> dict:
    m = HEALTH_RX.search(line)
    return dict(KV_RX.findall(m.group("body"))) if m else {}


def as_int(value, default=None):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


class Car:
    """차 한 대의 점검 상태"""

    def __init__(self, name: str, serial: str, ignore_tags: list[str]):
        self.name, self.serial = name, serial
        self.ignore_tags = ignore_tags
        sh(serial, "logcat -G 8M")
        self.since = device_time(serial)
        self.pid0 = pid_of(serial)
        self.last_tel = None
        self.health_lines: list[str] = []
        self.error_lines: list[str] = []
        self.rows: list[dict] = []

    def dongHaeng_since(self) -> list[str]:
        out = sh(self.serial, f"logcat -d -v time -T '{self.since}' -s DongHaeng", timeout=60)
        return [l for l in out.splitlines() if "DongHaeng" in l]

    def errors_since(self, pid: str) -> tuple[list[str], list[str]]:
        """(앱 pid 의 E 줄, crash 버퍼의 우리 패키지 FATAL 줄)"""
        errors: list[str] = []
        if pid:
            out = sh(self.serial, f"logcat -d -v time -T '{self.since}' --pid={pid} '*:E'", timeout=60)
            for l in out.splitlines():
                if not re.match(r"^\d\d-\d\d \S+ E/", l):
                    continue
                if any(f"E/{t}" in l for t in self.ignore_tags):
                    continue
                errors.append(l)
        crash = sh(self.serial, f"logcat -d -b crash -v time -T '{self.since}'", timeout=60)
        fatal = [l for l in crash.splitlines() if "FATAL" in l or (PKG in l and "AndroidRuntime" in l)]
        return errors, fatal

    def check(self, minute: int) -> dict:
        pid = pid_of(self.serial)
        lines = self.dongHaeng_since()
        health = [l for l in lines if " health mqtt=" in l]
        new_health = [l for l in health if l not in self.health_lines]
        self.health_lines.extend(new_health)
        last = parse_health(health[-1]) if health else {}
        tel = as_int(last.get("tel"))
        state_age = as_int(last.get("stateAgeMs"))
        errors, fatal = self.errors_since(pid or self.pid0)
        self.error_lines = errors + fatal

        checks = {
            "pid": bool(pid) and pid == self.pid0,
            "mqtt": last.get("mqtt") == "CONNECTED",
            "tel": tel is not None and (self.last_tel is None and tel > 0 or self.last_tel is not None and tel > self.last_tel),
            "state": state_age is not None and state_age < STATE_AGE_LIMIT_MS,
            "errors": not errors and not fatal,
            "health": bool(new_health),
        }
        row = {
            "minute": minute,
            "time": dt.datetime.now().strftime("%H:%M:%S"),
            "pid": pid or "-",
            "mqtt": last.get("mqtt", "-"),
            "rc": last.get("rc", "-"),
            "auth": last.get("auth", "-"),
            "tel": "-" if tel is None else (f"{tel} (+{tel - self.last_tel})" if self.last_tel is not None else str(tel)),
            "state": "-" if state_age is None else f"{state_age} ms",
            "group": last.get("group", "-"),
            "token": f"{last.get('token', '-')} exp {last.get('expMin', '-')}m",
            "errors": f"E {len(errors)} / FATAL {len(fatal)}",
            "checks": checks,
            "ok": all(checks.values()),
        }
        if tel is not None:
            self.last_tel = tel
        self.rows.append(row)
        return row


def mark(ok: bool) -> str:
    return "OK" if ok else "**FAIL**"


def write_report(out_dir: str, cars: list[Car], started: dt.datetime, minutes: int, interval: int) -> bool:
    os.makedirs(out_dir, exist_ok=True)
    all_ok = True
    lines = [
        f"# 30분 연속 실행 점검 (T33) {started:%Y-%m-%d %H:%M}",
        "",
        f"- 기간 {minutes}분, 간격 {interval}초, 차: " + ", ".join(f"{c.name}={c.serial}(pid {c.pid0 or '없음'})" for c in cars),
        f"- 기준: pid 불변 · mqtt=CONNECTED · 텔레메트리 증가 · 그룹 STATE 나이 < {STATE_AGE_LIMIT_MS // 1000} s · logcat E/FATAL 0 · health 줄 수신",
        "",
    ]
    for c in cars:
        ok = bool(c.rows) and all(r["ok"] for r in c.rows)
        all_ok &= ok
        fails = {k: sum(1 for r in c.rows if not r["checks"][k]) for k in ("pid", "mqtt", "tel", "state", "errors", "health")}
        lines += [
            f"## 차 {c.name} ({c.serial}) — {'통과' if ok else '실패'}",
            "",
            "실패 분 수: " + ", ".join(f"{k} {v}" for k, v in fails.items()),
            "",
            "| 분 | 시각 | pid | mqtt | 재연결 | 인증거부 | 텔레메트리 | STATE 나이 | 그룹 | 토큰 | E/FATAL | 판정 |",
            "|---|---|---|---|---|---|---|---|---|---|---|---|",
        ]
        for r in c.rows:
            ch = r["checks"]
            lines.append(
                f"| {r['minute']} | {r['time']} | {r['pid']} {'' if ch['pid'] else '✗'} | {r['mqtt']} {'' if ch['mqtt'] else '✗'} | {r['rc']} | {r['auth']} | "
                f"{r['tel']} {'' if ch['tel'] else '✗'} | {r['state']} {'' if ch['state'] else '✗'} | {r['group']} | {r['token']} | "
                f"{r['errors']} {'' if ch['errors'] else '✗'} | {mark(r['ok'])}{'' if ch['health'] else ' (health 줄 없음)'} |"
            )
        if c.error_lines:
            lines += ["", "E / FATAL 줄(최대 30):", "```"] + c.error_lines[:30] + ["```"]
        lines.append("")
        with open(os.path.join(out_dir, f"{c.name}_health.log"), "w", encoding="utf-8") as f:
            f.write("\n".join(c.health_lines) + "\n")
        with open(os.path.join(out_dir, f"{c.name}_errors.log"), "w", encoding="utf-8") as f:
            f.write("\n".join(c.error_lines) + "\n")
        with open(os.path.join(out_dir, f"{c.name}_donghaeng.log"), "w", encoding="utf-8") as f:
            f.write("\n".join(c.dongHaeng_since()) + "\n")
    lines.insert(2, f"**결과: {'무오류 통과' if all_ok else '실패 — 아래 표의 ✗ 확인'}**")
    lines.insert(3, "")
    with open(os.path.join(out_dir, "soak.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return all_ok


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--car-a", default="emulator-5556", help="차 A serial")
    ap.add_argument("--car-b", default="emulator-5558", help="차 B serial (빈 문자열이면 한 대만)")
    ap.add_argument("--minutes", type=int, default=30)
    ap.add_argument("--interval", type=int, default=60, help="점검 간격(초)")
    ap.add_argument("--ignore-tag", action="append", default=[], help="E 집계에서 뺄 logcat 태그(앱 밖 시스템 라이브러리 소음, 여러 번 지정 가능)")
    ap.add_argument("--out", default=os.path.join(HERE, "out"))
    a = ap.parse_args()

    serials = [("A", a.car_a)] + ([("B", a.car_b)] if a.car_b else [])
    started = dt.datetime.now()
    out_dir = os.path.join(a.out, started.strftime("%Y%m%d_%H%M%S") + "_soak")
    print(f"adb={ADB}")
    cars = []
    for name, serial in serials:
        if "device" not in adb(serial, "get-state"):
            print(f"차 {name} {serial}: adb 연결 없음")
            sys.exit(2)
        car = Car(name, serial, a.ignore_tag)
        if not car.pid0:
            print(f"차 {name} {serial}: 앱({PKG})이 실행 중이 아니다 — 먼저 그룹 주행 상태로 띄운다")
            sys.exit(2)
        print(f"차 {name} {serial}: pid {car.pid0}, 기준 시각 {car.since}")
        cars.append(car)

    rounds = max(1, a.minutes * 60 // a.interval)
    try:
        for i in range(1, rounds + 1):
            time.sleep(a.interval)
            for c in cars:
                r = c.check(i)
                print(f"[{r['time']}] {i:>2}/{rounds} 차 {c.name}: {mark(r['ok'])} pid={r['pid']} mqtt={r['mqtt']} tel={r['tel']} state={r['state']} {r['errors']}", flush=True)
    except KeyboardInterrupt:
        print("중단 — 지금까지 결과로 보고서를 쓴다")
    ok = write_report(out_dir, cars, started, a.minutes, a.interval)
    print(f"\n{'무오류 통과' if ok else '실패'} — {os.path.join(out_dir, 'soak.md')}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
