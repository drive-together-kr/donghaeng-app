package app.donghaeng.car.core.common

import android.content.Context
import java.util.Locale
import java.util.UUID

/**
 * CarInfo 가 쓸 만한 차량 ID 를 주지 않을 때(Pleos Connect AVD 는 VIN/CRN 이 자리표시자 `"invalid"`) 페어링 `POST /device/code` 에 쓰는
 * **설치별 고정 ID**. 서버는 어차피 해시만 저장하므로 형식 제약이 없고, AVD 두 대가 서로 다른 차량으로 등록되기만 하면 된다.
 */
fun interface VehicleIdFallback {
    fun get(): String
}

/** 첫 호출에 `DHAVD-XXXXXXXXXXXX` 를 만들어 일반 SharedPreferences 에 보관한다(비밀 값이 아니다). 앱 데이터 삭제 전까지 유지. */
class InstallationVehicleIdFallback(context: Context) : VehicleIdFallback {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun get(): String {
        prefs.getString(KEY, null)?.let { return it }
        val generated = "DHAVD-" + UUID.randomUUID().toString().replace("-", "").take(12).uppercase(Locale.ROOT)
        prefs.edit().putString(KEY, generated).apply()
        DhLog.i("vehicle id fallback: generated len=${generated.length}")
        return generated
    }

    private companion object {
        const val FILE_NAME = "dh_install"
        const val KEY = "fallback_vehicle_id"
    }
}
