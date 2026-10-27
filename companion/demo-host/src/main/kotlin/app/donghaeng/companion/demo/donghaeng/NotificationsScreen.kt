package app.donghaeng.companion.demo.donghaeng

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.companion.demo.MainActivity
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.Disclaimer
import app.donghaeng.companion.demo.ui.components.KiaCard
import app.donghaeng.companion.demo.ui.components.KiaTopBar
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.components.RowDivider
import app.donghaeng.companion.demo.ui.components.SectionHeader
import app.donghaeng.companion.demo.ui.theme.KiaColors
import app.donghaeng.protocol.GroupEventType

/**
 * 삽입 지점 F — 알림 벨: 동행 그룹 이벤트(합류, 휴게소 도착, 저잔량, 목적지 도착, 동승자 메시지…) 목록. 열면 모두 읽음 처리.
 * 공식 앱의 기존 알림(정비·프로모션)은 정적 더미로 아래에 둔다.
 */
@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notifications by DongHaengStore.notifications.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        DongHaengStore.markAllRead()
        // 백그라운드 시스템 알림용 권한(API 33+). 거부해도 앱 안의 목록은 그대로 동작
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(KiaColors.Bg),
    ) {
        KiaTopBar(title = "알림", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            SectionHeader("동행 그룹")
            KiaCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                if (notifications.isEmpty()) {
                    Text("동행 그룹 소식이 여기에 쌓여요. 그룹에 합류하면 멤버 합류·휴게소 도착·잔량 부족·목적지 도착을 알려 드려요.", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary, modifier = Modifier.padding(vertical = 16.dp))
                }
                notifications.forEachIndexed { i, n ->
                    if (i > 0) RowDivider()
                    Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(KiaColors.SubCard),
                            contentAlignment = Alignment.Center,
                        ) { MsIcon(iconFor(n.type), null, tint = if (n.read) KiaColors.TextPrimary else KiaColors.Accent, size = 22.dp) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n.title, style = MaterialTheme.typography.labelMedium, color = KiaColors.TextSecondary)
                            Text(n.body, style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(relativeTime(n.atMs), style = MaterialTheme.typography.labelSmall, color = KiaColors.TextSecondary)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            SectionHeader("서비스")
            KiaCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                listOf(
                    Triple(R.drawable.ms_build, "정비 예약 안내", "11월 4일 정비 예약이 있어요. 전날 다시 알려 드릴게요."),
                    Triple(R.drawable.ms_system_update, "차량 소프트웨어", "새 소프트웨어 업데이트가 준비됐어요."),
                    Triple(R.drawable.ms_storefront, "프로모션", "보증연장 서비스 할인 프로모션이 진행 중이에요."),
                ).forEachIndexed { i, (icon, title, body) ->
                    if (i > 0) RowDivider()
                    Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(KiaColors.SubCard), contentAlignment = Alignment.Center) { MsIcon(icon, null, size = 22.dp) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.labelMedium, color = KiaColors.TextSecondary)
                            Text(body, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Disclaimer()
        }
    }
}

private fun iconFor(type: GroupEventType?): Int = when (type) {
    GroupEventType.MEMBER_JOINED, GroupEventType.MEMBER_LEFT, GroupEventType.LEADER_CHANGED -> R.drawable.ms_groups
    GroupEventType.LOW_ENERGY -> R.drawable.ms_local_gas_station
    GroupEventType.WAYPOINT_ARRIVED -> R.drawable.ms_local_cafe
    GroupEventType.DESTINATION_ARRIVED -> R.drawable.ms_flag
    GroupEventType.HARD_BRAKE, GroupEventType.DEVIATION -> R.drawable.ms_warning
    GroupEventType.PASSENGER_MESSAGE -> R.drawable.ms_chat_bubble
    GroupEventType.GROUP_ENDED -> R.drawable.ms_check_circle
    else -> R.drawable.ms_notifications
}

/** 앱이 백그라운드일 때 그룹 이벤트를 시스템 알림으로 (삽입 지점 F). 채널은 앱 시작 때 만든다 */
object DongHaengNotifier {
    const val CHANNEL_ID = "donghaeng_group"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_donghaeng), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "동행 그룹의 합류·도착·잔량 소식"
                },
            )
        }
    }

    fun show(context: Context, n: DhNotification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ms_groups)
            .setContentTitle("동행 · ${n.title}")
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(n.id.hashCode(), notification) }
    }
}
