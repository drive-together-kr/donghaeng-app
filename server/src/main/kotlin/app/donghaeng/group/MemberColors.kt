package app.donghaeng.group

/**
 * 멤버 표시 색 (docs/03 §2.4 `color`, `#RRGGBB`). 그룹 안에서 겹치지 않게 팔레트 순서대로 비어 있는 첫 색을 준다.
 * 팔레트(10색)가 다 차면(maxMembers 최대 10 이므로 보통 없음) 처음부터 다시 돈다.
 */
object MemberColors {
    val PALETTE: List<String> = listOf(
        "#2E7DFF", // 파랑 (docs/03 예시의 선두차 색)
        "#FF6B2E", // 주황
        "#2EC27E", // 초록
        "#FFC42E", // 노랑
        "#B22EFF", // 보라
        "#2EC9FF", // 하늘
        "#FF2E7D", // 분홍
        "#8A5A2E", // 갈색
        "#0B8A8A", // 청록
        "#6B7280", // 회색
    )

    fun next(used: Collection<String>): String {
        val taken = used.map { it.uppercase() }.toSet()
        return PALETTE.firstOrNull { it !in taken } ?: PALETTE[used.size % PALETTE.size]
    }
}
