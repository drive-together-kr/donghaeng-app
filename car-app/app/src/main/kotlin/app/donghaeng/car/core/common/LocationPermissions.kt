package app.donghaeng.car.core.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 위치 관련 런타임 권한 (docs/05 §1.2).
 *
 * - `ACCESS_FINE_LOCATION`(+COARSE) 과 API 33+ `POST_NOTIFICATIONS` 는 dangerous → **그룹 세션이 시작된 뒤 정차 상태에서 1회** 요청한다.
 *   F16 무전의 `RECORD_AUDIO` 도 같은 다이얼로그에 묶는다(따로 묻지 않는다).
 * - `pleos.car.permission.FUSED_LOCATION` 은 Pleos AVD 실측 **dangerous**(2026-11-02 `pm list permissions -f`) — 이 앱 매니페스트에 선언돼 있고
 *   기기에 runtime(dangerous) 권한으로 정의돼 있을 때만 같은 다이얼로그로 요청한다(A-RT2). pleos flavor 에만 선언 — fake 는 요청하지 않는다.
 *   승인되면 위치 체인 재탐색(LocationFeed.restart)으로 FUSED 가 앞에 붙는다(규칙 5 — isSupported·승인 둘 다일 때만, 거부해도 다른 소스로 계속).
 * - 거부되면 위치 체인은 다음 순위(Fake)로 내려가고 속도는 위치 계산값을 쓴다 — 기능은 계속 동작한다.
 */
object LocationPermissions {
    const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
    const val CAR_SPEED = "android.car.permission.CAR_SPEED"

    /**
     * 런타임 요청 목록(순수 함수 — 단위 테스트). [fusedRequestable] = FUSED_LOCATION 이 이 앱에 선언되고 기기에서 dangerous 로 정의됨.
     */
    fun runtimePermissionList(sdkInt: Int, automotive: Boolean, fusedRequestable: Boolean): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        // 포그라운드 서비스 알림 + 주행 중 이벤트 알림 카드(surface.EventCardNotifier, docs/05 §5.2) — 거부되면 카드 없이 패널·음성만
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        // 자동차 기기에서만: Car API 속도(PERF_VEHICLE_SPEED) 용 dangerous 권한. 거부 시 위치 계산 속도로 폴백(T22)
        if (automotive) add(CAR_SPEED)
        // F16 무전 녹음·'네/아니' 인식(MicPermission). 거부되면 무전 버튼이 "마이크 권한 필요" — 받은 무전 재생은 그대로
        add(Manifest.permission.RECORD_AUDIO)
        // A-RT2: Pleos FusedLocation(선택적) — 선언·dangerous 일 때만
        if (fusedRequestable) add(FUSED_LOCATION)
    }

    /** FUSED_LOCATION 을 런타임으로 요청할 수 있나 — 이 앱이 선언했고(pleos flavor) 기기가 dangerous 로 정의했다. 확인 실패는 false(요청 안 함) */
    fun fusedRequestable(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val requested = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(FUSED_LOCATION) == true
        if (!requested) return@runCatching false
        val info = pm.getPermissionInfo(FUSED_LOCATION, 0)
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.protection else info.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE
        base == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)

    fun fineGranted(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun fusedGranted(context: Context): Boolean = granted(context, FUSED_LOCATION)

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    /** 런타임 요청 대상 전체(승인 여부와 무관) — 요청 다이얼로그 한 번에 묶는다 */
    fun runtimePermissions(context: Context): Array<String> = runtimePermissionList(
        sdkInt = Build.VERSION.SDK_INT,
        automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
        fusedRequestable = fusedRequestable(context),
    ).toTypedArray()

    fun carSpeedGranted(context: Context): Boolean = granted(context, CAR_SPEED)

    /** 아직 승인되지 않은 런타임 권한. 비어 있으면 요청할 것이 없다 */
    fun missing(context: Context): List<String> = runtimePermissions(context).filterNot { granted(context, it) }

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
