# tools/demo — 시연 보조 스크립트 (T30)

docs/09 §4 데모 시나리오를 재현 가능하게 만드는 Python 3.10+ 스크립트. 표준 라이브러리만 쓴다(설치 없음).
adb 는 환경변수 `ADB` 또는 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` 를 찾는다.

| 파일 | 역할 |
|---|---|
| `demo.py` | 장면 4~9 타임라인 실행 + 두 차 로그 수집·확인 표 (`out/<시각>/summary.md`) |
| `replay_gpx.py` | GPX → `adb emu geo fix` 재생 (차 A, 실 SDK Pleos AVD 의 GPS) |
| `vc.py` | Pleos 에뮬레이터 Virtual Console 클라이언트 (기어·속도·브레이크·EV 잔량·방향지시등) |
| `api.py` | 서버 REST 보조 (dev 로그인, 페어링 승인, 초대·합류, 동승자 메시지) — 폰 없이 리허설할 때 |
| `soak.py` | T33 30분 연속 실행 점검 — 1분마다 두 차의 앱 pid 불변·MQTT 연결·텔레메트리 증가·그룹 STATE 나이 < 10 s·logcat E/FATAL 0 확인 → `out/<시각>_soak/soak.md` |

GPX 는 `car-app/app/src/main/assets/gpx/{lead,follow}.gpx` 를 그대로 쓴다(단일 원본). 두 파일은 같은 가상 복도(직선 보간, 실제 도로·주소와 무관)를
선두가 1.2 km 앞서 달리는 구성이며 덕평휴게소 좌표(37.2963, 127.4120)를 지난다 — `RouteSyncEngine.DEMO_WAYPOINT` 와 같아 차 B(fake)의 `WaypointArrived` 가 난다.

## 준비
1. 서버: `cd infra && docker compose up -d`
2. 차 A = Pleos AVD(`emulator-5556`): `:app:assemblePleosDebug` 설치, Wi-Fi 끄기(`adb -s emulator-5556 shell svc wifi disable`), 폰으로 페어링.
3. 차 B = Pleos_B AVD(`emulator-5558`, 같은 Pleos 이미지의 복제 AVD): `:app:assembleFakeDebug` 설치 → GPX 역할을 후속차로:
   ```
   adb -s emulator-5558 shell am broadcast -n app.donghaeng.car/.debug.DemoControlReceiver --es action gpx --es role follow
   adb -s emulator-5558 shell am force-stop app.donghaeng.car   # 재시작 후 적용
   ```
   폰으로 페어링(닉네임 DemoCarB). 폰이 없으면 `python tools/demo/api.py --email <dev 이메일> pair <차 B 화면의 코드> --nickname DemoCarB`.
4. Virtual Console 포워딩: `adb -s emulator-5556 forward tcp:8089 tcp:8088`

## 실행
```
python tools/demo/demo.py --dry-run                                   # preflight 만
python tools/demo/demo.py --email <dev 이메일>                         # 전체. 장면 2~3(그룹 생성·QR 합류)은 화면·폰에서 → 합류를 감지하면 자동 진행
python tools/demo/api.py --email <dev 이메일> join --vehicle DemoCarB  # 폰 없이 차 B 합류(리허설)
```
30분 연속 실행(T33 완료 기준): 두 차를 같은 그룹으로 주행시키는 상태(위 `demo.py` 장면 4 이후, 또는 GPX 재생 중)에서
```
python tools/demo/soak.py --car-a emulator-5556 --car-b emulator-5558 --minutes 30
```
앱이 30초마다 남기는 `health mqtt=… tel=… stateAgeMs=…` 줄과 `pidof`·`logcat *:E`·crash 버퍼만 읽는다(기기 상태를 바꾸지 않는다). 시스템 라이브러리의 E 소음은 `--ignore-tag <태그>` 로 뺀다.
결과 표(`soak.md`)와 각 차의 health·E 줄·DongHaeng 로그가 `out/<시각>_soak/` 에 남는다 — 그대로 붙여넣으면 된다.

`--no-auto-accept` 면 차 B 의 제안("선두차 목적지까지 안내할까요?", "따라갈까요?")을 화면에서 직접 누른다(30초 안에, 주행 중엔 버튼이 막히므로 정차 상태에서).

## 디버그 리시버 (debug 빌드 전용)
`adb shell am broadcast -n app.donghaeng.car/.debug.DemoControlReceiver --es action <action>` — `route_test`(선두: 덕평휴게소 경유 ROUTE_CHANGED), `accept`, `decline`,
`auto_follow --ez on true`, `speak --es text …`, `voice --ez on false`, `navi_route`(그룹 목적지로 내비 요청), `gpx --es role lead|follow`, `open --es screen home|drive|events|create|settings|diagnostics`(화면 열기), `status`. 결과는 `logcat -s DongHaeng` 의 `demo:` 줄.
release 빌드에는 들어가지 않는다(`src/debug`).

## 리허설 사이 초기화 (실측에서 걸린 것들)
- 차 B 를 다시 합류시키려면 먼저 내보낸다: `api.py` 로 `POST /groups/{id}/leave {vehicleId}` → 차 B 앱이 자기 `MEMBER_LEFT` 를 받고 세션을 지운다(홈이 '그룹 없음' 으로). 그 뒤 `api.py join`.
- 서버는 같은 차의 `LOW_ENERGY` 를 **120 s 동안 중복 억제**한다. 차 A 앱을 재시작하면 현재 잔량이 15% 이하일 때 바로 한 번 발행하므로, 재시작 전에 `vc.py soc 80` 으로 올려 두고 2분 뒤에 시연을 시작한다(preflight 가 검사한다).
- 화면: `demo.py` 가 합류 감지 시 차 B, T0 에 두 차 모두 그룹 주행 화면을 연다(리시버 `open --es screen drive`). 제안 카드는 그룹 주행 화면에만 뜬다.
- Virtual Console 은 `speed_kph=60.0` 같은 실수 문자열을 400 으로 거부한다 — `vc.py` 가 정수로 바꿔 보낸다.

## 주의
- `out/` 은 git 에 올리지 않는다(로그에 마스킹된 ID 만 있지만 시연 환경 정보가 섞인다).
- Pleos AVD 에는 TTS 엔진이 없어 음성은 `voice: speak …` 로그 폴백으로 확인한다. 실제 음성은 TTS 엔진이 있는 기기(실차·일반 폰 AVD)에서 난다.
- 차 A(실 SDK)는 내비 앱이 에뮬레이터에서 지연 상태라 `WAYPOINT_ARRIVED` 를 내지 못한다. 그래서 장면 7 은 차 B(fake 내비)가 도착하고 차 A 가 듣는 방향으로 둔다(docs/09 §4).
