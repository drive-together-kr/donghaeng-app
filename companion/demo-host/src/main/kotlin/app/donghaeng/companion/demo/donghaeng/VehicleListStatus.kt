package app.donghaeng.companion.demo.donghaeng

import app.donghaeng.companion.sdk.MyVehicle

/**
 * 내 차 목록 영역에 무엇을 보일지(D080·RD-4) — 합류 시트·마이카 탭·동행 설정이 같은 규칙을 쓴다.
 * 목록이 비었을 때 '불러오는 중'·'불러오지 못함([다시 불러오기])'을 '계정에 연결된 차가 없어요(등록 안내)'와 구분한다 —
 * 불러오기 실패를 '차 없음'으로 보이면 이미 페어링한 사용자가 등록 QR 을 찾으러 가지만 차는 연결된 상태라 QR 이 없다.
 */
enum class VehicleListStatus {
    /** 목록이 비었고 불러오는 중(다시 불러오기 포함) */
    LOADING,

    /** 목록이 비었고 마지막 불러오기가 실패 — 오류 문구와 [다시 불러오기] */
    ERROR,

    /** 불러왔는데 계정에 차가 없음 — 등록 안내 */
    EMPTY,

    /** 차가 있음(다시 불러오다 실패해도 받아 둔 목록을 그대로 보인다) */
    LIST,
    ;

    companion object {
        fun of(vehicles: List<MyVehicle>, loading: Boolean, error: String?): VehicleListStatus = when {
            vehicles.isNotEmpty() -> LIST
            loading -> LOADING
            error != null -> ERROR
            else -> EMPTY
        }
    }
}
