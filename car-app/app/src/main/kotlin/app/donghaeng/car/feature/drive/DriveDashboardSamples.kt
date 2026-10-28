package app.donghaeng.car.feature.drive

/**
 * 시안·프리뷰용 샘플 데이터 (실제 데이터 연결은 2단계). 닉네임·장소는 가상.
 * 변형 이름: drive_landscape, drive_portrait, parked, ptt_recording, ptt_confirm, ptt_countdown, ptt_sending, ptt_sent, ptt_playing,
 * ptt_notice, ptt_mic(정차 + 마이크 권한 없음), drive_light,
 * panel_dark, panel_light, panel_full, panel_worst, panel_longdest, panel_recording, panel_confirm, panel_countdown, panel_sending, panel_sent,
 * panel_playing, panel_notice, panel_mic(플로팅 패널 단독 — FloatingPanelSamples)
 */
object DriveDashboardSamples {
    const val BLUE = 0xFF2E7DFFL
    const val ORANGE = 0xFFFF7A1AL
    const val GREEN = 0xFF2ECC71L
    const val PURPLE = 0xFF9B59B6L

    // 최후미(둘째 차) 0 km 기준: 삼촌 3.6, 내 차 5.7, 아빠(선두) 6.9 → 대열 길이 6.9 km. 다음 정차 덕평휴게소는 내 차에서 18 km
    val formation = FormationBarUi(
        cars = listOf(
            FormationCar("아빠 차", BLUE, offsetKm = 6.9f, gapToMeText = "앞 1.2 km", isLeader = true),
            FormationCar("내 차", ORANGE, offsetKm = 5.7f, isMe = true),
            FormationCar("삼촌 차", PURPLE, offsetKm = 3.6f, gapToMeText = "뒤 2.1 km"),
            FormationCar("둘째 차", GREEN, offsetKm = 0f, gapToMeText = "뒤 5.7 km", lowEnergy = true, gapWarn = true),
        ),
        nextStopName = "덕평휴게소",
        nextStopOffsetKm = 23.7f,
        destinationText = "경포해변 144 km",
        formationLengthText = "대열 길이 6.9 km",
    )

    val arrival = ArrivalCardUi(groupArrivalText = "14:35", lastCarText = "마지막: 둘째 차", myArrivalText = "14:28", myRemainText = "9.5 km · 9분")

    val nextStop = NextStopCardUi(
        name = "덕평휴게소",
        distanceText = "18 km",
        perCar = listOf(NextStopCarEta("아빠", BLUE, "13:12"), NextStopCarEta("내 차", ORANGE, "13:14"), NextStopCarEta("삼촌", PURPLE, "13:15"), NextStopCarEta("둘째", GREEN, "13:19", latest = true)),
        fuelAvailable = true,
        chargeAvailable = true,
        reachable = true,
        reachableText = "내 연료로 도달 가능 · 잔량 38% ≈ 210 km",
    )

    val myDrive = MyDriveLineUi(
        speedLimitKph = 100, nextTurnText = "300 m 앞 우측 출구", nextTurnKind = TurnKind.RIGHT,
        drivingTimeText = "연속 주행 1시간 42분", restSuggestionText = "다음 휴식 추천: 덕평휴게소",
    )

    val eventCaution = EventBannerUi("둘째 차 배터리가 12% 남았어요", Tone.CAUTION, ageS = 3)
    val eventDanger = EventBannerUi("앞쪽 아빠 차가 급제동했어요. 주의하세요", Tone.DANGER, ageS = 1)

