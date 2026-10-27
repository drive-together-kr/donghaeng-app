package app.donghaeng.car

import android.app.Application
import app.donghaeng.car.adapter.sdk.SdkProbe
import app.donghaeng.car.core.common.DhLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 동행 차량 앱 Application.
 *
 * 시작 로그 한 줄(`car app started …`)은 AVD 기동 확인용이다 — `adb logcat -s DongHaeng`.
 * 네트워크·MQTT 연결은 여기서 하지 않는다(그룹 활성 시 DriveSessionService 가 담당, docs/05 §6).
 */
@HiltAndroidApp
class DongHaengApp : Application() {

    @Inject
    lateinit var sdkProbe: SdkProbe

    override fun onCreate() {
        super.onCreate()
        // android.util.Log 는 DhLog 에서만 직접 호출한다(규칙 8 마스킹 단일 창구)
        DhLog.i("car app started flavor=${BuildConfig.FLAVOR} base=${BuildConfig.DH_PUBLIC_BASE_URL} mqtt=${BuildConfig.DH_MQTT_PUBLIC_URI}")
        DhLog.i("version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) adapter=${BuildConfig.ADAPTER} debug=${BuildConfig.DEBUG}")
        sdkProbe.runOnce()
    }
}
