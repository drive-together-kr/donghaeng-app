package app.donghaeng.car.core.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 위치 관련 런타임 권한 (docs/05 §1.2).
 *
 * - `ACCESS_FINE_LOCATION`(+COARSE) 과 API 33+ `POST_NOTIFICATIONS` 는 dangerous → **그룹 세션이 시작된 뒤 정차 상태에서 1회** 요청한다.
 * - `pleos.car.permission.FUSED_LOCATION` 은 normal(설치 시 자동 승인) 이라 요청하지 않고 승인 여부만 본다.
 * - 거부되면 위치 체인은 다음 순위(Fake)로 내려가고 속도는 위치 계산값을 쓴다 — 기능은 계속 동작한다.
 */
object LocationPermissions {
    const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
    const val CAR_SPEED = "android.car.permission.CAR_SPEED"

    fun fineGranted(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun fusedGranted(context: Context): Boolean = granted(context, FUSED_LOCATION)

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    /** 런타임 요청 대상 전체(승인 여부와 무관) — 요청 다이얼로그 한 번에 묶는다 */
    fun runtimePermissions(context: Context): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        // 자동차 기기에서만: Car API 속도(PERF_VEHICLE_SPEED) 용 dangerous 권한. 거부 시 위치 계산 속도로 폴백(T22)
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) add(CAR_SPEED)
    }.toTypedArray()

    fun carSpeedGranted(context: Context): Boolean = granted(context, CAR_SPEED)

    /** 아직 승인되지 않은 런타임 권한. 비어 있으면 요청할 것이 없다 */
    fun missing(context: Context): List<String> = runtimePermissions(context).filterNot { granted(context, it) }

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
