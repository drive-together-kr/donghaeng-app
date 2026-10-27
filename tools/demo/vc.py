#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Pleos 에뮬레이터 Virtual Console HTTP API 클라이언트 — T30, docs/09 §1.

먼저 포트를 포워딩한다:  adb -s emulator-5556 forward tcp:8089 tcp:8088
쓰기(PUT)는 브라우저 세션(POST /browser/session, 3초 타임아웃)이 살아 있을 때만 받으므로 1초마다 세션을 갱신하는 스레드를 돌린다.

사용 예:
  python tools/demo/vc.py --port 8089 state                 # 현재 차량 상태(JSON 일부)
  python tools/demo/vc.py --port 8089 gear Drive            # Park | Reverse | Neutral | Drive
  python tools/demo/vc.py --port 8089 speed 55 --brake 0    # km/h, 브레이크 %
  python tools/demo/vc.py --port 8089 soc 12                # EV 배터리 % → 15% 이하면 LOW_ENERGY (T26)
  python tools/demo/vc.py --port 8089 turn BlinkLeft        # Off | BlinkLeft | BlinkRight
  python tools/demo/vc.py --port 8089 hardbrake             # 55 km/h → 브레이크 80% + 0 km/h (HARD_BRAKE)
"""
import argparse
import json
import threading
import time
import urllib.parse
import urllib.request


class VirtualConsole:
    def __init__(self, port: int = 8089, client: str = "donghaeng-demo"):
        self.base = f"http://127.0.0.1:{port}"
        self.client = client
        self._stop = threading.Event()
        self._thread = None

    # ---- 세션 ----
    def start_session(self):
        if self._thread:
            return
        self._thread = threading.Thread(target=self._keepalive, daemon=True)
        self._thread.start()
        time.sleep(1.2)

    def close(self):
        self._stop.set()

    def _keepalive(self):
        while not self._stop.is_set():
            try:
                self._request("POST", f"/browser/session?client={urllib.parse.quote(self.client)}", timeout=5)
            except Exception:
                pass
            self._stop.wait(1.0)

    # ---- 기본 요청 ----
    def _request(self, method: str, path: str, timeout: float = 8):
        req = urllib.request.Request(self.base + path, method=method)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", errors="replace")

    def put(self, path: str, **params):
        # Virtual Console 은 "60.0" 같은 실수 문자열을 400 으로 거부한다 → 정수값은 정수로 보낸다
        clean = {k: (int(v) if isinstance(v, float) and v.is_integer() else v) for k, v in params.items()}
        q = urllib.parse.urlencode(clean)
        try:
            return self._request("PUT", f"{path}?{q}" if q else path)
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", errors="replace")[:200]
        except Exception as e:
            return "ERR", str(e)[:200]

    # ---- 차량 조작 (조회·시뮬레이션만, 실차 제어 API 아님) ----
    def state(self) -> dict:
        _, body = self._request("GET", "/vehicle/state")
        return json.loads(body)

    def catalog(self) -> dict:
        _, body = self._request("GET", "/vehicle/catalog")
        return json.loads(body)

    def gear(self, gear: str):
        return self.put("/vehicle/gear", gear=gear)

    def controls(self, speed_kph: float, brake_pct: float = 0):
        return self.put("/vehicle/controls", speed_kph=speed_kph, brake_pct=brake_pct)

    def turn_signal(self, mode: str):
        return self.put("/vehicle/turn-signal", mode=mode)

    def soc(self, percent: float):
        return self.put("/vehicle/ev-battery", field="soc", value=percent)

    def hard_brake(self, from_kph: float = 55, brake_pct: float = 80, settle_s: float = 2.5):
        """브레이크 ON + 1초 내 4 m/s² 이상 감속(EventDetector, docs/05 §3.3)을 만든다."""
        r1 = self.gear("Drive")
        r2 = self.controls(from_kph, 0)
        time.sleep(settle_s)
        r3 = self.controls(0, brake_pct)
        return r1, r2, r3


def pick(d: dict, *keys):
    out = {}
    for k in keys:
        cur = d
        for part in k.split("."):
            cur = cur.get(part) if isinstance(cur, dict) else None
        out[k] = cur
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8089)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("state")
    sub.add_parser("catalog")
    sub.add_parser("gear").add_argument("gear", choices=["Park", "Reverse", "Neutral", "Drive"])
    p = sub.add_parser("speed"); p.add_argument("kph", type=float); p.add_argument("--brake", type=float, default=0)
    sub.add_parser("brake").add_argument("pct", type=float)
    sub.add_parser("soc").add_argument("pct", type=float)
    sub.add_parser("turn").add_argument("mode", choices=["Off", "BlinkLeft", "BlinkRight"])
    p = sub.add_parser("hardbrake"); p.add_argument("--from-kph", type=float, default=55); p.add_argument("--brake", type=float, default=80)
    a = ap.parse_args()

    vc = VirtualConsole(a.port)
    if a.cmd in ("state", "catalog"):
        d = vc.state() if a.cmd == "state" else vc.catalog()
        print(json.dumps(d, ensure_ascii=False, indent=1)[:4000])
        return
    vc.start_session()
    try:
        if a.cmd == "gear":
            print(vc.gear(a.gear))
        elif a.cmd == "speed":
            print(vc.controls(a.kph, a.brake))
        elif a.cmd == "brake":
            st = vc.state()
            print(vc.controls(pick(st, "speed_kph").get("speed_kph") or 0, a.pct))
        elif a.cmd == "soc":
            print(vc.soc(a.pct))
        elif a.cmd == "turn":
            print(vc.turn_signal(a.mode))
        elif a.cmd == "hardbrake":
            print(vc.hard_brake(a.from_kph, a.brake))
        time.sleep(1.0)
    finally:
        vc.close()


if __name__ == "__main__":
    main()
