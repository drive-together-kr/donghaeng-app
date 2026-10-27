#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""동행 서버 REST 보조 (사용자 JWT 쪽 — 공식 앱/demo-host 가 하는 일을 터미널에서) — T30, docs/03 §1·§2.

폰(demo-host)이 없을 때 리허설용이다. 실제 시연에서는 공식 앱(demo-host)의 QR 스캔으로 같은 API 가 호출된다.

  python tools/demo/api.py --email dev@example.com login                       # dev 간편 로그인(토큰 캐시 tools/demo/out/.token)
  python tools/demo/api.py --email dev@example.com vehicles                    # 내 차 목록
  python tools/demo/api.py --email dev@example.com pair KQ7-4MZ --nickname DemoCarB   # 차 화면의 코드로 페어링 승인
  python tools/demo/api.py --email dev@example.com groups                      # 내 활성 그룹
  python tools/demo/api.py --email dev@example.com join --vehicle DemoCarB     # 내 활성 그룹(선두차 A 의 그룹)에 새 초대를 만들어 차 B 합류
  python tools/demo/api.py --email dev@example.com message "휴게소 들렀다 가요" # 동승자 메시지(PASSENGER_MESSAGE)

서버 주소 기본값 http://127.0.0.1:8080 (환경변수 DH_SERVER). 토큰·이메일은 로그에 남기지 않는다.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN_CACHE = os.path.join(HERE, "out", ".token")


class DhApi:
    def __init__(self, server: str, email: str | None = None, display_name: str = "동행 시연"):
        self.base = server.rstrip("/") + "/v1"
        self.email = email
        self.display_name = display_name
        self.token: str | None = None

    # ---- HTTP ----
    def call(self, method: str, path: str, body=None, auth: bool = True, accept_json: bool = True):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if accept_json:
            req.add_header("Accept", "application/json")
        if auth and self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                raw = r.read().decode("utf-8")
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", errors="replace")
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, raw[:300]

    # ---- 인증 ----
    def login(self, force: bool = False) -> str:
        if not force and os.path.exists(TOKEN_CACHE):
            try:
                cached = json.load(open(TOKEN_CACHE, encoding="utf-8"))
                if cached.get("email") == self.email and cached.get("accessToken"):
                    self.token = cached["accessToken"]
                    st, _ = self.call("GET", "/me/vehicles")
                    if st == 200:
                        return self.token
            except Exception:
                pass
        if not self.email:
            raise SystemExit("--email 이 필요하다 (demo-host 에서 dev 로그인에 쓴 것과 같은 이메일)")
        st, res = self.call("POST", "/auth/login", {"provider": "dev", "email": self.email, "displayName": self.display_name}, auth=False)
        if st != 200:
            raise SystemExit(f"login failed: {st} {res}")
        self.token = res["accessToken"]
        os.makedirs(os.path.dirname(TOKEN_CACHE), exist_ok=True)
        json.dump({"email": self.email, "accessToken": self.token}, open(TOKEN_CACHE, "w", encoding="utf-8"))
        return self.token

    # ---- 조회 ----
    def vehicles(self) -> list:
        st, res = self.call("GET", "/me/vehicles")
        if st != 200:
            raise SystemExit(f"vehicles failed: {st} {res}")
        return res

    def active_groups(self) -> list:
        st, res = self.call("GET", "/me/groups?active=true")
        if st != 200:
            raise SystemExit(f"groups failed: {st} {res}")
        return res

    def find_vehicle(self, nickname_or_id: str | None):
        vs = self.vehicles()
        if not vs:
            return None
        if not nickname_or_id:
            return vs[0]
        for v in vs:
            if v["vehicleId"] == nickname_or_id or v.get("nickname") == nickname_or_id:
                return v
        return None

    # ---- 동작 ----
    def approve_pairing(self, user_code: str, nickname: str):
        return self.call("POST", "/device/approve", {"userCode": user_code, "nickname": nickname})

    def create_invite(self, group_id: str):
        return self.call("POST", f"/groups/{group_id}/invites", {})

    def join(self, invite_token: str, vehicle_id: str | None):
        body = {"inviteToken": invite_token}
        if vehicle_id:
            body["vehicleId"] = vehicle_id
        return self.call("POST", "/groups/join", body)

    def send_message(self, group_id: str, text: str, from_vehicle_id: str | None = None):
        body = {"text": text}
        if from_vehicle_id:
            body["fromVehicleId"] = from_vehicle_id
        return self.call("POST", f"/groups/{group_id}/messages", body)


