package app.donghaeng.companion.demo

import android.app.Application
import android.util.Log

/** 시연용 호스트 앱. 실제 공식 앱에서는 이 자리에서 동행 모듈 초기화 시점(로그인 직후)을 정한다. */
class DemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "demo-host started base=${BuildConfig.DH_PUBLIC_BASE_URL} version=${BuildConfig.VERSION_NAME}")
    }

    companion object {
        const val TAG = "DongHaeng"
    }
}
