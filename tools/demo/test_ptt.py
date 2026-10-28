#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/demo/ptt.py 단위 테스트 (표준 라이브러리 unittest, adb·기기 없이 돈다).

  python -m unittest discover -s tools/demo -p "test_*.py" -v

WAV 헤더·모델 폴더 찾기·기기 명령 조립(run-as·tar 바꿔치기)·ptt_status 해석·시나리오 단계·패턴 채우기·tar 스트림을 본다.
기기에 실제로 넣고 두 대로 돌리는 부분은 통합 담당이 `ptt.py run` 으로 확인한다.
"""
import argparse
import contextlib
import io
import os
import re
import struct
import sys
import tarfile
import tempfile
import unittest
import zipfile
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ptt  # noqa: E402


def wav_bytes(rate=16000, channels=1, bits=16, ms=1000, extra_chunk=False) -> bytes:
    data = b"\x00\x00" * (rate * channels * ms // 1000) if bits == 16 else b"\x00" * (rate * channels * ms // 1000)
    fmt = struct.pack("<HHIIHH", 1, channels, rate, rate * channels * bits // 8, channels * bits // 8, bits)
    chunks = b"fmt " + struct.pack("<I", len(fmt)) + fmt
    if extra_chunk:
        info = b"INFOtest"
        chunks += b"LIST" + struct.pack("<I", len(info)) + info
    chunks += b"data" + struct.pack("<I", len(data)) + data
    return b"RIFF" + struct.pack("<I", 4 + len(chunks)) + b"WAVE" + chunks


class Opts:
    def __init__(self, **kw):
        self.answer_wav = False
        self.record_s = 2.5
        self.confirm_delay_s = 2.0
        self.b_timeout = 30.0
        self.shots = False
        self.user_a = 0
        self.__dict__.update(kw)

    def file(self, name):
        return f"{ptt.APP_AUDIO_DIR}/{name}.wav"


STATUS = (
    "10-28 15:01:02.000 I/DongHaeng( 123): demo: ptt_status stage=IDLE remaining=0s listening=false countdown=false notice=- mic=true unsent=2 "
    "last=YES/INJECTED recognizers=[PLEOS_STT=unavailable(no-caas) ANDROID_STT=failed(stt error=9) VOSK=ready] presses=3 rec=2 sent=1 cancelled=0 "
    "kept=1 short=0 sendFailed=0 countdown=0 ignored=0 | trigger group=true handleButton=true otherMedia=false session=true presses=3 consumed=2 "
    "last=MEDIA_SESSION/HEADSETHOOK | playback playing=- queued=0 held=false past=1[PLAYED=1] received=1 played=1 stale=0 own=0 notForMe=0 "
    "dlFail=0 playFail=0 | handleButton=true mic=true vosk=present(am,conf,graph,ivector)"
)


class WavTest(unittest.TestCase):

    def test_pcm16_mono_16k(self):
        info = ptt.wav_info(wav_bytes(ms=1500))
        self.assertEqual((16000, 1, 16), (info["rate"], info["channels"], info["bits"]))
        self.assertEqual(1500, info["duration_ms"])

    def test_skips_other_chunks(self):
        self.assertEqual(700, ptt.wav_info(wav_bytes(ms=700, extra_chunk=True))["duration_ms"])

    def test_not_wav(self):
        self.assertIsNone(ptt.wav_info(b"ID3\x03" + b"\x00" * 40))
        self.assertIsNone(ptt.wav_info(b"RIFF"))

    def test_problem_messages(self):
        with tempfile.TemporaryDirectory() as d:
            ok = os.path.join(d, "ok.wav")
            stereo = os.path.join(d, "st.wav")
            short = os.path.join(d, "short.wav")
            for path, data in ((ok, wav_bytes(ms=1200)), (stereo, wav_bytes(rate=44100, channels=2, ms=1200)), (short, wav_bytes(ms=100))):
                with open(path, "wb") as f:
                    f.write(data)
            self.assertIsNone(ptt.wav_problem(ok))
            self.assertIn("16 kHz", ptt.wav_problem(stereo))
            self.assertIn("짧다", ptt.wav_problem(short))
            self.assertEqual("파일 없음", ptt.wav_problem(os.path.join(d, "none.wav")))


class ModelLocateTest(unittest.TestCase):

    def test_zip_prefix(self):
        names = ["vosk-model-small-ko-0.22/", "vosk-model-small-ko-0.22/am/final.mdl", "vosk-model-small-ko-0.22/conf/model.conf",
                 "vosk-model-small-ko-0.22/graph/Gr.fst", "vosk-model-small-ko-0.22/README"]
        self.assertEqual("vosk-model-small-ko-0.22/", ptt.model_prefix_in_zip(names))
        self.assertEqual("", ptt.model_prefix_in_zip(["am/final.mdl", "conf/model.conf"]))
        self.assertIsNone(ptt.model_prefix_in_zip(["x/am/final.mdl", "x/README"]))

    def test_dir_root(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertIsNone(ptt.model_root_in_dir(d))
            inner = os.path.join(d, "vosk-model-small-ko-0.22")
            os.makedirs(os.path.join(inner, "am"))
            os.makedirs(os.path.join(inner, "conf"))
            self.assertEqual(inner, ptt.model_root_in_dir(d))
            self.assertEqual(inner, ptt.model_root_in_dir(inner))


class DeviceCommandTest(unittest.TestCase):

    def test_run_as_user_before_package(self):
        self.assertEqual("run-as --user 10 app.donghaeng.car sh -c 'pwd'", ptt.run_as(10, "pwd"))
        self.assertEqual("run-as app.donghaeng.car sh -c 'pwd'", ptt.run_as(0, "pwd"))

    def test_quote_inner_single_quotes(self):
        self.assertEqual("'a'\\''b'", ptt.sq("a'b"))

    def test_install_swaps_folder(self):
        c = ptt.install_cmd("vosk-ko-small")
        self.assertTrue(c.startswith("rm -rf files/vosk-ko-small.tmp && mkdir -p files/vosk-ko-small.tmp && tar -xf - -C files/vosk-ko-small.tmp"))
        self.assertTrue(c.endswith("mv files/vosk-ko-small.tmp files/vosk-ko-small && echo DH_OK"))

    def test_tar_dirs_parents_first(self):
        self.assertEqual(["am", "graph", "graph/phones"], ptt.tar_dirs(["am/final.mdl", "graph/phones/word_boundary.int", "graph/Gr.fst", "README"]))

    def test_tar_stream_roundtrip(self):
        files = {"am/final.mdl": b"abc", "conf/model.conf": b"--x\n", "README": b"r"}
        entries = [(k, len(v), (lambda v=v: io.BytesIO(v))) for k, v in files.items()]
        buf = io.BytesIO()
        ptt.write_tar(buf, entries)
        buf.seek(0)
        with tarfile.open(fileobj=buf, mode="r") as tf:
            names = tf.getnames()
            self.assertLess(names.index("am"), names.index("am/final.mdl"))
            self.assertEqual(b"--x\n", tf.extractfile("conf/model.conf").read())

    def test_zip_entries_strip_prefix(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("m/am/final.mdl", b"1")
            zf.writestr("m/conf/model.conf", b"22")
            zf.writestr("other/x", b"3")
        with zipfile.ZipFile(buf) as zf:
            got = sorted((a, s) for a, s, _ in ptt.zip_entries(zf, "m/"))
        self.assertEqual([("am/final.mdl", 1), ("conf/model.conf", 2)], got)

    def test_powershell_script_quotes_and_format(self):
        s = ptt.ps_script(r"C:\x\it's", [("t", "네, 보내")])
        self.assertIn("'C:\\x\\it''s\\t.wav'", s)
        self.assertIn("$s.Speak('네, 보내')", s)
        self.assertIn("SpeechAudioFormatInfo(16000", s)
        self.assertIn("*Heami*", s)
        self.assertIn("exit 3", s)


class StatusTest(unittest.TestCase):

    def test_parse(self):
        st = ptt.parse_status(STATUS)
        self.assertEqual("IDLE", st["stage"])
        self.assertEqual("2", st["unsent"])
        self.assertEqual("true", st["group"])
        self.assertEqual("true", st["session"])
        self.assertEqual("false", st["otherMedia"])
        self.assertEqual("present(am,conf,graph,ivector)", st["vosk"])
        self.assertEqual({"PLEOS_STT": "unavailable(no-caas)", "ANDROID_STT": "failed(stt error=9)", "VOSK": "ready"}, st["recognizers"])
        self.assertTrue(ptt.recognizer_ready(st))

    def test_no_ready_recognizer(self):
        st = ptt.parse_status(STATUS.replace("VOSK=ready", "VOSK=unavailable(no-model)").replace("vosk=present(am,conf,graph,ivector)", "vosk=absent"))
        self.assertFalse(ptt.recognizer_ready(st))
        self.assertEqual("absent", st["vosk"])

    def test_parse_new_fields(self):
        """F16 결정 반영 빌드 — 저장소 수(persisted)·세션 재생 상태(PAUSED)·끊긴 무전 대기(resumeWaiting)"""
        line = (STATUS.replace("unsent=2 ", "unsent=2 persisted=2 cycle=4/4 ")
                .replace("session=true ", "session=true sessionState=PAUSED ")
                .replace("held=false ", "held=false resumeWaiting=true "))
        st = ptt.parse_status(line)
        self.assertEqual("2", st["persisted"])
        self.assertEqual("PAUSED", st["sessionState"])
        self.assertEqual("true", st["session"])
        self.assertEqual("true", st["resumeWaiting"])
        self.assertEqual("2", st["unsent"])

    def test_fgs_microphone_type(self):
        st = ptt.parse_status(STATUS + " fgs=location+microphone")
        self.assertEqual("location+microphone", st["fgs"])
        self.assertTrue(ptt.fgs_has_mic(st))
        self.assertFalse(ptt.fgs_has_mic(ptt.parse_status(STATUS + " fgs=dataSync")))
        self.assertFalse(ptt.fgs_has_mic(ptt.parse_status(STATUS + " fgs=none")))
        self.assertIsNone(ptt.fgs_has_mic(ptt.parse_status(STATUS)), "옛 빌드는 fgs 를 안 낸다")


class PreflightWithoutAdbTest(unittest.TestCase):
    """adb 가 없는 PC 에서 `run --dry-run` 이 FileNotFoundError 트레이스백 대신 FAIL 행으로 끝난다"""

    def test_missing_adb_is_a_fail_row(self):
        a = argparse.Namespace(car_a="emulator-A", car_b="emulator-B", out=os.path.join(HERE, "out"), dry_run=True, trigger="key",
                               key="HEADSETHOOK", answer_wav=False, repush=False, skip_preflight=False, audio_dir=ptt.DEFAULT_AUDIO_DIR)
        buf = io.StringIO()
        with mock.patch.object(ptt, "adb_devices", return_value=None), \
                mock.patch.object(ptt, "sh", return_value="ERR FileNotFoundError"), \
                mock.patch.object(ptt, "ptt_status", return_value=(None, {})), \
                contextlib.redirect_stdout(buf):
            df = ptt.Runner(a).preflight()
        self.assertIsNone(df)
        out = buf.getvalue()
        self.assertIn("FAIL adb 실행 파일 없음", out)
        self.assertIn("FAIL A emulator-A 연결", out)


class ScenarioTest(unittest.TestCase):

    def kinds(self, sc):
        return [s.kind for s in sc.steps]

    def test_yes_injects_after_confirm_and_follows_voice_id_on_b(self):
        sc = ptt.build_scenario("yes", Opts())
        press = [i for i, s in enumerate(sc.steps) if s.kind == "press"]
        self.assertEqual(2, len(press), "녹음 시작·끝 두 번")
        confirm = next(i for i, s in enumerate(sc.steps) if "confirm mode" in s.pattern)
        answer = next(i for i, s in enumerate(sc.steps) if s.action == "ptt_answer")
        self.assertGreater(answer, confirm, "직접 답은 확인이 시작된 뒤(카운트다운이 보이게)")
        self.assertEqual({"answer": "yes"}, sc.steps[answer].extras)
        self.assertEqual({"file": "ptt_test/msg_rest.wav"}, sc.steps[0].extras)
        b = [s for s in sc.steps if s.car == "B"]
        self.assertTrue(all("{voiceId}" in s.pattern for s in b))
        self.assertIn("playback done", b[-1].pattern)

    def test_yes_wav_is_armed_before_listening(self):
        sc = ptt.build_scenario("yes", Opts(answer_wav=True))
        answer = next(i for i, s in enumerate(sc.steps) if s.action == "ptt_answer")
        second_press = [i for i, s in enumerate(sc.steps) if s.kind == "press"][1]
        self.assertLess(answer, second_press, "WAV 답은 듣기 전에 넣는다")
        self.assertEqual({"file": "ptt_test/yes_ne_bonae.wav"}, sc.steps[answer].extras)
        self.assertTrue(any("recognizer=VOSK answer=YES" in s.pattern for s in sc.steps))

    def test_no_expects_nothing_on_b(self):
        sc = ptt.build_scenario("no", Opts())
        self.assertEqual("absent", sc.steps[-1].kind)
        self.assertEqual("B", sc.steps[-1].car)
        self.assertTrue(any("cancelled" in s.pattern for s in sc.steps))

    def test_none_keeps_and_lists(self):
        sc = ptt.build_scenario("none", Opts())
        self.assertTrue(any(s.extras == {"answer": "none"} for s in sc.steps))
        self.assertTrue(any("kept unsent reason=NO_ANSWER" in s.pattern for s in sc.steps))
        self.assertTrue(any(s.action == "ptt_unsent" for s in sc.steps))

    def test_countdown_forces_and_always_restores(self):
        sc = ptt.build_scenario("countdown", Opts())
        self.assertEqual({"on": True}, sc.steps[0].extras)
        self.assertEqual({"on": False}, sc.steps[-1].extras)
        self.assertTrue(sc.steps[-1].always)
        self.assertFalse(any(s.action == "ptt_answer" for s in sc.steps), "카운트다운은 답 없이 5초 뒤 자동 보냄")

    def test_press_cancel_presses_three_times(self):
        self.assertEqual(3, self.kinds(ptt.build_scenario("press_cancel", Opts())).count("press"))

    def test_shots_only_when_asked(self):
        self.assertNotIn("shot", self.kinds(ptt.build_scenario("yes", Opts())))
        self.assertIn("shot", self.kinds(ptt.build_scenario("yes", Opts(shots=True))))

    def test_unknown(self):
        with self.assertRaises(ValueError):
            ptt.build_scenario("nope", Opts())

    def test_all_named_scenarios_build(self):
        for n in ptt.SCENARIOS:
            self.assertTrue(ptt.build_scenario(n, Opts()).steps)

    def test_default_run_is_the_verified_six(self):
        self.assertEqual(["yes", "no", "none", "resend", "press_cancel", "countdown"], ptt.DEFAULT_SCENARIOS)
        self.assertIn("interrupt", ptt.SCENARIOS)
        self.assertIn("persist", ptt.SCENARIOS)
        self.assertEqual(["yes", "persist"], ptt.scenario_names(" yes, ,persist,"))

    def test_interrupt_presses_b_while_playing_then_ends_b_flow_and_expects_resume(self):
        sc = ptt.build_scenario("interrupt", Opts())
        steps = sc.steps
        sent = next(i for i, s in enumerate(steps) if s.car == "A" and "ptt: sent voiceId=(?P<voiceId>" in s.pattern)
        play_start = next(i for i, s in enumerate(steps) if s.car == "B" and "play start" in s.pattern)
        self.assertGreater(play_start, sent)
        self.assertLess(steps[play_start].poll, 1.0, "짧은 재생 창을 잡으려고 빨리 본다")
        self.assertIn("resume=false", steps[play_start].pattern)
        b_presses = [i for i, s in enumerate(steps) if s.kind == "press" and s.car == "B"]
        self.assertEqual(3, len(b_presses), "끊기(=녹음 시작)·녹음 끝·확인 중 취소")
        self.assertEqual(play_start + 1, b_presses[0], "재생 시작을 보자마자 누른다")
        interrupted = next(i for i, s in enumerate(steps) if "playback interrupted" in s.pattern)
        self.assertGreater(interrupted, b_presses[0])
        self.assertIn("status=INTERRUPTED resume=after-flow", steps[interrupted].pattern)
        waiting = next(i for i, s in enumerate(steps) if "resumeWaiting=true" in s.pattern)
        self.assertLess(waiting, b_presses[1])
        released = next(i for i, s in enumerate(steps) if "resume released" in s.pattern)
        replay = next(i for i, s in enumerate(steps) if "manual=true resume=true" in s.pattern)
        done = next(i for i, s in enumerate(steps) if "playback done" in s.pattern)
        self.assertLess(b_presses[2], released)
        self.assertLess(released, replay)
        self.assertLess(replay, done)
        self.assertTrue(all("{voiceId}" in steps[i].pattern for i in (play_start, interrupted, replay, done)))
        self.assertFalse(any(s.kind == "shot" for s in steps))
        self.assertEqual(["interrupted", "resumed"], [s.label for s in ptt.build_scenario("interrupt", Opts(shots=True)).steps if s.kind == "shot" and s.car == "B"])

    def test_persist_force_stops_restarts_and_compares_same_id(self):
        sc = ptt.build_scenario("persist", Opts(user_a=10))
        steps = sc.steps
        kinds = [s.kind for s in steps]
        kept = next(i for i, s in enumerate(steps) if "kept unsent" in s.pattern)
        self.assertIn("persisted=yes", steps[kept].pattern)
        before = next(i for i, s in enumerate(steps) if "(?P<unsentId>" in s.pattern)
        stop = next(i for i, s in enumerate(steps) if s.kind == "shell" and "force-stop" in s.action)
        gone = next(i for i, s in enumerate(steps) if s.kind == "shell" and s.action.startswith("pidof"))
        mark = kinds.index("mark")
        start = next(i for i, s in enumerate(steps) if s.kind == "shell" and "am start" in s.action)
        restored = next(i for i, s in enumerate(steps) if "unsent restored" in s.pattern)
        after = next(i for i, s in enumerate(steps) if "{unsentId}" in s.pattern)
        self.assertTrue(kept < before < stop < gone < mark < start < restored < after)
        self.assertEqual("am force-stop app.donghaeng.car", steps[stop].action)
        self.assertEqual("am start --user 10 -n app.donghaeng.car/.MainActivity", steps[start].action)
        self.assertTrue(re.fullmatch(steps[gone].pattern, ""), "pidof 빈 출력 = 프로세스 없음")
        self.assertIn("persisted={unsentN}", steps[after].pattern)
        self.assertIn("dur={unsentDur}ms", steps[after].pattern)
        cleanup = [s for s in steps if s.kind == "bcast" and s.extras.get("op") == "delete"]
        self.assertEqual(1, len(cleanup))
        self.assertTrue(cleanup[0].always)
        self.assertEqual("{unsentId}", cleanup[0].extras["id"])

    def test_persist_before_pattern_captures_from_a_real_line(self):
        sc = ptt.build_scenario("persist", Opts())
        before = next(s for s in sc.steps if "(?P<unsentId>" in s.pattern)
        line = ("10-28 16:00:00.000 I/DongHaeng( 1): demo: ptt_unsent n=2 persisted=2 [u1730000000000-3 dur=2500ms reason=NO_ANSWER age=1s stored=yes, "
                "u1729999990000-2 dur=3000ms reason=NO_ANSWER age=10s stored=yes]")
        m = re.search(before.pattern, line)
        self.assertIsNotNone(m)
        self.assertEqual({"unsentN": "2", "unsentId": "u1730000000000-3", "unsentDur": "2500"}, m.groupdict())
        self.assertIsNone(re.search(before.pattern, line.replace("persisted=2", "persisted=0")), "저장소 수가 목록과 달라야 실패")
        after = next(s for s in sc.steps if "{unsentId}" in s.pattern)
        self.assertIsNotNone(re.search(ptt.fill(after.pattern, m.groupdict()), line))
        self.assertIsNone(re.search(ptt.fill(after.pattern, m.groupdict()), line.replace("u1730000000000-3", "u1730000000001-1")))


class FillTest(unittest.TestCase):

    def test_fill_escapes_and_requires_capture(self):
        self.assertIsNone(ptt.fill(r"voiceId={voiceId}", {}))
        self.assertEqual(r"voiceId=vm_1\.a", ptt.fill(r"voiceId={voiceId}", {"voiceId": "vm_1.a"}))
        self.assertEqual("plain", ptt.fill("plain", {}))

    def test_fill_extras_raw_values(self):
        self.assertEqual({"op": "delete", "id": "u1-2.x", "on": True}, ptt.fill_extras({"op": "delete", "id": "{unsentId}", "on": True}, {"unsentId": "u1-2.x"}))
        self.assertIsNone(ptt.fill_extras({"id": "{unsentId}"}, {}))
        self.assertEqual({}, ptt.fill_extras({}, {}))


class FakeLog:
    """LogWatcher 대역 — 공유 줄 목록에서 만든 뒤 생긴 줄만 본다(기기 시계 대신 줄 번호)"""
    lines: list = []

    def __init__(self, serial):
        self.serial = serial
        self.start = len(FakeLog.lines)

    def new(self):
        return [l for s, l in FakeLog.lines[self.start:] if s == self.serial]

    def find(self, pattern):
        return [l for l in self.new() if re.search(pattern, l)]

    def wait(self, pattern, timeout_s, poll_s=2.0):
        hits = self.find(pattern)
        return hits[-1] if hits else None


class RunnerStepKindsTest(unittest.TestCase):
    """mark(새 프로세스 줄만)·shell(출력 확인)·bcast 값 채우기 — adb 없이 대역으로"""

    def test_mark_hides_old_lines_and_shell_checks_output(self):
        FakeLog.lines = []
        sent = []

        def fake_sh(serial, cmd, timeout=30):
            if cmd.startswith("am start"):
                FakeLog.lines.append((serial, "I/DongHaeng: car app started flavor=pleos"))
            return "" if cmd.startswith("pidof") else "ok"

        def fake_broadcast(serial, action, **extras):
            sent.append((action, extras))
            if action == "ptt_unsent" and not extras:
                FakeLog.lines.append((serial, "I/DongHaeng: demo: ptt_unsent n=1 persisted=1 [u5-1 dur=2000ms reason=NO_ANSWER age=1s stored=yes]"))
            return ""

        sc = ptt.Scenario("t", "t", [
            ptt.Step("bcast", "A", "ptt_unsent"),
            ptt.Step("expect", "A", label="전", pattern=r"demo: ptt_unsent n=(?P<n>\d+) persisted=(?P=n) \[(?P<unsentId>u[\w-]+) "),
            ptt.Step("shell", "A", "pidof app.donghaeng.car", label="없음", pattern=r"^\s*$"),
            ptt.Step("mark", "A"),
            ptt.Step("absent", "A", label="mark 뒤 옛 줄은 안 보인다", pattern=r"demo: ptt_unsent"),
            ptt.Step("shell", "A", "am start --user 10 -n app.donghaeng.car/.MainActivity"),
            ptt.Step("expect", "A", label="새 프로세스", pattern=r"car app started"),
            ptt.Step("expect", "A", label="없는 줄", pattern=r"never-logged", timeout=0),
            ptt.Step("bcast", "A", "ptt_status"),   # 실패 뒤 — 건너뛴다
            ptt.Step("bcast", "A", "ptt_unsent", {"op": "delete", "id": "{unsentId}"}, always=True),
        ])
        a = argparse.Namespace(car_a="A1", car_b="B1", out=tempfile.mkdtemp(), gap_s=0, trigger="key", key="HEADSETHOOK")
        r = ptt.Runner(a)
        with mock.patch.object(ptt, "LogWatcher", FakeLog), mock.patch.object(ptt, "sh", fake_sh), \
                mock.patch.object(ptt, "broadcast", fake_broadcast), mock.patch.object(ptt.time, "sleep", lambda s: None), \
                contextlib.redirect_stdout(io.StringIO()):
            r.run_scenario(sc)
        verdicts = [(c, v) for _, c, v, _ in r.results]
        self.assertEqual([("A: 전", "OK"), ("A: 없음", "OK"), ("A: mark 뒤 옛 줄은 안 보인다", "OK"), ("A: 새 프로세스", "OK"), ("A: 없는 줄", "MISS")], verdicts)
        self.assertNotIn(("ptt_status", {}), sent, "실패 뒤 보통 단계는 건너뛴다")
        # 앞 단계 실패 뒤에도 always 정리는 한다 — 잡은 id 를 원문 그대로
        self.assertEqual(("ptt_unsent", {"op": "delete", "id": "u5-1"}), sent[-1])
        self.assertTrue(any("car app started" in l for l in r.watch["A"]), "로그 파일에는 시나리오 시작부터 전부")
        self.assertTrue(any("demo: ptt_unsent n=1" in l for l in r.watch["A"]), "mark 앞 줄도 로그 파일에는 남는다")

    def test_shell_pattern_miss_fails_scenario(self):
        FakeLog.lines = []
        sc = ptt.Scenario("t", "t", [
            ptt.Step("shell", "A", "pidof app.donghaeng.car", label="없음", pattern=r"^\s*$"),
            ptt.Step("shell", "A", "am start -n x"),
            ptt.Step("shell", "A", "pidof app.donghaeng.car", label="다시", pattern=r"^\s*$"),
        ])
        cmds = []
        a = argparse.Namespace(car_a="A1", car_b="B1", out=tempfile.mkdtemp(), gap_s=0, trigger="key", key="HEADSETHOOK")
        r = ptt.Runner(a)
        with mock.patch.object(ptt, "LogWatcher", FakeLog), mock.patch.object(ptt, "sh", lambda s, c, timeout=30: (cmds.append(c), "4321")[1]), \
                mock.patch.object(ptt.time, "sleep", lambda s: None), contextlib.redirect_stdout(io.StringIO()):
            r.run_scenario(sc)
        self.assertEqual([("A: 없음", "MISS"), ("A: 다시", "SKIP")], [(c, v) for _, c, v, _ in r.results])
        self.assertEqual(["pidof app.donghaeng.car"], cmds, "실패 뒤 명령은 돌리지 않는다")


if __name__ == "__main__":
    unittest.main()
