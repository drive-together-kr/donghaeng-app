#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/demo 단위 테스트 (표준 라이브러리 unittest, adb·서버 없이 돈다).

  python -m unittest discover -s tools/demo -p "test_*.py" -v

preflight 의 시연 닉네임 단계(demo.Demo.ensure_nicknames)와 REST 보조(api.DhApi)의 실패 처리를 본다.
adb 를 부르는 함수(broadcast·LogWatcher·which_vehicle)는 가짜로 바꾼다 — 실제 기기·서버는 통합 담당이 확인한다.
"""
import argparse
import io
import os
import socket
import sys
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import api  # noqa: E402
import demo  # noqa: E402


def closed_port_url() -> str:
    """지금 아무도 듣지 않는 로컬 포트 — 연결 거부(URLError)를 재현한다"""
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return f"http://127.0.0.1:{port}"


def args(**kw) -> argparse.Namespace:
    base = dict(car_a="emulator-A", car_b="emulator-B", vc_port=8089, out=os.path.join(HERE, "out"),
                server="http://127.0.0.1:8080", email="dev@example.invalid", dry_run=False,
                nick_a="아빠 차", nick_b="둘째 차", nick_c="삼촌 차")
    base.update(kw)
    return argparse.Namespace(**base)


class FakeApi:
    """api.DhApi 대역 — 차 3대(veh_A1·veh_B1·veh_C1), PATCH 결과는 rename_status 로 정한다"""
    vehicles_list = []
    rename_status = {}
    renamed = []

    def __init__(self, server, email=None):
        pass

    def login(self, force=False):
        return "token"

    def vehicles(self):
        return [dict(v) for v in FakeApi.vehicles_list]

    def rename_vehicle(self, vid, nick):
        FakeApi.renamed.append((vid, nick))
        st = FakeApi.rename_status.get(vid, 200)
        return st, ({"vehicleId": vid, "nickname": nick} if st == 200 else {"code": "NOT_FOUND"})

    def active_groups(self):
        return []


class FakeWatcher:
    def __init__(self, serial):
        self.serial = serial

    def new(self):
        return []

    def wait(self, pattern, timeout_s, poll_s=2.0):
        return "I/DongHaeng: demo: nickname changed=true len=4"


class EnsureNicknamesTest(unittest.TestCase):

    def setUp(self):
        FakeApi.vehicles_list = [
            {"vehicleId": "veh_A1", "nickname": "DemoCar"},
            {"vehicleId": "veh_B1", "nickname": "DemoCarB"},
            {"vehicleId": "veh_C1", "nickname": "예전 차"},
        ]
        FakeApi.rename_status = {}
        FakeApi.renamed = []
        self.broadcasts = []
        patches = [
            mock.patch.object(demo, "broadcast", side_effect=lambda serial, action, **ex: self.broadcasts.append((serial, action, ex)) or "Broadcast completed"),
            mock.patch.object(demo, "LogWatcher", FakeWatcher),
            mock.patch("sys.stdout", new_callable=io.StringIO),
            mock.patch.object(api, "TOKEN_CACHE", os.path.join(HERE, "out", "no-such-token-cache")),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)

    def run_ensure(self, a, found=None):
        d = demo.Demo(a)
        found = found if found is not None else {"emulator-A": "veh_A1", "emulator-B": "veh_B1"}
        d.which_vehicle = lambda serial, ids: found.get(serial)
        rows = []
        d.ensure_nicknames(rows)
        return rows

    def nickname_broadcasts(self):
        return [(serial, ex["name_b64"]) for serial, action, ex in self.broadcasts if action == "nickname"]

    def test_server_down_leaves_a_fail_row_instead_of_a_traceback(self):
        # 리뷰 지적: 서버가 꺼져 있으면 URLError 가 except SystemExit 를 지나쳐 preflight 표가 안 나왔다
        rows = self.run_ensure(args(server=closed_port_url(), dry_run=True))
        self.assertEqual(1, len(rows))
        label, ok = rows[0]
        self.assertIs(False, ok)
        self.assertIn("서버 조회 실패", label)
        self.assertEqual([], self.broadcasts, "서버를 못 읽으면 기기에 is_me·nickname 을 보내지 않는다")

    def test_failed_patch_does_not_push_the_new_name_into_that_car_app(self):
        # 리뷰 지적: PATCH 가 실패한 차에도 앱 저장 닉네임을 넣어 서버(음성·패널)와 앱(홈·그룹 기본 이름)이 어긋났다
        FakeApi.rename_status = {"veh_A1": 404}
        with mock.patch.object(api, "DhApi", FakeApi):
            rows = self.run_ensure(args())
        self.assertEqual([("veh_A1", "아빠 차"), ("veh_B1", "둘째 차"), ("veh_C1", "삼촌 차")], FakeApi.renamed)
        status = {label: ok for label, ok in rows}
        self.assertIs(False, next(ok for label, ok in status.items() if label.startswith("닉네임 차 A")))
        self.assertIs(True, status["닉네임 차 B = '둘째 차'"])
        self.assertIs(True, status["닉네임 차 C = '삼촌 차'"])
        pushed = self.nickname_broadcasts()
        self.assertEqual(["emulator-B"], [serial for serial, _ in pushed], "차 A 는 서버가 안 바뀌었으니 앱도 그대로")
        self.assertFalse(any(label.startswith("차 A 앱 저장") for label in status))

    def test_unchanged_server_name_is_skipped_but_the_app_value_is_still_aligned(self):
        FakeApi.vehicles_list[0]["nickname"] = "아빠 차"
        with mock.patch.object(api, "DhApi", FakeApi):
            rows = self.run_ensure(args())
        self.assertNotIn(("veh_A1", "아빠 차"), FakeApi.renamed, "이미 같으면 PATCH 하지 않는다")
        self.assertEqual({"emulator-A", "emulator-B"}, {serial for serial, _ in self.nickname_broadcasts()})
        self.assertTrue(all(ok for _, ok in rows), rows)

    def test_dry_run_only_plans(self):
        with mock.patch.object(api, "DhApi", FakeApi):
            rows = self.run_ensure(args(dry_run=True))
        self.assertEqual([], FakeApi.renamed)
        self.assertEqual([], self.nickname_broadcasts())
        self.assertEqual(3, sum(1 for _, ok in rows if ok is None), "차 A·B·C 계획은 WARN(None) 행")

    def test_rest_car_is_not_chosen_when_car_a_is_unknown(self):
        with mock.patch.object(api, "DhApi", FakeApi):
            rows = self.run_ensure(args(), found={"emulator-B": "veh_B1"})
        self.assertEqual([("veh_B1", "둘째 차")], FakeApi.renamed, "차 A 를 못 찾으면 '삼촌 차' 도 고르지 않는다")
        self.assertTrue(any(ok is False and "차 A 를" in label for label, ok in rows))

    def test_no_email_is_a_warning_row(self):
        rows = self.run_ensure(args(email=None))
        self.assertEqual(1, len(rows))
        self.assertIsNone(rows[0][1])


class DhApiNetworkErrorTest(unittest.TestCase):

    def test_unreachable_server_is_status_0_and_login_raises_system_exit(self):
        a = api.DhApi(closed_port_url(), "dev@example.invalid")
        st, res = a.call("GET", "/me/vehicles", auth=False)
        self.assertEqual(0, st)
        self.assertIn("연결 실패", res)
        with mock.patch.object(api, "TOKEN_CACHE", os.path.join(HERE, "out", "no-such-token-cache")):
            with self.assertRaises(SystemExit) as caught:
                a.login()
        self.assertIn("login failed: 0", str(caught.exception))


class AdbDevicesTest(unittest.TestCase):
    """adb 가 없는 PC 에서 preflight 가 트레이스백 대신 FAIL 행으로 끝나야 한다(ptt.py run --dry-run·demo.py)"""

    def test_missing_adb_is_none_not_a_traceback(self):
        self.assertIsNone(demo.adb_devices(os.path.join(HERE, "out", "no_such_dir", "adb.exe")))

    def test_device_connected(self):
        out = "List of devices attached\nemulator-5556\tdevice\nemulator-5558\toffline\n"
        self.assertTrue(demo.device_connected(out, "emulator-5556"))
        self.assertFalse(demo.device_connected(out, "emulator-5558"))
        self.assertFalse(demo.device_connected(out, "emulator-5560"))
        self.assertFalse(demo.device_connected(None, "emulator-5556"))


class GwaWaTest(unittest.TestCase):

    def test_matches_the_car_app_rule(self):
        self.assertEqual("와", demo.gwa_wa("아빠 차"))
        self.assertEqual("과", demo.gwa_wa("엄마 차량"))
        self.assertEqual("과", demo.gwa_wa("EV6"))
        self.assertEqual("와", demo.gwa_wa("DemoCarB"))


if __name__ == "__main__":
    unittest.main()
