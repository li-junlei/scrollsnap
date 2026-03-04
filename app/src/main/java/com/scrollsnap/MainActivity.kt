package com.scrollsnap

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scrollsnap.core.shizuku.ShizukuManager
import com.scrollsnap.core.stitch.StitchSettingsStore
import com.scrollsnap.core.stitch.StitchTuning
import com.scrollsnap.feature.control.OverlayControlService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var shizukuManager: ShizukuManager

    override fun onCreate(savedInstanceState: Bundle?) {
        UiPrefs(applicationContext).applyLanguage()
        super.onCreate(savedInstanceState)
        shizukuManager = ShizukuManager(applicationContext)

        setContent {
            MaterialTheme {
                ScrollSnapApp(shizukuManager)
            }
        }
    }

    override fun onDestroy() {
        shizukuManager.dispose()
        super.onDestroy()
    }
}

private enum class AppScreen {
    Onboarding,
    Home,
    Settings
}

private enum class AppLanguage(val tag: String) {
    SYSTEM("system"),
    ZH_CN("zh-CN"),
    EN("en")
}

private class UiPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("scrollsnap_ui", Context.MODE_PRIVATE)

    fun isOnboardingCompleted(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingCompleted(done: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).apply()
    }

    fun getLanguage(): AppLanguage =
        AppLanguage.entries.firstOrNull { it.tag == prefs.getString(KEY_LANG, AppLanguage.SYSTEM.tag) }
            ?: AppLanguage.SYSTEM

    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANG, language.tag).apply()
    }

    fun applyLanguage() {
        val locales = when (getLanguage()) {
            AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            AppLanguage.ZH_CN -> LocaleListCompat.forLanguageTags("zh-CN")
            AppLanguage.EN -> LocaleListCompat.forLanguageTags("en")
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    companion object {
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_LANG = "app_lang"
    }
}

@Composable
private fun ScrollSnapApp(shizukuManager: ShizukuManager) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val manager = remember { shizukuManager }
    val uiPrefs = remember { UiPrefs(context.applicationContext) }
    val stitchSettingsStore = remember { StitchSettingsStore(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()

    val shizukuStatus by manager.status.collectAsState()
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notificationGranted by remember { mutableStateOf(isNotificationGranted(context)) }
    var currentScreen by rememberSaveable {
        mutableStateOf(if (uiPrefs.isOnboardingCompleted()) AppScreen.Home else AppScreen.Onboarding)
    }

    var tuning by remember { mutableStateOf(stitchSettingsStore.getTuning()) }
    var toleranceText by remember { mutableStateOf(tuning.toleranceMultiplier.toString()) }
    var quantileText by remember { mutableStateOf(tuning.overlapQuantile.toString()) }
    var safetyRatioText by remember { mutableStateOf(tuning.safetyRatio.toString()) }
    var language by remember { mutableStateOf(uiPrefs.getLanguage()) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationGranted = isNotificationGranted(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                notificationGranted = isNotificationGranted(context)
                manager.requestBinder()
                manager.refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        manager.requestBinder()
        manager.refreshStatus()
    }

    val allRequiredGranted = overlayGranted && notificationGranted &&
        shizukuStatus.isBinderAvailable && shizukuStatus.isPermissionGranted

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            when (currentScreen) {
                AppScreen.Onboarding -> {
                    OnboardingScreen(
                        overlayGranted = overlayGranted,
                        notificationGranted = notificationGranted,
                        shizukuBinderGranted = shizukuStatus.isBinderAvailable,
                        shizukuPermissionGranted = shizukuStatus.isPermissionGranted,
                        onGrantOverlay = {
                            val uri = Uri.parse("package:${context.packageName}")
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        onGrantNotification = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onGrantShizuku = { manager.requestPermission() },
                        onContinue = {
                            uiPrefs.setOnboardingCompleted(true)
                            currentScreen = AppScreen.Home
                        },
                        allRequiredGranted = allRequiredGranted
                    )
                }

                AppScreen.Home -> {
                    HomeScreen(
                        status = shizukuStatus,
                        onOpenFloating = {
                            val intent = Intent(context, OverlayControlService::class.java)
                                .setAction(OverlayControlService.ACTION_START_OVERLAY)
                            ContextCompat.startForegroundService(context, intent)
                            Toast.makeText(
                                context,
                                context.getString(R.string.overlay_started),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onOpenSettings = { currentScreen = AppScreen.Settings }
                    )
                }

                AppScreen.Settings -> {
                    SettingsScreen(
                        language = language,
                        toleranceText = toleranceText,
                        quantileText = quantileText,
                        safetyRatioText = safetyRatioText,
                        onBack = { currentScreen = AppScreen.Home },
                        onLanguageChange = {
                            language = it
                            uiPrefs.setLanguage(it)
                            uiPrefs.applyLanguage()
                            (context as? Activity)?.recreate()
                        },
                        onToleranceChange = { toleranceText = it },
                        onQuantileChange = { quantileText = it },
                        onSafetyRatioChange = { safetyRatioText = it },
                        onSave = {
                            val tol = toleranceText.toFloatOrNull()
                            val qua = quantileText.toFloatOrNull()
                            val saf = safetyRatioText.toFloatOrNull()
                            if (tol == null || qua == null || saf == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_input), Toast.LENGTH_SHORT)
                                    .show()
                                return@SettingsScreen
                            }
                            val newTuning = StitchTuning(
                                toleranceMultiplier = tol.coerceIn(1.0f, 1.2f),
                                overlapQuantile = qua.coerceIn(0.2f, 0.8f),
                                safetyRatio = saf.coerceIn(0.0f, 0.04f),
                                minSafetyPx = StitchTuning.DEFAULT_MIN_SAFETY_PX
                            )
                            coroutineScope.launch {
                                stitchSettingsStore.saveTuning(newTuning)
                                tuning = newTuning
                                toleranceText = tuning.toleranceMultiplier.toString()
                                quantileText = tuning.overlapQuantile.toString()
                                safetyRatioText = tuning.safetyRatio.toString()
                                Toast.makeText(context, context.getString(R.string.saved_ok), Toast.LENGTH_SHORT)
                                    .show()
                            }
                        },
                        onReset = {
                            stitchSettingsStore.resetToDefaults()
                            tuning = stitchSettingsStore.getTuning()
                            toleranceText = tuning.toleranceMultiplier.toString()
                            quantileText = tuning.overlapQuantile.toString()
                            safetyRatioText = tuning.safetyRatio.toString()
                        },
                        onRunOnboardingAgain = {
                            uiPrefs.setOnboardingCompleted(false)
                            currentScreen = AppScreen.Onboarding
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingScreen(
    overlayGranted: Boolean,
    notificationGranted: Boolean,
    shizukuBinderGranted: Boolean,
    shizukuPermissionGranted: Boolean,
    onGrantOverlay: () -> Unit,
    onGrantNotification: () -> Unit,
    onGrantShizuku: () -> Unit,
    onContinue: () -> Unit,
    allRequiredGranted: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(R.string.onboarding_subtitle), style = MaterialTheme.typography.bodyMedium)

        PermissionItem(
            title = stringResource(R.string.permission_overlay_title),
            description = stringResource(R.string.permission_overlay_desc),
            granted = overlayGranted,
            onGrant = onGrantOverlay
        )

        PermissionItem(
            title = stringResource(R.string.permission_notification_title),
            description = stringResource(R.string.permission_notification_desc),
            granted = notificationGranted,
            onGrant = onGrantNotification,
            grantEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        )

        PermissionItem(
            title = stringResource(R.string.permission_shizuku_title),
            description = if (shizukuBinderGranted) {
                stringResource(R.string.permission_shizuku_desc)
            } else {
                stringResource(R.string.onboarding_wait_for_binder)
            },
            granted = shizukuBinderGranted && shizukuPermissionGranted,
            onGrant = onGrantShizuku,
            grantEnabled = shizukuBinderGranted && !shizukuPermissionGranted
        )

        Button(onClick = onContinue, enabled = allRequiredGranted, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.continue_to_home))
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
    grantEnabled: Boolean = true
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (granted) stringResource(R.string.permission_granted) else stringResource(R.string.permission_missing),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onGrant, enabled = !granted && grantEnabled) {
                    Text(stringResource(R.string.grant_permission))
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    status: com.scrollsnap.core.shizuku.ShizukuStatus,
    onOpenFloating: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.status_card_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.status_binder_label) + ": " +
                        if (status.isBinderAvailable) stringResource(R.string.status_binder_connected)
                        else stringResource(R.string.status_binder_disconnected)
                )
                Text(
                    text = stringResource(R.string.status_permission_label) + ": " +
                        if (status.isPermissionGranted) stringResource(R.string.status_permission_granted)
                        else stringResource(R.string.status_permission_not_granted)
                )
                Text(text = stringResource(R.string.status_message_label) + ": ${status.message}")
            }
        }

        Button(onClick = onOpenFloating, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.open_floating_button))
        }

        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.open_settings))
        }
    }
}

