package com.futurepath.actionbox

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.futurepath.actionbox.billing.BillingRepository
import com.futurepath.actionbox.data.NotificationRepository
import com.futurepath.actionbox.data.SettingsRepository
import com.futurepath.actionbox.service.NotificationAccessUtils
import com.futurepath.actionbox.ui.MainScreen
import com.futurepath.actionbox.ui.onboarding.PermissionOnboardingScreen
import com.futurepath.actionbox.ui.theme.ActionBoxTheme
import com.futurepath.actionbox.viewmodel.NotificationViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: NotificationViewModel by viewModels {
        NotificationViewModel.Factory(
            NotificationRepository.getInstance(applicationContext),
            SettingsRepository.getInstance(applicationContext),
            BillingRepository.getInstance(applicationContext)
        )
    }

    // A plain (not remember-scoped) Compose state property: it needs to be assignable from
    // onNewIntent, which runs outside Composition entirely — launchMode="singleTop" (see the
    // manifest) means a widget tap while the app is already running reuses this Activity
    // instance via onNewIntent rather than creating a new one, so this is the only way for that
    // tap to reach the already-composed MainScreen. MainScreen consumes it via LaunchedEffect
    // and reports back through onWidgetIntentHandled so the same intent doesn't re-navigate on
    // every recomposition.
    private var widgetIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetIntent = intent
        setContent {
            ActionBoxTheme {
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current

                var isAccessGranted by remember {
                    mutableStateOf(NotificationAccessUtils.isNotificationAccessGranted(context))
                }
                var hasContinuedPastOnboarding by remember { mutableStateOf(isAccessGranted) }

                // Re-check permission state whenever the activity resumes, e.g. when the
                // user comes back from the system notification-access settings screen.
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            isAccessGranted = NotificationAccessUtils.isNotificationAccessGranted(context)
                            if (isAccessGranted) hasContinuedPastOnboarding = true
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (hasContinuedPastOnboarding && isAccessGranted) {
                    MainScreen(
                        viewModel = viewModel,
                        widgetIntent = widgetIntent,
                        onWidgetIntentHandled = { widgetIntent = null }
                    )
                } else {
                    PermissionOnboardingScreen(
                        isAccessGranted = isAccessGranted,
                        onGrantAccessClick = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onContinueClick = {
                            hasContinuedPastOnboarding = true
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetIntent = intent
    }
}
