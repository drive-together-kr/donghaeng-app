package app.donghaeng.car.surface

import android.content.Context
import app.donghaeng.car.core.common.DhLog
import kotlin.math.min
import kotlin.math.roundToInt

/** 패널 모서리. 기본 [TOP_END](오른쪽 위) — 정차 중 드래그로만 바꾼다 */
enum class PanelCorner {
    TOP_END, TOP_START, BOTTOM_END, BOTTOM_START;

    val isEnd: Boolean get() = this == TOP_END || this == BOTTOM_END
    val isBottom: Boolean get() = this == BOTTOM_END || this == BOTTOM_START

    companion object {
        /** "top_end" / "TOP_END" / "te" … → 모서리, 모르면 null */
        fun parse(text: String?): PanelCorner? {
            val t = text?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == t } ?: when (t) {
                "TE", "TR", "TOP_RIGHT" -> TOP_END
                "TS", "TL", "TOP_LEFT" -> TOP_START
                "BE", "BR", "BOTTOM_RIGHT" -> BOTTOM_END
                "BS", "BL", "BOTTOM_LEFT" -> BOTTOM_START
                else -> null
            }
        }
    }
}

/**
 * 플로팅 패널 크기·위치 규칙 (docs/05 §5.2) — 순수 함수(픽셀 입력)라 단위 테스트한다.
 *
 * - 내비 화면 폭: 가로 디스플레이면 폭의 2/3(Pleos 2창 실측 — 왼쪽 853/2560 px 가 차량 영역), 세로면 전체 폭.
 * - 패널 폭 ≤ 내비 화면 폭의 22%, 높이 ≤ 디스플레이 높이의 32%(차 4대 + 소식 2줄 기준 — 소식 문장을 자르지 않는다).
 * - 글자·여백 배율 = 패널 폭 / 기준 375 dp (Pleos 2560×1440, 1 dp = 1 px 에서 1.0). 높이 상한에 못 들면 그만큼 더 줄인다.
 * - 위치(모서리별 여백): 오른쪽 위는 실측 시안 그대로(오른쪽 110 dp — 오른쪽 끝 메뉴 손잡이 회피, 위 190 dp — 내비 2D 버튼 아래),
 *   오른쪽 아래는 음량·설정 버튼 열(오른쪽 ≈150 dp)을 피하고, 왼쪽은 내비 TBT(왼쪽 위 ≈600×340 dp)·ETA 카드(왼쪽 아래 ≈455×315 dp)를 피해 그 사이에 둔다.
 *   좌표계는 오버레이 창 기준(상태 표시줄 아래부터) — docs/images/surfaces/b_floating_panel_design.png 실측.
 */
object PanelLayout {
    const val WIDTH_RATIO = 0.22f
    const val HEIGHT_RATIO = 0.32f
    const val BASE_WIDTH_DP = 375f
    /** 차 4대 + "외 N대" + 이벤트 2줄(+28 dp) + [무전](76 dp, E037) 의 기준 높이(scale 1). Pleos 2560×1440 의 32%(460 px) 안이라 배율 1 그대로 */
    const val BASE_HEIGHT_DP = 454f
    const val MIN_SCALE = 0.6f
    const val MAX_SCALE = 1.2f

    data class Geometry(val widthPx: Int, val maxHeightPx: Int, val scale: Float)

    fun naviWidthPx(displayW: Int, displayH: Int): Int = if (displayW > displayH) displayW * 2 / 3 else displayW

    fun naviLeftPx(displayW: Int, displayH: Int): Int = displayW - naviWidthPx(displayW, displayH)

    fun geometry(displayW: Int, displayH: Int, density: Float): Geometry {
        val d = density.coerceAtLeast(0.1f)
        val widthLimitPx = naviWidthPx(displayW, displayH) * WIDTH_RATIO
        val maxHeightPx = (displayH * HEIGHT_RATIO).toInt()
        val byWidth = widthLimitPx / d / BASE_WIDTH_DP
        val byHeight = maxHeightPx / d / BASE_HEIGHT_DP
        val scale = min(byWidth, byHeight).coerceIn(MIN_SCALE, MAX_SCALE)
        val widthPx = min(widthLimitPx, BASE_WIDTH_DP * scale * d).toInt()
        return Geometry(widthPx = widthPx, maxHeightPx = maxHeightPx, scale = scale)
    }

    /** 모서리 기준 여백(px) — 창 gravity 가 그 모서리일 때의 x(가로 끝에서), y(세로 끝에서) */
    fun offsetPx(corner: PanelCorner, displayW: Int, displayH: Int, density: Float): Pair<Int, Int> {
        fun dp(v: Int) = (v * density).roundToInt()
        val naviLeft = naviLeftPx(displayW, displayH)
        return when (corner) {
            PanelCorner.TOP_END -> dp(110) to dp(190)
            PanelCorner.BOTTOM_END -> dp(150) to dp(24)
            PanelCorner.TOP_START -> naviLeft + dp(24) to dp(360)
            PanelCorner.BOTTOM_START -> naviLeft + dp(24) to dp(340)
        }
    }

    /** 드래그를 놓은 위치(패널 중심, 디스플레이 좌표)에서 가장 가까운 모서리 — 좌우는 내비 화면 가운데, 위아래는 디스플레이 가운데 기준 */
    fun nearestCorner(centerX: Float, centerY: Float, displayW: Int, displayH: Int): PanelCorner {
        val naviMid = naviLeftPx(displayW, displayH) + naviWidthPx(displayW, displayH) / 2f
        val end = centerX >= naviMid
        val bottom = centerY >= displayH / 2f
        return when {
            end && !bottom -> PanelCorner.TOP_END
            end && bottom -> PanelCorner.BOTTOM_END
            !end && !bottom -> PanelCorner.TOP_START
            else -> PanelCorner.BOTTOM_START
        }
    }
}

/** 패널 모서리 저장소 — SharedPreferences("dh_surface") */
interface PanelPositionStore {
    fun load(): PanelCorner
    fun save(corner: PanelCorner)
}

class SharedPrefsPanelPositionStore(context: Context) : PanelPositionStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): PanelCorner = runCatching { PanelCorner.parse(prefs.getString(KEY_CORNER, null)) }.getOrNull() ?: PanelCorner.TOP_END

    override fun save(corner: PanelCorner) {
        runCatching { prefs.edit().putString(KEY_CORNER, corner.name).apply() }
            .onFailure { DhLog.w("surface: 패널 위치 저장 실패 ${it.javaClass.simpleName}") }
    }

    companion object {
        const val PREFS = "dh_surface"
        const val KEY_CORNER = "panel_corner"
    }
}

class InMemoryPanelPositionStore(var corner: PanelCorner = PanelCorner.TOP_END) : PanelPositionStore {
    override fun load(): PanelCorner = corner
    override fun save(corner: PanelCorner) { this.corner = corner }
}
