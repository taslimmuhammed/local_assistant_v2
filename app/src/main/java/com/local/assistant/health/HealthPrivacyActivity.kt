package com.local.assistant.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.GlassCard
import com.local.assistant.ui.theme.GlassIconButton
import com.local.assistant.ui.theme.LocalAssistantTheme
import com.local.assistant.ui.theme.installGlassBackdrop

/**
 * How the app uses health data: what Health Connect links to from its permission screen, and
 * won't show that screen without. Also opened from Settings → Health data.
 */
class HealthPrivacyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LocalAssistantTheme {
                HealthPrivacy(onBack = ::finish, onManage = ::manage)
            }
        }
        installGlassBackdrop(this)
    }

    private fun manage() = HealthConnectSource.openManageAccess(this)
}

/** What is read, when, and where it goes: nowhere. */
internal val HEALTH_PRIVACY = listOf(
    "Local Assistant reads your steps, distance, calories, heart rate, resting heart rate, sleep and workouts from Health " +
        "Connect, where your phone's health apps and watch keep them, and only when you ask about them: “how many steps " +
        "today?”, “how did I sleep?”.",
    "It only reads. It never writes to Health Connect or changes anything there.",
    "What it reads stays on this phone. The model that answers runs on the phone, nothing is sent to a server, and your " +
        "readings are never put in a web search or in the app's logs.",
    "Answers stay in the chat they were asked in until you delete it, and aren't kept in the assistant's long-term memory.",
    "You choose each kind of data in Health Connect, and can turn any of it off at any time.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthPrivacy(onBack: () -> Unit, onManage: () -> Unit) {
    Scaffold(
        containerColor = AppColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Health data", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, Modifier.padding(start = 10.dp, end = 6.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.Background,
                    titleContentColor = AppColors.TextPrimary,
                    navigationIconContentColor = AppColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            GlassCard(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), padding = 16.dp) {
                HEALTH_PRIVACY.forEachIndexed { i, line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (i == 0) AppColors.TextPrimary else AppColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                OutlinedButton(onClick = onManage, modifier = Modifier.padding(top = 8.dp)) { Text("Manage access in Health Connect") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
