package app.donghaeng.car.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.app.NotificationCompat
import app.donghaeng.car.R
import app.donghaeng.car.core.common.DhLog

/*
 * 주행 중 "얹히는 표면" 가능 여부 실측용 디버그 코드 (2026-10-28 방향 재정정 조사, docs/10 R16~).
 * (b) 플로팅 오버레이 창, (c) 런처 미니앱(AppWidget), (e) 헤드업 알림, (f) 오디오 포커스. 전부 debug 빌드에만 있다.
 */

/** (b) TYPE_APPLICATION_OVERLAY 작은 창 — 순정 내비가 전면일 때 떠 있는지 본다 */
class OverlayProbeService : Service() {
    private var panel: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra("cmd")) {
            "hide" -> { remove(); stopSelf() }
            else -> show()
        }
        return START_NOT_STICKY
    }

    private fun show() {
        if (panel != null) return
        val canDraw = Settings.canDrawOverlays(this)
        DhLog.i("probe overlay: canDrawOverlays=$canDraw sdk=${Build.VERSION.SDK_INT}")
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            background = GradientDrawable().apply { cornerRadius = 20 * density; setColor(Color.argb(230, 24, 28, 34)) }
        }
        fun line(text: String, color: Int, size: Float, bold: Boolean = false) = TextView(this).apply {
            this.text = text; setTextColor(color); textSize = size; if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }.also { root.addView(it) }
        line("동행 · 강릉 가족 여행", Color.rgb(196, 198, 207), 14f)
        line("● 아빠  앞 1.2 km  13:12", Color.rgb(46, 125, 255), 20f, bold = true)
        line("● 삼촌  뒤 2.1 km  13:15", Color.rgb(155, 89, 182), 20f, bold = true)
        line("● 둘째 ⛽ 뒤 5.7 km  13:19", Color.rgb(255, 159, 28), 20f, bold = true)
        line("▲ 둘째 차 배터리 12%", Color.rgb(255, 159, 28), 16f)
        line("🎙 무전", Color.rgb(16, 20, 0), 22f, bold = true).apply {
            setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply { cornerRadius = 14 * density; setColor(Color.rgb(198, 255, 61)) }
            gravity = Gravity.CENTER
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = (24 * density).toInt(); y = (90 * density).toInt() }
        try {
            wm.addView(root, lp)
            panel = root
            DhLog.i("probe overlay: addView ok")
        } catch (e: Exception) {
            DhLog.w("probe overlay: addView failed ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            stopSelf()
        }
    }

    private fun remove() {
        panel?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        panel = null
        DhLog.i("probe overlay: removed")
    }

    override fun onDestroy() { remove(); super.onDestroy() }
}

/** (e) 헤드업 알림 — 카테고리·중요도별로 순정 내비 위에 뜨는지 본다 */
object ProbeNotifications {
    private const val CHANNEL = "dh_probe_hun"

    fun post(context: Context, kind: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL, "동행 알림(조사)", NotificationManager.IMPORTANCE_HIGH).apply { description = "헤드업 조사용" }
        nm.createNotificationChannel(channel)
        val (category, title, text) = when (kind) {
            "warning" -> Triple("car_warning", "앞쪽 아빠 차가 급제동했어요", "주의하세요")
            "nav" -> Triple(NotificationCompat.CATEGORY_NAVIGATION, "둘째 차가 덕평휴게소에 도착했어요", "그룹 도착 예상 14:35")
            "msg" -> Triple(NotificationCompat.CATEGORY_MESSAGE, "아빠 차 무전", "0:07 · 재생 중")
            "info" -> Triple("car_information", "삼촌 차가 그룹에 들어왔어요", "멤버 4대")
            else -> Triple(NotificationCompat.CATEGORY_STATUS, "둘째 차 배터리가 12% 남았어요", "다음 휴게소 18 km")
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(category)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(8_000)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        val enabled = nm.areNotificationsEnabled()
        nm.notify(kind.hashCode() and 0xffff, n)
        DhLog.i("probe notify: kind=$kind category=$category enabled=$enabled importance=${nm.getNotificationChannel(CHANNEL)?.importance}")
    }
}

/** (c) 런처 미니앱 자리에 서드파티 AppWidget 이 올라가는지 — 공급자만 등록해 둔다 */
class ProbeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        DhLog.i("probe widget: onUpdate ids=${appWidgetIds.size}")
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.probe_widget)
            views.setTextViewText(R.id.probe_widget_line1, "● 아빠 앞 1.2 km · 13:12")
            views.setTextViewText(R.id.probe_widget_line2, "● 삼촌 뒤 2.1 km · 둘째 ⛽ 뒤 5.7 km")
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onEnabled(context: Context) { DhLog.i("probe widget: enabled (호스트가 올렸다)") }
}

/** (f) 오디오 포커스 — 내비 안내 usage 로 요청했을 때 결과·변화를 로그로 */
object ProbeAudioFocus {
    private var request: AudioFocusRequest? = null

    fun test(context: Context, usage: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrUsage = when (usage) { "media" -> AudioAttributes.USAGE_MEDIA; "assistant" -> AudioAttributes.USAGE_ASSISTANT; else -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
        val attrs = AudioAttributes.Builder().setUsage(attrUsage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener({ change -> DhLog.i("probe focus: change=$change (${focusName(change)})") }, Handler(Looper.getMainLooper()))
            .build()
        request = req
        val result = am.requestAudioFocus(req)
        DhLog.i("probe focus: usage=$usage request=${focusResult(result)} mode=${am.mode}")
        Handler(Looper.getMainLooper()).postDelayed({ request?.let { am.abandonAudioFocusRequest(it) }; request = null; DhLog.i("probe focus: abandoned") }, 4_000)
    }

    private fun focusResult(r: Int) = when (r) { AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "GRANTED"; AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "DELAYED"; AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "FAILED"; else -> "$r" }
    private fun focusName(c: Int) = when (c) { AudioManager.AUDIOFOCUS_GAIN -> "GAIN"; AudioManager.AUDIOFOCUS_LOSS -> "LOSS"; AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"; AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"; else -> "$c" }
}
