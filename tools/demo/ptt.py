#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""동행 F16 무전(녹음형 PTT) 기기 확인 도구 — docs/05 § 무전, docs/09 § 무전 장면.

차 A(보내는 차, 보통 Pleos AVD pleos flavor)와 차 B(받는 차, Pleos_B AVD fake flavor)가 **같은 그룹**에 있을 때
  ① 테스트 음성 WAV 를 만들고(Windows 한국어 음성) 앱 files 로 넣고, ② (선택) Vosk 소형 한국어 모델을 넣고,
  ③ 핸들 키(adb input keyevent) → 녹음(주입 파일) → "보낼까요?" → 답 주입 → 보냄 → 차 B 재생 을 시나리오별로 돌려
  두 차 로그로 확인 표를 만든다(demo.py 와 같은 모양, tools/demo/out/<시각>_ptt/summary.md).

  python tools/demo/ptt.py audio [--out DIR]                                   # 테스트 WAV 5개(16 kHz 모노 16 bit) 만들기
  python tools/demo/ptt.py setup --car-a S [--car-b S] [--audio-dir DIR] [--model-zip ZIP | --model-dir DIR] [--model-on a|b|both] [--grant-mic]
  python tools/demo/ptt.py run --car-a S --car-b S [--scenarios yes,no,none,resend,press_cancel,countdown] [--answer-wav] [--trigger key|debug] [--key HEADSETHOOK] [--shots]
  python tools/demo/ptt.py run --car-a S --car-b S --scenarios interrupt,persist [--shots]   # 기본 목록 밖(사용자 결정 2026-10-28)
  python tools/demo/ptt.py status --car-a S [--car-b S]

- interrupt: 차 B 가 받은 무전을 트는 중 B 에서 무전 키 → 끊김('다 못 들음', resume=after-flow) → B 의 녹음·확인 흐름 끝(IDLE) → 처음부터 다시(resume=true) → 다 들음.
- persist: 차 A 무응답 보관(앱 내부 저장소 files/ptt_unsent) → `am force-stop` → 다시 시작 → `ptt_unsent` 에 같은 id·길이 그대로.
  다시 시작은 동행 화면을 앞으로 연다 — 다음 시나리오 전에 순정 내비를 다시 앞으로 둔다.

- 테스트 음성은 저장소에 넣지 않는다(합성 음성 재배포 회피). `audio` 가 Windows `System.Speech`(한국어 음성 Microsoft Heami)로 만들거나 `--audio-dir` 로 받은 폴더를 쓴다.
- Vosk 모델(vosk-model-small-ko-0.22, zip 82 MB·풀면 253 MB, Apache 2.0)도 저장소·APK 에 넣지 않는다 — `--model-zip`(받은 zip) 또는 `--model-dir`(풀린 폴더).
- 기기 파일 넣기: debug 빌드 `run-as --user <현재 사용자> app.donghaeng.car` 로 앱 files 아래에 tar 를 흘려 넣는다(Pleos AVD 는 사용자 10).
  run-as 가 안 되면 /data/local/tmp/dh_ptt 로 push 하고 앱이 그 경로를 읽게 한다(모델은 디버그 `vosk_install --es dir`).
- 앱 디버그 리시버 액션: ptt_press·ptt_inject_record·ptt_answer·ptt_status·ptt_unsent·ptt_handle·ptt_countdown·vosk_install (DemoControlReceiver KDoc 표).
- `setup --grant-mic`: 마이크 권한을 준 뒤 동행 화면을 한 번 앞으로 연다 — 그룹 서비스의 포그라운드 타입(microphone)은 앱이 앞일 때만 붙는다
  (`ptt_status` 의 `fgs=`). 시나리오는 순정 내비 전면에서 돌리므로 그 뒤 내비를 다시 앞으로 둔다.
