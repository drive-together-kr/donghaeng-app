package app.donghaeng.companion.demo.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.donghaeng.companion.demo.BuildConfig

/**
 * (1) 로그인(dev). 실제 공식 앱에서는 블루링크·기아 커넥트의 로그인이 이 자리다.
 * 서버 `POST /v1/auth/login` 으로 dev 토큰을 받고(호스트의 일), 그 토큰을 돌려주는 TokenProvider 로 동행 모듈을 초기화한다
 * ([app.donghaeng.companion.demo.DemoSession.login]). 동행 모듈 자체에는 로그인이 없다.
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    viewModel: LoginViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("rider01@dev.local") }
    var nickname by rememberSaveable { mutableStateOf("동승자") }
    val canSubmit = email.isNotBlank() && nickname.isNotBlank() && !state.loading

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) onLoggedIn()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("동행 — 공식 앱 흉내 (데모용)", style = MaterialTheme.typography.headlineSmall)
            Text(
                "이 화면은 공식 앱(블루링크·마이현대·기아 커넥트·제네시스)의 로그인을 흉내 냅니다. " +
                    "동행 모듈은 자체 로그인 없이 앱이 가진 계정 토큰을 받아 서버와 교환해 동작합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    viewModel.clearError()
                },
                label = { Text("이메일 (dev 로그인)") },
                singleLine = true,
                enabled = !state.loading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = nickname,
                onValueChange = {
                    nickname = it
                    viewModel.clearError()
                },
                label = { Text("닉네임") },
                singleLine = true,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.submit(email, nickname) },
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("서버에 로그인 중…", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Text("로그인", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "서버 ${BuildConfig.DH_PUBLIC_BASE_URL} · 제공자 DEV (POST /v1/auth/login → 모듈이 /v1/auth/exchange) · v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
