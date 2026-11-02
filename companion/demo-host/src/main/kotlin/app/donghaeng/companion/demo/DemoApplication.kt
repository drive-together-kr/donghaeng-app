package app.donghaeng.companion.demo

import android.app.Application
import android.util.Log
import app.donghaeng.companion.demo.donghaeng.DongHaengNotifier
import app.donghaeng.companion.demo.donghaeng.DongHaengStore

/** 시연용 호스트 앱. 실제 공식 앱에서는 이 자리에서 동행 모듈 초기화 시점(로그인 직후)을 정한다. */
class DemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        DemoPrefs.init(this)
        DongHaengStore.storage = DemoPrefs
        // 시스템 알림 출구는 Application 수명으로 — Activity 를 붙잡지 않고, Activity 가 없을 때도 알림이 간다(D086)
        DongHaengNotifier.ensureChannel(this)
        val app = this
        DongHaengStore.notifier = { DongHaengNotifier.show(app, it) }
        Log.i(TAG, "demo-host started base=${BuildConfig.DH_PUBLIC_BASE_URL} version=${BuildConfig.VERSION_NAME}")
    }

    companion object {
        const val TAG = "DongHaeng"
    }
}