- 로그에는 판정·근거만 남는다(인식 원문·녹음 내용·닉네임 없음 — 규칙 8).
"""
import argparse
import datetime as dt
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile
from dataclasses import dataclass, field

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from demo import ADB, PKG, LogWatcher, adb, adb_devices, broadcast, device_connected, sh  # noqa: E402

# 테스트 음성 — 이름은 F16 명세 §1 과 같다(통합 담당이 만든 원본과 같은 이름이면 --audio-dir 로 그대로 쓸 수 있다)
PHRASES = [
    ("yes_ne", "네"),
    ("yes_ne_bonae", "네, 보내"),
    ("no_ani", "아니"),
    ("no_aniyo_chwiso", "아니요, 취소"),
    ("msg_rest", "다음 휴게소에서 쉬었다 가요"),
]
APP_AUDIO_DIR = "ptt_test"          # 앱 files/ptt_test/*.wav
MODEL_DIR = "vosk-ko-small"         # 앱 files/vosk-ko-small/{am,conf,graph,ivector}
TMP_ROOT = "/data/local/tmp/dh_ptt"  # run-as 를 못 쓸 때
DEFAULT_AUDIO_DIR = os.path.join(HERE, "out", "ptt_audio")
SCENARIOS = ["yes", "no", "none", "resend", "press_cancel", "countdown", "interrupt", "persist"]
# `run` 기본 — 2026-10-28 기기 증명(OK 49/49)과 같은 여섯. interrupt·persist 는 --scenarios 로 고른다(persist 는 앱을 다시 띄운다)
DEFAULT_SCENARIOS = ["yes", "no", "none", "resend", "press_cancel", "countdown"]
TRIGGER_RX = r"ptt: trigger source=(MEDIA_SESSION|ACTIVITY_KEY|DEBUG)"


# ---------------------------------------------------------------- 순수 도우미(단위 테스트 대상)
def wav_info(head: bytes, file_len: int | None = None) -> dict | None:
    """RIFF/WAVE PCM 헤더 → {rate, channels, bits, data_bytes, duration_ms}. WAV 가 아니면 None (앱 WavInfo 와 같은 규칙)"""
    if len(head) < 12 or head[0:4] != b"RIFF" or head[8:12] != b"WAVE":
        return None
    file_len = len(head) if file_len is None else file_len
    pos, fmt = 12, None
    while pos + 8 <= len(head):
        cid = head[pos:pos + 4]
        size = int.from_bytes(head[pos + 4:pos + 8], "little")
        body = pos + 8
        if cid == b"fmt ":
            if body + 16 > len(head):
                return None
            fmt = dict(
                format=int.from_bytes(head[body:body + 2], "little"),
                channels=int.from_bytes(head[body + 2:body + 4], "little"),
                rate=int.from_bytes(head[body + 4:body + 8], "little"),
                bits=int.from_bytes(head[body + 14:body + 16], "little"),
            )
        elif cid == b"data":
            if not fmt or fmt["format"] not in (1, 0xFFFE) or fmt["rate"] <= 0 or fmt["channels"] <= 0 or fmt["bits"] <= 0:
                return None
            available = max(0, file_len - body)
            data = available if size <= 0 or size > available else size
            bps = fmt["rate"] * fmt["channels"] * (fmt["bits"] // 8)
            return dict(rate=fmt["rate"], channels=fmt["channels"], bits=fmt["bits"], data_bytes=data, duration_ms=(data * 1000 // bps) if bps else 0)
        pos = body + size + (size & 1)
    return None


def wav_problem(path: str) -> str | None:
    """앱 주입·Vosk 입력으로 쓸 수 있는가(16 kHz·모노·16 bit) — 문제 설명, 괜찮으면 None"""
    if not os.path.isfile(path):
        return "파일 없음"
    with open(path, "rb") as f:
        head = f.read(4096)
    info = wav_info(head, os.path.getsize(path))
    if info is None:
        return "WAV(PCM) 아님"
    if (info["rate"], info["channels"], info["bits"]) != (16000, 1, 16):
        return f"형식 {info['rate']} Hz·{info['channels']} ch·{info['bits']} bit — 16 kHz 모노 16 bit 여야 한다"
    if info["duration_ms"] < 300:
        return f"너무 짧다({info['duration_ms']} ms)"
    return None


def ps_quote(s: str) -> str:
    """PowerShell 작은따옴표 문자열"""
    return "'" + s.replace("'", "''") + "'"


def ps_script(out_dir: str, phrases=PHRASES) -> str:
    """Windows System.Speech 로 한국어 WAV(16 kHz 모노 16 bit)를 만드는 PowerShell — Heami 를 먼저, 없으면 아무 ko-KR 음성. 없으면 종료 코드 3"""
    lines = [
        "$ErrorActionPreference = 'Stop'",
        "Add-Type -AssemblyName System.Speech",
        "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer",
        "$ko = @($s.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'ko-KR' })",
        "if ($ko.Count -eq 0) { Write-Output 'NO_KO_VOICE'; exit 3 }",
        "$pick = @($ko | Where-Object { $_.VoiceInfo.Name -like '*Heami*' }) + $ko | Select-Object -First 1",
        "$s.SelectVoice($pick.VoiceInfo.Name)",
        "$s.Rate = -1",
        "$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)",
    ]
    for name, text in phrases:
        path = os.path.join(out_dir, name + ".wav")
        lines.append(f"$s.SetOutputToWaveFile({ps_quote(path)}, $fmt); $s.Speak({ps_quote(text)})")
    lines += ["$s.SetOutputToNull()", "Write-Output ('VOICE=' + $pick.VoiceInfo.Name)"]
    return "\r\n".join(lines) + "\r\n"


def model_prefix_in_zip(names: list[str]) -> str | None:
    """zip 안에서 am/·conf/ 를 바로 아래에 둔 폴더 접두사("vosk-model-small-ko-0.22/" 또는 "") — 없으면 None"""
    dirs = set()
    for n in names:
        parts = n.replace("\\", "/").split("/")
        for i in range(len(parts) - 1):
            dirs.add(("/".join(parts[:i]) + "/" if i else "", parts[i]))
    prefixes = {p for p, d in dirs if d == "am"} & {p for p, d in dirs if d == "conf"}
    return min(prefixes, key=len) if prefixes else None


def model_root_in_dir(path: str) -> str | None:
    """am/·conf/ 가 있는 폴더 — 주어진 폴더 또는 그 바로 아래 한 폴더"""
    def ok(p):
        return os.path.isdir(os.path.join(p, "am")) and os.path.isdir(os.path.join(p, "conf"))
    if not os.path.isdir(path):
        return None
    if ok(path):
        return path
    for d in sorted(os.listdir(path)):
        full = os.path.join(path, d)
        if os.path.isdir(full) and ok(full):
            return full
    return None


def sq(s: str) -> str:
    """기기 sh 작은따옴표"""
    return "'" + s.replace("'", "'\\''") + "'"


def run_as(user: int | None, inner: str, pkg: str = PKG) -> str:
    """앱 데이터 폴더(cwd)에서 sh -c <inner> 를 도는 기기 명령 한 줄 — `run-as [--user U] <pkg> sh -c '…'`(사용자 10 인 Pleos AVD 는 --user 가 있어야 한다)"""
    user_part = f"--user {user} " if user else ""
    return f"run-as {user_part}{pkg} sh -c {sq(inner)}"


def install_cmd(dest_rel: str) -> str:
    """stdin 의 tar 를 files/<dest_rel> 로 바꿔 넣는 sh 스크립트(임시 폴더에 풀고 바꿔치기)"""
    tmp = f"files/{dest_rel}.tmp"
    dst = f"files/{dest_rel}"
    return f"rm -rf {tmp} && mkdir -p {tmp} && tar -xf - -C {tmp} && rm -rf {dst} && mv {tmp} {dst} && echo DH_OK"


def tar_dirs(arcnames) -> list[str]:
    """파일 이름들의 부모 폴더(얕은 것부터) — toybox tar 가 폴더를 만들지 않아도 되게 폴더 항목을 먼저 넣는다"""
    out = set()
    for a in arcnames:
        parts = a.split("/")[:-1]
        for i in range(1, len(parts) + 1):
            out.add("/".join(parts[:i]))
    return sorted(out, key=lambda d: (d.count("/"), d))


def parse_kv(text: str) -> dict:
    """`a=1 b=x/y` → dict (값은 공백 전까지)"""
    return dict(re.findall(r"(\w+)=([^\s\]|]+)", text))


def parse_status(line: str) -> dict:
    """`demo: ptt_status …` 한 줄 → {stage, unsent, persisted, mic, handleButton, vosk, group, session, sessionState, resumeWaiting, recognizers:{KIND: state}, …}"""
    body = line.split("demo: ptt_status", 1)[-1]
    out = parse_kv(body)
    m = re.search(r"recognizers=\[([^\]]*)\]", body)
    out["recognizers"] = dict(re.findall(r"(\w+)=(\w+(?:\([^)]*\))?)", m.group(1))) if m else {}
    m = re.search(r"vosk=(present\([^)]*\)|absent)", body)
    if m:
        out["vosk"] = m.group(1)
    m = re.search(r"\| trigger (.*?) \| playback", body)
    if m:
        trig = parse_kv(m.group(1))
        out["group"] = trig.get("group")
        out["session"] = trig.get("session")
        out["otherMedia"] = trig.get("otherMedia")
    return out


def fgs_has_mic(status: dict) -> bool | None:
    """`fgs=location+microphone` → True, microphone 없음 → False, 앱이 이 값을 안 내보내면(옛 빌드) None"""
    fgs = status.get("fgs")
    if fgs is None:
        return None
    return "microphone" in fgs.split("+")


def mic_gate_row(name: str, status: dict, injected: bool = True):
    """그룹 서비스에 microphone 타입이 없을 때의 preflight WARN 행(없으면 None).
    배치 B(B015): API 30+ 에서 동행 화면이 뒤(순정 내비가 앞)이고 microphone 타입이 없으면 앱이 실마이크 무전을 **녹음하지 않고 안내만** 한다
    (`ptt: mic not usable in background …`) — 예전처럼 무음을 보내지 않는다. 주행 중 경로 질문의 '네/아니' 도 묻지 않는다(RA-4).
    [injected] = 이 차의 녹음이 주입인가(주입 녹음·답 주입은 이 판정을 거치지 않는다)"""
    if status.get("mic") != "true" or fgs_has_mic(status) is not False:
        return None
    effect = ("주입 녹음·답 주입은 상관없다" if injected
              else "이 차의 실마이크 녹음은 `ptt: mic not usable in background` 안내로 끝난다 — 흐름 확인은 그대로")
    return (f"{name} 그룹 서비스에 microphone 타입 없음(fgs={status.get('fgs')}) — 내비가 앞일 때 실마이크 무전은 녹음하지 않고 안내만, "
            f"동행 화면을 한 번 열면 붙는다({effect})", None)


def recognizer_ready(status: dict) -> bool:
    """실제 인식기 중 하나라도 ready 인가(아니면 확인은 카운트다운 모드)"""
    return any(v == "ready" for v in status.get("recognizers", {}).values())


def scenario_names(text: str) -> list[str]:
    """`yes, none,,persist` → ['yes', 'none', 'persist']"""
    return [n.strip() for n in (text or "").split(",") if n.strip()]


# ---------------------------------------------------------------- 시나리오(데이터 — 단위 테스트 대상)
@dataclass
class Step:
    kind: str                 # bcast | press | sleep | expect | absent | shot | shell | mark | note
    car: str = "A"
    action: str = ""          # bcast = 리시버 액션, shell = 기기 명령
    extras: dict = field(default_factory=dict)   # 값의 {이름} 은 앞 단계에서 잡은 값으로(원문 그대로)
    label: str = ""
    pattern: str = ""         # expect·absent = 로그 정규식, shell = 명령 출력 정규식(있으면 확인 행)
    timeout: float = 15.0
    seconds: float = 0.0
    always: bool = False      # 앞 단계가 실패해도 한다(정리 단계)
    poll: float = 1.0         # expect 로그 확인 간격(초) — 짧은 창을 잡아야 할 때 줄인다


@dataclass
class Scenario:
    name: str
    title: str
    steps: list


def _record(opts, steps, answer_wav: str | None = None):
    """주입 녹음 → 누름(녹음 시작) → 기다림 → 누름(끝) → 확인 단계"""
    steps += [
        Step("bcast", "A", "ptt_inject_record", {"file": opts.file("msg_rest")}),
        Step("expect", "A", label="녹음 주입 준비", pattern=r"demo: ptt_inject_record armed", timeout=8),
        Step("press", "A"),
        Step("expect", "A", label="트리거(핸들 키)", pattern=TRIGGER_RX, timeout=8),
        Step("expect", "A", label="녹음 시작(주입)", pattern=r"ptt: recording start inject=true", timeout=8),
    ]
    if opts.shots:
        steps.append(Step("shot", "A", label="recording"))
    steps.append(Step("sleep", seconds=opts.record_s))
    if answer_wav:
        # WAV 답은 듣기가 시작되기 전에 넣어야 한다(체인이 듣기 시작할 때 한 번 읽는다) — 넣어 두면 확인이 음성 모드가 된다
        steps.append(Step("bcast", "A", "ptt_answer", {"file": opts.file(answer_wav)}))
    steps += [
        Step("press", "A"),
        Step("expect", "A", label="녹음 끝(다시 누름)", pattern=r"ptt: recorded end=press dur=\d+ms", timeout=8),
        Step("expect", "A", label="확인 시작", pattern=r"ptt: confirm mode=(voice|countdown)", timeout=8),
    ]


def _send_yes(opts, steps):
    """A: 주입 녹음 → 확인 → 답 '네'(주입) → 보냄 — voiceId 를 잡는다"""
    _record(opts, steps)
    steps += [
        Step("sleep", seconds=opts.confirm_delay_s),
        Step("bcast", "A", "ptt_answer", {"answer": "yes"}),
        Step("expect", "A", label="답 YES (주입)", pattern=r"ptt: recognizer=\w+ answer=YES", timeout=15),
        Step("expect", "A", label="보냄", pattern=r"ptt: sent voiceId=(?P<voiceId>\S+) to=(?P<to>\d+)", timeout=20),
    ]


def _interrupt(opts, steps):
    """차 B 가 받은 무전을 트는 중(알림 포함) B 에서 무전 키 → 끊김 '다 못 들음' → B 의 흐름 끝(IDLE) → 처음부터 → 다 들음(사용자 결정 2026-10-28)"""
    _send_yes(opts, steps)
    steps += [
        Step("expect", "B", label="B 받음", pattern=r"ptt: voice received voiceId={voiceId}", timeout=opts.b_timeout),
        # 알림("아빠 차 무전" ~1–2 s) + 녹음(~2.5 s) 창 안에 눌러야 한다 — 로그를 0.3 s 마다 본다
        Step("expect", "B", label="B 재생 시작(알림부터)", pattern=r"ptt: play start voiceId={voiceId} manual=false resume=false", timeout=opts.b_timeout, poll=0.3),
        Step("press", "B"),
        Step("expect", "B", label="B 재생 중 누름 → 끊김(다 못 들음), 흐름이 끝나면 다시", timeout=10,
             pattern=r"ptt: playback interrupted voiceId={voiceId} why=ptt-press status=INTERRUPTED resume=after-flow"),
        Step("expect", "B", label="B 녹음 시작(마이크 권한·FGS microphone 이 없으면 안내)", pattern=r"ptt: press source=\S+ stage=IDLE", timeout=8),
    ]
    if opts.shots:
        steps.append(Step("shot", "B", label="interrupted"))
    steps += [
        Step("bcast", "B", "ptt_status"),
        Step("expect", "B", label="B 끊긴 무전이 흐름 끝을 기다림(resumeWaiting=true, INTERRUPTED)", timeout=8,
             pattern=r"demo: ptt_status .*resumeWaiting=true past=\d+\[[^\]]*INTERRUPTED=[1-9]"),
        Step("sleep", seconds=1.5),
        Step("press", "B"),       # 녹음 끝 → '보낼까요?'(마이크 권한·FGS microphone 이 없으면 안내 중 새 누름)
        Step("sleep", seconds=1.0),
        Step("press", "B"),       # 확인 중 누름 = 취소 → 안내 3초 → IDLE
        Step("expect", "B", label="B 흐름 끝(IDLE) → 끊긴 무전 풀림", pattern=r"ptt: resume released why=send-flow-idle", timeout=25),
        Step("expect", "B", label="B 처음부터 다시(resume=true)", timeout=15,
             pattern=r"ptt: playing from=\S+ voiceId={voiceId} dur=\d+ms manual=true resume=true"),
    ]
    if opts.shots:
        steps.append(Step("shot", "B", label="resumed"))
    steps.append(Step("expect", "B", label="B 다 들음(들음)", pattern=r"ptt: playback done voiceId={voiceId} resume=true", timeout=30))


def _persist(opts, steps):
    """A 무응답 보관(앱 내부 저장소) → am force-stop → 다시 시작 → ptt_unsent 에 같은 id·길이 그대로 → 정리(정차 중이면 지운다)"""
    _record(opts, steps)
    user = getattr(opts, "user_a", 0)
    steps += [
        Step("sleep", seconds=opts.confirm_delay_s),
        Step("bcast", "A", "ptt_answer", {"answer": "none"}),
        Step("expect", "A", label="무응답 → 보관(앱 내부 저장소)", pattern=r"ptt: kept unsent reason=NO_ANSWER n=\d+ persisted=yes", timeout=15),
        Step("bcast", "A", "ptt_unsent"),
        Step("expect", "A", label="재시작 전 목록(persisted = n)", timeout=8,
             pattern=r"demo: ptt_unsent n=(?P<unsentN>[1-9]\d*) persisted=(?P=unsentN) \[(?P<unsentId>u[\w-]+) dur=(?P<unsentDur>\d+)ms .*?stored=yes"),
        Step("shell", "A", f"am force-stop {PKG}", label="앱 강제 종료(am force-stop)"),
        Step("sleep", seconds=2.0),
        Step("shell", "A", f"pidof {PKG}", label="프로세스 없음(pidof 빈 출력)", pattern=r"^\s*$"),
        Step("mark", "A", label="여기부터 새 프로세스 로그만"),
        Step("shell", "A", f"am start --user {user} -n {PKG}/.MainActivity", label="앱 다시 시작"),
        Step("expect", "A", label="새 프로세스 시작", pattern=r"car app started", timeout=30),
        Step("expect", "A", label="저장소에서 복원(같은 그룹)", pattern=r"ptt: unsent restored kept=[1-9]\d* dropped=0", timeout=40),
        Step("sleep", seconds=1.0),
        Step("bcast", "A", "ptt_unsent"),
        Step("expect", "A", label="재시작 뒤 목록 그대로(같은 id·길이, 저장소)", timeout=10,
             pattern=r"demo: ptt_unsent n={unsentN} persisted={unsentN} \[{unsentId} dur={unsentDur}ms .*?stored=yes"),
    ]
    if opts.shots:
        steps.append(Step("shot", "A", label="restored_dashboard"))
    steps += [
        # 정리 — 정차 중일 때만 지워진다(주행 중이면 deleted=false 로 남는다)
        Step("bcast", "A", "ptt_unsent", {"op": "delete", "id": "{unsentId}"}, always=True),
        Step("note", "A", label="동행 화면이 앞에 있다 — 다음 시나리오 전에 순정 내비를 다시 앞으로 둔다", always=True),
    ]


def _received_on_b(steps, opts):
    steps += [
        Step("expect", "B", label="B 받음", pattern=r"ptt: voice received voiceId={voiceId}", timeout=opts.b_timeout),
        Step("expect", "B", label="B 재생", pattern=r"ptt: playing from=\S+ voiceId={voiceId}", timeout=opts.b_timeout),
    ]
    if opts.shots:
        steps.append(Step("shot", "B", label="playing"))
    steps.append(Step("expect", "B", label="B 재생 끝", pattern=r"ptt: playback done voiceId={voiceId}", timeout=opts.b_timeout))


def build_scenario(name: str, opts) -> Scenario:
    """시나리오 이름 → 단계 목록. opts: file(name)→기기 경로, answer_wav, record_s, confirm_delay_s, b_timeout, shots"""
    steps: list[Step] = []
    if name in ("yes", "no"):
        word = "YES" if name == "yes" else "NO"
        wav = ("yes_ne_bonae" if name == "yes" else "no_aniyo_chwiso") if opts.answer_wav else None
        _record(opts, steps, answer_wav=wav)
        if opts.shots:
            steps.append(Step("shot", "A", label="confirm"))
        if not wav:
            steps += [Step("sleep", seconds=opts.confirm_delay_s), Step("bcast", "A", "ptt_answer", {"answer": name})]
        steps.append(Step("expect", "A", label=f"답 {word}" + (" (Vosk WAV)" if wav else " (주입)"),
                          pattern=(r"ptt: recognizer=VOSK answer=" if wav else r"ptt: recognizer=\w+ answer=") + word, timeout=15))
        if name == "yes":
            steps.append(Step("expect", "A", label="보냄", pattern=r"ptt: sent voiceId=(?P<voiceId>\S+) to=(?P<to>\d+)", timeout=20))
            if opts.shots:
                steps.append(Step("shot", "A", label="sent"))
            _received_on_b(steps, opts)
            title = "네 → 보냄 → 차 B 재생" + (" (Vosk 가 WAV '네, 보내' 를 인식)" if wav else "")
        else:
            steps += [
                Step("expect", "A", label="취소", pattern=r"ptt: cancelled why=(answer-no|injected-no)", timeout=10),
                Step("absent", "B", label="B 에 아무것도 안 감", pattern=r"ptt: voice received", seconds=6),
            ]
            title = "아니 → 취소(버림)" + (" (Vosk 가 WAV '아니요, 취소' 를 인식)" if wav else "")
        return Scenario(name, title, steps)
    if name == "none":
        _record(opts, steps)
        steps += [
            Step("sleep", seconds=opts.confirm_delay_s),
            Step("bcast", "A", "ptt_answer", {"answer": "none"}),
            Step("expect", "A", label="무응답 → 보관", pattern=r"ptt: kept unsent reason=NO_ANSWER n=\d+", timeout=15),
            Step("bcast", "A", "ptt_unsent"),
            Step("expect", "A", label="보내지 않은 무전 목록", pattern=r"demo: ptt_unsent n=[1-9]", timeout=8),
            Step("absent", "B", label="B 에 아무것도 안 감", pattern=r"ptt: voice received", seconds=4),
        ]
        return Scenario(name, "무응답 → '보내지 않은 무전'에 보관", steps)
    if name == "resend":
        steps += [
            Step("bcast", "A", "ptt_unsent", {"op": "resend", "id": "first"}),
            Step("expect", "A", label="다시 보내기 시작(정차 중)", pattern=r"demo: ptt_unsent resend id=\S+ started=true", timeout=8),
            Step("expect", "A", label="보냄", pattern=r"ptt: sent voiceId=(?P<voiceId>\S+) to=(?P<to>\d+)", timeout=20),
        ]
        _received_on_b(steps, opts)
        return Scenario(name, "보내지 않은 무전 다시 보내기(정차 중) → 차 B 재생", steps)
    if name == "press_cancel":
        _record(opts, steps)
        steps += [
            Step("sleep", seconds=1.0),
            Step("press", "A"),
            Step("expect", "A", label="확인 중 누름 = 취소", pattern=r"ptt: cancelled why=press-during-(prompt|confirm|countdown-prompt|countdown)", timeout=10),
        ]
        return Scenario(name, "확인 중 무전 버튼 → 취소", steps)
    if name == "countdown":
        steps += [
            Step("bcast", "A", "ptt_countdown", {"on": True}),
            Step("expect", "A", label="인식기 끔(카운트다운 강제)", pattern=r"demo: ptt_countdown on=true", timeout=8),
        ]
        _record(opts, steps)
        steps += [Step("expect", "A", label="카운트다운 모드", pattern=r"ptt: confirm mode=countdown", timeout=8)]
        if opts.shots:
            steps.append(Step("shot", "A", label="countdown"))
        steps += [
            Step("expect", "A", label="5초 뒤 자동 보냄", pattern=r"ptt: countdown elapsed", timeout=20),
            Step("expect", "A", label="보냄", pattern=r"ptt: sent voiceId=(?P<voiceId>\S+) to=(?P<to>\d+)", timeout=20),
        ]
        _received_on_b(steps, opts)
        steps.append(Step("bcast", "A", "ptt_countdown", {"on": False}, always=True))
        return Scenario(name, "인식기 없음 → 카운트다운 모드 5초 → 자동 보냄 → 차 B 재생", steps)
    if name == "interrupt":
        _interrupt(opts, steps)
        return Scenario(name, "차 B 재생 중 무전 키 → 끊김(다 못 들음) → B 흐름 끝 → 처음부터 다시 → 다 들음", steps)
    if name == "persist":
        _persist(opts, steps)
        return Scenario(name, "무응답 보관 → 앱 강제 종료·재시작 → 보내지 않은 무전 그대로(앱 내부 저장소)", steps)
    raise ValueError(f"모르는 시나리오 {name} (가능: {', '.join(SCENARIOS)})")


def fill(pattern: str, captures: dict) -> str | None:
    """패턴의 {이름} 을 앞 단계에서 잡은 값(정규식 이스케이프)으로 — 모르는 이름이 있으면 None"""
    names = re.findall(r"\{(\w+)\}", pattern)
    if any(n not in captures for n in names):
        return None
    out = pattern
    for n in names:
        out = out.replace("{" + n + "}", re.escape(captures[n]))
    return out


def fill_extras(extras: dict, captures: dict) -> dict | None:
    """브로드캐스트 값의 {이름} 을 잡은 값 **원문**으로(이스케이프 없음) — 모르는 이름이 있으면 None. 문자열이 아닌 값은 그대로"""
    out = {}
    for k, v in extras.items():
        if isinstance(v, str):
            names = re.findall(r"\{(\w+)\}", v)
            if any(n not in captures for n in names):
                return None
            for n in names:
                v = v.replace("{" + n + "}", captures[n])
        out[k] = v
    return out


# ---------------------------------------------------------------- 기기
def current_user(serial: str) -> int:
    out = sh(serial, "am get-current-user").strip()
    return int(out) if out.isdigit() else 0


class DeviceFiles:
    """앱 files 아래에 파일을 넣는다 — run-as(기본) 또는 /data/local/tmp(대체, 앱에는 절대 경로를 준다)"""

    def __init__(self, serial: str):
        self.serial = serial
        self.user = current_user(serial)
        probe = sh(serial, run_as(self.user, "pwd"))
        self.mode = "run-as" if "/" + PKG in probe and "run-as:" not in probe else "tmp"
        self.probe = probe.strip()

    def path(self, rel: str) -> str:
        """앱에 넘길 경로 — run-as 면 files 기준 상대, 아니면 /data/local/tmp 절대"""
        return rel if self.mode == "run-as" else f"{TMP_ROOT}/{rel}"

    def exists(self, rel: str) -> bool:
        if self.mode == "run-as":
            return "DH_YES" in sh(self.serial, run_as(self.user, f"[ -e files/{rel} ] && echo DH_YES"))
        return "DH_YES" in sh(self.serial, f"[ -e {TMP_ROOT}/{rel} ] && echo DH_YES")

    def put_tree(self, dest_rel: str, entries, timeout: int = 900) -> bool:
        """entries: (arcname, size, opener) — dest_rel 폴더를 통째로 바꾼다"""
        entries = list(entries)
        if self.mode == "run-as":
            cmd = run_as(self.user, install_cmd(dest_rel))
            try:
                proc = subprocess.Popen([ADB, "-s", self.serial, "exec-in", cmd], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            except OSError as e:
                print(f"  adb 실행 실패({ADB}): {type(e).__name__}: {e}")
                return False
            try:
                write_tar(proc.stdin, entries)
                proc.stdin.close()
                out, err = proc.communicate(timeout=timeout)
            except Exception as e:
                proc.kill()
                print(f"  run-as 전송 실패: {type(e).__name__}: {e}")
                return False
            text = (out or b"").decode("utf-8", "replace") + (err or b"").decode("utf-8", "replace")
            if "DH_OK" not in text:
                print(f"  run-as 전송 실패: {text.strip()[:200]}")
                return False
            return True
        # 대체: PC 임시 폴더에 풀어 adb push
        with tempfile.TemporaryDirectory() as tmp:
            for arc, _size, opener in entries:
                dst = os.path.join(tmp, *arc.split("/"))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                with opener() as src, open(dst, "wb") as f:
                    while True:
                        b = src.read(1 << 20)
                        if not b:
                            break
                        f.write(b)
            sh(self.serial, f"rm -rf {TMP_ROOT}/{dest_rel} && mkdir -p {TMP_ROOT} && chmod 755 {TMP_ROOT}")
            out = adb(self.serial, "push", tmp, f"{TMP_ROOT}/{dest_rel}", timeout=timeout)
            sh(self.serial, f"chmod -R a+rX {TMP_ROOT}")
            return "error" not in out.lower()


def write_tar(stream, entries):
    """(arcname, size, opener) → tar 스트림(폴더 항목을 먼저)"""
    now = time.time()
    with tarfile.open(fileobj=stream, mode="w|") as tf:
        for d in tar_dirs([a for a, _, _ in entries]):
            ti = tarfile.TarInfo(d)
            ti.type, ti.mode, ti.mtime = tarfile.DIRTYPE, 0o755, now
            tf.addfile(ti)
        for arc, size, opener in entries:
            ti = tarfile.TarInfo(arc)
            ti.size, ti.mode, ti.mtime = size, 0o644, now
            with opener() as f:
                tf.addfile(ti, f)


def dir_entries(root: str):
    for base, _dirs, files in os.walk(root):
        for fn in sorted(files):
            full = os.path.join(base, fn)
            arc = os.path.relpath(full, root).replace(os.sep, "/")
            yield arc, os.path.getsize(full), (lambda p=full: open(p, "rb"))


def zip_entries(zf: zipfile.ZipFile, prefix: str):
    for info in zf.infolist():
        if info.is_dir() or not info.filename.startswith(prefix):
            continue
        arc = info.filename[len(prefix):]
        if arc:
            yield arc, info.file_size, (lambda i=info: zf.open(i))


def ptt_status(serial: str) -> tuple[str | None, dict]:
    w = LogWatcher(serial)
    time.sleep(1.1)   # LogWatcher 는 초 단위 — 직전 줄과 섞이지 않게
    broadcast(serial, "ptt_status")
    line = w.wait(r"demo: ptt_status", 8, poll_s=1.0)
    return line, (parse_status(line) if line else {})


def make_audio(out_dir: str) -> bool:
    """Windows 한국어 음성으로 테스트 WAV 를 만든다 — 성공하면 True"""
    if os.name != "nt":
        print("  테스트 음성 만들기는 Windows(System.Speech) 에서만 — 다른 OS 는 --audio-dir 로 WAV 폴더를 준다")
        return False
    os.makedirs(out_dir, exist_ok=True)
    script = os.path.join(out_dir, "make_ptt_audio.ps1")
    with open(script, "w", encoding="utf-8-sig") as f:   # BOM — Windows PowerShell 5.1 이 한글을 UTF-8 로 읽게
        f.write(ps_script(out_dir))
    r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script], capture_output=True, text=True, encoding="utf-8", errors="replace")
    print(f"  {r.stdout.strip() or r.stderr.strip()[:200]}")
    if r.returncode == 3:
        print("  한국어 음성이 없다 — Windows 설정 > 시간 및 언어 > 음성 에서 한국어 음성을 추가하거나 --audio-dir 로 WAV 폴더를 준다")
        return False
    bad = [(n, wav_problem(os.path.join(out_dir, n + ".wav"))) for n, _ in PHRASES]
    for n, p in bad:
        print(f"  {'OK  ' if p is None else 'FAIL'} {n}.wav{'' if p is None else ' — ' + p}")
    return all(p is None for _, p in bad)


def local_audio(dir_: str) -> list[tuple[str, str | None]]:
    return [(n, wav_problem(os.path.join(dir_, n + ".wav"))) for n, _ in PHRASES]


# ---------------------------------------------------------------- 명령
def cmd_audio(a) -> int:
    print(f"== 테스트 음성 → {a.out}")
    return 0 if make_audio(a.out) else 1


def ensure_audio_local(audio_dir: str) -> bool:
    probs = local_audio(audio_dir)
    if all(p is None for _, p in probs):
        return True
    if audio_dir == DEFAULT_AUDIO_DIR:
        print(f"  테스트 음성이 없어 만든다 → {audio_dir}")
        return make_audio(audio_dir)
    for n, p in probs:
        if p:
            print(f"  FAIL {n}.wav — {p}")
    return False


def push_audio(df: DeviceFiles, audio_dir: str) -> bool:
    entries = []
    for n, _ in PHRASES:
        p = os.path.join(audio_dir, n + ".wav")
        entries.append((f"{n}.wav", os.path.getsize(p), (lambda p=p: open(p, "rb"))))
    return df.put_tree(APP_AUDIO_DIR, entries)


def push_model(df: DeviceFiles, serial: str, a) -> bool:
    if a.model_zip:
        with zipfile.ZipFile(a.model_zip) as zf:
            prefix = model_prefix_in_zip(zf.namelist())
            if prefix is None:
                print("  zip 안에 am/·conf/ 가 없다 — vosk-model-small-ko-0.22.zip 인지 확인")
                return False
            print(f"  zip 접두사 '{prefix}' → files/{MODEL_DIR} ({df.mode})")
            ok = df.put_tree(MODEL_DIR, zip_entries(zf, prefix))
    else:
        root = model_root_in_dir(a.model_dir)
        if root is None:
            print(f"  {a.model_dir} 에 am/·conf/ 가 없다")
            return False
        print(f"  {root} → files/{MODEL_DIR} ({df.mode})")
        ok = df.put_tree(MODEL_DIR, dir_entries(root))
    if ok and df.mode == "tmp":
        # run-as 를 못 쓰면 앱이 /data/local/tmp 에서 files 로 복사한다(253 MB — 1~2분)
        w = LogWatcher(serial)
        broadcast(serial, "vosk_install", dir=df.path(MODEL_DIR))
        ok = bool(w.wait(r"demo: vosk_install done", 240, poll_s=3.0))
    return ok


def cmd_setup(a) -> int:
    cars = [("A", a.car_a)] + ([("B", a.car_b)] if a.car_b else [])
    rows = []
    if not ensure_audio_local(a.audio_dir):
        print("테스트 음성을 준비하지 못했다.")
        return 2
    for name, serial in cars:
        df = DeviceFiles(serial)
        print(f"== 차 {name} {serial} (사용자 {df.user}, 넣는 방법 {df.mode}{'' if df.mode == 'run-as' else ' — run-as 실패: ' + df.probe[:80]})")
        rows.append((f"{name} 테스트 음성 → files/{APP_AUDIO_DIR}", push_audio(df, a.audio_dir)))
        if (a.model_zip or a.model_dir) and (a.model_on == "both" or a.model_on.upper() == name):
            rows.append((f"{name} Vosk 모델 → files/{MODEL_DIR}", push_model(df, serial, a)))
        if a.grant_mic:
            sh(serial, f"pm grant --user {df.user} {PKG} android.permission.RECORD_AUDIO")
            # 포그라운드 서비스 타입(microphone)은 앱이 앞일 때만 붙는다 — 한 번 열어 MainActivity.onResume 이 맞추게 한다
            sh(serial, f"am start --user {df.user} -n {PKG}/.MainActivity")
            time.sleep(3)
        _, st = ptt_status(serial)
        rows.append((f"{name} 상태 vosk={st.get('vosk', '?')} mic={st.get('mic', '?')} fgs={st.get('fgs', '?')}", bool(st)))
        if a.grant_mic and st and fgs_has_mic(st) is False:
            # 실패로 치지 않는다 — 그룹이 아직 없으면 서비스도 없다(none). 그룹이 있는데도 없으면 동행 화면을 다시 열어 본다
            print(f"  참고: 차 {name} 그룹 서비스에 microphone 타입이 없다(fgs={st.get('fgs')}) — 그룹이 있으면 동행 화면을 한 번 더 연다")
    if a.grant_mic:
        print("  (동행 화면을 앞으로 열었다 — 시나리오 전에 순정 내비를 다시 앞으로 둔다)")
    print("\n== setup ==")
    for k, ok in rows:
        print(f"  {'OK  ' if ok else 'FAIL'} {k}")
    return 0 if all(ok for _, ok in rows) else 1


def cmd_status(a) -> int:
    for name, serial in [("A", a.car_a)] + ([("B", a.car_b)] if a.car_b else []):
        line, st = ptt_status(serial)
        print(f"== 차 {name} {serial}")
        print(f"  {line.split('demo: ', 1)[-1] if line else '응답 없음(debug 빌드·앱 실행 확인)'}")
        if st:
            print(f"  → stage={st.get('stage')} group={st.get('group')} session={st.get('session')} sessionState={st.get('sessionState')} "
                  f"handleButton={st.get('handleButton')} mic={st.get('mic')} unsent={st.get('unsent')} persisted={st.get('persisted')} "
                  f"resumeWaiting={st.get('resumeWaiting')} vosk={st.get('vosk')} recognizers={st.get('recognizers')}")
    return 0


class Opts:
    def __init__(self, a, df: DeviceFiles | None):
        self.df = df
        self.answer_wav = a.answer_wav
        self.record_s = a.record_s
        self.confirm_delay_s = a.confirm_delay_s
        self.b_timeout = a.b_timeout
        self.shots = a.shots
        # 차 A 의 현재 사용자(Pleos AVD = 10) — persist 의 `am start --user`
        self.user_a = df.user if df else 0

    def file(self, name: str) -> str:
        rel = f"{APP_AUDIO_DIR}/{name}.wav"
        return self.df.path(rel) if self.df else rel


class Runner:
    def __init__(self, a):
        self.a = a
        self.cars = {"A": a.car_a, "B": a.car_b}
        self.out = os.path.join(a.out, dt.datetime.now().strftime("%Y%m%d_%H%M%S") + "_ptt")
        self.results = []   # (시나리오, 확인, 결과 OK/MISS/SKIP, 관측)
        self.watch = {}

    def say(self, msg):
        print(f"[{dt.datetime.now():%H:%M:%S}] {msg}", flush=True)

    def preflight(self) -> DeviceFiles | None:
        rows = []
        devices = adb_devices()
        if devices is None:
            rows.append((f"adb 실행 파일 없음({ADB}) — Android SDK platform-tools 를 깔거나 환경 변수 ADB 로 경로를 준다", False))
        for name, s in self.cars.items():
            rows.append((f"{name} {s} 연결", device_connected(devices, s)))
            rows.append((f"{name} 앱 설치", "package:" in sh(s, f"pm path {PKG}")))
        st = {}
        for name, s in self.cars.items():
            line, st[name] = ptt_status(s)
            rows.append((f"{name} 디버그 리시버 ptt_status 응답(debug 빌드)", bool(line)))
            if not line:
                continue
            rows.append((f"{name} 그룹 있음(trigger group=true)", st[name].get("group") == "true"))
            rows.append((f"{name} 무전 대기(stage=IDLE, 지금 {st[name].get('stage')})", st[name].get("stage") == "IDLE"))
        sa = st.get("A", {})
        # 키를 누르는 차 — 기본은 A, interrupt 는 B 에서도 누른다
        key_cars = ["A"] + (["B"] if "interrupt" in scenario_names(getattr(self.a, "scenarios", "")) else [])
        for name in key_cars:
            sx = st.get(name, {})
            if not sx:
                continue
            if sx.get("handleButton") != "true":
                if self.a.dry_run:
                    rows.append((f"{name} 설정 '핸들 버튼으로 무전' 꺼짐 — 실행 때 켠다(dry-run 이라 그대로)", None))
                else:
                    broadcast(self.cars[name], "ptt_handle", on=True)
                    rows.append((f"{name} 설정 '핸들 버튼으로 무전' 켬(꺼져 있어 켰다)", None))
            if self.a.trigger == "key":
                rows.append((f"{name} MediaSession 활성(session={sx.get('session')}, sessionState={sx.get('sessionState', '?')}, otherMedia={sx.get('otherMedia')}) — 아니면 내비가 앞일 때 키가 안 온다",
                             True if sx.get("session") == "true" else None))
        # interrupt 는 B 에서 실마이크로 녹음한다(주입 없음) — microphone 타입이 없으면 녹음 대신 안내로 끝난다(알리기만)
        if "B" in key_cars and st.get("B"):
            row = mic_gate_row("B", st["B"], injected=False)
            if row:
                rows.append(row)
        if sa:
            rows.append((f"A 마이크 권한(mic={sa.get('mic')}) — 주입 녹음은 없어도 된다", True if sa.get("mic") == "true" else None))
            row = mic_gate_row("A", sa)
            if row:
                rows.append(row)
            if self.a.answer_wav:
                rows.append((f"A Vosk 모델(vosk={sa.get('vosk')}) — --answer-wav 에 필요(setup --model-zip)", str(sa.get("vosk", "")).startswith("present")))
            rows.append((f"A 인식기 {sa.get('recognizers')} — 하나도 ready 가 아니면 확인은 카운트다운 모드(답 주입은 그대로 받는다)", None if not recognizer_ready(sa) else True))
        # 테스트 음성(로컬 → 차 A files)
        df = None
        if self.cars["A"] and sa:
            df = DeviceFiles(self.cars["A"])
            rows.append((f"A 파일 넣는 방법 {df.mode} (사용자 {df.user})", True if df.mode == "run-as" else None))
            if all(df.exists(f"{APP_AUDIO_DIR}/{n}.wav") for n, _ in PHRASES) and not self.a.repush:
                rows.append((f"A 테스트 음성 files/{APP_AUDIO_DIR}", True))
            elif self.a.dry_run:
                rows.append((f"A 테스트 음성 files/{APP_AUDIO_DIR} 없음 — dry-run 이라 넣지 않았다(실행 때 넣는다)", None))
            else:
                ok = ensure_audio_local(self.a.audio_dir) and push_audio(df, self.a.audio_dir)
                rows.append((f"A 테스트 음성 넣기 → files/{APP_AUDIO_DIR}", ok))
        if self.a.key == "SEARCH" and self.a.trigger == "key":
            rows.append(("--key SEARCH: 순정 내비가 앞이면 Pleos 전역 검색이 열린다 — 동행 화면을 앞에 두고만(docs/10 R14)", None))
        print("\n== preflight ==")
        bad = 0
        for k, ok in rows:
            print(f"  {'WARN' if ok is None else 'OK  ' if ok else 'FAIL'} {k}")
            bad += 0 if ok or ok is None else 1
        if bad and not self.a.skip_preflight:
            print("preflight 실패 항목이 있다(--skip-preflight 로 무시).")
            return None
        return df

    def press(self, car):
        s = self.cars[car]
        if self.a.trigger == "debug":
            broadcast(s, "ptt_press")
        else:
            sh(s, f"input keyevent KEYCODE_{self.a.key}")

    def shot(self, car, label, sc):
        os.makedirs(self.out, exist_ok=True)
        path = os.path.join(self.out, f"{sc}_{car}_{label}.png")
        try:
            r = subprocess.run([ADB, "-s", self.cars[car], "exec-out", "screencap", "-p"], capture_output=True, timeout=30)
        except (OSError, subprocess.SubprocessError) as e:
            self.say(f"  캡처 실패 {type(e).__name__}: {e}")
            return
        with open(path, "wb") as f:
            f.write(r.stdout)
        self.say(f"  캡처 {os.path.basename(path)}")

    def run_scenario(self, sc: Scenario):
        print(f"\n== 시나리오 {sc.name}: {sc.title}")
        base = {car: LogWatcher(s) for car, s in self.cars.items()}   # 로그 파일용(시나리오 시작부터 전부)
        w = dict(base)                                                 # 확인용 — mark 가 차마다 새로 건다
        time.sleep(1.1)   # 시작 초의 이전 줄과 섞이지 않게
        captures, failed = {}, False
        for st in sc.steps:
            if failed and not st.always and st.kind in ("bcast", "press", "sleep", "shot", "shell", "mark"):
                if st.kind == "shell" and st.pattern:
                    self.record(sc.name, st, "SKIP", "(앞 단계 실패)")
                continue
            if st.kind == "bcast":
                extras = fill_extras(st.extras, captures)
                if extras is None:
                    self.say(f"  건너뜀 {st.car}: {st.action} (앞 단계 값 없음)")
                    continue
                broadcast(self.cars[st.car], st.action, **extras)
            elif st.kind == "press":
                self.press(st.car)
            elif st.kind == "sleep":
                time.sleep(st.seconds)
            elif st.kind == "shot":
                self.shot(st.car, st.label, sc.name)
            elif st.kind == "note":
                self.say(f"  참고: {st.label}")
            elif st.kind == "mark":
                # 이 뒤 확인은 지금부터 생긴 줄만 본다(앱을 다시 띄우기 전 같은 줄과 섞이지 않게)
                w[st.car] = LogWatcher(self.cars[st.car])
                time.sleep(1.1)
            elif st.kind == "shell":
                out = sh(self.cars[st.car], st.action)
                if st.pattern:
                    ok = re.search(st.pattern, out) is not None
                    self.record(sc.name, st, "OK" if ok else "MISS", out.strip()[:120] or "(빈 출력)")
                    failed = failed or not ok
                else:
                    self.say(f"  {st.car}$ {st.action} → {out.strip()[:80] or '(빈 출력)'}")
            elif st.kind in ("expect", "absent"):
                pat = fill(st.pattern, captures)
                if pat is None:
                    self.record(sc.name, st, "SKIP", "(앞 단계 값 없음)")
                    continue
                if st.kind == "absent":
                    if failed:
                        self.record(sc.name, st, "SKIP", "(앞 단계 실패)")
                        continue
                    time.sleep(st.seconds)
                    hits = w[st.car].find(pat)
                    self.record(sc.name, st, "OK" if not hits else "MISS", hits[-1] if hits else "(없음 — 기대대로)")
                    continue
                if failed:
                    self.record(sc.name, st, "SKIP", "(앞 단계 실패)")
                    continue
                hit = w[st.car].wait(pat, st.timeout, poll_s=st.poll)
                if hit:
                    m = re.search(pat, hit)
                    captures.update({k: v for k, v in (m.groupdict() if m else {}).items() if v})
                    self.record(sc.name, st, "OK", hit)
                else:
                    failed = True
                    self.record(sc.name, st, "MISS", "(못 봄)")
        for car, ww in base.items():
            self.watch.setdefault(car, []).extend(ww.new())
        time.sleep(self.a.gap_s)   # 서버 빈도 제한(차량당 3초) + 안내 3초가 지나도록

    def record(self, sc, st: Step, verdict, observed):
        self.results.append((sc, f"{st.car}: {st.label}", verdict, observed))
        tail = observed.split("DongHaeng", 1)[-1][-110:] if isinstance(observed, str) else observed
        self.say(f"{verdict:4} {st.car}: {st.label} → {tail}")

    def finish(self) -> bool:
        os.makedirs(self.out, exist_ok=True)
        for car, lines in self.watch.items():
            with open(os.path.join(self.out, f"car{car}.log"), "w", encoding="utf-8") as f:
                f.write("\n".join(lines))
        ok = sum(1 for r in self.results if r[2] == "OK")
        miss = sum(1 for r in self.results if r[2] == "MISS")
        lines = [f"# 무전(F16) 확인 {dt.datetime.now():%Y-%m-%d %H:%M}", "",
                 f"A={self.cars['A']} B={self.cars['B']} trigger={self.a.trigger}/{self.a.key} answer={'wav(Vosk)' if self.a.answer_wav else 'inject'}  OK {ok} / MISS {miss} / 전체 {len(self.results)}", "",
                 "| 시나리오 | 확인 | 결과 | 관측 |", "|---|---|---|---|"]
        for sc, check, verdict, obs in self.results:
            o = obs.split("DongHaeng", 1)[-1] if isinstance(obs, str) else str(obs)
            lines.append(f"| {sc} | {check} | {verdict} | {o[-120:].replace('|', '/')} |")
        with open(os.path.join(self.out, "summary.md"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        print("\n" + "\n".join(lines[2:]))
        print(f"\n로그·요약: {self.out}")
        return miss == 0


def cmd_run(a) -> int:
    names = scenario_names(a.scenarios)
    for n in names:
        if n not in SCENARIOS:
            print(f"모르는 시나리오 {n} (가능: {', '.join(SCENARIOS)})")
            return 2
    r = Runner(a)
    df = r.preflight()
    if df is None and not a.skip_preflight:
        return 2
    if a.dry_run:
        opts = Opts(a, df)
        for n in names:
            sc = build_scenario(n, opts)
            print(f"\n[{n}] {sc.title}")
            for st in sc.steps:
                print(f"   {st.kind:6} {st.car} {st.action or st.label} {st.extras or ''}{(' /' + st.pattern + '/') if st.pattern else ''}")
        print("\ndry-run: 여기까지")
        return 0
    opts = Opts(a, df)
    for n in names:
        r.run_scenario(build_scenario(n, opts))
    return 0 if r.finish() else 1


def main():
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("audio", help="테스트 WAV 만들기(Windows 한국어 음성)")
    p.add_argument("--out", default=DEFAULT_AUDIO_DIR)
    p.set_defaults(fn=cmd_audio)

    def cars(p, b_required=False):
        p.add_argument("--car-a", default="emulator-5556", help="보내는 차(Pleos AVD, pleos flavor)")
        p.add_argument("--car-b", default="emulator-5558" if b_required else None, help="받는 차(Pleos_B AVD, fake flavor)")

    p = sub.add_parser("setup", help="기기에 테스트 음성·Vosk 모델 넣기")
    cars(p)
    p.add_argument("--audio-dir", default=DEFAULT_AUDIO_DIR, help="yes_ne.wav 등 5개가 있는 폴더(없으면 기본 폴더에 만든다)")
    g = p.add_mutually_exclusive_group()
    g.add_argument("--model-zip", help="vosk-model-small-ko-0.22.zip")
    g.add_argument("--model-dir", help="풀린 모델 폴더(am/·conf/ 가 있는 폴더 또는 그 위)")
    p.add_argument("--model-on", default="a", choices=["a", "b", "both"], help="모델을 넣을 차(기본 A — 보내는 차만 '네/아니'를 듣는다)")
    p.add_argument("--grant-mic", action="store_true", help="pm grant RECORD_AUDIO(현재 사용자)")
    p.set_defaults(fn=cmd_setup)

    p = sub.add_parser("run", help="2대 시나리오 실행 + 확인 표")
    cars(p, b_required=True)
    p.add_argument("--scenarios", default=",".join(DEFAULT_SCENARIOS),
                   help=f"쉼표로(기본 {','.join(DEFAULT_SCENARIOS)} — 그 밖에 interrupt·persist, 전체 {','.join(SCENARIOS)})")
    p.add_argument("--answer-wav", action="store_true", help="네/아니 를 WAV 로 인식기(Vosk)에 흘린다(모델 필요). 기본은 판정 직접 주입")
    p.add_argument("--trigger", default="key", choices=["key", "debug"], help="key = adb input keyevent(핸들 키와 같은 경로), debug = 리시버 ptt_press")
    p.add_argument("--key", default="HEADSETHOOK", choices=["HEADSETHOOK", "MEDIA_PLAY_PAUSE", "MEDIA_PLAY", "SEARCH"])
    p.add_argument("--audio-dir", default=DEFAULT_AUDIO_DIR)
    p.add_argument("--repush", action="store_true", help="테스트 음성을 다시 넣는다")
    p.add_argument("--record-s", type=float, default=2.5, help="녹음 시작 뒤 다시 누르기까지(초)")
    p.add_argument("--confirm-delay-s", type=float, default=2.0, help="확인 시작 뒤 답을 넣기까지(초 — 카운트다운이 보이게)")
    p.add_argument("--b-timeout", type=float, default=30.0)
    p.add_argument("--gap-s", type=float, default=4.0, help="시나리오 사이(서버 빈도 제한 3초)")
    p.add_argument("--shots", action="store_true", help="녹음·확인·보냄(A)·재생(B) 순간 화면 캡처")
    p.add_argument("--out", default=os.path.join(HERE, "out"))
    p.add_argument("--dry-run", action="store_true", help="preflight + 단계 목록만")
    p.add_argument("--skip-preflight", action="store_true")
    p.set_defaults(fn=cmd_run)

    p = sub.add_parser("status", help="두 차의 ptt_status 한 줄")
    cars(p)
    p.set_defaults(fn=cmd_status)

    a = ap.parse_args()
    sys.exit(a.fn(a))


if __name__ == "__main__":
    main()
