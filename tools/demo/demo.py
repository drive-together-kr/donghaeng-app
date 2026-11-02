#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""동행 시연 타임라인 실행기 — T30, docs/09 §4 (장면 4~9 자동, 장면 2~3 은 화면·폰에서).

차 A = Pleos AVD(실 SDK, pleos flavor, Virtual Console 로 차량 상태 조작, GPS 는 이 스크립트가 lead.gpx 로 주입)
차 B = Pleos_B AVD(fake flavor, FakeNaviAdapter 가 follow.gpx 를 재생 — 합류 뒤 "선두 목적지까지 안내" 수락 시 출발)

  python tools/demo/demo.py --car-a emulator-5556 --car-b emulator-5558 --vc-port 8089 [--email dev@example.com]

순서
  0 preflight  : 기기·앱·서버·Virtual Console·차 B GPX 역할(follow)·음성 경로(ANDROID_TTS|PLEOS_TTS — 화면만이면 FAIL)·음성 안내 켜짐(voice=true —
                 꺼져 있으면 실행 때 켠다)·플로팅 창 권한·차 A 위치 소스(android_first) 확인
                 + 시연 닉네임(--email 필요): 차 A "아빠 차"·차 B "둘째 차"·같은 계정의 나머지 차 하나 "삼촌 차"(--nick-a/-b/-c)
  2~3 대기     : 차 A '같이 가기 시작' → 폰(demo-host)으로 QR 합류(또는 api.py join). 차 B 의 합류를 로그로 감지하면 합류 제안(합류 약 3 s 뒤 —
                 경유지가 있는 그룹이면 서버 경로 제안 하나)이 뜬 것을 보고 자동 수락(--no-auto-accept 면 화면에서)
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
import base64
import datetime as dt
import os
import re
import secrets
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import replay_gpx  # noqa: E402
from vc import VirtualConsole  # noqa: E402

PKG = "app.donghaeng.car"
# 에뮬레이터 음성 엔진(오픈 소스 오프라인 한국어) — sherpa-onnx TTS engine(Apache-2.0) + Supertonic 3(OpenRAIL-M), docs/09 §1.4
TTS_ENGINE_PKG = "com.k2fsa.sherpa.onnx.tts.engine"
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


def adb_devices(adb_path: str | None = None) -> str | None:
    """`adb devices` 출력 — adb 실행 파일이 없거나 실행이 안 되면 None(트레이스백 대신 preflight 의 FAIL 행으로)"""
    try:
        return subprocess.run([adb_path or ADB, "devices"], capture_output=True, text=True, timeout=30).stdout or ""
    except (OSError, subprocess.SubprocessError):
        return None


def device_connected(devices: str | None, serial: str) -> bool:
    """`adb devices` 출력에서 serial 이 device 상태인가(출력이 없으면 False)"""
    if not devices or not serial:
        return False
    return "device" in [l.split("\t")[-1] for l in devices.splitlines() if l.startswith(serial)]


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


