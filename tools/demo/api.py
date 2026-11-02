#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""동행 서버 REST 보조 (사용자 JWT 쪽 — 공식 앱/demo-host 가 하는 일을 터미널에서) — T30, docs/03 §1·§2.

폰(demo-host)이 없을 때 리허설용이다. 실제 시연에서는 공식 앱(demo-host)의 QR 스캔으로 같은 API 가 호출된다.

  python tools/demo/api.py --email dev@example.com login                       # dev 간편 로그인(토큰 캐시 tools/demo/out/.token)
  python tools/demo/api.py --email dev@example.com vehicles                    # 내 차 목록
  python tools/demo/api.py --email dev@example.com pair KQ7-4MZ --nickname "둘째 차"   # 차 화면의 코드로 페어링 승인
  python tools/demo/api.py --email dev@example.com rename DemoCarB "둘째 차"   # 내 차 닉네임 바꾸기(PATCH /me/vehicles/{id}) — 차는 지금 닉네임 또는 vehicleId
  python tools/demo/api.py --email dev@example.com groups                      # 내 활성 그룹
  python tools/demo/api.py --email dev@example.com join --vehicle "둘째 차"    # 내 활성 그룹(선두차 A 의 그룹)에 새 초대를 만들어 차 B 합류
  python tools/demo/api.py --email dev@example.com message "휴게소 들렀다 가요" # 동승자 메시지(PASSENGER_MESSAGE)

서버 주소 기본값 http://127.0.0.1:8080 (환경변수 DH_SERVER). 토큰·이메일은 로그에 남기지 않는다.
클라우드 서버(T32)는 dev 로그인에 접속 키를 요구한다 → 환경변수 DH_DEV_LOGIN_KEY(VM infra/.env 의 같은 이름 값)를 주면
X-Dev-Login-Key 헤더로 보낸다(docs/03 §1.1, infra/cloud/README.md §3). demo.py 도 이 클래스를 쓰므로 같은 환경변수로 된다.
"""
import argparse
import http.client
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
        self.dev_login_key = (os.environ.get("DH_DEV_LOGIN_KEY") or "").strip()

    # ---- HTTP ----
    def call(self, method: str, path: str, body=None, auth: bool = True, accept_json: bool = True, headers: dict | None = None):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
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
        except (urllib.error.URLError, OSError, http.client.HTTPException) as e:
            # 서버가 꺼졌거나 닿지 않음(연결 거부·시간 초과·끊김) → HTTP 상태 0 으로 돌려준다.
            # 예외를 그대로 올리면 demo.py preflight 처럼 SystemExit 만 잡는 호출자가 traceback 으로 죽어 진단 표가 안 나온다(2026-10-28 리뷰).
            # 상태를 보는 login()·vehicles()·active_groups() 는 200 이 아니면 SystemExit 로 바꾼다.
            reason = getattr(e, "reason", None) or e
            return 0, f"연결 실패: {type(e).__name__} {reason}"[:300]

    # ---- 인증 ----
    def login(self, force: bool = False) -> str:
        if not force and os.path.exists(TOKEN_CACHE):
            try:
                cached = json.load(open(TOKEN_CACHE, encoding="utf-8"))
                # 서버별로 구분한다 — 로컬 http 서버에 클라우드 토큰을(또는 그 반대로) 보내지 않게(점검 F095)
                if cached.get("email") == self.email and cached.get("server") == self.base and cached.get("accessToken"):
                    self.token = cached["accessToken"]
                    st, _ = self.call("GET", "/me/vehicles")
                    if st == 200:
                        return self.token
            except Exception:
                pass
        if not self.email:
            raise SystemExit("--email 이 필요하다 (demo-host 에서 dev 로그인에 쓴 것과 같은 이메일)")
        key_header = {"X-Dev-Login-Key": self.dev_login_key} if self.dev_login_key else None
        st, res = self.call("POST", "/auth/login", {"provider": "dev", "email": self.email, "displayName": self.display_name}, auth=False, headers=key_header)
        if st == 403 and not self.dev_login_key:
            raise SystemExit("login failed: 403 — 이 서버는 dev 로그인 접속 키가 필요하다. 환경변수 DH_DEV_LOGIN_KEY 를 주고 다시 실행 (infra/cloud/README.md §3)")
        if st != 200:
            raise SystemExit(f"login failed: {st} {res}")
        self.token = res["accessToken"]
        os.makedirs(os.path.dirname(TOKEN_CACHE), exist_ok=True)
        with open(TOKEN_CACHE, "w", encoding="utf-8") as f:
            json.dump({"email": self.email, "server": self.base, "accessToken": self.token}, f)
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

    def rename_vehicle(self, vehicle_id: str, nickname: str):
        """PATCH /me/vehicles/{vehicleId} {nickname} (docs/03 §1.3) → 200 MyVehicleDto / 404 내 차 아님 / 400 1~30자 아님.
        그룹 스냅샷·이벤트 문구는 서버가 매번 DB 에서 닉네임을 읽으므로 다음 스냅샷(2초)부터 바뀐다."""
        return self.call("PATCH", f"/me/vehicles/{vehicle_id}", {"nickname": nickname})

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
    # 출력을 파이프로 받으면 Windows 는 cp949 로 써서 "—" 같은 글자에서 죽는다(--help 포함) → 못 쓰는 글자는 ? 로
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--server", default=os.environ.get("DH_SERVER", "http://127.0.0.1:8080"))
    ap.add_argument("--email", default=os.environ.get("DH_DEMO_EMAIL"))
    ap.add_argument("--name", default="동행 시연", help="dev 로그인 표시 이름")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("login")
    sub.add_parser("vehicles")
    sub.add_parser("groups")
    p = sub.add_parser("pair"); p.add_argument("code"); p.add_argument("--nickname", default="둘째 차")
    p = sub.add_parser("rename", help="내 차 닉네임 바꾸기"); p.add_argument("vehicle", help="바꿀 차(지금 닉네임 또는 vehicleId)"); p.add_argument("nickname", help="새 닉네임(1~30자)")
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
    elif a.cmd == "rename":
        v = api.find_vehicle(a.vehicle)
        if not v:
            raise SystemExit(f"차량을 못 찾음: {a.vehicle} (vehicles 로 확인)")
        new = a.nickname.strip()
        if v.get("nickname") == new:
            print(f"skip {mask(v['vehicleId'])} 이미 '{new}'")
        else:
            st, res = api.rename_vehicle(v["vehicleId"], new)
            print(st, res if st != 200 else f"renamed {mask(res.get('vehicleId'))} '{v.get('nickname')}' → '{res.get('nickname')}'")
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
