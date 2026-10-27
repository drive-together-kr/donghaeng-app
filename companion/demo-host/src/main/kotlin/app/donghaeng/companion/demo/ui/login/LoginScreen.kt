package app.donghaeng.companion.demo.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.donghaeng.companion.demo.BuildConfig
import app.donghaeng.companion.demo.DemoSession

/**
 * (1) 로그인(dev). 실제 공식 앱에서는 블루링크·기아 커넥트의 로그인이 이 자리다.
 * 동행 모듈은 자체 로그인이 없고, 로그인 성공 시점에 [DemoSession.login] 이 토큰 제공자를 넘겨 초기화한다.
 */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("rider01@dev.local") }
    var nickname by rememberSaveable { mutableStateOf("동승자") }
    val canSubmit = email.isNotBlank() && nickname.isNotBlank()

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
                    "동행 모듈은 자체 로그인 없이 앱이 가진 계정 토큰을 받아 동작합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("이메일 (dev 로그인)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("닉네임") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    DemoSession.login(email.trim(), nickname.trim())
                    onLoggedIn()
                },
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("로그인", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "서버 ${BuildConfig.DH_PUBLIC_BASE_URL} · 제공자 DEV(서버 없이 Fake 동작) · v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
