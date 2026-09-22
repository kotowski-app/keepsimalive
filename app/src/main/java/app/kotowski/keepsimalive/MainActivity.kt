package app.kotowski.keepsimalive

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotowski.keepsimalive.navigation.AppNavGraph
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import app.kotowski.keepsimalive.ui.theme.ThemePreference
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var themePreference: ThemePreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by themePreference.mode.collectAsStateWithLifecycle()
            KeepSimAliveTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AppNavGraph()
                }
            }
        }
    }
}