@Composable
private fun SettingsScreen(
    language: AppLanguage,
    toleranceText: String,
    quantileText: String,
    safetyRatioText: String,
    onBack: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onToleranceChange: (String) -> Unit,
    onQuantileChange: (String) -> Unit,
    onSafetyRatioChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onRunOnboardingAgain: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_language_title), style = MaterialTheme.typography.titleMedium)
                LanguageOption(
                    selected = language == AppLanguage.SYSTEM,
                    title = stringResource(R.string.settings_language_system),
                    onClick = { onLanguageChange(AppLanguage.SYSTEM) }
                )
                LanguageOption(
                    selected = language == AppLanguage.ZH_CN,
                    title = stringResource(R.string.settings_language_zh_cn),
                    onClick = { onLanguageChange(AppLanguage.ZH_CN) }
                )
                LanguageOption(
                    selected = language == AppLanguage.EN,
                    title = stringResource(R.string.settings_language_en),
                    onClick = { onLanguageChange(AppLanguage.EN) }
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_stitch_title), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = toleranceText,
                    onValueChange = onToleranceChange,
                    label = { Text(stringResource(R.string.settings_tolerance)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = quantileText,
                    onValueChange = onQuantileChange,
                    label = { Text(stringResource(R.string.settings_quantile)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = safetyRatioText,
                    onValueChange = onSafetyRatioChange,
                    label = { Text(stringResource(R.string.settings_safety_ratio)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.save_settings))
                }
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.reset_defaults))
                }
            }
        }

        TextButton(onClick = onRunOnboardingAgain, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reopen_guide))
        }
    }
}

@Composable
private fun LanguageOption(
    selected: Boolean,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = title, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
    }
}

private fun isNotificationGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}
