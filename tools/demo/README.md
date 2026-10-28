# tools/demo — 시연 보조 스크립트 (T30)

docs/09 §4 데모 시나리오를 재현 가능하게 만드는 Python 3.10+ 스크립트. 표준 라이브러리만 쓴다(설치 없음).
adb 는 환경변수 `ADB` 또는 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` 를 찾는다.

| 파일 | 역할 |
|---|---|
| `demo.py` | 장면 4~9 타임라인 실행 + 두 차 로그 수집·확인 표 (`out/<시각>/summary.md`) |
| `replay_gpx.py` | GPX → `adb emu geo fix` 재생 (차 A, 실 SDK Pleos AVD 의 GPS) |
| `vc.py` | Pleos 에뮬레이터 Virtual Console 클라이언트 (기어·속도·브레이크·EV 잔량·방향지시등) |
| `api.py` | 서버 REST 보조 (dev 로그인, 페어링 승인, 닉네임 바꾸기, 초대·합류, 동승자 메시지) — 폰 없이 리허설할 때 |
| `soak.py` | T33 30분 연속 실행 점검 — 1분마다 두 차의 앱 pid 불변·MQTT 연결·텔레메트리 증가·그룹 STATE 나이 < 10 s·그 분(직전 점검 이후)의 logcat E/FATAL 0 확인(E/FATAL 줄은 실행 전체 누적·중복 제거로 보고서에) → `out/<시각>_soak/soak.md` |
| `test_demo.py` | 위 스크립트 단위 테스트(표준 라이브러리 unittest, adb·서버 없이) — `python -m unittest discover -s tools/demo -p "test_*.py"` |
| `ptt.py` | F16 무전 확인 — 테스트 음성 WAV 만들기(Windows 한국어 음성)·기기에 음성/Vosk 모델 넣기(`run-as`)·2대 시나리오(핸들 키 → 주입 녹음 → "보낼까요?" → 답 주입 → 보냄 → 차 B 재생)·확인 표(`out/<시각>_ptt/summary.md`) |
| `test_ptt.py` | `ptt.py` 순수 부분 단위 테스트(WAV 헤더·모델 폴더·run-as/tar 명령·`ptt_status` 해석·시나리오 단계) |

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
   폰으로 페어링(닉네임은 아무거나 — preflight 가 "둘째 차"로 맞춘다). 폰이 없으면 `python tools/demo/api.py --email <dev 이메일> pair <차 B 화면의 코드> --nickname "둘째 차"`.
4. Virtual Console 포워딩: `adb -s emulator-5556 forward tcp:8089 tcp:8088`

## 실행
```
python tools/demo/demo.py --dry-run                                   # preflight 만
python tools/demo/demo.py --email <dev 이메일> --dry-run               # preflight + 시연 닉네임 계획(바꾸지 않음)
python tools/demo/demo.py --email <dev 이메일>                         # 전체. 장면 2~3(그룹 생성·QR 합류)은 화면·폰에서 → 합류를 감지하면 자동 진행
python tools/demo/api.py --email <dev 이메일> join --vehicle "둘째 차"  # 폰 없이 차 B 합류(리허설)
python tools/demo/api.py --email <dev 이메일> rename DemoCarB "둘째 차" # 닉네임 하나만 손으로 바꾸기(차는 지금 닉네임 또는 vehicleId)
```
30분 연속 실행(T33 완료 기준): 두 차를 같은 그룹으로 주행시키는 상태(위 `demo.py` 장면 4 이후, 또는 GPX 재생 중)에서
```
python tools/demo/soak.py --car-a emulator-5556 --car-b emulator-5558 --minutes 30
```
앱이 30초마다 남기는 `health mqtt=… tel=… stateAgeMs=…` 줄과 `pidof`·`logcat *:E`·crash 버퍼만 읽는다(기기 상태를 바꾸지 않는다). 시스템 라이브러리의 E 소음은 `--ignore-tag <태그>` 로 뺀다.
결과 표(`soak.md`)와 각 차의 health·E 줄·DongHaeng 로그가 `out/<시각>_soak/` 에 남는다 — 그대로 붙여넣으면 된다.

preflight 표의 `WARN` 은 알리기만 하고 실패로 세지 않는다(`FAIL` 이 있으면 `--skip-preflight` 없이는 멈춘다).
`--no-auto-accept` 면 차 B 의 제안("선두차 목적지까지 안내할까요?", "따라갈까요?")을 화면에서 직접 누른다(30초 안에, 주행 중엔 버튼이 막히므로 정차 상태에서).

## 디버그 리시버 (debug 빌드 전용)
`adb shell am broadcast -n app.donghaeng.car/.debug.DemoControlReceiver --es action <action>` — `route_test`(선두: 덕평휴게소 경유 ROUTE_CHANGED), `accept`, `decline`,
`auto_follow --ez on true`, `speak --es text …`, `voice --ez on false`, `navi_route`(그룹 목적지로 내비 요청), `gpx --es role lead|follow`, `open --es screen home|drive|events|create|settings|diagnostics`(화면 열기), `status`, `surface`(표면 상태 한 줄), `event --es type HARD_BRAKE`(로컬 이벤트로 패널·카드·음성 확인), `tbt --ei m 120 --ei for_s 60`(카드 TBT 게이트가 읽는 다음 회전 거리 덮어쓰기, `m=-1` 해제 — docs/09 §1.3).,
`is_me --es vid <vehicleId> [--es tag <꼬리표>]`(이 차인가 — `demo: is_me=true|false tag=…` 만, id 원문 없음), `nickname --es name_b64 <UTF-8 base64>`(저장된 내 차 닉네임 바꾸기 — `demo: nickname changed=… len=N`). 결과는 `logcat -s DongHaeng` 의 `demo:` 줄.
F16 무전: `ptt_press`, `ptt_inject_record --es file ptt_test/msg_rest.wav`, `ptt_answer --es answer yes|no|none`(또는 `--es file <wav>`), `ptt_status`, `ptt_unsent [--es op resend|delete]`, `ptt_handle --ez on false`, `ptt_countdown --ez on true`, `vosk_install --es dir <폴더>` — 보통은 `ptt.py` 가 부른다.
release 빌드에는 들어가지 않는다(`src/debug`).

## 주행 중 표면 준비 (2026-10-28)
- 주행 장면에서는 두 대 모두 **순정 내비를 전면**에 둔다. 동행 정보는 내비 위 플로팅 패널(소식 줄)·음성으로 나온다 — 패널 모드에서는 헤드업 카드를 띄우지 않는다(권한이 없을 때만 알림 카드 5초, 회전 300 m 이내면 회전 후).
- 플로팅 창 권한(Pleos 에는 설정 화면이 없다): `adb -s <serial> shell appops set --user 10 app.donghaeng.car SYSTEM_ALERT_WINDOW allow`. 권한이 없으면 앱이 '알림 카드 + 음성' 모드로 바꾼다.
- 차 A(Pleos AVD, 실 SDK)는 순정 내비 위치가 고정돼 있어 위치 소스를 Android 먼저로: `… DemoControlReceiver --es action loc_order --es order android_first` → 앱 재시작. 그래야 `replay_gpx.py` 의 `geo fix` 가 동행 위치·간격에 반영된다(docs/09 §1.2). 순정 내비 화면의 내 차 아이콘은 에뮬레이터에서 움직이지 않는다.

## 시연 닉네임·그룹 이름 (2026-10-28)
- `demo.py` preflight 가 `--email` 계정의 차 닉네임을 맞춘다: 차 A = `--nick-a`(기본 "아빠 차"), 차 B = `--nick-b`("둘째 차"), 같은 계정의 나머지 차 하나(대표 차량 먼저·등록순, 있으면) = `--nick-c`("삼촌 차"). 이미 같으면 건너뛰고(`같음`), `--dry-run` 이면 `계획` 만 출력하고 바꾸지 않는다. `--email` 이 없으면 WARN 한 줄.
- 어느 vehicleId 가 차 A/B 인지: `GET /me/vehicles` 의 id 를 각 차에 `is_me` 로 물어 `demo: is_me=true` 인 것을 고른다(기기 로그에 id 원문이 남지 않는다 — `is_me` 응답에는 id 가 없고, 이어지는 `nickname` 저장·그룹 스냅샷 동기화가 남기는 `token store: saved vehicleId=…` 도 마스킹한다). 차 A·B 를 둘 다 찾았을 때만 나머지 차를 고른다 — 못 찾은 차는 FAIL(다른 계정으로 페어링했거나 앱이 응답하지 않음).
- 실패해도 표는 나온다: 서버가 꺼져 있거나 닿지 않으면 `시연 닉네임 — 서버 조회 실패(login failed: 0 연결 실패: …)` FAIL 한 줄(`DhApi.call` 은 연결 오류를 HTTP 상태 0 으로 돌려준다). 서버 `PATCH` 가 실패한 차(404·400·500·0)는 그 차 앱 저장값을 **바꾸지 않는다** — 앱만 새 이름이면 홈·그룹 기본 이름과 서버 음성·패널 이름이 어긋나고 다음 그룹 스냅샷이 옛 이름으로 되돌린다.
- 스크립트 단위 테스트(adb·서버 없이): `python -m unittest discover -s tools/demo -p "test_*.py" -v` — 닉네임 단계의 서버 꺼짐·PATCH 실패·dry-run·같음·차 A 미확인, `DhApi` 연결 실패.
- 서버 쪽 이름(그룹 스냅샷의 패널 이름·음성 문구·멤버 목록)은 서버가 매번 DB 에서 읽어 다음 스냅샷(2초)부터 바뀐다. 차 앱의 저장값(홈 "연결됨: …"·그룹 기본 이름)은 그룹 스냅샷으로도 따라오지만 그룹 전에는 알 길이 없어, preflight 가 리시버 `nickname` 으로 같은 값을 넣는다(`차 A 앱 저장 닉네임 맞춤` 행).
- 패널 머리글 = 그룹 이름. 닉네임을 맞춘 **뒤에** 차 A 에서 그룹을 만들면 기본 이름이 "아빠 차와 같이 가기"(받침에 따라 와/과). 옛 이름의 그룹이 살아 있으면 preflight 가 `(활성 그룹 '…')` 로 알려 준다 — 끝내고 새로 만든다.

## 무전(F16) — `ptt.py` (2026-10-28)
두 차가 같은 그룹일 때(장면 3 이후) 순정 내비를 앞에 두고:
```
python tools/demo/ptt.py audio                                                    # 테스트 WAV 5개 → out/ptt_audio (Microsoft Heami 한국어 음성, 16 kHz 모노 16 bit)
python tools/demo/ptt.py setup --car-a emulator-5556 --car-b emulator-5558 [--model-zip vosk-model-small-ko-0.22.zip]   # 앱 files/ptt_test·files/vosk-ko-small
python tools/demo/ptt.py run --car-a emulator-5556 --car-b emulator-5558 --shots   # yes,no,none,resend,press_cancel,countdown
python tools/demo/ptt.py run … --scenarios yes,no --answer-wav                     # Vosk 모델이 있으면 WAV '네, 보내'·'아니요, 취소' 를 실제로 인식
python tools/demo/ptt.py status --car-a emulator-5556 --car-b emulator-5558       # 두 차 ptt_status 한 줄
```
- 테스트 음성·Vosk 모델은 저장소·APK 에 넣지 않는다(합성 음성 재배포·크기). Windows 가 아니면 `--audio-dir` 로 같은 이름의 WAV(`yes_ne`·`yes_ne_bonae`·`no_ani`·`no_aniyo_chwiso`·`msg_rest`) 폴더를 준다.
- 기기에 넣기는 debug 빌드 `run-as --user <현재 사용자>`(Pleos AVD = 10)로 앱 files 아래에 tar 를 흘린다(`adb exec-in`). 안 되면 `/data/local/tmp/dh_ptt` 로 push 하고 앱에 절대 경로를 준다(모델은 리시버 `vosk_install` 이 복사).
- 기본 트리거는 `adb shell input keyevent KEYCODE_HEADSETHOOK`(핸들 키와 같은 경로). `--key SEARCH` 는 동행 화면이 앞일 때만 — 내비가 앞이면 Pleos 전역 검색이 열린다. `--trigger debug` 는 리시버 `ptt_press`.
- `resend` 는 `none` 이 남긴 '보내지 않은 무전'을 다시 보낸다 — 정차 중이어야 한다(Virtual Console 기어 P). 시나리오 사이는 4초(서버 차량당 3초 빈도 제한).
- 기대 로그·캡처 목록은 docs/09 §4.1. preflight `FAIL` 이 있으면 멈춘다(`--skip-preflight`), `--dry-run` 은 preflight + 단계 목록만.

## 리허설 사이 초기화 (실측에서 걸린 것들)
- 차 B 를 다시 합류시키려면 먼저 내보낸다: `api.py` 로 `POST /groups/{id}/leave {vehicleId}` → 차 B 앱이 자기 `MEMBER_LEFT` 를 받고 세션을 지운다(홈이 '그룹 없음' 으로). 그 뒤 `api.py join`.
- 서버는 같은 차의 `LOW_ENERGY` 를 **120 s 동안 중복 억제**한다. 차 A 앱을 재시작하면 현재 잔량이 15% 이하일 때 바로 한 번 발행하므로, 재시작 전에 `vc.py soc 80` 으로 올려 두고 2분 뒤에 시연을 시작한다(preflight 가 검사한다).
- 화면: `demo.py` 가 합류 감지 시 차 B, T0 에 두 차 모두 그룹 주행 화면을 연다(리시버 `open --es screen drive`). 제안 카드는 그룹 주행 화면에만 뜬다.
- Virtual Console 은 `speed_kph=60.0` 같은 실수 문자열을 400 으로 거부한다 — `vc.py` 가 정수로 바꿔 보낸다.

## 주의
- `out/` 은 git 에 올리지 않는다(로그에 마스킹된 ID 만 있지만 시연 환경 정보가 섞인다).
- 음성은 앱의 체인이 고른 경로로 나온다(Android TTS → Pleos TTS SDK → 화면 표시만, docs/05 §3.5). 로그 `voice: 경로 = …` 와 리시버 `status` 의 `ttsPath=` 로 확인한다.
- 차 A(실 SDK)는 내비 앱이 에뮬레이터에서 지연 상태라 `WAYPOINT_ARRIVED` 를 내지 못한다. 그래서 장면 7 은 차 B(fake 내비)가 도착하고 차 A 가 듣는 방향으로 둔다(docs/09 §4).

## 에뮬레이터 음성 (2026-10-28, R19 해결)
앱 음성은 체인으로 자동 동작한다: Android TTS(한국어 엔진·ko_KR) → **Pleos TTS SDK ON_DEVICE**(pleos flavor) → 화면 표시만. Pleos AVD 의 시스템 엔진은 Android `TextToSpeech` 에 안 보이므로(docs/10 R19) 엔진을 따로 깔지 않으면 **차 A(pleos)는 Pleos TTS 로 읽는다**(부하 시 시작까지 5~9 s).
sherpa-onnx Supertonic 3 한국어 엔진은 **음질 선택 사항**이다 — 받아 `python tools/demo/demo.py --dry-run --tts-apk <APK>` 로 두 대에 설치·기본 지정한다(없을 때만, `DH_TTS_APK` 환경 변수도 됨). 차 B(fake flavor)는 Pleos SDK 가 없어 Supertonic 이 없으면 화면 표시만이다.
preflight 표: `Supertonic 엔진`(없으면 WARN)·`기본 TTS 엔진`(있을 때만)·`앱 음성 경로 = ANDROID_TTS|PLEOS_TTS`(OK) — `DISPLAY_ONLY`(소리 없음)면 FAIL. 내려받기 주소·라이선스·품질 비교·심사 환경 재현은 docs/09 §1.4.
