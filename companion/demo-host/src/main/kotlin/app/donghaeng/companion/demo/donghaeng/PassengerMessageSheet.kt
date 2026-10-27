package app.donghaeng.companion.demo.donghaeng

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.AccentButton
import app.donghaeng.companion.demo.ui.components.KiaChip
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.components.demoToast
import app.donghaeng.companion.demo.ui.theme.KiaColors
import app.donghaeng.companion.sdk.DongHaengException
import kotlinx.coroutines.launch

/** 동승자 메시지 프리셋 4개 (삽입 지점 G) */
val MESSAGE_PRESETS = listOf("앞에 휴게소 들르자", "천천히 와", "먼저 가 있을게", "배고파요")

const val MESSAGE_MAX = 60

/**
 * 삽입 지점 G — [메시지 보내기] 바텀시트: 프리셋 칩 4개 + 60자 입력 → sdk.sendPassengerMessage. 그룹 차량에서 음성으로 읽힌다.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PassengerMessageSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val group by DongHaengStore.activeGroup.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = KiaColors.Card,
        dragHandle = { Box(Modifier.padding(top = 12.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(KiaColors.SubCard)) },
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MsIcon(R.drawable.ms_chat_bubble, null, tint = KiaColors.Accent, size = 24.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("메시지 보내기", style = MaterialTheme.typography.headlineSmall)
                    Text("'${group?.name ?: "동행 그룹"}' 차량에서 음성으로 읽어 드려요", style = MaterialTheme.typography.bodySmall, color = KiaColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MESSAGE_PRESETS.forEach { preset ->
                    KiaChip(preset, selected = text == preset, color = KiaColors.SubCard, onClick = { text = preset })
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= MESSAGE_MAX) text = it },
                placeholder = { Text("직접 입력 (최대 ${MESSAGE_MAX}자)", color = KiaColors.TextTertiary) },
                supportingText = { Text("${text.length}/$MESSAGE_MAX", color = KiaColors.TextSecondary) },
                singleLine = false,
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KiaColors.Accent,
                    unfocusedBorderColor = KiaColors.SubCard,
                    cursorColor = KiaColors.Accent,
                    focusedContainerColor = KiaColors.SubCard,
                    unfocusedContainerColor = KiaColors.SubCard,
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(it, color = KiaColors.Dot, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            AccentButton(text = if (busy) "보내는 중…" else "보내기", enabled = !busy && text.isNotBlank()) {
                busy = true
                error = null
                scope.launch {
                    try {
                        DongHaengStore.sendMessage(text.trim())
                        demoToast(context, "메시지를 보냈어요 — 차에서 음성으로 읽어 드려요")
                        onDismiss()
                    } catch (e: DongHaengException) {
                        error = e.toUserText()
                    } catch (e: Exception) {
                        error = "보내지 못했어요 (${e.javaClass.simpleName})"
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }
}
