package app.donghaeng.car.adapter.sdk

import android.content.Context
import android.content.pm.PackageManager
import app.donghaeng.car.core.common.DhLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * pleos flavor — Pleos SDK 가 classpath 에 있는지, 그리고 AVD 에 상대 서비스 앱이 깔려 있는지 점검한다 (T04).
 *
 * 클래스 이름은 Gradle 캐시의 AAR(classes.jar)에서 확인한 값이다:
 * - Vehicle 2.3.2       → `ai.pleos.playground.vehicle.Vehicle`                 (서비스 패키지 `ai.pleos.playground.service.vehicle`, 최소 2.3.13)
 * - NaviHelper 3.0.5    → `ai.pleos.playground.navi.helper.NaviHelper`
 * - FusedLocation 1.1.3 → `ai.pleos.playground.location.fused.FusedLocation`   (서비스 패키지 `ai.pleos.playground.service.location.fused`, "준비 중")
 *
 * `Class.forName(name, initialize = false, loader)` 로 **정적 초기화 없이** 존재만 확인한다 — SDK 객체를 만들거나
 * 서비스에 바인딩하지 않는다(그건 T11/T20/T21 어댑터의 일).
 */
@Singleton
class PleosSdkProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) : SdkProbe {

    private val _report = MutableStateFlow<List<ProbeLine>?>(null)
    override val report: StateFlow<List<ProbeLine>?> = _report.asStateFlow()

    override fun runOnce() {
        if (_report.value != null) return
        val lines = buildList {
            add(probeClass("Vehicle 2.3.2", VEHICLE_CLASS))
            add(probeClass("NaviHelper 3.0.5", NAVI_HELPER_CLASS))
            add(probeClass("FusedLocation 1.1.3", FUSED_LOCATION_CLASS))
            add(probePackage("Vehicle Service", VEHICLE_SERVICE_PACKAGE))
            add(probePackage("FusedLocation Service", FUSED_LOCATION_SERVICE_PACKAGE))
        }
        _report.value = lines
        lines.forEach { line ->
            DhLog.i("sdk probe: ${line.name} ${if (line.available) "OK" else "MISSING"} — ${line.detail}")
        }
    }

    private fun probeClass(name: String, className: String): ProbeLine = try {
        Class.forName(className, false, PleosSdkProbe::class.java.classLoader)
        ProbeLine(name, available = true, detail = className)
    } catch (e: ClassNotFoundException) {
        ProbeLine(name, available = false, detail = "ClassNotFoundException: $className")
    } catch (e: LinkageError) {
        ProbeLine(name, available = false, detail = "${e.javaClass.simpleName}: ${e.message}")
    }

    /** AVD 에 상대 서비스 앱이 있는지(없으면 SDK initialize 가 실패한다 — 공식 샘플 RuntimeVersionProvider 와 같은 방식). */
    private fun probePackage(name: String, packageName: String): ProbeLine {
        val versionName = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()
        return if (versionName != null) {
            ProbeLine(name, available = true, detail = "$packageName v$versionName")
        } else {
            ProbeLine(name, available = false, detail = "$packageName 미설치")
        }
    }

    private companion object {
        const val VEHICLE_CLASS = "ai.pleos.playground.vehicle.Vehicle"
        const val NAVI_HELPER_CLASS = "ai.pleos.playground.navi.helper.NaviHelper"
        const val FUSED_LOCATION_CLASS = "ai.pleos.playground.location.fused.FusedLocation"
        const val VEHICLE_SERVICE_PACKAGE = "ai.pleos.playground.service.vehicle"
        const val FUSED_LOCATION_SERVICE_PACKAGE = "ai.pleos.playground.service.location.fused"
    }
}
