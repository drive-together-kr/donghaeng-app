#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GPX 경로를 에뮬레이터 GPS(adb emu geo fix)로 재생한다 — T30, docs/09 §1·§4.

차량 앱의 위치 체인에서 Android LocationManager 소스(docs/05 §3.2 2순위)가 이 위치를 받는다.
Pleos AVD(실 SDK, 내비 앱이 지연 상태)의 선두차 A 를 움직일 때 쓴다. fake flavor 는 FakeNaviAdapter 가 같은 GPX 를 스스로 재생하므로 필요 없다.

사용 예:
  python tools/demo/replay_gpx.py --serial emulator-5556                      # lead.gpx, 60 km/h
  python tools/demo/replay_gpx.py --serial emulator-5556 --gpx car-app/app/src/main/assets/gpx/follow.gpx --speed-kph 80 --loop
  python tools/demo/replay_gpx.py --serial emulator-5556 --start-m 1200       # 1.2 km 지점부터

adb 경로: 환경변수 ADB, 없으면 %LOCALAPPDATA%\\Android\\Sdk\\platform-tools\\adb.exe (그 외 OS 는 PATH 의 adb).
"""
import argparse
import math
import os
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_GPX = os.path.join(REPO, "car-app", "app", "src", "main", "assets", "gpx", "lead.gpx")


def adb_path() -> str:
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    if os.name == "nt":
        return os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    return "adb"


def parse_gpx(path: str):
    """trkpt / rtept / wpt 순으로 찾는다. 네임스페이스는 무시."""
    root = ET.parse(path).getroot()
    for tag in ("trkpt", "rtept", "wpt"):
        pts = [(float(e.get("lat")), float(e.get("lon"))) for e in root.iter() if e.tag.endswith(tag)]
        if len(pts) >= 2:
            return pts
    raise SystemExit(f"GPX 에 점이 2개 미만: {path}")


def dist_m(a, b) -> float:
    r = 6371000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dphi, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def resample(points, step_m: float):
    """경로를 따라 step_m 간격으로 다시 표본화 (car-app GpxRoute.resample 과 같은 규칙)."""
    out = [points[0]]
    carry = 0.0
    for a, b in zip(points, points[1:]):
        seg = dist_m(a, b)
        if seg <= 0:
            continue
        pos = step_m - carry
        while pos <= seg:
            t = pos / seg
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
            pos += step_m
        carry = seg - (pos - step_m)
    if out[-1] != points[-1]:
        out.append(points[-1])
    return out


def geo_fix(adb: str, serial: str, lat: float, lon: float) -> bool:
    try:
        r = subprocess.run([adb, "-s", serial, "emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}"], capture_output=True, text=True, timeout=10)
        return r.returncode == 0 and "KO" not in (r.stdout or "")
    except Exception:
        return False


def replay(serial: str, points, speed_kph: float, rate_hz: float = 1.0, start_m: float = 0.0, loop: bool = False,
           hold: bool = True, stop: "threading.Event|None" = None, quiet: bool = False, duration_s: float = 0.0):
    """점 목록을 speed_kph 로 재생한다. hold=True 면 끝점에 머물며 1 Hz 로 계속 보낸다(위치 신선도 유지). stop 이벤트로 중단."""
    adb = adb_path()
    step = speed_kph / 3.6 / rate_hz
    track = resample(points, step)
    start_idx = min(int(start_m / step), len(track) - 1)
    total_m = sum(dist_m(a, b) for a, b in zip(points, points[1:]))
    if not quiet:
        print(f"replay {serial}: {len(points)} pts → {len(track)} fixes, {total_m/1000:.2f} km @ {speed_kph:g} km/h ≈ {total_m/(speed_kph/3.6):.0f} s, start {start_idx}", flush=True)
    t0 = time.time()
    i = start_idx
    sent = fails = 0
    while True:
        if stop is not None and stop.is_set():
            break
        if duration_s and time.time() - t0 > duration_s:
            break
        lat, lon = track[i]
        ok = geo_fix(adb, serial, lat, lon)
        sent += 1
        fails += 0 if ok else 1
        if not quiet and (sent % 15 == 1 or not ok):
            done_m = min(i, len(track) - 1) * step
            print(f"  t+{time.time()-t0:5.0f}s  #{i}/{len(track)-1}  {done_m/1000:5.2f} km  {'ok' if ok else 'FAIL'}", flush=True)
        if i < len(track) - 1:
            i += 1
        elif loop:
            i = 0
        elif not hold:
            break
        # 전송에 걸린 시간을 빼고 자기
        next_at = t0 + (sent) / rate_hz
        time.sleep(max(0.0, next_at - time.time()))
    if not quiet:
        print(f"replay {serial}: sent={sent} failed={fails} elapsed={time.time()-t0:.0f}s", flush=True)
    return sent, fails


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", required=True, help="에뮬레이터 serial (예: emulator-5556)")
    ap.add_argument("--gpx", default=DEFAULT_GPX)
    ap.add_argument("--speed-kph", type=float, default=60.0)
    ap.add_argument("--rate", type=float, default=1.0, help="초당 전송 횟수")
    ap.add_argument("--start-m", type=float, default=0.0, help="이 거리(m)만큼 지난 지점부터 시작")
    ap.add_argument("--duration-s", type=float, default=0.0, help="이 시간이 지나면 종료(0 = 끝까지)")
    ap.add_argument("--loop", action="store_true")
    ap.add_argument("--no-hold", action="store_true", help="끝점에 도착하면 종료(기본은 끝점 유지)")
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args()
    pts = parse_gpx(a.gpx)
    try:
        replay(a.serial, pts, a.speed_kph, a.rate, a.start_m, a.loop, not a.no_hold, None, a.quiet, a.duration_s)
    except KeyboardInterrupt:
        print("stopped")


if __name__ == "__main__":
    main()