def mask(s: str | None) -> str:
    if not s:
        return "-"
    return s if len(s) <= 8 else s[:6] + "…" + s[-2:]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--server", default=os.environ.get("DH_SERVER", "http://127.0.0.1:8080"))
    ap.add_argument("--email", default=os.environ.get("DH_DEMO_EMAIL"))
    ap.add_argument("--name", default="동행 시연", help="dev 로그인 표시 이름")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("login")
    sub.add_parser("vehicles")
    sub.add_parser("groups")
    p = sub.add_parser("pair"); p.add_argument("code"); p.add_argument("--nickname", default="DemoCarB")
    p = sub.add_parser("join"); p.add_argument("--vehicle", help="합류시킬 내 차(닉네임 또는 vehicleId, 생략 시 대표 차량)"); p.add_argument("--group", help="그룹 ID(생략 시 내 활성 그룹 중 첫 번째)")
    p = sub.add_parser("message"); p.add_argument("text"); p.add_argument("--vehicle", help="보내는 차(닉네임 또는 vehicleId)"); p.add_argument("--group")
    a = ap.parse_args()

    api = DhApi(a.server, a.email, a.name)
    api.login()
    if a.cmd == "login":
        print("login ok (token cached)")
    elif a.cmd == "vehicles":
        for v in api.vehicles():
            print(f"{mask(v['vehicleId'])}  {v.get('nickname')}  {v.get('brand')}/{v.get('model')}  primary={v.get('isPrimary')}")
    elif a.cmd == "groups":
        for g in api.active_groups():
            print(f"{mask(g['groupId'])}  {g.get('name')}  leader={mask(g.get('leaderVehicleId'))}  members={g.get('memberCount')}  dest={(g.get('destination') or {}).get('name')}  mine={[mask(x) for x in g.get('myVehicleIds', [])]}")
    elif a.cmd == "pair":
        st, res = api.approve_pairing(a.code, a.nickname)
        print(st, res if st != 200 else f"approved {mask(res.get('vehicleId'))} {res.get('nickname')}")
    elif a.cmd == "join":
        v = api.find_vehicle(a.vehicle)
        if not v:
            raise SystemExit(f"차량을 못 찾음: {a.vehicle} (vehicles 로 확인)")
        gid = a.group or (api.active_groups() or [{}])[0].get("groupId")
        if not gid:
            raise SystemExit("내 활성 그룹이 없다 — 차 A 에서 '같이 가기 시작' 먼저")
        st, inv = api.create_invite(gid)
        if st != 200:
            raise SystemExit(f"invite failed: {st} {inv}")
        st, res = api.join(inv["token"], v["vehicleId"])
        print(st, res if st != 200 else f"joined {mask(res.get('groupId'))} {res.get('name')} members={len(res.get('members', []))}")
    elif a.cmd == "message":
        gid = a.group or (api.active_groups() or [{}])[0].get("groupId")
        if not gid:
            raise SystemExit("내 활성 그룹이 없다")
        v = api.find_vehicle(a.vehicle) if a.vehicle else None
        st, res = api.send_message(gid, a.text, v["vehicleId"] if v else None)
        print(st, res if st != 201 else f"sent eventId={mask(res.get('eventId'))} speech={res.get('speech')}")


if __name__ == "__main__":
    main()
