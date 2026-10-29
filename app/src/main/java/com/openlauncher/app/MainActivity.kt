package com.openlauncher.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.openlauncher.app.data.DayNightMode
import com.openlauncher.app.data.SidebarPosition
import com.openlauncher.app.data.GradientDirection
import com.openlauncher.app.data.AppLanguage
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.os.ConfigurationCompat
import java.util.Locale
import com.openlauncher.app.model.NavDestination
import com.openlauncher.app.ui.components.Sidebar
import com.openlauncher.app.ui.screen.*
import com.openlauncher.app.ui.theme.OpenLauncherTheme
import com.openlauncher.app.viewmodel.LauncherViewModel

class MainActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    private fun updateAppLocale(language: AppLanguage) {
        val targetLocale = when (language) {
            AppLanguage.ENGLISH -> Locale.ENGLISH
            AppLanguage.POLISH  -> Locale.forLanguageTag("pl")
            AppLanguage.GERMAN  -> Locale.forLanguageTag("de")
            AppLanguage.SPANISH -> Locale.forLanguageTag("es")
            AppLanguage.FRENCH  -> Locale.forLanguageTag("fr")
            AppLanguage.RUSSIAN -> Locale.forLanguageTag("ru")
            AppLanguage.SYSTEM  -> {
                val systemLang = Resources.getSystem()
                    .configuration.locales[0].language
                if (systemLang.equals("pl", ignoreCase = true)) {
                    Locale.forLanguageTag("pl")
                } else if (systemLang.equals("de", ignoreCase = true)) {
                    Locale.forLanguageTag("de")
                } else if (systemLang.equals("es", ignoreCase = true)) {
                    Locale.forLanguageTag("es")
                } else if (systemLang.equals("fr", ignoreCase = true)) {
                    Locale.forLanguageTag("fr")
                } else if (systemLang.equals("ru", ignoreCase = true)) {
                    Locale.forLanguageTag("ru")
                } else {
                    Locale.ENGLISH
                }
            }
        }
        Locale.setDefault(targetLocale)
        val config = resources.configuration
        config.setLocale(targetLocale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private val locationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            vm.startLocationUpdates()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == android.content.Intent.ACTION_MAIN &&
            intent.hasCategory(android.content.Intent.CATEGORY_HOME)
        ) {
            vm.navigate(NavDestination.HOME)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        setContent {
            val settingsLoaded by vm.settingsLoaded.collectAsStateWithLifecycle()
            val settings       by vm.settings.collectAsStateWithLifecycle()
            
            LaunchedEffect(settings.appLanguage) {
                updateAppLocale(settings.appLanguage)
            }

            val nav            by vm.nav.collectAsStateWithLifecycle()
            val apps        by vm.apps.collectAsStateWithLifecycle()
            val appsLoading by vm.appsLoading.collectAsStateWithLifecycle()
            val nowPlaying  by vm.nowPlaying.collectAsStateWithLifecycle()
            val weather     by vm.weather.collectAsStateWithLifecycle()
            val placeName   by vm.placeName.collectAsStateWithLifecycle()
            val voltage     by vm.voltage.collectAsStateWithLifecycle()
            val location    by vm.location.collectAsStateWithLifecycle()
            val bearing     by vm.compassBearing.collectAsStateWithLifecycle()
            val isWifi      by vm.isWifi.collectAsStateWithLifecycle()
            val isData      by vm.isData.collectAsStateWithLifecycle()
            val isDayModeVM by vm.isDayMode.collectAsStateWithLifecycle()
            val hardwareRadio by vm.hardwareRadio.collectAsStateWithLifecycle()
            val systemIsDark = isSystemInDarkTheme()
            val isDayMode = if (settings.dayNightMode == DayNightMode.SYSTEM) !systemIsDark else isDayModeVM
            val pickerSlot      by vm.shortcutPickerSlot.collectAsStateWithLifecycle()
            val appPickerTarget by vm.appPickerTarget.collectAsStateWithLifecycle()

            // A preset themeId overrides the manually-picked accent/background/font
            // colors below; "custom" (or an unrecognized id) falls through to those.
            val resolvedTheme  = com.openlauncher.app.data.resolveDashboardTheme(settings.themeId, isDayMode)
            val accent         = resolvedTheme?.accent ?: Color(settings.accentColor)
            val bg             = resolvedTheme?.background ?: if (settings.useCustomBackgroundColor) {
                Color(settings.backgroundColor)
            } else {
                if (isDayMode) Color(0xFFEEEEEE) else Color.Black
            }
            val textColor      = resolvedTheme?.ink
                ?: if (isDayMode) Color(0xFF111111) else Color(settings.fontColor)
            val bgGradientEnd  = Color(settings.gradientEndColor)
            val customBrush    = if (settings.useCustomBackgroundColor && settings.useGradient) {
                val colors = listOf(bg, bgGradientEnd)
                when (settings.gradientDirection) {
                    GradientDirection.TOP_TO_BOTTOM -> androidx.compose.ui.graphics.Brush.verticalGradient(colors)
                    GradientDirection.LEFT_TO_RIGHT -> androidx.compose.ui.graphics.Brush.horizontalGradient(colors)
                    GradientDirection.DIAGONAL -> androidx.compose.ui.graphics.Brush.linearGradient(colors)
                    GradientDirection.RADIAL -> androidx.compose.ui.graphics.Brush.radialGradient(colors)
                }
            } else null
            // A dashboard theme's gradient wins over the user's custom gradient when a preset is active.
            val bgBrush = resolvedTheme?.backgroundBrush ?: customBrush

            val currentContext = LocalContext.current
            val localizedContext = remember(settings.appLanguage, currentContext) {
                val targetLocale = when (settings.appLanguage) {
                    AppLanguage.ENGLISH -> Locale.ENGLISH
                    AppLanguage.POLISH  -> Locale.forLanguageTag("pl")
                    AppLanguage.GERMAN  -> Locale.forLanguageTag("de")
                    AppLanguage.SPANISH -> Locale.forLanguageTag("es")
                    AppLanguage.FRENCH  -> Locale.forLanguageTag("fr")
                    AppLanguage.RUSSIAN -> Locale.forLanguageTag("ru")
                    AppLanguage.SYSTEM  -> {
                        val systemLang = Resources.getSystem()
                            .configuration.locales[0].language
                        if (systemLang.equals("pl", ignoreCase = true)) {
                            Locale.forLanguageTag("pl")
                        } else if (systemLang.equals("de", ignoreCase = true)) {
                            Locale.forLanguageTag("de")
                        } else if (systemLang.equals("es", ignoreCase = true)) {
                            Locale.forLanguageTag("es")
                        } else if (systemLang.equals("fr", ignoreCase = true)) {
                            Locale.forLanguageTag("fr")
                        } else if (systemLang.equals("ru", ignoreCase = true)) {
                            Locale.forLanguageTag("ru")
                        } else {
                            Locale.ENGLISH
                        }
                    }
                }
                Locale.setDefault(targetLocale)
                val config = Configuration(currentContext.resources.configuration)
                config.setLocale(targetLocale)
                currentContext.createConfigurationContext(config)
            }

            val activity: ComponentActivity = this
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                androidx.activity.compose.LocalActivityResultRegistryOwner provides activity,
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
                LocalDensity provides Density(
                    density   = baseDensity.density * settings.uiScale,
                    fontScale = baseDensity.fontScale
                )
            ) {
                if (!settingsLoaded) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                } else OpenLauncherTheme(
                    accent     = accent,
                    background = bg,
                    textColor  = textColor,
                    fontBold   = settings.fontBold,
                    textScale  = settings.textScale,
                    appFont    = settings.appFont,
                    isDayMode  = isDayMode,
                    useCustomBg = resolvedTheme != null || settings.useCustomBackgroundColor
                ) {
                if (!settings.onboardingCompleted) {
                    OnboardingScreen(
                        accent = accent,
                        onComplete = {
                            vm.updateSettings { copy(onboardingCompleted = true) }
                            // Start location updates immediately upon completion
                            vm.startLocationUpdates()
                        }
                    )
                } else {
                    com.openlauncher.app.ui.components.VolumeGestureBox(
                        accent = accent,
                        panel  = resolvedTheme?.background ?: (if (isDayMode) Color(0xFFF5F5F5) else Color(0xFF121212)),
                        ink    = textColor,
                        modifier = Modifier.let { m ->
                            if (bgBrush != null) m.background(bgBrush) else m.background(bg)
                        },
                    ) {
                        // Optional wallpaper layer
                        if (settings.wallpaperUri.isNotEmpty()) {
                            AsyncImage(
                                model              = android.net.Uri.parse(settings.wallpaperUri),
                                contentDescription = null,
                                contentScale       = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier           = Modifier.fillMaxSize()
                            )
                            Box(modifier = Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = settings.wallpaperDim)))
                        }

                        val isBottomBar    = settings.sidebarPosition == SidebarPosition.BOTTOM
                        val layoutDivColor = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1A1A1A)

                        val sidebarContent: @Composable () -> Unit = {
                            val sidebarDensity = Density(
                                density = baseDensity.density * (1.0f + (settings.uiScale - 1.0f) * 0.35f),
                                fontScale = baseDensity.fontScale
                            )
                            CompositionLocalProvider(LocalDensity provides sidebarDensity) {
                                Sidebar(
                                    currentDest   = nav,
                                    settings      = settings,
                                    isHorizontal  = isBottomBar,
                                    themeAccent   = accent,
                                    themeBg       = resolvedTheme?.background,
                                    installedIconFor = { pkg ->
                                        apps.find { it.packageName == pkg }?.icon
                                    },
                                    onNavigate    = { dest ->
                                        vm.cancelShortcutPicker()
                                        vm.cancelCarPlayPicker()
                                        vm.exitRearrangeMode()
                                        vm.navigate(dest)
                                    },
                                    onShortcutClick = { slot ->
                                        val shortcut = settings.shortcuts[slot]
                                        if (shortcut.packageName.isNotEmpty()) {
                                            vm.launchApp(shortcut.packageName)
                                        }
                                    },
                                    onShortcutLongPress  = { slot -> vm.startShortcutPicker(slot) },
                                    onShortcutRemove     = { slot -> vm.removeShortcut(slot) },
                                    onShortcutSetIcon    = { slot, icon -> vm.setShortcutIcon(slot, icon) },
                                    onReorder            = { from, to -> vm.reorderShortcut(from, to) }
                                )
                            }
                        }

                        val mainPane: @Composable (Modifier) -> Unit = { paneModifier ->
                            // ── Main content pane ─────────────────────────────
                            AnimatedContent(
                                targetState   = nav,
                                transitionSpec = {
                                    fadeIn() + slideInHorizontally { it / 10 } togetherWith
                                    fadeOut() + slideOutHorizontally { -it / 10 }
                                },
                                modifier = paneModifier,
                                label    = "pane_transition"
                            ) { destination ->
                                when (destination) {
                                    NavDestination.HOME -> if (settings.themeId == "mbux") MbuxHomeScreen(
                                        nowPlaying      = nowPlaying,
                                        hardwareRadio   = hardwareRadio,
                                        accent          = accent,
                                        onPlayPause     = vm::playPause,
                                        onNext          = vm::skipNext,
                                        onPrev          = vm::skipPrev,
                                        onTapNowPlaying = {
                                            val pkg = nowPlaying?.controller?.packageName
                                            if (!pkg.isNullOrEmpty()) vm.launchApp(pkg)
                                        },
                                        onLaunchRadio   = { vm.launchHardwareRadioApp() },
                                        onRadioSeekUp   = { vm.radioSeekUp() },
                                        onRadioSeekDown = { vm.radioSeekDown() },
                                        onOpenApps      = { vm.navigate(NavDestination.APP_LIBRARY) },
                                    ) else HomeScreen(
                                        settings            = settings,
                                        weather             = weather,
                                        nowPlaying          = nowPlaying,
                                        location            = location,
                                        placeName           = placeName,
                                        voltage             = voltage,
                                        bearing             = bearing,
                                        isWifi              = isWifi,
                                        isData              = isData,
                                        isDayMode           = isDayMode,
                                        onPlayPause         = vm::playPause,
                                        onNext              = vm::skipNext,
                                        onPrev              = vm::skipPrev,
                                        onLaunchCarPlay     = { vm.launchApp(settings.carPlayPackage) },
                                        onLaunchAndroidAuto = { vm.launchApp(settings.androidAutoPackage) },
                                        onAssignCarPlay     = { vm.startCarPlayPicker() },
                                        onAssignAndroidAuto = { vm.startAndroidAutoPicker() },
                                        onClearCarPlay      = { vm.clearCarPlayApp() },
                                        onClearAndroidAuto  = { vm.clearAndroidAutoApp() },
                                        onAssignPip         = { vm.startPipPicker() },
                                        onClearPip          = { vm.clearPipApp() },
                                        onLaunchPip         = { vm.launchApp(settings.pipAppPackage) },
                                        onTapNowPlaying     = {
                                            val pkg = nowPlaying?.controller?.packageName
                                            if (!pkg.isNullOrEmpty()) vm.launchApp(pkg)
                                        },
                                        onUpdateWidget      = { id, sx, sy -> vm.updateWidgetConfig(id, sx, sy) },
                                        onMoveWidget        = { id, gx, gy -> vm.moveWidgetConfig(id, gx, gy) },
                                        onAddWidget         = { id -> vm.addWidget(id) },
                                        onRemoveWidget      = { id -> vm.removeWidget(id) },
                                        onApplyPreset       = { preset -> vm.applyLayoutPreset(preset) },
                                        onAddFuelEntry      = { odo, vol, cost -> vm.addFuelEntry(odo, vol, cost) },
                                        onSetClockStyle     = { style -> vm.updateSettings { copy(clockStyle = style) } },
                                        onSetVitalsAsBars   = { asBars -> vm.updateSettings { copy(vitalsAsBars = asBars) } },
                                        onSetSpeedometerDigitalOnly = { digital -> vm.updateSettings { copy(speedometerDigitalOnly = digital) } },
                                        onSetLocationDetailLevel = { level ->
                                            vm.updateSettings { copy(locationDetailLevel = level) }
                                            location?.let { vm.fetchPlaceName(it.latitude, it.longitude) }
                                        },
                                        onUpdateSoundPad    = { idx, pad -> vm.updateSoundboardPad(idx, pad) },
                                        hardwareRadio         = hardwareRadio,
                                        onLaunchHardwareRadio = { vm.launchHardwareRadioApp() },
                                        onStopHardwareRadio   = { vm.stopHardwareRadioApp() },
                                        onRadioSeekUp         = { vm.radioSeekUp() },
                                        onRadioSeekDown       = { vm.radioSeekDown() },
                                        onRadioCycleFm        = { vm.radioCycleFm() },
                                        onRadioSwitchAm       = { vm.radioSwitchAm() },
                                        onRadioTune           = { band, freq -> vm.radioTune(band, freq) },
                                        onAssignRadio         = { vm.startRadioPicker() },
                                        onUpdate              = { block -> vm.updateSettings(block) }
                                    )

                                    NavDestination.APP_LIBRARY -> AppLibraryScreen(
                                        apps                = apps,
                                        isLoading           = appsLoading,
                                        isPickerMode        = pickerSlot != null,
                                        pickerSlot          = pickerSlot,
                                        isCarPlayPickerMode = appPickerTarget != null,
                                        carPlayPickerLabel  = when (appPickerTarget) {
                                            com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget.ANDROID_AUTO -> stringResource(R.string.choose_android_auto_app)
                                            com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget.PIP          -> stringResource(R.string.choose_pip_app)
                                            com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget.RADIO        -> stringResource(R.string.choose_radio_app)
                                            else -> stringResource(R.string.choose_carplay_app)
                                        },
                                        accent              = accent,
                                        onAppClick          = { app -> vm.launchApp(app.packageName) },
                                        onPickerSelect      = { slot, app -> vm.assignShortcut(slot, app) },
                                        onCarPlaySelect     = { app -> vm.assignPickerApp(app) }
                                    )

                                    NavDestination.SETTINGS -> SettingsScreen(
                                        settings = settings,
                                        accent   = accent,
                                        onUpdate = { block -> vm.updateSettings(block) },
                                        onReset  = { vm.resetSettings() }
                                    )
                                }
                            }
                        }

                        if (isBottomBar) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                mainPane(Modifier.weight(1f).fillMaxWidth())
                                androidx.compose.material3.HorizontalDivider(color = layoutDivColor)
                                sidebarContent()
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxSize()) {
                                val vDivider: @Composable () -> Unit = {
                                    androidx.compose.material3.VerticalDivider(
                                        modifier = Modifier.fillMaxHeight(),
                                        color    = layoutDivColor
                                    )
                                }
                                if (settings.sidebarPosition == SidebarPosition.LEFT) {
                                    sidebarContent()
                                    vDivider()
                                }
                                mainPane(Modifier.weight(1f).fillMaxHeight())
                                if (settings.sidebarPosition == SidebarPosition.RIGHT) {
                                    vDivider()
                                    sidebarContent()
                                }
                            }
                        }
                    }
                }
            }
            } // CompositionLocalProvider
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshConnectivity()
        vm.refreshMedia()
    }

    override fun onStop() {
        super.onStop()
        vm.stopLocationUpdates()
    }

    override fun onStart() {
        super.onStart()
        vm.startLocationUpdates()
    }
}
