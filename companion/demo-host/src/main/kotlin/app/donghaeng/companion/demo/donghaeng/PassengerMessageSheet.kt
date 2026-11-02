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
 * 보낼 문장별 멱등 키(D085). 같은 문장의 재시도는 같은 키, 다른 문장이나 보내기에 성공한 뒤의 같은 문장(새로 한 번 더 보내기)은 새 키.
 * 시트가 다시 그려지거나 회전해도 유지되도록 [Saver] 로 저장한다.
 */
class MessageKeys(
    private var text: String? = null,
    private var key: String? = null,
    private val newKey: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    fun keyFor(message: String): String {
        val current = key
        if (current != null && text == message) return current
        return newKey().also {
            text = message
            key = it
        }
    }

    /** 이 문장은 전달됐다 — 다음에 같은 문장을 또 보내면 새 메시지다 */
    fun sent(message: String) {
        if (text == message) {
            text = null
            key = null
        }
    }

    companion object {
        val Saver: androidx.compose.runtime.saveable.Saver<MessageKeys, List<String>> = androidx.compose.runtime.saveable.Saver(
            save = { listOf(it.text.orEmpty(), it.key.orEmpty()) },
            restore = { saved -> MessageKeys(saved[0].takeIf { it.isNotEmpty() }, saved[1].takeIf { it.isNotEmpty() }) },
        )
    }
}

/**
 * 삽입 지점 G — [메시지 보내기] 바텀시트: 프리셋 칩 4개 + 60자 입력 → sdk.sendPassengerMessage. 그룹 차량에서 음성으로 읽힌다.
 *
 * 멱등 키(D085·docs/03 계약 3): 보낼 문장 하나에 키 하나 — 문장을 바꾸면 새 키, 같은 문장으로 [보내기]를 다시 누르면(타임아웃 뒤 재시도) 같은 키라
 * 서버가 한 번만 처리해 차에서 두 번 읽히지 않는다. 키는 [MessageKeys] 가 문장별로 만든다.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PassengerMessageSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val group by DongHaengStore.activeGroup.collectAsStateWithLifecycle()
    val connection by DongHaengStore.connection.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val keys = rememberSaveable(saver = MessageKeys.Saver) { MessageKeys() }

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
            if (connection.finished) {
                Text(connection.message ?: "끝난 그룹이에요", color = KiaColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            AccentButton(text = if (busy) "보내는 중…" else "보내기", enabled = !busy && text.isNotBlank() && !connection.finished) {
                busy = true
                error = null
                val message = text.trim()
                val key = keys.keyFor(message)
                scope.launch {
                    try {
                        DongHaengStore.sendMessage(message, idempotencyKey = key)
                        keys.sent(message)
                        demoToast(context, "메시지를 보냈어요 — 차에서 음성으로 읽어 드려요")
                        onDismiss()
                    } catch (e: DongHaengException) {
                        error = if (e is DongHaengException.Api && e.code == "NETWORK") {
                            // 서버가 받았는데 응답만 사라졌을 수 있다 — 같은 문장으로 다시 보내면 같은 키라 한 번만 읽힌다
                            "보냈는지 확인하지 못했어요. [보내기]를 다시 누르면 중복 없이 한 번만 전해져요"
                        } else {
                            e.toUserText()
                        }
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
