#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""동행 시연 타임라인 실행기 — T30, docs/09 §4 (장면 4~9 자동, 장면 2~3 은 화면·폰에서).

차 A = Pleos AVD(실 SDK, pleos flavor, Virtual Console 로 차량 상태 조작, GPS 는 이 스크립트가 lead.gpx 로 주입)
차 B = Pleos_B AVD(fake flavor, FakeNaviAdapter 가 follow.gpx 를 재생 — 합류 뒤 "선두 목적지까지 안내" 수락 시 출발)

  python tools/demo/demo.py --car-a emulator-5556 --car-b emulator-5558 --vc-port 8089 [--email dev@example.com]

순서
  0 preflight  : 기기·앱·서버·Virtual Console·차 B GPX 역할(follow)·플로팅 창 권한·차 A 위치 소스(android_first) 확인
  2~3 대기     : 차 A '같이 가기 시작' → 폰(demo-host)으로 QR 합류(또는 api.py join). 차 B 의 합류를 로그로 감지하면 합류 제안을 자동 수락(--no-auto-accept 면 화면에서)
  4 함께 주행  : T0 — 차 A GPS 재생 시작 + Virtual Console 기어 D·속도. 차 B 는 수락 순간부터 스스로 달린다
  5 저잔량     : T0+20s  VC soc=12 → 차 B 음성 "…배터리가 12% 남았어요"
  6 동승자 메시지 : T0+45s  --email 이 있으면 REST 로 전송, 없으면 폰에서 보내라고 안내
  8 경로 변경  : T0+60s  차 A ROUTE_CHANGED(덕평휴게소 경유) → 차 B 제안 → 8초 뒤 자동 수락 → 차 B 내비 경유지
  7 휴게소 도착: 차 B 가 덕평휴게소 좌표에 닿으면(약 T0+150s) WAYPOINT_ARRIVED → 차 A 이벤트 피드
  9 급제동     : 도착 직후 VC 55 km/h → 브레이크 80% → 차 B 음성 "…급제동했어요"
  수집         : 두 차의 logcat(DongHaeng) 과 장면별 확인 표 → tools/demo/out/<시각>/