    val presets = listOf("휴게소 들르자", "먼저 가", "잠깐 멈춰", "속도 줄여요")
    val recentEvents = listOf(
        RecentEventUi("둘째 차 배터리가 12% 남았어요", Tone.CAUTION, "2분 전"),
        RecentEventUi("아빠 차가 덕평휴게소를 경유지로 추가했어요", Tone.INFO, "5분 전"),
        RecentEventUi("삼촌 차가 그룹에 들어왔어요", Tone.INFO, "21분 전"),
        RecentEventUi("아빠 차에서 온 메시지. 휴게소 들렀다 가요", Tone.INFO, "26분 전"),
        RecentEventUi("앞쪽 아빠 차가 급제동했어요", Tone.DANGER, "31분 전"),
    )
    val unsent = listOf(
        UnsentPttUi("u2", "무전 0:07 · 13:02", "대답 없음"),
        UnsentPttUi("u1", "무전 0:04 · 12:48", "보내기 실패", failed = true),
    )
    val past = listOf(
        PastPttUi("v3", "아빠 차 무전 0:05", BLUE, "1분 전"),
        PastPttUi("v2", "삼촌 차 무전 0:11", PURPLE, "4분 전", statusText = "못 들음", tone = Tone.INFO),
        PastPttUi("v1", "둘째 차 무전 0:03", GREEN, "9분 전", statusText = "다 못 들음", tone = Tone.INFO),
    )

    /** 가로 2창: 순정 내비가 옆에 보인다 → 휴식 추천 줄 */
    val driving = DriveDashboardUi(
        groupName = "강릉 가족 여행",
        formation = formation,
        arrival = arrival,
        nextStop = nextStop,
        myDrive = myDrive,
        event = eventCaution,
        connected = true,
        latencyMs = 12,
        naviVisible = true,
    )

    val parked = driving.copy(
        parked = true,
        event = null,
        myDrive = myDrive.copy(nextTurnText = null, nextTurnKind = TurnKind.NONE, drivingTimeText = "연속 주행 2시간 05분", drivingTimeWarn = true),
        presets = presets,
        recentEvents = recentEvents,
        unsentPtt = unsent,
        pastPtt = past,
    )

    fun of(variant: String): DriveDashboardUi = when (variant) {
        "parked" -> parked
        // 세로 1창: 내비가 안 보인다 → 제한속도·회전 줄 표시
        "drive_portrait" -> driving.copy(naviVisible = false)
        "ptt_recording" -> driving.copy(event = null, ptt = PttUi(PttPhase.RECORDING, seconds = 9, totalSeconds = 15, level = 0.7f))
        "ptt_confirm" -> driving.copy(event = null, ptt = PttUi(PttPhase.CONFIRMING, seconds = 4, totalSeconds = 5, listening = true))
        "ptt_countdown" -> driving.copy(event = null, ptt = PttUi(PttPhase.CONFIRMING, seconds = 3, totalSeconds = 5, fallbackCountdown = true))
        "ptt_sending" -> driving.copy(event = null, ptt = PttUi(PttPhase.SENT, sending = true))
        "ptt_sent" -> driving.copy(event = null, ptt = PttUi(PttPhase.SENT, sentTo = 3))
        "ptt_notice" -> driving.copy(event = null, ptt = PttUi(notice = app.donghaeng.car.domain.ptt.PttNotice.KEPT_UNSENT))
        "ptt_mic" -> parked.copy(ptt = PttUi(micDenied = true))
        "ptt_playing" -> driving.copy(event = eventDanger, ptt = PttUi(PttPhase.PLAYING, seconds = 7, fromName = "아빠 차", fromColorArgb = BLUE, durationText = "0:07"))
        else -> driving
    }

    val variants = listOf(
        "drive_landscape", "drive_portrait", "parked", "ptt_recording", "ptt_confirm", "ptt_countdown", "ptt_sending", "ptt_sent", "ptt_playing", "ptt_notice", "ptt_mic",
        "drive_light", "panel_dark", "panel_light", "panel_full", "panel_worst", "panel_longdest",
        "panel_recording", "panel_confirm", "panel_countdown", "panel_sending", "panel_sent", "panel_playing", "panel_notice", "panel_mic",
    )
}
