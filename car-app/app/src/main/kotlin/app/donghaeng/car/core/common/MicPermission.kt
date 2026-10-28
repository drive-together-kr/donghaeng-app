package app.donghaeng.car.core.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * F16 무전 마이크 권한(`RECORD_AUDIO`, dangerous) 상태. 요청은 위치·알림 권한과 같은 시점(그룹 활성 + 정차, `LocationPermissionGate`)에
 * 한 다이얼로그로 묶는다([LocationPermissions.runtimePermissions]). 거부면 무전 버튼이 "마이크 권한 필요"를 보이고(정차 중에만 [권한 열기]),
 * 받은 무전 재생은 그대로 된다.
 */
class MicPermission(private val check: () -> Boolean) {
    private val _granted = MutableStateFlow(runCatching(check).getOrDefault(false))
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    /** 지금 값을 다시 읽는다 — 권한 다이얼로그 결과·화면 복귀·무전 버튼 누름 때 */
    fun refresh(): Boolean = runCatching(check).getOrDefault(false).also { _granted.value = it }

    companion object {
        fun android(context: Context): MicPermission {
            val app = context.applicationContext
            return MicPermission { app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
        }
    }
}