def tts_path_row(name: str, st: dict, engine_installed: bool):
    """preflight '앱 음성 경로' 행 (label, ok) — ok: True = OK, None = WARN, False = FAIL.
    st = 디버그 status dict(tts=ready|init|off, ttsEngine, ttsPath=ANDROID_TTS|PLEOS_TTS|DISPLAY_ONLY, ttsSelecting).
    소리 경로가 있으면(Android TTS 든 Pleos TTS 든) OK, 화면 표시만이면 FAIL. Supertonic 이 깔려 있는데 Pleos 로 읽으면 WARN(기본 엔진·적재 확인)"""
    if not st:
        return (f"{name} 앱 음성 경로 — 디버그 리시버 응답 없음", False)
    tts, path, engine = st.get("tts"), st.get("ttsPath"), st.get("ttsEngine", "-")
    selecting = st.get("ttsSelecting") == "true"
    if tts == "ready" and path in (None, "-"):
        return (f"{name} 앱 음성 준비(tts=ready, 옛 앱 — 경로 표시 없음)", True)
    if tts == "ready" and path == "ANDROID_TTS":
        return (f"{name} 앱 음성 경로 = ANDROID_TTS(engine={engine})", True)
    if tts == "ready" and path == "PLEOS_TTS":
        if selecting:
            return (f"{name} 앱 음성 경로 = PLEOS_TTS (Android TTS 아직 적재 중 — 시간 초과, 나중에 Android 로 올라갈 수 있음)", None)
        if engine_installed:
            return (f"{name} 앱 음성 경로 = PLEOS_TTS (Supertonic 이 있는데 Android TTS 불가 — 기본 엔진·앱 재시작 확인, 진단 '음성 안내' 사유)", None)
        return (f"{name} 앱 음성 경로 = PLEOS_TTS (Supertonic 없음 — Pleos TTS 로 자동 동작, 음질은 docs/09 §1.4)", True)
    if tts == "init":
        return (f"{name} 앱 음성 경로 — 소리 경로 없음, 초기화 중인 후보 있음(Pleos TTS 늦은 응답·다시 시도일 수 있음 — 시간 초과, 진단 '음성 안내' 후보 줄·잠시 뒤 다시)", False)
    return (f"{name} 앱 음성 경로 = {path or 'DISPLAY_ONLY'} — 소리 없음(화면 표시만): Supertonic 설치(docs/09 §1.4) 또는 Pleos CaaS 확인", False)


def voice_setting_row(name: str, st: dict, turned_on: bool = False):
    """preflight '음성 안내 설정' 행 (label, ok) — 디버그 status 의 `voice=true|false`(VoiceAnnouncer 저장값, 앱을 다시 켜도 남는다).
    꺼져 있으면 장면 5·6·9 의 `voice: speak …` 가 나지 않아 확인이 MISS 다(배치 B 리뷰 RB — latency.py 가 끈 채 멈췄거나 설정 화면에서 끈 경우).
    ok: True = 켜짐, False = 꺼짐(FAIL), None = 값 없음(옛 앱 — WARN)"""
    v = (st or {}).get("voice")
    if v == "true":
        return (f"{name} 음성 안내 켜짐(voice=true{' — 꺼져 있어 켰다' if turned_on else ''})", True)
    if v == "false":
        hint = "켜기를 보냈는데도 꺼짐 — 진단·설정 화면 확인" if turned_on else \
            "장면 5·6·9 음성이 나지 않는다: --dry-run 없이 실행하면 자동으로 켠다(또는 설정 '음성 안내')"
        return (f"{name} 음성 안내 꺼짐(voice=false, 앱 저장값) — {hint}", False)
    return (f"{name} 음성 안내 설정 — status 에 voice= 없음(리시버 응답 없음 또는 옛 앱)", None)


# 장면 3: 합류 뒤 차 B 가 낸 제안 — 로컬 합류 제안(경유지 없는 그룹, 합류 약 3 s 뒤) 또는 서버 경로 제안(경유지 있는 그룹, GROUP_JOINED 직후).
# RC-3: 둘 중 하나만 나온다. `routesync: joined → destination ask …`(예약)·`routesync: join ask skipped …`(생략)에는 맞지 않는다
JOIN_OFFER_RX = r"routesync: join proposal dest=|routesync: proposal \S+ dest="


