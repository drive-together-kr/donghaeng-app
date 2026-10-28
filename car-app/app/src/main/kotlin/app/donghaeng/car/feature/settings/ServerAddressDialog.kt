package app.donghaeng.car.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.R
import app.donghaeng.car.core.network.ServerAddressRules
import app.donghaeng.car.core.network.ServerAddresses
import app.donghaeng.car.core.network.ServerConfig
import app.donghaeng.car.core.network.ServerOverrides

/** 확인 단계 — 편집 중(null) / 저장 확인 / 기본값 복원 확인 */
private sealed interface ServerConfirm {
    data class Save(val ready: ServerAddressRules.FormResult.Ready) : ServerConfirm
    data class Reset(val baseUrlChanges: Boolean) : ServerConfirm
}

/**
 * 숨은 메뉴 '서버 주소' (T32) — 설정 화면 제목을 3초 안에 5번 탭하면 열린다(정차 중).
 *
 * - 지금 유효값(사용자 지정 ?: 빌드 기본값)과 출처를 보이고, 서버 주소·MQTT 주소(비우면 페어링 응답)를 받는다.
 * - [저장] → 형식 검증([ServerAddressRules.checkForm]) → 확인 창(서버가 바뀌면 '연결이 지워지고 폰에서 다시 연결') → [onApply].
 * - [기본값 복원] → 확인 창 → [onReset]. 주행 중이면 저장·복원 버튼이 꺼지고 안내만 보인다.
 */
@Composable
fun ServerAddressDialog(
    current: ServerAddresses,
    isDriving: Boolean,
    onCheck: (baseUrl: String, mqttUri: String) -> ServerAddressRules.FormResult,
    onApply: (ServerOverrides) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var baseInput by rememberSaveable { mutableStateOf(current.publicBaseUrl) }
    var mqttInput by rememberSaveable { mutableStateOf(current.mqttUriOverride.orEmpty()) }
    var baseError by remember { mutableStateOf<ServerAddressRules.Error?>(null) }
    var mqttError by remember { mutableStateOf<ServerAddressRules.Error?>(null) }
    var unchanged by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ServerConfirm?>(null) }

    val step = confirm
    if (step != null) {
        val (title, message) = when (step) {
            is ServerConfirm.Save ->
                if (step.ready.baseUrlChanges) {
                    stringResource(R.string.t32_server_confirm_base_title) to
                        stringResource(R.string.t32_server_confirm_base_message, step.ready.overrides.publicBaseUrl ?: ServerConfig.BUILD_DEFAULT_BASE_URL)
                } else {
                    stringResource(R.string.t32_server_confirm_mqtt_title) to
                        stringResource(R.string.t32_server_confirm_mqtt_message, step.ready.overrides.mqttUri ?: stringResource(R.string.t32_server_mqtt_from_server))
                }
            is ServerConfirm.Reset ->
                stringResource(R.string.t32_server_confirm_reset_title) to
                    if (step.baseUrlChanges) stringResource(R.string.t32_server_confirm_base_message, ServerConfig.BUILD_DEFAULT_BASE_URL)
                    else stringResource(R.string.t32_server_confirm_mqtt_message, stringResource(R.string.t32_server_mqtt_from_server))
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                    if (isDriving) DrivingNotice()
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when (step) {
                            is ServerConfirm.Save -> onApply(step.ready.overrides)
                            is ServerConfirm.Reset -> onReset()
                        }
                        confirm = null
                        onDismiss()
                    },
                    enabled = !isDriving,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.t32_server_confirm_ok)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirm = null }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.t33_confirm_cancel)) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.t32_server_dialog_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val source = stringResource(if (current.baseUrlOverridden) R.string.t32_server_source_override else R.string.t32_server_source_build)
                Text(stringResource(R.string.t32_server_current_base, current.publicBaseUrl, source), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.t32_server_current_mqtt, current.mqttUriOverride ?: stringResource(R.string.t32_server_mqtt_from_server)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.t32_server_build_defaults, ServerConfig.BUILD_DEFAULT_BASE_URL, ServerConfig.BUILD_DEFAULT_MQTT_URI),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = baseInput,
                    onValueChange = { baseInput = it; baseError = null; unchanged = false },
                    label = { Text(stringResource(R.string.t32_server_label_base)) },
                    singleLine = true,
                    enabled = !isDriving,
                    isError = baseError != null,
                    supportingText = baseError?.let { e -> { Text(errorText(e)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mqttInput,
                    onValueChange = { mqttInput = it; mqttError = null; unchanged = false },
                    label = { Text(stringResource(R.string.t32_server_label_mqtt)) },
                    singleLine = true,
                    enabled = !isDriving,
                    isError = mqttError != null,
                    supportingText = mqttError?.let { e -> { Text(errorText(e)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.t32_server_wss_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (unchanged) Text(stringResource(R.string.t32_server_unchanged), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                if (isDriving) DrivingNotice()
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { confirm = ServerConfirm.Reset(baseUrlChanges = current.baseUrlOverridden) },
                    enabled = !isDriving && current.anyOverride,
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.t32_server_btn_reset)) }
                Button(
                    onClick = {
                        when (val result = onCheck(baseInput, mqttInput)) {
                            is ServerAddressRules.FormResult.Invalid -> {
                                baseError = result.baseError
                                mqttError = result.mqttError
                            }
                            ServerAddressRules.FormResult.Unchanged -> unchanged = true
                            is ServerAddressRules.FormResult.Ready -> confirm = ServerConfirm.Save(result)
                        }
                    },
                    enabled = !isDriving,
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.t32_server_btn_save)) }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.t33_confirm_cancel)) }
        },
    )
}

@Composable
private fun DrivingNotice() {
    Text(stringResource(R.string.t32_server_driving_disabled), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
}

@Composable
private fun errorText(error: ServerAddressRules.Error): String = when (error) {
    ServerAddressRules.Error.BASE_FORMAT -> stringResource(R.string.t32_server_err_base_format)
    ServerAddressRules.Error.BASE_HAS_PATH -> stringResource(R.string.t32_server_err_base_path)
    ServerAddressRules.Error.BASE_CLEARTEXT_BLOCKED -> stringResource(R.string.t32_server_err_base_cleartext, BuildConfig.DH_CLEARTEXT_HOSTS.replace(",", ", "))
    ServerAddressRules.Error.MQTT_FORMAT -> stringResource(R.string.t32_server_err_mqtt_format)
    ServerAddressRules.Error.MQTT_PLAINTEXT -> stringResource(R.string.t32_server_err_mqtt_plaintext)
}
