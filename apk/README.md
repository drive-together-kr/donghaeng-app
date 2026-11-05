# apk — 제출용 release APK (T40)

| 파일 | 설치 대상 | 내용 |
|---|---|---|
| `DongHaeng_동행_release.apk` | 차 — Pleos Connect 에뮬레이터 v2.1.0(API 34) | 동행 차량 앱 1.0.0(pleos), 기본 서버 `https://3.24.206.147.sslip.io` · MQTT `ssl://…:8883`(막히면 `wss://…/mqtt` 자동 전환) |
| `DongHaeng_동행_demo-host_release.apk` | 폰 — 휴대폰 AVD 또는 실기기(Android 9+) | 공식 앱의 '동행' 메뉴를 재현한 시연용 호스트 1.0.0 + companion SDK. 같은 클라우드 서버, dev 간편 로그인(설치마다 다른 시연 계정) |

- 차량 앱은 QR·코드를 **보여 주기만** 한다. 스캔은 폰 앱이 하고, 폰에서 적용할 차를 고른다(차에는 카메라가 없다).
- 설치·첫 실행: 루트 [README](../README.md) '실행 방법'. 플로팅 패널 권한: `adb shell appops set --user 10 app.donghaeng.car SYSTEM_ALERT_WINDOW allow`.
- 서명: 두 APK 모두 같은 릴리스 키(인증서 DN `CN=DongHaeng, O=DongHaeng, C=KR`). 키(`*.jks`)와 서명 정보 파일은 저장소 밖에만 있고 커밋하지 않는다.
- 축소·난독화는 하지 않았다(Pleos SDK·Hilt·kotlinx.serialization 리플렉션 — 기기 검증 범위 밖의 위험). 차량 APK 약 72 MB(2026-11-05 다시 빌드 — 화면 비율 대응 포함), demo-host 약 53 MB.
- 다시 만들기: `car-app` → `./gradlew :app:assemblePleosRelease -PdhCloudHost=3.24.206.147.sslip.io -PdhReleaseSigning=<서명 정보 파일>`,
  `companion` → `./gradlew :demo-host:assembleRelease -PdhCloudHost=3.24.206.147.sslip.io -PdhDevLoginKey=<클라우드 dev 로그인 키> -PdhReleaseSigning=<서명 정보 파일>`.
