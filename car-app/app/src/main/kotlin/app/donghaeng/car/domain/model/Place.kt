package app.donghaeng.car.domain.model

import app.donghaeng.car.core.common.CoordinateMasking

/**
 * 목적지·경유지 지점.
 *
 * 메시지 스키마는 protocol/ 에만 정의한다는 규칙(CLAUDE.md 7)에 따라 별도 클래스를 만들지 않고
 * `app.donghaeng.protocol.Place(lat, lon, name, poiId, address)` 를 도메인 타입으로 그대로 쓴다.
 * 내비(NaviHelper `RouteInfo`)와 서버(ROUTE_CHANGED / GROUP_JOINED destination)가 같은 형태를 쓰므로
 * 어댑터 안에서 SDK 타입 ↔ Place 변환만 하면 된다.
 */
typealias Place = app.donghaeng.protocol.Place

/** 로그용 문자열 — 좌표는 마스킹, 이름은 그대로(장소명은 개인정보가 아님). */
fun Place.toLogString(): String = "Place(${name}, ${CoordinateMasking.mask(lat, lon)}, poiId=${poiId ?: "-"})"
