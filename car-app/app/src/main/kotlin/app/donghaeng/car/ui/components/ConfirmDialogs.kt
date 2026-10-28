package app.donghaeng.car.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.donghaeng.car.R
import app.donghaeng.car.core.common.ClearRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 파괴적 동작(연결 해제·저장소 초기화) 확인 창 (T33, docs/10 R13 — 확인 없이 토큰이 지워진 경로를 막는다).
 * [isDriving] 이면 확인 버튼을 막고 안내 문구를 보인다(CarUxRestrictions, CLAUDE.md 규칙 6).
 */
@Composable
fun DestructiveConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    isDriving: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyLarge)
                if (isDriving) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.t33_driving_disabled), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDriving,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text(confirmText) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.t33_confirm_cancel)) }
        },
    )
}

/** 삭제 사유 enum 이름 → 사람이 읽는 문구. 모르는 값은 이름 그대로 */
@Composable
fun clearReasonLabel(reason: String): String = when (reason) {
    "USER_SETTINGS_DISCONNECT" -> stringResource(R.string.t33_reason_user_settings_disconnect)
    "USER_REPAIR" -> stringResource(R.string.t33_reason_user_repair)
    "TOKEN_REVOKED_CMD" -> stringResource(R.string.t33_reason_token_revoked_cmd)
    "REFRESH_REJECTED" -> stringResource(R.string.t33_reason_refresh_rejected)
    "SERVER_CHANGED" -> stringResource(R.string.t33_reason_server_changed)
    "STORE_RESET" -> stringResource(R.string.t33_reason_store_reset)
    "UNPAIRED" -> stringResource(R.string.t33_reason_unpaired)
    "GROUP_ENDED" -> stringResource(R.string.t33_reason_group_ended)
    "MEMBER_LEFT_ME" -> stringResource(R.string.t33_reason_member_left_me)
    "LEFT_LOCALLY" -> stringResource(R.string.t33_reason_left_locally)
    "STORE_CORRUPT" -> stringResource(R.string.t33_reason_store_corrupt)
    else -> reason
}

/** "10-28 16:51 · 설정에서 연결 해제 (USER_SETTINGS_DISCONNECT) veh_…7Q" — 진단 표시용 */
@Composable
fun clearRecordText(record: ClearRecord?): String {
    if (record == null) return "-"
    return "${formatShortTime(record.atMs)} · ${clearReasonLabel(record.reason)} (${record.reason})" + (record.idMasked?.let { "  $it" } ?: "")
}

/** epoch ms → "MM-dd HH:mm:ss"(기기 시간대) */
fun formatShortTime(atMs: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date(atMs))
