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


class FillTest(unittest.TestCase):

    def test_fill_escapes_and_requires_capture(self):
        self.assertIsNone(ptt.fill(r"voiceId={voiceId}", {}))
        self.assertEqual(r"voiceId=vm_1\.a", ptt.fill(r"voiceId={voiceId}", {"voiceId": "vm_1.a"}))
        self.assertEqual("plain", ptt.fill("plain", {}))


if __name__ == "__main__":
    unittest.main()
