package app.donghaeng.car.feature.drive

/**
 * 시안·프리뷰용 샘플 데이터 (실제 데이터 연결은 2단계). 닉네임·장소는 가상.
 * 변형 이름: drive_landscape, drive_portrait, parked, ptt_recording, ptt_confirm, ptt_sent, ptt_playing, drive_light
 */
object DriveDashboardSamples {
    const val BLUE = 0xFF2E7DFFL
    const val ORANGE = 0xFFFF7A1AL
    const val GREEN = 0xFF2ECC71L
    const val PURPLE = 0xFF9B59B6L

    val formation = FormationBarUi(
        cars = listOf(
            FormationCar("아빠 차", BLUE, progress = 0.62f, isLeader = true),
            FormationCar("내 차", ORANGE, progress = 0.55f, gapText = "+1.2 km / +2분", isMe = true),
            FormationCar("둘째 차", GREEN, progress = 0.38f, gapText = "+4.8 km / +6분", lowEnergy = true, gapWarn = true),
            FormationCar("삼촌 차", PURPLE, progress = 0.50f, gapText = "+2.1 km / +3분"),
        ),
        destinationName = "경포해변",
        routeText = "남은 144 km",
    )

    val arrival = ArrivalCardUi(groupArrivalText = "14:35", lastCarText = "마지막: 둘째 차", myArrivalText = "14:28", myRemainText = "9.5 km · 9분")

    val nextStop = NextStopCardUi(
        name = "덕평휴게소",
        distanceText = "18 km",
        perCar = listOf(NextStopCarEta("아빠 차", BLUE, "13:12"), NextStopCarEta("내 차", ORANGE, "13:14"), NextStopCarEta("삼촌 차", PURPLE, "13:15"), NextStopCarEta("둘째 차", GREEN, "13:19")),
        fuelAvailable = true,
        chargeAvailable = true,
        reachable = true,
        reachableText = "내 연료로 도달 가능 · 잔량 38% ≈ 210 km",
    )

    val myDrive = MyDriveLineUi(speedLimitKph = 100, nextTurnText = "300 m 앞 우측 출구", nextTurnKind = TurnKind.RIGHT, drivingTimeText = "연속 주행 1시간 42분")

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
    val unsent = listOf("무전 0:07 · 13:02", "무전 0:04 · 12:48")

    val driving = DriveDashboardUi(
        groupName = "강릉 가족 여행",
        formation = formation,
        arrival = arrival,
        nextStop = nextStop,
        myDrive = myDrive,
        event = eventCaution,
        connected = true,
        latencyMs = 12,
    )

    val parked = driving.copy(
        parked = true,
        event = null,
        myDrive = myDrive.copy(nextTurnText = null, nextTurnKind = TurnKind.NONE, drivingTimeText = "연속 주행 2시간 05분", drivingTimeWarn = true),
        presets = presets,
        recentEvents = recentEvents,
        unsentPtt = unsent,
    )

    fun of(variant: String): DriveDashboardUi = when (variant) {
        "parked" -> parked
        "ptt_recording" -> driving.copy(event = null, ptt = PttUi(PttPhase.RECORDING, seconds = 9, level = 0.7f))
        "ptt_confirm" -> driving.copy(event = null, ptt = PttUi(PttPhase.CONFIRMING, seconds = 4, listening = true))
        "ptt_sent" -> driving.copy(event = null, ptt = PttUi(PttPhase.SENT, sentTo = 3))
        "ptt_playing" -> driving.copy(event = eventDanger, ptt = PttUi(PttPhase.PLAYING, seconds = 7, fromName = "아빠 차", fromColorArgb = BLUE))
        else -> driving
    }

    val variants = listOf("drive_landscape", "drive_portrait", "parked", "ptt_recording", "ptt_confirm", "ptt_sent", "ptt_playing", "drive_light")
}
