package app.donghaeng.companion.demo.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import app.donghaeng.companion.demo.DemoSession
import app.donghaeng.companion.demo.ui.components.AccentButton
import app.donghaeng.companion.demo.ui.components.Disclaimer
import app.donghaeng.companion.demo.ui.components.KiaButton
import app.donghaeng.companion.demo.ui.theme.KiaColors

/**
 * 로그인 — 공식 앱의 계정 로그인 자리(데모는 서버 dev 로그인). 로그인 성공 시 [DemoSession.login] 이 동행 모듈을 초기화한다.
 * "서버 없이 둘러보기"는 호스트 쪽 가짜(FakeGateway)로 동행 기능이 움직이는 시연 모드.
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    viewModel: LoginViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("rider01@dev.local") }
    var nickname by rememberSaveable { mutableStateOf(DemoSession.USER_NAME) }

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) onLoggedIn()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KiaColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(72.dp))
        Text("KIA", style = MaterialTheme.typography.displayMedium, letterSpacing = 6.sp)
        Text("(시연)", style = MaterialTheme.typography.titleMedium, color = KiaColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        Text("공식 앱의 '동행' 메뉴를 재현한 시연용 호스트 앱이에요.", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
        Spacer(Modifier.height(40.dp))

        Text("계정", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = KiaColors.Accent,
            unfocusedBorderColor = KiaColors.SubCard,
            focusedLabelColor = KiaColors.Accent,
            unfocusedLabelColor = KiaColors.TextSecondary,
            cursorColor = KiaColors.Accent,
            focusedContainerColor = KiaColors.Card,
            unfocusedContainerColor = KiaColors.Card,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; viewModel.clearError() },
            label = { Text("이메일") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            colors = fieldColors,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it; viewModel.clearError() },
            label = { Text("이름") },
            singleLine = true,
            colors = fieldColors,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let {
            Text(it, color = KiaColors.Dot, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(20.dp))
        if (state.loading) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = KiaColors.Accent)
                Spacer(Modifier.height(8.dp))
                Text("서버에 로그인하는 중…", style = MaterialTheme.typography.bodySmall, color = KiaColors.TextSecondary)
            }
        } else {
            AccentButton("로그인", onClick = { viewModel.submit(email, nickname) })
            Spacer(Modifier.height(10.dp))
            KiaButton("서버 없이 둘러보기 (시연 모드)", onClick = { viewModel.enterDemo() })
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "서버 ${BuildConfig.DH_PUBLIC_BASE_URL} · 로그인은 서버의 dev 간편 로그인(POST /v1/auth/login)이고, 동행 모듈은 그 토큰을 교환해 씁니다.",
            style = MaterialTheme.typography.labelSmall,
            color = KiaColors.TextTertiary,
        )
        Spacer(Modifier.weight(1f, fill = true))
        Disclaimer()
    }
}

private val Int.sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