def gwa_wa(word: str) -> str:
    """차 앱 GroupNaming 과 같은 조사 규칙(안내 문구용): 한글 받침 → 과, 숫자는 읽는 소리(0·1·3·6·7·8 → 과), 영문·기호 → 와"""
    w = word.rstrip().rstrip(")]}\"'”’」』.!? ")
    if not w:
        return "와"
    c = w[-1]
    if "가" <= c <= "힣":
        return "과" if (ord(c) - 0xAC00) % 28 else "와"
    return "과" if c in "013678" else "와"


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
        devices = adb_devices()
        if devices is None:
            rows.append((f"adb 실행 파일 없음({ADB}) — Android SDK platform-tools 를 깔거나 환경 변수 ADB 로 경로를 준다", False))
        for name, s in (("A", self.A), ("B", self.B)):
            sh(s, "logcat -G 8M")   # 1 Hz 로그가 링 버퍼를 밀어내지 않게 (증거 수집용)
            rows.append((f"{name} {s} 연결", device_connected(devices, s)))
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
        # 음성 체인(R19 해결, docs/09 §1.4·docs/10 R19): 앱은 Android TTS(한국어 엔진·ko_KR) → Pleos TTS SDK ON_DEVICE(pleos flavor) → 화면 표시만
        # 순으로 스스로 고른다. Pleos AVD 시스템 엔진은 Android TextToSpeech 에 안 보이므로 엔진이 없으면 차 A(pleos)는 Pleos TTS 로 읽는다.
        # 오픈 소스 오프라인 엔진 sherpa-onnx(Supertonic 3, 한국어)는 음질 선택 사항 — 있으면 Android TTS 경로로 더 자연스럽게 읽는다.
        # 차 B(fake flavor)는 Pleos SDK 가 없어 Supertonic 이 없으면 화면 표시만 → FAIL 로 알린다
        for name, s in (("A", self.A), ("B", self.B)):
            rows.extend(self.ensure_tts_engine(name, s))
        # 음성 안내 스위치(앱 저장값) — 꺼져 있으면 경로가 있어도 읽지 않는다. 실행 때는 켜고 다시 확인한다(dry-run 은 바꾸지 않음)
        for name, s in (("A", self.A), ("B", self.B)):
            rows.append(self.ensure_voice_on(name, s))
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
        # 시연 닉네임(2026-10-28): 패널·음성·카드에 나오는 차 이름을 "아빠 차"·"둘째 차"·"삼촌 차"로 맞춘다
        self.ensure_nicknames(rows)
        print("\n== preflight ==")
        bad = 0
        for k, ok in rows:
            # ok: True = OK, False = FAIL(시연 중단 사유), None = WARN(알리기만, 실패로 세지 않음)
            print(f"  {'WARN' if ok is None else 'OK  ' if ok else 'FAIL'} {k}")
            bad += 0 if ok or ok is None else 1
        if bad and not self.a.skip_preflight:
            print("preflight 실패 항목이 있다. --skip-preflight 로 무시할 수 있다.")
            if not self.a.dry_run:
                sys.exit(2)
        return sa, sb

    def ensure_tts_engine(self, name: str, serial: str):
        """차 한 대의 음성 경로 점검(+ --tts-apk 가 있으면 Supertonic 설치·기본 지정). 표 행 목록을 돌려준다.
        Supertonic 은 음질 선택 사항(없으면 WARN) — 판정은 앱이 고른 경로(디버그 status ttsPath)로 한다"""
        rows = []
        installed = TTS_ENGINE_PKG in sh(serial, f"pm list packages --user 10 {TTS_ENGINE_PKG}")
        if not installed and self.a.tts_apk and not self.a.dry_run:
            print(f"  {name}: TTS 엔진 설치 중 ({os.path.basename(self.a.tts_apk)}) …", flush=True)
            out = subprocess.run([ADB, "-s", serial, "install", "-r", self.a.tts_apk], capture_output=True, text=True, timeout=600).stdout
            installed = "Success" in out
        rows.append((f"{name} Supertonic 엔진({TTS_ENGINE_PKG}, 음질 선택)" + ("" if installed else " 없음 — 있으면 더 자연스럽다: --tts-apk <APK>, docs/09 §1.4"),
                     True if installed else None))
        changed = False
        if installed:
            current = sh(serial, "settings --user 10 get secure tts_default_synth").strip()
            if current != TTS_ENGINE_PKG and not self.a.dry_run:
                sh(serial, f"settings --user 10 put secure tts_default_synth {TTS_ENGINE_PKG}")
                current = sh(serial, "settings --user 10 get secure tts_default_synth").strip()
                changed = True
            rows.append((f"{name} 기본 TTS 엔진 = {TTS_ENGINE_PKG} (지금 {current or '없음'})", True if current == TTS_ENGINE_PKG else None))
        if changed:
            # 앱의 TextToSpeech 는 시작할 때 기본 엔진에 붙는다 — 바꿨으면 앱을 다시 띄운다
            sh(serial, f"am force-stop {PKG}; am start -n {PKG}/.MainActivity >/dev/null 2>&1")
        st = self.wait_tts_path(serial, 75 if changed else 40)
        rows.append(tts_path_row(name, st, installed))
        return rows

    def ensure_voice_on(self, name: str, serial: str):
        """차 한 대의 '음성 안내' 저장값 점검 — 꺼져 있으면(실행 때만) 리시버 `voice --ez on true` 로 켜고 status 로 다시 본다. 표 행 하나"""
        st = status(serial)
        if st.get("voice") == "false" and not self.a.dry_run:
            broadcast(serial, "voice", on=True)
            return voice_setting_row(name, status(serial), turned_on=True)
        return voice_setting_row(name, st)

    def wait_tts_path(self, serial: str, timeout_s: float) -> dict:
        """앱의 음성 체인이 경로를 정할 때까지(디버그 status: tts=ready + ttsSelecting=false, 또는 tts=off) 기다린다 — 마지막 status dict.
        로그가 밀리거나 지워져도 맞는 값. 위 순위(Android TTS) 적재가 길면 그동안 tts=ready ttsPath=PLEOS_TTS ttsSelecting=true 다(선택 상한 30 s)"""
        end = time.time() + timeout_s
        while True:
            st = status(serial)
            if st.get("tts") == "ready" and st.get("ttsSelecting") != "true":
                return st
            if st.get("tts") == "off" or time.time() >= end:
                return st
            time.sleep(3)

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

    # ---- 시연 닉네임 ----
    def which_vehicle(self, serial: str, ids: list) -> str | None:
        """차 앱에 vehicleId 후보를 하나씩 물어(debug 리시버 is_me) 그 차의 id 를 찾는다. 못 찾으면 None.
        앱은 `demo: is_me=true|false tag=<꼬리표>` 만 남긴다 — id 원문은 기기 로그에 남지 않고, 대응은 이 스크립트 메모리에서만 한다."""
        if not ids:
            return None
        w = LogWatcher(serial)
        run = secrets.token_hex(3)   # 이전 실행·다른 질의의 줄과 섞이지 않게
        tags = {f"{run}-{i}": vid for i, vid in enumerate(ids)}
        for tag, vid in tags.items():
            broadcast(serial, "is_me", vid=vid, tag=tag)
        rx = re.compile(r"demo: is_me=(true|false) tag=([0-9a-f]+-\d+)")
        answered = {}
        end = time.time() + 8
        while time.time() < end:
            for line in w.new():
                m = rx.search(line)
                if m and m.group(2) in tags:
                    answered[m.group(2)] = m.group(1) == "true"
            if len(answered) == len(tags) or any(answered.values()):
                break
            time.sleep(1.0)
        mine = [tags[t] for t, yes in answered.items() if yes]
        return mine[0] if len(mine) == 1 else None

    def ensure_nicknames(self, rows):
        """시연 닉네임을 서버(PATCH /me/vehicles/{id})와 두 차 앱 저장값에 맞춘다 (docs/09 §5).
        차 A = --nick-a, 차 B = --nick-b, 같은 계정의 나머지 차 하나(있으면) = --nick-c. 이미 같으면 건너뛰고, --dry-run 이면 계획만 출력한다.
        그룹 스냅샷·이벤트 문구는 서버가 매번 DB 에서 닉네임을 읽어 바로 반영되고, 차 앱 저장값(홈 화면·그룹 기본 이름 "아빠 차와 같이 가기")은
        그룹 스냅샷으로도 맞춰지지만 그룹 전에는 알 길이 없어 debug 리시버 `nickname` 으로 같은 값을 넣는다.
        서버에 닿지 않아도 예외로 죽지 않고 FAIL 행만 남긴다 — preflight 표가 바로 그 상황을 진단하는 곳이다(2026-10-28 리뷰)."""
        a = self.a
        if not a.email:
            rows.append(("시연 닉네임 — --email 없음: 서버 닉네임을 바꾸지 않았다(패널·음성에 페어링 때 이름이 나온다)", None))
            return
        from api import DhApi, mask
        try:
            api = DhApi(a.server, a.email)
            api.login()
            vehicles = api.vehicles()
        except (SystemExit, OSError) as e:   # OSError: 혹시 DhApi.call 밖에서 난 연결 오류(URLError·TimeoutError 는 OSError 하위)
            rows.append((f"시연 닉네임 — 서버 조회 실패({e})", False))
            return
        print("\n== 시연 닉네임 ==")
        by_id = {v["vehicleId"]: v for v in vehicles}
        ids = list(by_id)
        found = {"A": self.which_vehicle(self.A, ids), "B": self.which_vehicle(self.B, ids)}
        plan = []   # (역할, vehicleId, 새 닉네임)
        for role, nick in (("A", a.nick_a), ("B", a.nick_b)):
            if found[role]:
                plan.append((role, found[role], nick))
            else:
                rows.append((f"시연 닉네임 — 차 {role} 를 이 계정 차량 {len(ids)}대에서 못 찾음(다른 계정으로 페어링됐거나 앱 응답 없음)", False))
        # 나머지 차 = A·B 를 둘 다 찾았을 때만 고른다(못 찾은 차의 id 가 '나머지' 로 섞여 엉뚱한 차가 '삼촌 차' 가 되지 않게). 대표 차량 먼저·등록순
        rest = [vid for vid in ids if vid not in found.values()] if found["A"] and found["B"] else []
        if rest:
            plan.append(("C", rest[0], a.nick_c))
        else:
            print(f"  (나머지 차 없음 또는 차 A/B 미확인 — '{a.nick_c}' 는 건너뜀)")
        synced = []   # 서버 값이 새 닉네임과 같아진 항목(처음부터 같음 또는 PATCH 200)만 — 차 앱 저장값은 이것만 맞춘다
        for role, vid, nick in plan:
            old = by_id[vid].get("nickname")
            label = f"차 {role} {mask(vid)} '{old}' → '{nick}'"
            if old == nick:
                print(f"  같음  {label}")
                rows.append((f"닉네임 차 {role} = '{nick}'", True))
                synced.append((role, nick))
            elif a.dry_run:
                print(f"  계획  {label} (dry-run — 바꾸지 않음)")
                rows.append((f"닉네임 차 {role} = '{nick}' (dry-run: 지금 '{old}', 실제 실행 때 바꾼다)", None))
            else:
                try:
                    st, res = api.rename_vehicle(vid, nick)
                except (SystemExit, OSError) as e:
                    st, res = 0, e
                print(f"  {'변경' if st == 200 else '실패'}  {label} → HTTP {st}")
                rows.append((f"닉네임 차 {role} = '{nick}'" + ("" if st == 200 else f" (PATCH {st} {str(res)[:80]})"), st == 200))
                if st == 200:
                    synced.append((role, nick))
        # 차 앱 저장값(홈·그룹 기본 이름) — 서버가 같아도 앱이 옛 값일 수 있어 넣는다(같으면 앱이 changed=false).
        # 서버 PATCH 가 실패한 차는 넣지 않는다: 앱만 새 이름이면 홈·그룹 기본 이름과 서버 음성·패널 이름이 어긋나고, 다음 그룹 스냅샷이 다시 옛 이름으로 되돌린다
        if not a.dry_run:
            for role, _vid, _nick in plan:
                if role in ("A", "B") and role not in {r for r, _ in synced}:
                    print(f"  (차 {role} 서버 닉네임이 안 바뀌어 앱 저장값은 그대로 둔다)")
            for role, nick in synced:
                serial = {"A": self.A, "B": self.B}.get(role)
                if not serial:
                    continue
                w = LogWatcher(serial)
                broadcast(serial, "nickname", name_b64=base64.urlsafe_b64encode(nick.encode("utf-8")).decode("ascii"))
                hit = w.wait(r"demo: nickname changed=(true|false)", 8, poll_s=1.0)
                rows.append((f"차 {role} 앱 저장 닉네임 맞춤({'바꿈' if hit and 'changed=true' in hit else '이미 같음' if hit else '응답 없음'})", bool(hit)))
        try:
            groups = api.active_groups()
        except (SystemExit, OSError):
            groups = []
        if groups:
            print(f"  (활성 그룹 '{groups[0].get('name')}' — 패널 머리글에 그대로 나온다. 이름을 바꾸려면 그룹을 끝내고 차 A 에서 새로 만든다: 기본 이름 '{a.nick_a}{gwa_wa(a.nick_a)} 같이 가기')")

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
        print(f"\n== 장면 2~3: 차 A 에서 '같이 가기 시작' → 목적지 선택 → 폰(demo-host)으로 QR 합류 (또는: python tools/demo/api.py --email … join --vehicle \"{self.a.nick_b}\")")
        self.say(f"차 B 의 합류를 최대 {self.a.wait_join}초 기다린다…")
        wb = LogWatcher(self.B)
        hit = wb.wait(r"GROUP_JOINED|routesync: join proposal", self.a.wait_join, poll_s=2.0)
        if not hit:
            print("합류를 감지하지 못했다. 종료."); sys.exit(3)
        self.say("B 합류 감지: " + hit[-100:])
        broadcast(self.B, "open", screen="drive")   # 제안 카드·지도가 보이게
        if self.a.auto_accept:
            # RC-3: 합류 제안은 합류 약 3 s 뒤(경유지가 있는 그룹이면 그 대신 서버 경로 제안 하나) — 제안이 뜬 것을 보고 수락한다
            offer = wb.wait(JOIN_OFFER_RX, 15, poll_s=1.0)
            self.say("B 합류 뒤 제안: " + (offer[-100:] if offer else "15 s 안에 못 봄 — 그래도 수락을 보낸다"))
            time.sleep(4)   # 카드가 화면에 잠깐 보이도록(영상용) 뒤 수락 — 30초 자동 거절 안
            self.say("B 합류 제안(선두 목적지 안내 또는 선두 경로) 자동 수락 → " + broadcast(self.B, "accept"))
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
        # B 는 선두 A 보다 뒤 → 수신 대상(docs/03 §5.4 audience). `voice: speak` 만 성공으로 센다 — 넓은 `HARD_BRAKE` 는
        # 대상에서 빠졌을 때의 `voice: skip not-for-me HARD_BRAKE`·`surface: skip not-for-me HARD_BRAKE` 에도 맞아 회귀를 못 잡는다
        obs = wb.wait(r"voice: speak HARD_BRAKE|skip not-for-me HARD_BRAKE", 20)
        skipped = wb.find(r"skip not-for-me HARD_BRAKE")
        if skipped:
            self.say("장면 9 실패: 뒤차 B 가 급제동 대상에서 빠졌다 → " + skipped[-1][-110:])
            obs = None
        self.record("9 (B 음성)", "B: voice: speak HARD_BRAKE (뒤차 = 대상, skip not-for-me 없음)", obs)

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
    # 출력을 파이프로 받으면 Windows 는 cp949 로 써서 "—" 같은 글자에서 죽는다 → 못 쓰는 글자는 ? 로
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
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
    ap.add_argument("--tts-apk", default=os.environ.get("DH_TTS_APK"),
                    help="에뮬레이터에 TTS 엔진이 없으면 설치할 sherpa-onnx 한국어 TTS 엔진 APK(x86_64, docs/09 §1.4) — 설치 뒤 기본 엔진으로 지정")
    ap.add_argument("--nick-a", default="아빠 차", help="preflight 가 맞출 차 A 닉네임(--email 필요)")
    ap.add_argument("--nick-b", default="둘째 차", help="preflight 가 맞출 차 B 닉네임")
    ap.add_argument("--nick-c", default="삼촌 차", help="같은 계정의 나머지 차 하나(있으면)의 닉네임")
    a = ap.parse_args()
    for flag in ("nick_a", "nick_b", "nick_c"):
        v = getattr(a, flag).strip()
        if not 1 <= len(v) <= 30:
            ap.error(f"--{flag.replace('_', '-')}: 1~30자여야 한다(서버 닉네임 한도)")
        setattr(a, flag, v)
    ok = Demo(a).run()
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
