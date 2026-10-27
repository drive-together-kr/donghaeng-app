package app.donghaeng.companion.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.donghaeng.companion.demo.ui.DemoNavHost
import app.donghaeng.companion.demo.ui.theme.DemoHostTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DemoHostTheme {
                DemoNavHost()
            }
        }
    }
}
