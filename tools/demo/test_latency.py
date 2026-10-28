#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/demo/latency.py 단위 테스트 (표준 라이브러리 unittest, adb·서버 없이 돈다).

  python -m unittest discover -s tools/demo -p "test_*.py" -v

백분위(앱 LatencyPercentiles 와 같은 최근접 순위법), 앱 로그 줄 해석(net_rtt·latency_dump·수신 이벤트·logcat -v epoch),
시계 보정(최소 왕복 표본·앞뒤 평균과 표류), 메시지 매칭('PC 송신 → 차 수신'), 비교표를 본다.
로그 줄은 car-app 단위 테스트(NetProbeFormatTest·LatencyMeterTest)가 기대하는 줄과 같은 모양이다.
"""
import os
import sys
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import latency  # noqa: E402

NET_HTTP = ("1730100000.123  4321  4400 I DongHaeng: net: http_rtt n=20 first=412.3ms p50=109.0ms p95=118.0ms min=100.0ms max=119.0ms "
            "base=https://1.2.3.4.sslip.io fail=0 proto=http/1.1 firstConn=new firstDns=3.1ms firstConnect=301.0ms firstTls=150.2ms newConnAfterFirst=0 gap=300ms")
NET_MQTT_FAIL = "1730100000.123  4321  4400 I DongHaeng: net: mqtt_rtt n=0 first=fail p50=- p95=- min=- max=- base=- fail=2 transport=tls state=RECONNECTING via=broker-puback gap=300ms lastError=not-connected"
SUMMARY = ("10-28 21:00:00.000  4321  4400 I DongHaeng: latency: summary event n=21 p50=100ms p95=190ms min=0ms max=200ms | "
           "state n=3 p50=20ms p95=30ms min=10ms max=30ms | clockRtt=12ms offset=1000ms clamped=1 unsynced=1")
EVENT = ("1730100001.250  4321  4410 I DongHaeng: latency: event 182ms (avg 150 max 200 n=3) "
         "type=PASSENGER_MESSAGE id=…X7K2QZ ts=1730100001000 rx=1730099999818")


def sample_result(label, http_p50, e2e_p50):
    """compare·markdown 용 최소 결과 JSON"""
    s = lambda p50: {"n": 20, "p50": p50, "p95": p50 + 10, "min": p50 - 5, "max": p50 + 20}  # noqa: E731
    car = {"serial": "emulator-X", "http": {"first_ms": 300.0, "summary": s(http_p50), "base": "https://h", "extra": {"firstConn": "new"}},
           "mqtt": {"first_ms": 20.0, "summary": s(http_p50 - 1), "base": "ssl://h:8883", "extra": {"transport": "tls"}},
           "dump": {"event": s(e2e_p50 - 40), "state": s(e2e_p50 - 50), "clock_rtt_ms": 12.0, "clock_offset_ms": 1000.0, "clamped": 0, "unsynced": 0},
           "clock": {"offset_ms": -1200.0, "err_ms": 2.0, "method": "adb", "drift_ms": 0.5},
           "e2e": {"method": "adb 기기 시계", "err_ms": 3.0, "summary": s(e2e_p50), "missing": [], "rows": [{"i": 1, "e2e_ms": e2e_p50, "srv_to_car_ms": 100}]}}
    return {"label": label, "server": "https://h", "started": "2026-10-28T21:00:00",
            "params": {"rtt_n": 30, "gap_ms": 300, "messages": 1, "msg_gap_s": 1.5},
            "pc_http": {"first_ms": 250.0, "first_conn": "new", "summary": s(http_p50 + 2), "fail": 0},
            "pc_offset": {"offset_ms": 5.0, "err_ms": 70.0, "rtt_ms": 140.0, "samples": 30},
            "cars": {"A": car, "B": car}, "pc_to_publish": {"summary": s(e2e_p50 / 2), "err_ms": 70.0},
            "post": {"summary": s(http_p50 + 30)}, "messages": [{"i": 1, "id": "X7K2QZ", "post_ms": 180.0, "send_ms": 1.0}], "warnings": []}


class PercentileTest(unittest.TestCase):
    def test_n1(self):
        self.assertEqual(latency.summarize([42]), {"n": 1, "p50": 42, "p95": 42, "min": 42, "max": 42})

    def test_n20_p95_is_19th(self):
        vals = list(range(20, 0, -1))   # 섞인 순서 — 안에서 정렬
        self.assertEqual(latency.percentile(vals, 95), 19)
        self.assertEqual(latency.percentile(vals, 50), 10)

    def test_same_as_app_definition(self):
        # 앱 LatencyPercentilesTest 와 같은 기대값: n=2 → P50 작은 쪽, n=21 → P95 20번째, n=100 → P95 정확히 95번째(정수 산술)
        self.assertEqual(latency.percentile([300, 100], 50), 100)
        self.assertEqual(latency.percentile([300, 100], 95), 300)
        self.assertEqual(latency.percentile(list(range(1, 22)), 95), 20)
        self.assertEqual(latency.percentile(list(range(1, 22)), 50), 11)
        self.assertEqual(latency.percentile(list(range(1, 101)), 95), 95)

    def test_empty_and_none(self):
        self.assertIsNone(latency.summarize([]))
        self.assertIsNone(latency.summarize([None, None]))
        with self.assertRaises(ValueError):
            latency.percentile([], 50)
        self.assertEqual(latency.summarize([None, 5.0, 3.0])["n"], 2)

    def test_first_is_separated(self):
        first, rest, fail = latency.first_and_rest([412.3, 100.0, None, 102.0])
        self.assertEqual(first, 412.3)
        self.assertEqual(rest, {"n": 2, "p50": 100.0, "p95": 102.0, "min": 100.0, "max": 102.0})
        self.assertEqual(fail, 1)
        self.assertEqual(latency.first_and_rest([None, 1.0])[0], None)


class ParseTest(unittest.TestCase):
    def test_net_http_line(self):
        r = latency.parse_net_line(NET_HTTP)
        self.assertEqual(r["kind"], "http_rtt")
        self.assertEqual(r["first_ms"], 412.3)
        self.assertEqual(r["summary"], {"n": 20, "p50": 109.0, "p95": 118.0, "min": 100.0, "max": 119.0})
        self.assertEqual(r["base"], "https://1.2.3.4.sslip.io")
        self.assertEqual(r["fail"], 0)
        self.assertEqual(r["extra"]["firstConn"], "new")
        self.assertEqual(r["extra"]["proto"], "http/1.1")

    def test_net_mqtt_failed_and_start_lines(self):
        r = latency.parse_net_line(NET_MQTT_FAIL)
        self.assertEqual(r["kind"], "mqtt_rtt")
        self.assertIsNone(r["first_ms"])
        self.assertIsNone(r["summary"])
        self.assertEqual(r["fail"], 2)
        self.assertEqual(r["extra"]["lastError"], "not-connected")
        self.assertIsNone(latency.parse_net_line("I DongHaeng: net: http_rtt start n=30 gap=300ms fresh=true"))
        self.assertIsNone(latency.parse_net_line(""))

    def test_summary_line(self):
        r = latency.parse_summary_line(SUMMARY)
        self.assertEqual(r["event"], {"n": 21, "p50": 100.0, "p95": 190.0, "min": 0.0, "max": 200.0})
        self.assertEqual(r["state"], {"n": 3, "p50": 20.0, "p95": 30.0, "min": 10.0, "max": 30.0})
        self.assertEqual((r["clock_rtt_ms"], r["clock_offset_ms"], r["clamped"], r["unsynced"]), (12.0, 1000.0, 1, 1))
        empty = latency.parse_summary_line("I DongHaeng: latency: summary event n=0 | state n=0 | clockRtt=- offset=- clamped=0 unsynced=0")
        self.assertIsNone(empty["event"])
        self.assertIsNone(empty["state"])
        self.assertIsNone(empty["clock_offset_ms"])
        self.assertIsNone(latency.parse_summary_line("I DongHaeng: latency: reset event=3 state=9"))

    def test_event_lines(self):
        e = latency.parse_event_line(EVENT)
        self.assertEqual(e, {"latency_ms": 182, "type": "PASSENGER_MESSAGE", "id": "X7K2QZ", "ts": 1730100001000, "rx": 1730099999818, "clamped": False})
        c = latency.parse_event_line("I DongHaeng: latency: event 0ms (avg 0 max 0 n=1) clamped(raw=-7) type=HARD_BRAKE id=* ts=10 rx=5")
        self.assertTrue(c["clamped"])
        self.assertIsNone(c["id"])
        u = latency.parse_event_line("I DongHaeng: latency: event unsynced type=PASSENGER_MESSAGE id=…ABC123 ts=10 rx=5")
        self.assertIsNone(u["latency_ms"])
        self.assertEqual(u["id"], "ABC123")
        # 꼬리가 없는 옛 줄·STATE 줄은 매칭에 못 쓴다
        self.assertIsNone(latency.parse_event_line("I DongHaeng: latency: event 120ms (avg 120 max 120 n=1)"))
        self.assertIsNone(latency.parse_event_line("I DongHaeng: latency: state 80ms (avg 80 max 80 n=1)"))

    def test_logcat_epoch_and_device_date(self):
        self.assertEqual(latency.logcat_epoch_ms(EVENT), 1730100001250)
        self.assertIsNone(latency.logcat_epoch_ms("10-28 21:00:00.000  1  2 I DongHaeng: x"))
        self.assertEqual(latency.device_ms("1730100000123456789\r\n"), 1730100000123.4568)
        self.assertIsNone(latency.device_ms("1730100000N"))   # %N 을 모르는 date
        self.assertIsNone(latency.device_ms(None))


class ClockTest(unittest.TestCase):
    def test_offset_uses_min_rtt_sample(self):
        # (호스트 t0, 기기 시각, 호스트 t1): 기기가 1200 ms 늦다. 왕복 20 ms·4 ms·10 ms → 4 ms 표본
        samples = [(1000.0, -190.0, 1020.0), (2000.0, 801.0, 2004.0), (3000.0, 1805.0, 3010.0), (4000.0, None, 4001.0)]
        r = latency.clock_offset(samples)
        self.assertAlmostEqual(r["offset_ms"], 801.0 - 2002.0)
        self.assertEqual(r["err_ms"], 2.0)
        self.assertEqual(r["rtt_ms"], 4.0)
        self.assertEqual(r["samples"], 3)
        self.assertIsNone(latency.clock_offset([(1.0, None, 2.0)]))

    def test_device_shell_answers_stay_paired(self):
        """상주 adb 세션: 남은 줄·배너·이전 답은 버리고 이번 표지의 줄만 받는다(밀린 답이 최소 왕복 표본으로 뽑히지 않게)"""
        import queue
        s = latency.DeviceShell.__new__(latency.DeviceShell)   # adb 를 띄우지 않는다
        s.q = queue.Queue()
        s.q.put("DH0 1730100000000000000")   # 지난번 시간 초과로 늦게 온 답 — 보내기 전에 버린다
        s.p = mock.Mock()
        s.p.stdin.write.side_effect = lambda b: [s.q.put(x) for x in ("console:/ $", "DH10 1", "DH1 1730100000123456789")]
        self.assertEqual(s.ask('date +"DH1 %s%N"', "DH1 "), "1730100000123456789")
        self.assertIn(b'date +"DH1 %s%N"\n', s.p.stdin.write.call_args[0])
        s.p.stdin.write.side_effect = lambda b: s.q.put(None)
        with self.assertRaises(EOFError):
            s.ask("date", "DH2 ")

    class FakeShell:
        """상주 adb 세션 가짜 — 기기 시계는 호스트보다 1200 ms 늦고, fail_at 번째 date 답에서 시간 초과(queue.Empty)"""
        made = 0
        closed = 0
        fail_at = None

        def __init__(self, serial):
            ClockTest.FakeShell.made += 1
            self.dates = 0

        def ask(self, cmd, marker, timeout=5.0):
            import queue
            if marker == "DHready":
                return ""
            self.dates += 1
            if ClockTest.FakeShell.fail_at is not None and self.dates >= ClockTest.FakeShell.fail_at:
                raise queue.Empty()
            return str(int((latency.CLOCK.now_ms() - 1200.0) * 1_000_000))

        def close(self):
            ClockTest.FakeShell.closed += 1

    def test_device_clock_keeps_samples_when_resident_shell_breaks(self):
        """15번 중 14번째에서 시간 초과 → 앞의 13개로 오프셋, adb 매번 실행으로 넘어가지 않는다(리뷰 반영)"""
        ClockTest.FakeShell.made, ClockTest.FakeShell.closed, ClockTest.FakeShell.fail_at = 0, 0, 14
        with mock.patch.object(latency, "DeviceShell", ClockTest.FakeShell), \
                mock.patch.object(latency, "adb", side_effect=AssertionError("fallback 을 쓰면 안 된다")):
            r = latency.device_clock("emulator-X", samples=15)
        self.assertEqual(r["samples"], 13)
        self.assertLess(abs(r["offset_ms"] + 1200.0), 5.0)
        self.assertTrue(r["method"].startswith("adb shell 상주 세션"))
        self.assertIn("13/15", r["method"])
        self.assertEqual(ClockTest.FakeShell.closed, 1)   # 이 스크립트가 띄운 세션은 닫는다

    def test_device_clock_falls_back_only_without_valid_sample(self):
        """첫 date 부터 시간 초과(유효 표본 0) → adb 매번 실행 7회"""
        ClockTest.FakeShell.made, ClockTest.FakeShell.closed, ClockTest.FakeShell.fail_at = 0, 0, 1
        calls = []

        def fake_adb(serial, *args, **kw):
            calls.append(args)
            return str(int((latency.CLOCK.now_ms() - 1200.0) * 1_000_000))

        with mock.patch.object(latency, "DeviceShell", ClockTest.FakeShell), mock.patch.object(latency, "adb", side_effect=fake_adb):
            r = latency.device_clock("emulator-X", samples=15)
        self.assertEqual(len(calls), 7)
        self.assertEqual(r["method"], "adb shell 매번 실행 date +%s%N")
        self.assertEqual(r["samples"], 7)
        self.assertEqual(ClockTest.FakeShell.closed, 1)
        # 세션을 띄우지도 못하면(adb 없음 등) 역시 fallback
        with mock.patch.object(latency, "DeviceShell", side_effect=OSError("no adb")), mock.patch.object(latency, "adb", side_effect=fake_adb):
            r = latency.device_clock("emulator-X", samples=15)
        self.assertEqual(r["method"], "adb shell 매번 실행 date +%s%N")

    def test_combine_before_after(self):
        b = {"offset_ms": -1200.0, "err_ms": 2.0, "method": "m"}
        a = {"offset_ms": -1196.0, "err_ms": 3.0, "method": "m"}
        r = latency.combine_offsets(b, a)
        self.assertEqual(r["offset_ms"], -1198.0)
        self.assertEqual(r["drift_ms"], 4.0)
        self.assertEqual(r["err_ms"], 3.0 + 2.0)   # 큰 오차 + 표류/2
        self.assertEqual(latency.combine_offsets(None, a)["offset_ms"], -1196.0)
        self.assertIsNone(latency.combine_offsets(None, None))

    def test_match_messages_end_to_end(self):
        # 호스트 1000 ms 에 보냄, 기기 시계는 호스트보다 1200 ms 늦다, 250 ms 뒤 수신 → 기기 rx = 1000 + 250 − 1200 = 50
        msgs = [{"i": 1, "id": "AAAAAA", "send_ms": 1000.0}, {"i": 2, "id": "BBBBBB", "send_ms": 2500.0}, {"i": 3, "id": None, "send_ms": 4000.0}]
        events = [
            {"type": "PASSENGER_MESSAGE", "id": "AAAAAA", "rx": 50, "ts": 9_000, "latency_ms": 90, "logcat_ms": 52},
            {"type": "PASSENGER_MESSAGE", "id": "AAAAAA", "rx": 70, "ts": 9_000, "latency_ms": 110, "logcat_ms": 72},   # 중복 수신 → 첫 줄
            {"type": "HARD_BRAKE", "id": "BBBBBB", "rx": 1500, "ts": 1, "latency_ms": 1},                           # 다른 종류는 무시
        ]
        rows, missing = latency.match_messages(msgs, events, {"offset_ms": -1200.0, "err_ms": 2.0})
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["e2e_ms"], 250.0)
        self.assertEqual(rows[0]["srv_to_car_ms"], 90)
        self.assertEqual(missing, [2])   # 3 은 POST 실패(id 없음) — 못 받음으로 세지 않는다
        # 기기 시계를 못 쟀으면 e2e 없이 행만
        rows2, _ = latency.match_messages(msgs, events, None)
        self.assertNotIn("e2e_ms", rows2[0])

    def test_clocksync_cross_check(self):
        # 서버 시계 기준: PC 오프셋 +5 (서버 − PC), 앱 오프셋 +1250 (서버 − 기기)
        msgs = [{"i": 1, "id": "AAAAAA", "send_ms": 1000.0}]
        rows = [{"i": 1, "rx_dev_ms": 50, "ts_srv_ms": 1200}]
        latency.clocksync_cross(rows, msgs, app_offset_ms=1250.0, pc_offset_ms=5.0)
        self.assertEqual(rows[0]["e2e_cs_ms"], (50 + 1250) - (1000 + 5))
        self.assertEqual(rows[0]["pc_to_publish_ms"], 1200 - 1005)


class HelperTest(unittest.TestCase):
    def test_origin_and_same_server(self):
        self.assertEqual(latency.origin("https://user:pw@1.2.3.4.sslip.io/v1/x?t=1"), "https://1.2.3.4.sslip.io")
        self.assertEqual(latency.origin("http://127.0.0.1:8080"), "http://127.0.0.1:8080")
        self.assertTrue(latency.same_server("http://10.0.2.2:8080", "http://127.0.0.1:8080"))
        self.assertFalse(latency.same_server("http://10.0.2.2:8080", "https://1.2.3.4.sslip.io"))
        self.assertTrue(latency.same_server("https://1.2.3.4.sslip.io", "https://1.2.3.4.sslip.io/"))
        self.assertFalse(latency.same_server("-", "http://127.0.0.1:8080"))
        self.assertEqual(latency.default_label("http://127.0.0.1:8080"), "local")
        self.assertEqual(latency.default_label("https://1.2.3.4.sslip.io"), "cloud")

    def test_http_probe_warnings(self):
        """fresh 는 유휴 연결만 닫는다 — 첫 표본이 reused 이거나 이후 표본이 새 연결이면 경고(리뷰 반영)"""
        ok = latency.parse_net_line(NET_HTTP)
        self.assertEqual(latency.http_probe_warnings("A", ok), [])
        reused = latency.parse_net_line(NET_HTTP.replace("firstConn=new firstDns=3.1ms firstConnect=301.0ms firstTls=150.2ms", "firstConn=reused"))
        w = latency.http_probe_warnings("A", reused)
        self.assertEqual(len(w), 1)
        self.assertIn("firstConn=reused", w[0])
        later = latency.parse_net_line(NET_HTTP.replace("newConnAfterFirst=0", "newConnAfterFirst=2"))
        w = latency.http_probe_warnings("B", later)
        self.assertEqual(len(w), 1)
        self.assertIn("차 B net_rtt 두 번째 이후 표본 2개가 새 연결", w[0])
        self.assertEqual(latency.http_probe_warnings("A", None), [])

    def test_mask_id_matches_app(self):
        self.assertEqual(latency.mask_id("grp_01JB8Z3M4N5P6Q7R8S9T0X7K2QZ"), "grp_…QZ")
        self.assertEqual(latency.mask_id("abc"), "***")

    def test_epoch_watcher_uses_epoch_format(self):
        with mock.patch.object(latency, "sh", return_value="2026\n") as sh_:
            with mock.patch("demo.sh", return_value="10-28 21:00:00.000"):
                w = latency.EpochLogWatcher("emulator-X")
            sh_.return_value = EVENT + "\nnoise line\n"
            lines = w.new()
        self.assertEqual(lines, [EVENT])
        self.assertIn("-v epoch", sh_.call_args[0][1])
        self.assertIn("-T '10-28 21:00:00.000'", sh_.call_args[0][1])
        self.assertEqual(latency.car_events(lines)[0]["logcat_ms"], 1730100001250)


class ReportTest(unittest.TestCase):
    def test_compare_table_delta(self):
        md = latency.compare_table(sample_result("local", 10.0, 60.0), sample_result("cloud", 150.0, 400.0))
        self.assertIn("| 지표 | local P50 | local P95 | cloud P50 | cloud P95 | Δ P50 | Δ P95 |", md)
        self.assertIn("| PC → 서버 HTTP 왕복 | 12.0 | 22.0 | 152.0 | 162.0 | +140.0 | +140.0 |", md)
        self.assertIn("| PC 송신 → 차 A 수신(메시지) | 60.0 | 70.0 | 400.0 | 410.0 | +340.0 | +340.0 |", md)

    def test_compare_handles_missing_metric(self):
        b = sample_result("cloud", 150.0, 400.0)
        b["cars"] = {"A": dict(b["cars"]["A"], mqtt=None), "B": b["cars"]["B"]}
        md = latency.compare_table(sample_result("local", 10.0, 60.0), b)
        self.assertIn("| 차 A MQTT PUBLISH → PUBACK | 9.0 | 19.0 | - | - | - | - |", md)

    def test_markdown_report_rows(self):
        md = latency.markdown_report(sample_result("cloud", 150.0, 400.0))
        self.assertIn("# 지연 측정 — cloud", md)
        self.assertIn("| 차 A → 서버 HTTP 왕복 | 앱 OkHttp(같은 연결 풀) ↔ 서버, GET /v1/time | 20 | 300.0 | 150.0 | 160.0 | 145.0 | 170.0 | 첫 표본 new 연결 |", md)
        self.assertIn("| 1 | X7K2QZ | 180.0 | 400.0 | 400.0 | 100 | 100 |", md)
        self.assertIn("기기 시계(기기 − PC) -1200.0 ms ±2.0", md)
        keys = [m["key"] for m in latency.metric_rows(sample_result("x", 1.0, 2.0))]
        self.assertEqual(len(keys), len(set(keys)))


class RunFlowTest(unittest.TestCase):
    """Runner.run 전체 흐름 — adb·HTTP 를 가짜로 바꿔 결과 조립(매칭·보정·파일)을 본다.
    가짜 기기 시계는 호스트보다 1200 ms 늦고, 메시지는 보낸 뒤 250 ms 에 두 차가 받으며 서버는 150 ms 에 발행한다."""

    class FakeWatcher:
        events = {}

        def __init__(self, serial):
            self.serial = serial

        def wait(self, pattern, timeout_s, poll_s=1.0):
            if "reset" in pattern:
                return "I DongHaeng: latency: reset event=0 state=0"
            if "http_rtt" in pattern:
                return NET_HTTP
            if "mqtt_rtt" in pattern:
                return NET_HTTP.replace("http_rtt", "mqtt_rtt").replace("https://1.2.3.4.sslip.io", "ssl://1.2.3.4.sslip.io:8883")
            if "summary" in pattern:
                return SUMMARY
            return None

        def new(self):
            return list(RunFlowTest.FakeWatcher.events.get(self.serial, []))

    class FakeHttp:
        sent = []

        def __init__(self, server, timeout=15):
            pass

        def request(self, method, path, body=None, headers=None):
            if method == "GET":
                return 200, b'{"serverTimeMs": 5005.0}', 5000.0, 5010.0, False   # PC→서버 오프셋 0, 왕복 10
            i = len(RunFlowTest.FakeHttp.sent) + 1
            t0 = 10_000.0 * i
            eid = f"evt_01JTEST{i:06d}"
            RunFlowTest.FakeHttp.sent.append((eid, t0, headers.get("Authorization"), json_text(body)))
            for serial in ("emulator-A", "emulator-B"):
                RunFlowTest.FakeWatcher.events.setdefault(serial, []).append(
                    f"1730100001.250  1  2 I DongHaeng: latency: event 100ms (avg 100 max 100 n=1) type=PASSENGER_MESSAGE id=…{eid[-6:]} "
                    f"ts={int(t0 + 150)} rx={int(t0 + 250 - 1200)}")
            return 201, ('{"eventId": "%s"}' % eid).encode(), t0, t0 + 180.0, False

        def close(self):
            pass

    def test_run_assembles_result(self):
        import tempfile
        import json
        RunFlowTest.FakeWatcher.events, RunFlowTest.FakeHttp.sent = {}, []
        casts = []
        a = mock.Mock(server="https://1.2.3.4.sslip.io", label=None, car_a="emulator-A", car_b="emulator-B", email="dev@example.invalid", group=None,
                      messages=3, msg_gap_s=0, rtt_n=5, gap_ms=0, settle_s=0, no_mqtt=False, keep_voice=False, dry_run=False, skip_preflight=False)
        with tempfile.TemporaryDirectory() as out, \
                mock.patch.object(latency, "EpochLogWatcher", RunFlowTest.FakeWatcher), \
                mock.patch.object(latency, "KeepAliveHttp", RunFlowTest.FakeHttp), \
                mock.patch.object(latency, "device_clock", return_value={"offset_ms": -1200.0, "err_ms": 1.0, "rtt_ms": 2.0, "samples": 15, "method": "m"}), \
                mock.patch.object(latency, "broadcast", side_effect=lambda s, action, **kw: casts.append((s, action, kw))), \
                mock.patch.object(latency, "status", return_value={"group": "grp_…QZ", "voice": "true"}), \
                mock.patch.object(latency.time, "sleep"), \
                mock.patch("builtins.print"):
            a.out = out
            r = latency.Runner(a)
            r.api = mock.Mock(token="tok")
            r.group_id = "grp_01JTESTQZ"
            code = r.run()
            with open(r.base + ".json", encoding="utf-8") as f:
                res = json.load(f)
            self.assertTrue(os.path.exists(r.base + ".md"))
            self.assertTrue(os.path.exists(r.base + "_carB.log"))
        self.assertEqual(code, 0)
        self.assertEqual(res["label"], "cloud")
        for car in ("A", "B"):
            e2e = res["cars"][car]["e2e"]
            self.assertEqual(e2e["summary"]["p50"], 250.0)
            self.assertEqual(e2e["summary"]["n"], 3)
            self.assertEqual(e2e["missing"], [])
            self.assertEqual(e2e["err_ms"], 2.0)   # 기기 오프셋 ±1 + rx 절사 1
            self.assertEqual(e2e["clocksync"]["p50"], 50.0)   # (rx + 앱 오프셋 1000) − 송신 — 가짜 앱 오프셋이 기기 시계와 맞지 않는 값이라 교차 확인이 어긋나 보인다
            self.assertEqual(res["cars"][car]["http"]["summary"]["p50"], 109.0)
            self.assertEqual(res["cars"][car]["dump"]["event"]["n"], 21)
        self.assertEqual(res["pc_to_publish"]["summary"]["p50"], 150.0)
        self.assertEqual(res["post"]["summary"]["p50"], 180.0)
        self.assertEqual(res["warnings"], [])
        r.api.login.assert_called_once_with(force=True)   # ③ 앞에서 토큰을 새로 받는다(캐시 토큰 만료 대비)
        # 토큰은 헤더로만 — 결과 파일에 없다
        self.assertNotIn("tok", json.dumps(res))
        self.assertTrue(all(s[2] == "Bearer tok" for s in RunFlowTest.FakeHttp.sent))
        self.assertEqual(RunFlowTest.FakeHttp.sent[0][3]["text"][:9], "지연 측정 1/3")
        # 음성: 끄고 → 되돌림, 프로브는 --ei 대신 문자열로(앱이 둘 다 받는다)
        actions = [(s, act, kw) for s, act, kw in casts]
        self.assertIn(("emulator-A", "voice", {"on": False}), actions)
        self.assertIn(("emulator-A", "voice", {"on": True}), actions)
        self.assertIn(("emulator-B", "net_rtt", {"n": 5, "gap_ms": 0, "fresh": True}), actions)


class SendMessagesTokenTest(unittest.TestCase):
    """③ 메시지 전송의 토큰 처리 — 캐시 토큰이 ①·② 동안 만료돼도 메시지가 401 로 다 빠지지 않는다(리뷰 반영)"""

    class FakeHttp:
        rejected = set()
        sent = []

        def __init__(self, server, timeout=15):
            pass

        def request(self, method, path, body=None, headers=None):
            if method == "GET":
                return 200, b'{"serverTimeMs": 5005.0}', 5000.0, 5010.0, False
            auth = headers.get("Authorization")
            SendMessagesTokenTest.FakeHttp.sent.append(auth)
            if auth.split(" ", 1)[1] in SendMessagesTokenTest.FakeHttp.rejected:
                return 401, b'{"code": "INVALID_TOKEN"}', 1.0, 2.0, False
            n = len(SendMessagesTokenTest.FakeHttp.sent)
            return 201, ('{"eventId": "evt_01JTEST%06d"}' % n).encode(), 10.0 * n, 10.0 * n + 5, False

        def close(self):
            pass

    def runner(self, tokens):
        a = mock.Mock(server="https://1.2.3.4.sslip.io", label="cloud", car_a="emulator-A", car_b="emulator-B", messages=2, msg_gap_s=0,
                      settle_s=0, out="unused")
        r = latency.Runner(a)
        r.group_id = "grp_01JTESTQZ"
        r.pc_samples = []
        it = iter(tokens)
        r.api = mock.Mock(token="cached")

        def login(force=False):
            r.api.token = next(it)
            return r.api.token
        r.api.login.side_effect = login
        return r

    def send(self, r):
        with mock.patch.object(latency, "KeepAliveHttp", SendMessagesTokenTest.FakeHttp), \
                mock.patch.object(latency.time, "sleep"), mock.patch("builtins.print"):
            return r.send_messages()

    def test_fresh_token_before_sending(self):
        SendMessagesTokenTest.FakeHttp.rejected, SendMessagesTokenTest.FakeHttp.sent = {"cached"}, []
        r = self.runner(["t1"])
        msgs = self.send(r)
        self.assertTrue(all(m["id"] for m in msgs))
        self.assertEqual(SendMessagesTokenTest.FakeHttp.sent, ["Bearer t1", "Bearer t1"])
        r.api.login.assert_called_once_with(force=True)
        self.assertEqual(r.warnings, [])

    def test_401_relogins_once_and_resends(self):
        # 새로 받은 t1 도 거부(서버 키 교체 등) → 다시 로그인한 t2 로 같은 메시지를 다시 보낸다
        SendMessagesTokenTest.FakeHttp.rejected, SendMessagesTokenTest.FakeHttp.sent = {"cached", "t1"}, []
        r = self.runner(["t1", "t2"])
        msgs = self.send(r)
        self.assertEqual([m["status"] for m in msgs], [201, 201])
        self.assertTrue(all(m["id"] for m in msgs))
        self.assertEqual(SendMessagesTokenTest.FakeHttp.sent, ["Bearer t1", "Bearer t2", "Bearer t2"])
        self.assertEqual(r.api.login.call_count, 2)
        self.assertEqual(r.warnings, ["메시지 1 HTTP 401 → 다시 로그인해 한 번 더 보냄"])

    def test_401_does_not_loop(self):
        # 다시 로그인해도 계속 401 → 메시지마다 한 번만 다시 보내고 끝(무한 반복 없음), 결과는 401·id 없음
        SendMessagesTokenTest.FakeHttp.rejected, SendMessagesTokenTest.FakeHttp.sent = {"cached", "t1", "t2", "t3"}, []
        r = self.runner(["t1", "t2", "t3"])
        msgs = self.send(r)
        self.assertEqual([m["status"] for m in msgs], [401, 401])
        self.assertFalse(any(m["id"] for m in msgs))
        self.assertEqual(len(SendMessagesTokenTest.FakeHttp.sent), 4)
        self.assertIn("메시지 2 HTTP 401", r.warnings)


def json_text(body):
    import json
    return json.loads(body.decode("utf-8"))


if __name__ == "__main__":
    unittest.main()
