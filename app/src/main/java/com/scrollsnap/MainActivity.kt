package com.scrollsnap

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.scrollsnap.core.shizuku.ShizukuManager
import com.scrollsnap.core.stitch.StitchSettingsStore
import com.scrollsnap.core.stitch.StitchTuning
import com.scrollsnap.core.update.GitHubUpdateChecker
import com.scrollsnap.core.update.UpdateCheckResult
import com.scrollsnap.core.update.UpdateInfo
import com.scrollsnap.feature.control.OverlayControlService
import com.scrollsnap.ui.theme.Primary
import com.scrollsnap.ui.theme.ScrollSnapTheme
import com.scrollsnap.ui.theme.Secondary
import com.scrollsnap.ui.theme.Success
import com.scrollsnap.ui.theme.Surface
import com.scrollsnap.ui.theme.SurfaceVariant
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var shizukuManager: ShizukuManager

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiPrefs.wrapContextWithLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shizukuManager = ShizukuManager(applicationContext)

        setContent {
            ScrollSnapTheme {
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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    fun isDebugModeEnabled(): Boolean = prefs.getBoolean(KEY_DEBUG_MODE, false)

    fun setDebugModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
    }

    fun getSkippedUpdateTag(): String? = prefs.getString(KEY_SKIPPED_UPDATE_TAG, null)

    fun setSkippedUpdateTag(tag: String?) {
        prefs.edit().putString(KEY_SKIPPED_UPDATE_TAG, tag).apply()
    }

    companion object {
        const val PREFS_NAME = "scrollsnap_ui"
        const val KEY_DEBUG_MODE = "debug_mode_enabled"
        private const val KEY_SKIPPED_UPDATE_TAG = "skipped_update_tag"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_LANG = "app_lang"

        fun wrapContextWithLanguage(base: Context): Context {
            val prefs = base.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedTag = prefs.getString(KEY_LANG, AppLanguage.SYSTEM.tag) ?: AppLanguage.SYSTEM.tag
            val language = AppLanguage.entries.firstOrNull { it.tag == savedTag } ?: AppLanguage.SYSTEM
            if (language == AppLanguage.SYSTEM) return base

            val locale = Locale.forLanguageTag(language.tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            return base.createConfigurationContext(config)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScrollSnapApp(shizukuManager: ShizukuManager) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    val manager = remember { shizukuManager }
    val uiPrefs = remember { UiPrefs(context.applicationContext) }
    val stitchSettingsStore = remember { StitchSettingsStore(context.applicationContext) }
    val updateChecker = remember {
        GitHubUpdateChecker(
            owner = BuildConfig.GITHUB_OWNER,
            repo = BuildConfig.GITHUB_REPO,
            releasesBaseUrl = BuildConfig.RELEASES_URL
        )
    }
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
    var debugModeEnabled by remember { mutableStateOf(uiPrefs.isDebugModeEnabled()) }
    var updateDialogInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateChecking by remember { mutableStateOf(false) }

    // Slider values
    var toleranceValue by remember { mutableFloatStateOf(tuning.toleranceMultiplier) }
    var quantileValue by remember { mutableFloatStateOf(tuning.overlapQuantile) }
    var safetyValue by remember { mutableFloatStateOf(tuning.safetyRatio) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationGranted = isNotificationGranted(context)
    }

    DisposableEffect(lifecycleOwner, context) {
        if (lifecycleOwner == null) {
            onDispose {}
        } else {
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
    }

    LaunchedEffect(Unit) {
        manager.requestBinder()
        manager.refreshStatus()
        coroutineScope.launch {
            val result = updateChecker.checkForUpdate(
                currentVersion = BuildConfig.VERSION_NAME,
                skippedTag = uiPrefs.getSkippedUpdateTag()
            )
            if (result is UpdateCheckResult.UpdateAvailable) {
                updateDialogInfo = result.info
            }
        }
    }

    val allRequiredGranted = overlayGranted && notificationGranted &&
        shizukuStatus.isBinderAvailable && shizukuStatus.isPermissionGranted

    Scaffold(
        topBar = {
            when (currentScreen) {
                AppScreen.Home -> {
                    TopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.app_name),
                                fontWeight = FontWeight.Bold
                            )
                        },
                        actions = {
                            TextButton(onClick = { currentScreen = AppScreen.Settings }) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = null,
                                    tint = Primary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.White,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                }
                else -> {}
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
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
                        toleranceValue = toleranceValue,
                        quantileValue = quantileValue,
                        safetyValue = safetyValue,
                        debugModeEnabled = debugModeEnabled,
                        currentVersion = "v${BuildConfig.VERSION_NAME}",
                        updateChecking = updateChecking,
                        onBack = { currentScreen = AppScreen.Home },
                        onLanguageChange = {
                            language = it
                            uiPrefs.setLanguage(it)
                            (context as? Activity)?.recreate()
                        },
                        onToleranceChange = { toleranceValue = it },
                        onQuantileChange = { quantileValue = it },
                        onSafetyRatioChange = { safetyValue = it },
                        onDebugModeChange = {
                            debugModeEnabled = it
                            uiPrefs.setDebugModeEnabled(it)
                        },
                        onSave = {
                            val newTuning = StitchTuning(
                                toleranceMultiplier = toleranceValue,
                                overlapQuantile = quantileValue,
                                safetyRatio = safetyValue,
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
                            toleranceValue = tuning.toleranceMultiplier
                            quantileValue = tuning.overlapQuantile
                            safetyValue = tuning.safetyRatio
                        },
                        onRunOnboardingAgain = {
                            uiPrefs.setOnboardingCompleted(false)
                            currentScreen = AppScreen.Onboarding
                        },
                        onCheckUpdate = {
                            coroutineScope.launch {
                                updateChecking = true
                                val result = updateChecker.checkForUpdate(
                                    currentVersion = BuildConfig.VERSION_NAME,
                                    skippedTag = uiPrefs.getSkippedUpdateTag()
                                )
                                updateChecking = false
                                when (result) {
                                    is UpdateCheckResult.UpdateAvailable -> {
                                        updateDialogInfo = result.info
                                    }

                                    is UpdateCheckResult.UpToDate -> {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.update_up_to_date),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }

                                    is UpdateCheckResult.Error -> {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.update_check_failed, result.message),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        },
                        onOpenReleasePage = {
                            openUrl(context, BuildConfig.RELEASES_URL)
                        },
                        onOpenPrivacyPolicy = {
                            val url =
                                "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/blob/${BuildConfig.GITHUB_DOCS_BRANCH}/docs/PRIVACY.md"
                            openUrl(context, url)
                        },
                        onOpenInstallGuide = {
                            val url =
                                "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/blob/${BuildConfig.GITHUB_DOCS_BRANCH}/docs/INSTALL.md"
                            openUrl(context, url)
                        }
                    )
                }
            }
        }
    }

    updateDialogInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { updateDialogInfo = null },
            title = { Text(text = stringResource(R.string.update_dialog_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.update_dialog_message,
                        "v${BuildConfig.VERSION_NAME}",
                        info.tag
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    updateDialogInfo = null
                    openUrl(context, info.releaseUrl)
                }) {
                    Text(text = stringResource(R.string.update_download))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        uiPrefs.setSkippedUpdateTag(info.tag)
                        updateDialogInfo = null
                    }) {
                        Text(text = stringResource(R.string.update_skip_version))
                    }
                    TextButton(onClick = { updateDialogInfo = null }) {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            }
        )
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
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // App Icon
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Android,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = stringResource(R.string.onboarding_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = Secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Permission Items
        PermissionItem(
            icon = Icons.Filled.Settings,
            title = stringResource(R.string.permission_overlay_title),
            description = stringResource(R.string.permission_overlay_desc),
            granted = overlayGranted,
            onGrant = onGrantOverlay
        )

        PermissionItem(
            icon = Icons.Filled.Notifications,
            title = stringResource(R.string.permission_notification_title),
            description = stringResource(R.string.permission_notification_desc),
            granted = notificationGranted,
            onGrant = onGrantNotification,
            grantEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        )

        PermissionItem(
            icon = Icons.Filled.Android,
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

        Spacer(modifier = Modifier.height(32.dp))

        // Continue Button
        Button(
            onClick = onContinue,
            enabled = allRequiredGranted,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                disabledContainerColor = SurfaceVariant
            )
        ) {
            Text(
                text = stringResource(R.string.continue_to_home),
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionItem(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
    grantEnabled: Boolean = true
) {
    val statusColor by animateColorAsState(
        targetValue = if (granted) Success else Secondary,
        label = "statusColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Surface
        ),
        border = BorderStroke(1.dp, SurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Secondary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (granted) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Success,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                TextButton(
                    onClick = onGrant,
                    enabled = grantEnabled
                ) {
                    Text(
                        text = stringResource(R.string.grant_permission),
                        color = if (grantEnabled) Primary else Secondary
                    )
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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Main Action Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            border = BorderStroke(1.dp, SurfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Android,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Status indicators
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatusIndicator(
                        label = stringResource(R.string.status_binder_label),
                        isConnected = status.isBinderAvailable
                    )
                    StatusIndicator(
                        label = stringResource(R.string.status_permission_label),
                        isConnected = status.isPermissionGranted
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Main Button
                Button(
                    onClick = onOpenFloating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    enabled = status.isBinderAvailable && status.isPermissionGranted
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.open_floating_button),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        // Quick Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuickActionCard(
                icon = Icons.Filled.Tune,
                title = stringResource(R.string.open_settings),
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatusIndicator(
    label: String,
    isConnected: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) Success else MaterialTheme.colorScheme.error)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isConnected)
                    stringResource(R.string.status_binder_connected)
                else
                    stringResource(R.string.status_binder_disconnected),
                style = MaterialTheme.typography.bodySmall,
                color = if (isConnected) Success else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun QuickActionCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, SurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    language: AppLanguage,
    toleranceValue: Float,
    quantileValue: Float,
    safetyValue: Float,
    debugModeEnabled: Boolean,
    currentVersion: String,
    updateChecking: Boolean,
    onBack: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onToleranceChange: (Float) -> Unit,
    onQuantileChange: (Float) -> Unit,
    onSafetyRatioChange: (Float) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onRunOnboardingAgain: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenInstallGuide: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(text = stringResource(R.string.back), color = Primary)
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(60.dp))
        }

        // Language Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            border = BorderStroke(1.dp, SurfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_language_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

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

        // Stitch Settings Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            border = BorderStroke(1.dp, SurfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_stitch_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tolerance Slider
                SettingSlider(
                    label = stringResource(R.string.settings_tolerance),
                    value = toleranceValue,
                    onValueChange = onToleranceChange,
                    valueRange = 1.0f..1.2f,
                    valueDisplay = String.format("%.2f", toleranceValue)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Quantile Slider
                SettingSlider(
                    label = stringResource(R.string.settings_quantile),
                    value = quantileValue,
                    onValueChange = onQuantileChange,
                    valueRange = 0.2f..0.8f,
                    valueDisplay = String.format("%.2f", quantileValue)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Safety Ratio Slider
                SettingSlider(
                    label = stringResource(R.string.settings_safety_ratio),
                    value = safetyValue,
                    onValueChange = onSafetyRatioChange,
                    valueRange = 0.0f..0.04f,
                    valueDisplay = String.format("%.3f", safetyValue)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onReset,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.reset_defaults))
                    }
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text(stringResource(R.string.save_settings))
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            border = BorderStroke(1.dp, SurfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_debug_mode_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_debug_mode_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Secondary
                    )
                }
                Switch(
                    checked = debugModeEnabled,
                    onCheckedChange = onDebugModeChange
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            border = BorderStroke(1.dp, SurfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_version_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_latest_version, currentVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Secondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCheckUpdate,
                        enabled = !updateChecking,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (updateChecking) {
                                stringResource(R.string.update_checking)
                            } else {
                                stringResource(R.string.settings_check_update)
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenReleasePage,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.settings_release_download))
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            border = BorderStroke(1.dp, SurfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenPrivacyPolicy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.settings_privacy_policy))
                    }
                    OutlinedButton(
                        onClick = onOpenInstallGuide,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.settings_install_guide))
                    }
                }
            }
        }

        // Re-run onboarding
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRunOnboardingAgain),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            border = BorderStroke(1.dp, SurfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.reopen_guide),
                    style = MaterialTheme.typography.titleSmall,
                    color = Primary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueDisplay: String
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Primary,
                activeTrackColor = Primary,
                inactiveTrackColor = SurfaceVariant
            )
        )
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
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                selectedColor = Primary
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun isNotificationGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
