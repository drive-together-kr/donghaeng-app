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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 그룹 활성 중 포그라운드 서비스 (T23, docs/05 §6). 하는 일은 알림 하나로 프로세스 우선순위를 올려
 * 다른 앱이 화면을 차지해도 텔레메트리(`TelemetryCollector`)·MQTT·(T27) 음성이 계속 돌게 하는 것뿐이다 — 수집·발행은 Application 수명의 싱글턴이 한다.
 *
 * - 시작/종료는 [DriveSessionController] 가 그룹 세션 유무를 보고 한다. 그룹이 없으면 서비스도 없다(불필요한 상주 금지).
 * - API 34+: `foregroundServiceType` 은 **위치 권한이 있으면 location, 없으면 dataSync** (location 타입은 권한 없이 시작하면 SecurityException).
 *   둘 다 실패하면 알림 없이 일반 서비스로라도 남는다(크래시 없음).
 * - **F16 무전**: 마이크 권한이 있으면 `| microphone`(API 30+) — 순정 내비가 앞이라 우리 앱이 백그라운드여도 녹음이 무음이 되지 않게(while-in-use 권한은
 *   그 타입의 포그라운드 서비스가 있어야 쓸 수 있다). 앱이 앞에 있을 때 시작해야 붙으므로 권한 다이얼로그 결과 뒤 [DriveSessionController.refreshType] 으로 다시 시작한다.
 *   막히면 microphone 을 빼고 → dataSync 순으로 다시 시도([typeCandidates]). 실제로 붙은 타입은 [attachedType] — 백그라운드에서 시작(폰 합류·START_STICKY 재시작)돼
 *   microphone·location 이 빠졌으면 동행 화면이 앞에 올 때 [DriveSessionController.ensureType] 이 다시 시작해 올린다([needsUpgrade]).
 * - **조용한 상시 알림(docs/05 §5.2, 2026-10-28):** 옛 채널 `drive_session`(IMPORTANCE_LOW, category navigation)은 Pleos 에서 상단 카드로 계속 떠
 *   순정 내비 TBT 를 가렸다(docs/images/surfaces/b_floating_panel_design.png). 새 채널 `drive_session_quiet` 는 IMPORTANCE_MIN 으로 **만들지만**
 *   포그라운드 서비스 알림이라 Android(NotificationManagerService)가 사용자 설정이 없으면 **LOW 로 올린다**(dumpsys 에 importance=2 로 보일 수 있음).
 *   LOW 여도 소리·진동·헤드업은 없다. Pleos 상단 카드를 막는 핵심은 **category service**(category navigation 이 카드로 뜨던 원인 — 78dcafd 프로브는
 *   FGS 가 아닌 ongoing 알림으로 확인, FGS 알림 실측은 메인 세션 확인 명령으로). 알림은 무음·한 번만·PRIORITY_MIN·CATEGORY_SERVICE 이고
 *   **문구는 고정**("동행 그룹 주행 중") — 내용을 갱신하지 않는다. 내비 위에 뜨는 것은 플로팅 패널과(권한이 없을 때만) 이벤트 알림 카드(5초)뿐이다. 옛 채널은 지운다.
 */
class DriveSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
                .onSuccess {
                    _attachedType.value = LEGACY_TYPE
                    DhLog.i("drive session service: foreground (legacy) channel=$CHANNEL_ID (quiet)")
                }
                .onFailure { DhLog.w("drive session service: startForeground 실패 ${it.javaClass.simpleName} — 일반 서비스로 계속") }
            return START_STICKY
        }
        // F16: 마이크 권한이 있으면 microphone 타입을 더한다 — 순정 내비가 앞(우리 앱은 백그라운드)일 때 무전 녹음이 무음이 되지 않게(Android 11+ while-in-use).
        // 백그라운드에서 microphone·location 타입으로 시작하면 막힐 수 있어(API 34 SecurityException) 타입을 하나씩 줄여 다시 시도한다.
        val candidates = typeCandidates(LocationPermissions.fineGranted(this), micGranted(), Build.VERSION.SDK_INT)
        for ((i, type) in candidates.withIndex()) {
            try {
                startForeground(NOTIFICATION_ID, notification, type)
                _attachedType.value = type
                DhLog.i("drive session service: foreground type=${typeName(type)} channel=$CHANNEL_ID (quiet)${if (i > 0) " fallback=$i" else ""}")
                return START_STICKY
            } catch (e: Exception) {
                DhLog.w("drive session service: startForeground(${typeName(type)}) 실패 ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            }
        }
        DhLog.w("drive session service: foreground 실패 — 일반 서비스로 계속")
        return START_STICKY
    }

    private fun micGranted(): Boolean = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        _attachedType.value = null
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

        /** API 29 미만 — 타입 없이 포그라운드 */
        const val LEGACY_TYPE = 0

        private val _attachedType = MutableStateFlow<Int?>(null)

        /** 지금 붙은 포그라운드 타입(비트 합, API 29 미만은 [LEGACY_TYPE]) — 서비스가 없거나 포그라운드가 되지 못했으면 null */
        val attachedType: StateFlow<Int?> = _attachedType.asStateFlow()

        /**
         * 붙은 타입이 지금 권한으로 붙일 수 있는 타입([typeCandidates] 첫 번째)보다 모자란가 — 순수, 단위 테스트.
         * 백그라운드에서 시작돼 microphone·location 이 빠진 서비스를 앱이 앞에 있을 때 다시 시작해 올리는 판단([DriveSessionController.ensureType]).
         * API 29 미만은 타입이 없어 늘 false. 포그라운드가 되지 못한(null) 서비스는 true(앞에서는 붙을 수 있다).
         */
        fun needsUpgrade(attached: Int?, fineGranted: Boolean, micGranted: Boolean, sdk: Int): Boolean {
            if (sdk < Build.VERSION_CODES.Q) return false
            val wanted = typeCandidates(fineGranted, micGranted, sdk).first()
            if (attached == null) return true
            return attached and wanted != wanted
        }

        /**
         * 시도할 포그라운드 타입(앞에서부터) — 순수, 단위 테스트.
         * 위치 권한 → location(없으면 dataSync), 마이크 권한(API 30+) → | microphone. 실패하면 microphone 을 빼고, 그래도 안 되면 dataSync 만.
         */
        fun typeCandidates(fineGranted: Boolean, micGranted: Boolean, sdk: Int): List<Int> {
            val base = if (fineGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            val withMic = micGranted && sdk >= Build.VERSION_CODES.R
            return buildList {
                if (withMic) add(base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                add(base)
                if (base != ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        }

        fun typeName(type: Int): String = buildList {
            if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) add("location")
            if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0) add("dataSync")
            if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) add("microphone")
        }.joinToString("|").ifEmpty { "none" }
    }
}
