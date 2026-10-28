package app.donghaeng.car.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.donghaeng.car.MainActivity
import app.donghaeng.car.R
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.LocationPermissions

/**
 * 그룹 활성 중 포그라운드 서비스 (T23, docs/05 §6). 하는 일은 알림 하나로 프로세스 우선순위를 올려
 * 다른 앱이 화면을 차지해도 텔레메트리(`TelemetryCollector`)·MQTT·(T27) 음성이 계속 돌게 하는 것뿐이다 — 수집·발행은 Application 수명의 싱글턴이 한다.
 *
 * - 시작/종료는 [DriveSessionController] 가 그룹 세션 유무를 보고 한다. 그룹이 없으면 서비스도 없다(불필요한 상주 금지).
 * - API 34+: `foregroundServiceType` 은 **위치 권한이 있으면 location, 없으면 dataSync** (location 타입은 권한 없이 시작하면 SecurityException).
 *   둘 다 실패하면 알림 없이 일반 서비스로라도 남는다(크래시 금지).
 */
class DriveSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private var lastGroupName: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val groupName = intent?.getStringExtra(EXTRA_GROUP_NAME) ?: lastGroupName
        lastGroupName = groupName
        // 상시 상태 카드(T34 조사): 제목·본문을 바꿔 다시 startForeground 하면 Pleos 상단 카드가 갱신되는지 — 주행 중 표면 후보
        val notification = buildNotification(groupName, intent?.getStringExtra(EXTRA_STATUS_TITLE), intent?.getStringExtra(EXTRA_STATUS_TEXT))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (LocationPermissions.fineGranted(this)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIFICATION_ID, notification, type)
                DhLog.i("drive session service: foreground type=${if (type == ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) "location" else "dataSync"} group=${groupName.take(20)}")
            } else {
                startForeground(NOTIFICATION_ID, notification)
                DhLog.i("drive session service: foreground (legacy)")
            }
        } catch (e: Exception) {
            DhLog.w("drive session service: startForeground 실패 ${e.javaClass.simpleName}: ${e.message?.take(100)} — 일반 서비스로 계속")
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        DhLog.i("drive session service: stopped")
        super.onDestroy()
    }

    private fun buildNotification(groupName: String, statusTitle: String? = null, statusText: String? = null): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.drive_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.drive_channel_desc)
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(statusTitle ?: getString(R.string.drive_notification_title))
            .setContentText(statusText ?: if (groupName.isBlank()) getString(R.string.drive_notification_text) else getString(R.string.drive_notification_text_named, groupName))
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "drive_session"
        const val NOTIFICATION_ID = 2301
        /** 상시 카드 문구 갱신(선택) — 없으면 기본 문구 */
        const val EXTRA_STATUS_TITLE = "statusTitle"
        const val EXTRA_STATUS_TEXT = "statusText"
        const val EXTRA_GROUP_NAME = "groupName"

        fun intent(context: Context, groupName: String): Intent =
            Intent(context, DriveSessionService::class.java).putExtra(EXTRA_GROUP_NAME, groupName)
    }
}
