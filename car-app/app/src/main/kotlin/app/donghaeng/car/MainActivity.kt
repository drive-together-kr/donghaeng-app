package app.donghaeng.car

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.donghaeng.car.ui.navigation.DhNavHost
import app.donghaeng.car.ui.theme.DongHaengTheme
import dagger.hilt.android.AndroidEntryPoint

/** 단일 액티비티. 화면 전환은 Navigation Compose(`DhNavHost`)가 담당한다. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DongHaengTheme {
                DhNavHost()
            }
        }
    }
}
