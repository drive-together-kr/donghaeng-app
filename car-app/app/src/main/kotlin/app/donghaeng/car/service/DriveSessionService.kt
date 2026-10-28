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
import androidx.core.app.NotificationCompat
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
 *   둘 다 실패하면 알림 없이 일반 서비스로라도 남는다(크래시 없음).
 * - **조용한 상시 알림(docs/05 §5.2, 2026-10-28):** 옛 채널 `drive_session`(IMPORTANCE_LOW, category navigation)은 Pleos 에서 상단 카드로 계속 떠
 *   순정 내비 TBT 를 가렸다(docs/images/surfaces/b_floating_panel_design.png). 새 채널 `drive_session_quiet` 는 IMPORTANCE_MIN(소리·진동·배지·헤드업 없음),
 *   알림은 무음·한 번만·PRIORITY_MIN·CATEGORY_SERVICE 이고 **문구는 고정**("동행 그룹 주행 중") — 내용을 갱신하지 않는다.
 *   내비 위에 뜨는 것은 플로팅 패널과 이벤트 알림 카드(8초)뿐이다. 옛 채널은 지운다.
 */
class DriveSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (LocationPermissions.fineGranted(this)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIFICATION_ID, notification, type)
                DhLog.i("drive session service: foreground type=${if (type == ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) "location" else "dataSync"} channel=$CHANNEL_ID (quiet)")
            } else {
                startForeground(NOTIFICATION_ID, notification)
                DhLog.i("drive session service: foreground (legacy) channel=$CHANNEL_ID (quiet)")
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

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
                // 그 채널로 포그라운드 알림이 떠 있으면 SecurityException — 다음 시작 때 다시 시도한다
                runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
                    .onSuccess { DhLog.i("drive session service: 옛 채널 $LEGACY_CHANNEL_ID 삭제") }
                    .onFailure { DhLog.w("drive session service: 옛 채널 삭제 실패 ${it.javaClass.simpleName}") }
            }
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.surface_fgs_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                        description = getString(R.string.surface_fgs_channel_desc)
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                        enableLights(false)
                    },
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.surface_fgs_title))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "drive_session_quiet"
        /** 2026-10-28 이전 채널(IMPORTANCE_LOW) — 남아 있으면 지운다 */
        const val LEGACY_CHANNEL_ID = "drive_session"
        const val NOTIFICATION_ID = 2301

        fun intent(context: Context): Intent = Intent(context, DriveSessionService::class.java)
    }
}