기대 로그는 docs/09 §4 표와 같다. 좌표·ID 는 앱이 마스킹해 남긴다.
"""
import argparse
import datetime as dt
import os
import re
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import replay_gpx  # noqa: E402
from vc import VirtualConsole  # noqa: E402

PKG = "app.donghaeng.car"
RECEIVER = f"{PKG}/.debug.DemoControlReceiver"
ADB = replay_gpx.adb_path()
FOLLOW_GPX = os.path.join(replay_gpx.REPO, "car-app", "app", "src", "main", "assets", "gpx", "follow.gpx")


# ---------------------------------------------------------------- adb helpers
def adb(serial: str, *args, timeout: int = 30) -> str:
    try:
        r = subprocess.run([ADB, "-s", serial, *args], capture_output=True, timeout=timeout)
        return (r.stdout or b"").decode("utf-8", errors="replace").strip()
    except Exception as e:
        return f"ERR {type(e).__name__}"


def sh(serial: str, cmd: str, timeout: int = 30) -> str:
    return adb(serial, "shell", cmd, timeout=timeout)


def broadcast(serial: str, action: str, **extras) -> str:
    args = ["shell", "am", "broadcast", "-n", RECEIVER, "--es", "action", action]
    for k, v in extras.items():
        if isinstance(v, bool):
            args += ["--ez", k, "true" if v else "false"]
        else:
            args += ["--es", k, str(v)]
    out = adb(serial, *args)
    return out.splitlines()[0] if out else out


class LogWatcher:
    """logcat(DongHaeng) 에서 시작 시점 이후 새로 생긴 줄만 본다 — 기기 시계 기준 `logcat -T <time>`.
    (줄 수 기준 baseline 은 1 Hz 로그가 링 버퍼를 밀어내면 새 줄을 못 본다 — T30 실측)"""

    def __init__(self, serial: str):
        self.serial = serial
        self.since = sh(serial, "date '+%m-%d %H:%M:%S.000'").strip()

    def lines(self):
        return self.new()

    def new(self):
        out = sh(self.serial, f"logcat -d -v time -T '{self.since}' -s DongHaeng", timeout=60)
        return [l for l in out.splitlines() if "DongHaeng" in l]

    def find(self, pattern: str, since: int = 0):
        rx = re.compile(pattern)
        return [l for l in self.new()[since:] if rx.search(l)]

    def wait(self, pattern: str, timeout_s: float, poll_s: float = 2.0):
        t0 = time.time()
        while time.time() - t0 < timeout_s:
            hits = self.find(pattern)
            if hits:
                return hits[-1]
            time.sleep(poll_s)
        return None


def status(serial: str) -> dict:
    """리시버 status → 마지막 'demo: status' 줄을 dict 로"""
    broadcast(serial, "status")
    time.sleep(1.2)
    lines = [l for l in sh(serial, "logcat -d -s DongHaeng").splitlines() if "demo: status" in l]
    if not lines:
        return {}
    return dict(re.findall(r"(\w+)=([^\s]+)", lines[-1].split("demo: status", 1)[1]))


# ---------------------------------------------------------------- scenes
class Demo:
    def __init__(self, a):
        self.a = a
        self.A, self.B = a.car_a, a.car_b
        self.vc = VirtualConsole(a.vc_port)
        self.out = os.path.join(a.out, dt.datetime.now().strftime("%Y%m%d_%H%M%S"))
        self.results = []   # (scene, expected, observed)
        self.replay_stop = threading.Event()
        self.replay_thread = None
        self.t0 = None

    def say(self, msg):
        t = f"T0+{time.time()-self.t0:4.0f}s" if self.t0 else dt.datetime.now().strftime("%H:%M:%S")
        print(f"[{t}] {msg}", flush=True)

    def record(self, scene, expected, observed):
        ok = bool(observed)
        self.results.append((scene, expected, observed or "(없음)"))
        self.say(f"{'OK ' if ok else 'MISS'} 장면 {scene}: {expected} → {str(observed)[-110:] if observed else '미확인'}")

    # ---- 0 ----
    def preflight(self):
        rows = []
        devices = adb(self.A, "devices") if False else subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
        for name, s in (("A", self.A), ("B", self.B)):
            sh(s, "logcat -G 8M")   # 1 Hz 로그가 링 버퍼를 밀어내지 않게 (증거 수집용)
            rows.append((f"{name} {s} 연결", "device" in [l.split("\t")[-1] for l in devices.splitlines() if l.startswith(s)]))
            rows.append((f"{name} 부팅 완료", sh(s, "getprop sys.boot_completed") == "1"))
            rows.append((f"{name} 앱 설치", "package:" in sh(s, f"pm path {PKG}")))
            rows.append((f"{name} 서버 도달(10.0.2.2)", "1 received" in sh(s, "ping -c 1 -W 2 10.0.2.2") or "1 packets received" in sh(s, "ping -c 1 -W 2 10.0.2.2")))
            sh(s, f"am start -n {PKG}/.MainActivity")
        time.sleep(3)
        try:
            import urllib.request
            with urllib.request.urlopen(f"{self.a.server}/actuator/health", timeout=5) as r:
                rows.append(("서버 health", b"UP" in r.read()))
        except Exception as e:
            rows.append(("서버 health", False))
        adb(self.A, "forward", f"tcp:{self.a.vc_port}", "tcp:8088")
        try:
            st = self.vc.state()
            rows.append((f"Virtual Console :{self.a.vc_port}", isinstance(st, dict)))
        except Exception:
            rows.append((f"Virtual Console :{self.a.vc_port} (adb forward tcp:{self.a.vc_port} tcp:8088)", False))
        sb = status(self.B)
        rows.append(("B GPX 역할 = follow", sb.get("gpx") == "follow"))
        # 서버는 같은 차의 LOW_ENERGY 를 120 s 동안 중복 억제한다(docs/06 event) → 직전 2분 내 발행이 있으면 장면 5 음성이 생략된다
        rows.append(("A 최근 2분 내 LOW_ENERGY 발행 없음(서버 중복 억제 120 s)", self.seconds_since_last_event(self.A, "LOW_ENERGY") > 125))
        sa = status(self.A)
        rows.append(("A 리시버 응답", bool(sa)))
        # 주행 중 표면(2026-10-28): 플로팅 창 권한 — 없으면 알림 카드 + 음성 모드로 동작하므로 경고만
        for name, s in (("A", self.A), ("B", self.B)):
            mode = sh(s, f"appops get --user 10 {PKG} SYSTEM_ALERT_WINDOW")
            rows.append((f"{name} 플로팅 창 허용(없으면 카드+음성 모드)", "allow" in mode))
        # 차 A 위치 소스: 순정 내비 위치가 고정된 에뮬레이터에서는 android_first 여야 GPX 재생이 반영된다(docs/09 §1.2)
        order_lines = [l for l in sh(self.A, "logcat -d -s DongHaeng").splitlines() if "location: 소스 순서" in l]
        rows.append(("A 위치 소스 android_first", bool(order_lines) and order_lines[-1].rstrip().endswith("android_first")))
        # 앱을 막 띄우면 Android GPS 첫 Fix 가 10 s 를 넘겨 체인이 순정 내비(고정 위치, 차 B 와 약 28 km)로 내려갔다가 60 s 재탐색 때 올라온다.
        # 그 사이 패널 간격이 "앞 28 km" 처럼 튄다(2026-10-28 실측, docs/10 R17). 텔레메트리는 그룹이 있을 때만 나가므로 그룹이 없으면 장면 4 직전에 확인한다
        if sa.get("group") not in (None, "없음"):
            src = self.a_location_source()
            rows.append((f"A 현재 프로세스 텔레메트리 src=ANDROID (지금 {src or '아직 없음 — 1분 뒤 다시'})", src == "ANDROID"))
        else:
            print("  (A 그룹 없음 — 위치 소스 src=ANDROID 는 장면 4 직전에 확인)")
        print("\n== preflight ==")
        bad = 0
        for k, ok in rows:
            print(f"  {'OK  ' if ok else 'FAIL'} {k}")
            bad += 0 if ok else 1
        if bad and not self.a.skip_preflight:
            print("preflight 실패 항목이 있다. --skip-preflight 로 무시할 수 있다.")
            if not self.a.dry_run:
                sys.exit(2)
        return sa, sb

    def a_location_source(self):
        """차 A **현재 프로세스**의 마지막 텔레메트리 src(ANDROID/NAVI/…) — 이전 프로세스 로그는 보지 않는다. 없으면 None"""
        pid = sh(self.A, f"pidof {PKG}").strip().split(" ")[0]
        if not pid:
            return None
        tele = [l for l in sh(self.A, f"logcat -d --pid={pid} -s DongHaeng").splitlines() if "telemetry #" in l and "src=" in l]
        m = re.search(r"src=(\w+)", tele[-1]) if tele else None
        return m.group(1) if m else None

    def ensure_a_android_source(self, timeout_s: float = 75.0):
        """장면 4 전: 차 A 위치가 Android(GPX 재생)인지 — 아니면 체인이 올라올 때까지(60 s 재탐색) 기다린다"""
        end = time.time() + timeout_s
        src = self.a_location_source()
        while src != "ANDROID" and time.time() < end:
            self.say(f"차 A 위치 소스 {src or '없음'} — ANDROID 로 올라오길 기다린다(docs/10 R17)")
            time.sleep(5)
            src = self.a_location_source()
        self.say(f"차 A 위치 소스 = {src}" + ("" if src == "ANDROID" else " — 경고: 간격이 순정 내비 고정 위치 기준으로 보일 수 있다"))

    def seconds_since_last_event(self, serial: str, event: str) -> float:
        """기기 로그에서 마지막 `vehicle event <event>` 발행 뒤 지난 초(없으면 큰 값). 기기 시계 기준."""
        out = sh(serial, f"logcat -d -v epoch -s DongHaeng | grep 'vehicle event {event}' | tail -1")
        try:
            last = float(out.strip().split()[0])
            now = float(sh(serial, "date +%s"))
            return now - last
        except Exception:
            return 1e9

    # ---- 2~3 ----
    def server_says_joined(self) -> bool | None:
        """--email 이 있으면 서버 기준으로 'A 의 활성 그룹에 내 차 2대 이상' 인지 본다. 없으면 None(판단 불가)"""
        if not self.a.email:
            return None
        try:
            from api import DhApi
            api = DhApi(self.a.server, self.a.email)
            api.login()
            groups = api.active_groups()
            return any(len(g.get("myVehicleIds", [])) >= 2 for g in groups)
        except SystemExit as e:
            self.say(f"서버 조회 실패({e}) → 차량 로그로 판단")
            return None

    def wait_for_group(self, sa, sb):
        local_ok = sb.get("group") not in (None, "없음") and sa.get("leader") == "true" and sb.get("group") == sa.get("group")
        server_ok = self.server_says_joined()
        # 차 B 의 로컬 세션은 폰/REST 로 내보내진 뒤에도 남을 수 있어(MEMBER_LEFT 는 세션을 지우지 않음) 서버 판단을 우선한다
        already = (server_ok if server_ok is not None else local_ok) and not self.a.force_wait_join
        if already:
            self.say(f"이미 그룹 상태: A leader, B 멤버 (dest={sb.get('dest')}, 서버확인={server_ok})")
            if sb.get("pending") not in (None, "없음") and self.a.auto_accept:
                self.say("B 대기 중 제안 자동 수락 → " + broadcast(self.B, "accept"))
            return
        print("\n== 장면 2~3: 차 A 에서 '같이 가기 시작' → 목적지 선택 → 폰(demo-host)으로 QR 합류 (또는: python tools/demo/api.py --email … join --vehicle DemoCarB)")
        self.say(f"차 B 의 합류를 최대 {self.a.wait_join}초 기다린다…")
        wb = LogWatcher(self.B)
        hit = wb.wait(r"GROUP_JOINED|routesync: join proposal", self.a.wait_join, poll_s=2.0)
        if not hit:
            print("합류를 감지하지 못했다. 종료."); sys.exit(3)
        self.say("B 합류 감지: " + hit[-100:])
        broadcast(self.B, "open", screen="drive")   # 제안 카드·지도가 보이게
        if self.a.auto_accept:
            time.sleep(6)   # 카드가 화면에 잠깐 보이도록(영상용) 뒤 수락 — 30초 자동 거절 안
            self.say("B 합류 제안(선두 목적지 안내) 자동 수락 → " + broadcast(self.B, "accept"))
        else:
            self.say("차 B 화면에서 '따라가기' 를 30초 안에 누르세요")

    # ---- 4 ----
    def start_driving(self):
        if not self.a.dry_run:
            self.ensure_a_android_source()
        self.vc.start_session()
        broadcast(self.A, "open", screen="drive"); broadcast(self.B, "open", screen="drive")
        self.t0 = time.time()
        pts = replay_gpx.parse_gpx(self.a.gpx)
        self.replay_thread = threading.Thread(target=replay_gpx.replay, kwargs=dict(serial=self.A, points=pts, speed_kph=self.a.speed_kph, stop=self.replay_stop, quiet=True), daemon=True)
        self.replay_thread.start()
        self.say(f"장면 4: 차 A GPS 재생 시작({os.path.basename(self.a.gpx)}, {self.a.speed_kph:g} km/h) + VC 기어 D·속도 {self.a.speed_kph:g} → {self.vc.gear('Drive')[0]}/{self.vc.controls(self.a.speed_kph, 0)[0]}")

    def until(self, t_s: float):
        while time.time() - self.t0 < t_s:
            time.sleep(0.5)

    # ---- 5 ----
    def scene_low_energy(self, wb: LogWatcher, wa: LogWatcher):
        self.until(20)
        r = self.vc.soc(12)
        self.say(f"장면 5: VC soc=12 → {r[0]}")
        obs = wa.wait(r"vehicle event.*LOW_ENERGY|LOW_ENERGY.*publish", 25)
        self.record("5 (A 발행)", "A: vehicle event LOW_ENERGY 발행", obs)
        obs = wb.wait(r"voice: speak LOW_ENERGY|LOW_ENERGY", 25)
        self.record("5 (B 음성)", "B: voice: speak LOW_ENERGY", obs)

    # ---- 6 ----
    def scene_message(self, wb: LogWatcher):
        self.until(45)
        if self.a.email:
            try:
                from api import DhApi
                api = DhApi(self.a.server, self.a.email)
                api.login()
                groups = api.active_groups()
                gid = groups[0]["groupId"] if groups else None
                st, res = api.send_message(gid, self.a.message) if gid else ("no-group", None)
                self.say(f"장면 6: 동승자 메시지 REST → {st}")
            except SystemExit as e:
                self.say(f"장면 6: 메시지 전송 실패 {e}")
        else:
            self.say(f"장면 6: 폰(demo-host) 동행 메뉴에서 메시지를 보내세요: \"{self.a.message}\" (--email 을 주면 자동)")
        obs = wb.wait(r"PASSENGER_MESSAGE", 40)
        self.record("6", "B: voice: speak PASSENGER_MESSAGE", obs)

    # ---- 8 ----
    def scene_route_change(self, wb: LogWatcher, wa: LogWatcher):
        self.until(60)
        self.say("장면 8: 차 A ROUTE_CHANGED(덕평휴게소 경유) → " + broadcast(self.A, "route_test"))
        obs = wa.wait(r"routesync: ROUTE_CHANGED published=true", 10)
        self.record("8 (A 발행)", "A: ROUTE_CHANGED published=true", obs)
        obs = wb.wait(r"routesync: proposal ", 25)
        self.record("8 (B 제안)", "B: routesync: proposal … 따라갈까요", obs)
        if self.a.auto_accept:
            time.sleep(8)
            self.say("장면 8: 차 B 제안 자동 수락 → " + broadcast(self.B, "accept"))
        obs = wb.wait(r"FakeNavi requestRouteWithWaypoints|routesync: accepted", 40)
        self.record("8 (B 수락)", "B: requestRouteWithWaypoints + ACK", obs)

    # ---- 7 ----
    def scene_waypoint(self, wb: LogWatcher, wa: LogWatcher):
        remaining = max(30.0, 240 - (time.time() - self.t0))
        self.say(f"장면 7: 차 B 의 덕평휴게소 도착을 최대 {remaining:.0f}초 기다린다 (follow.gpx 2.5 km, 60 km/h ≈ 150 s)")
        obs = wb.wait(r"FakeNavi waypoint arrived|WAYPOINT_ARRIVED", remaining)
        self.record("7 (B 도착)", "B: FakeNavi waypoint arrived → WAYPOINT_ARRIVED 발행", obs)
        obs = wa.wait(r"WAYPOINT_ARRIVED", 20)
        self.record("7 (A 수신)", "A: 그룹 이벤트 WAYPOINT_ARRIVED 수신(피드)", obs)

    # ---- 9 ----
    def scene_hard_brake(self, wb: LogWatcher, wa: LogWatcher):
        self.say("장면 9: VC 급제동(55 km/h → 브레이크 80%) → " + str([r[0] for r in self.vc.hard_brake(55, 80)]))
        obs = wa.wait(r"HARD_BRAKE", 20)
        self.record("9 (A 발행)", "A: vehicle event HARD_BRAKE", obs)
        obs = wb.wait(r"voice: speak HARD_BRAKE|HARD_BRAKE", 20)
        self.record("9 (B 음성)", "B: voice: speak HARD_BRAKE", obs)

    # ---- 마무리 ----
    def finish(self, wa: LogWatcher, wb: LogWatcher):
        time.sleep(5)
        self.vc.controls(0, 0); self.vc.gear("Park"); self.vc.close()
        self.replay_stop.set()
        os.makedirs(self.out, exist_ok=True)
        for name, w in (("carA", wa), ("carB", wb)):
            with open(os.path.join(self.out, f"{name}.log"), "w", encoding="utf-8") as f:
                f.write("\n".join(w.new()))
        ok = sum(1 for _, _, o in self.results if o != "(없음)")
        lines = [f"# 시연 결과 {dt.datetime.now():%Y-%m-%d %H:%M}", "", f"A={self.A} B={self.B} speed={self.a.speed_kph:g} km/h  확인 {ok}/{len(self.results)}", "", "| 장면 | 기대 | 관측 |", "|---|---|---|"]
        for s, e, o in self.results:
            lines.append(f"| {s} | {e} | {o[-120:].replace('|', '/')} |")
        with open(os.path.join(self.out, "summary.md"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        print("\n" + "\n".join(lines[2:]))
        print(f"\n로그·요약: {self.out}")
        return ok == len(self.results)

    def run(self):
        sa, sb = self.preflight()
        if self.a.dry_run:
            print("dry-run: 여기까지"); return True
        self.wait_for_group(sa, sb)
        wa, wb = LogWatcher(self.A), LogWatcher(self.B)
        self.start_driving()
        try:
            self.scene_low_energy(wb, wa)
            self.scene_message(wb)
            self.scene_route_change(wb, wa)
            self.scene_waypoint(wb, wa)
            self.scene_hard_brake(wb, wa)
        finally:
            return self.finish(wa, wb)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--car-a", default="emulator-5556", help="선두차(Pleos AVD, pleos flavor)")
    ap.add_argument("--car-b", default="emulator-5558", help="후속차(Pleos_B AVD, fake flavor)")
    ap.add_argument("--vc-port", type=int, default=8089, help="차 A Virtual Console 포워딩 포트")
    ap.add_argument("--speed-kph", type=float, default=60.0)
    ap.add_argument("--gpx", default=replay_gpx.DEFAULT_GPX, help="차 A 에 주입할 GPX(기본 lead.gpx)")
    ap.add_argument("--server", default=os.environ.get("DH_SERVER", "http://127.0.0.1:8080"))
    ap.add_argument("--email", default=os.environ.get("DH_DEMO_EMAIL"), help="장면 6 REST 전송용 dev 로그인 이메일(폰과 같은 계정)")
    ap.add_argument("--message", default="휴게소 들렀다 가요")
    ap.add_argument("--no-auto-accept", dest="auto_accept", action="store_false", help="차 B 제안을 화면에서 직접 수락")
    ap.add_argument("--wait-join", type=int, default=600)
    ap.add_argument("--force-wait-join", action="store_true", help="이미 그룹이어도 새 합류(GROUP_JOINED)를 기다린다")
    ap.add_argument("--out", default=os.path.join(HERE, "out"))
    ap.add_argument("--dry-run", action="store_true", help="preflight 만")
    ap.add_argument("--skip-preflight", action="store_true")
    a = ap.parse_args()
    ok = Demo(a).run()
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
