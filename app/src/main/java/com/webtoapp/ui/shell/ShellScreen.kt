package com.webtoapp.ui.shell

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.webkit.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.webtoapp.WebToAppApplication
import com.webtoapp.core.logging.AppLogger
import com.webtoapp.core.shell.ShellConfig
import com.webtoapp.core.webview.LongPressHandler
import com.webtoapp.core.i18n.Strings
import com.webtoapp.data.model.Announcement
import com.webtoapp.core.forcedrun.ForcedRunConfig
import com.webtoapp.util.TvUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Shell 模式主屏幕 Composable
 *
 * 包含所有 UI 状态声明、初始化逻辑、以及各子组件的组合。
 * 从 ShellActivity.kt 中提取。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(
    config: ShellConfig,
    deepLinkUrl: String? = null,
    onWebViewCreated: (WebView) -> Unit,
    onFileChooser: (ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean,
    onShowCustomView: (View, WebChromeClient.CustomViewCallback?) -> Unit,
    onHideCustomView: () -> Unit,
    onFullscreenModeChanged: (Boolean) -> Unit,
    onForcedRunStateChanged: (Boolean, ForcedRunConfig?) -> Unit,
    // Status bar配置
    statusBarBackgroundType: String = "COLOR",
    statusBarBackgroundColor: String? = null,
    statusBarBackgroundImage: String? = null,
    statusBarBackgroundAlpha: Float = 1.0f,
    statusBarHeightDp: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = context as android.app.Activity
    val activation = WebToAppApplication.activation
    val announcement = WebToAppApplication.announcement
    val adBlocker = WebToAppApplication.adBlock
    // 强制运行状态管理（逻辑已提取到 ShellForcedRunState.kt）
    val forcedRunState = rememberForcedRunState(context)
    val forcedRunActive = forcedRunState.forcedRunActive
    val forcedRunRemainingMs = forcedRunState.forcedRunRemainingMs
    val forcedRunBlocked = forcedRunState.forcedRunBlocked
    val forcedRunBlockedMessage = forcedRunState.forcedRunBlockedMessage

    // Normalize appType (avoid case/whitespace issues)
    val appType = config.appType.trim().uppercase()
    // 调试：打印 appType
    AppLogger.d("ShellScreen", "appType='${config.appType}' (normalized='$appType'), targetUrl='${config.targetUrl}'")
    
    // 状态
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showActivationDialog by remember { mutableStateOf(false) }
    var showAnnouncementDialog by remember { mutableStateOf(false) }
    
    // Activation状态：如果启用了激活码，默认未激活，防止 WebView 在检查完成前加载
    var isActivated by remember { mutableStateOf(!config.activationEnabled) }
    // Activation检查是否完成（用于显示加载状态）
    var isActivationChecked by remember { mutableStateOf(!config.activationEnabled) }
    // 当渲染进程被杀死时自增，强制 AndroidView 重建
    var webViewRecreationKey by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    // 同步检查启动画面配置（必须在 WebView 初始化之前）
    // 同时检查加密和非加密版本
    val splashMediaExists = remember {
        if (config.splashEnabled) {
            val extension = if (config.splashType == "VIDEO") "mp4" else "png"
            val assetPath = "splash_media.$extension"
            val encryptedPath = "$assetPath.enc"
            
            // 先检查加密版本
            val hasEncrypted = try {
                context.assets.open(encryptedPath).close()
                true
            } catch (e: Exception) { false }
            
            // 再检查非加密版本
            val hasNormal = try {
                context.assets.open(assetPath).close()
                true
            } catch (e: Exception) { false }
            
            val exists = hasEncrypted || hasNormal
            AppLogger.d("ShellActivity", "同步检查: 启动画面媒体 encrypted=$hasEncrypted, normal=$hasNormal, exists=$exists")
            exists
        } else false
    }
    
    // Start画面状态 - 根据配置同步初始化
    var showSplash by remember { mutableStateOf(config.splashEnabled && splashMediaExists) }
    var splashCountdown by remember { mutableIntStateOf(if (config.splashEnabled && splashMediaExists) config.splashDuration else 0) }
    var originalOrientation by remember { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }

    // App Links setup banner: shown once when deepLink is enabled and domain not yet verified as default
    val appSigningFingerprint = remember { getAppSigningFingerprint(context) }
    var showAppLinksBanner by remember {
        val prefs = context.getSharedPreferences("shell_prefs", android.content.Context.MODE_PRIVATE)
        val dismissed = prefs.getBoolean("app_links_banner_dismissed", false)
        mutableStateOf(
            config.deepLinkEnabled &&
            config.deepLinkHosts.isNotEmpty() &&
            !dismissed &&
            !isAppLinksVerified(context)
        )
    }
    
    // Handle启动画面横屏
    LaunchedEffect(showSplash) {
        if (showSplash && config.splashLandscape) {
            originalOrientation = activity.requestedOrientation
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    // WebView引用
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    
    // 长按菜单状态
    var showLongPressMenu by remember { mutableStateOf(false) }
    var longPressResult by remember { mutableStateOf<LongPressHandler.LongPressResult?>(null) }
    var longPressTouchX by remember { mutableFloatStateOf(0f) }
    var longPressTouchY by remember { mutableFloatStateOf(0f) }
    val longPressHandler = remember { LongPressHandler(context, scope) }

    // Initialize配置
    LaunchedEffect(Unit) {
        // 设置界面语言（根据 APK 打包时的配置）
        try {
            val appLanguage = when (config.language.uppercase()) {
                "ENGLISH" -> com.webtoapp.core.i18n.AppLanguage.ENGLISH
                "ARABIC" -> com.webtoapp.core.i18n.AppLanguage.ARABIC
                else -> com.webtoapp.core.i18n.AppLanguage.CHINESE
            }
            Strings.setLanguage(appLanguage)
            AppLogger.d("ShellActivity", "设置界面语言: ${config.language} -> $appLanguage")
        } catch (e: Exception) {
            AppLogger.e("ShellActivity", "设置语言失败", e)
        }
        
        // Configure广告拦截
        if (config.adBlockEnabled) {
            adBlocker.initialize(config.adBlockRules, useDefaultRules = true)
            adBlocker.setEnabled(true)
        }

        // Check激活状态
        if (config.activationEnabled) {
            // 如果配置为每次都需要验证，则重置激活状态
            if (config.activationRequireEveryTime) {
                activation.resetActivation(-1L)
                isActivated = false
                isActivationChecked = true
                showActivationDialog = true
            } else {
                // Shell 模式使用固定 ID
                val activated = activation.isActivated(-1L).first()
                isActivated = activated
                isActivationChecked = true
                if (!activated) {
                    showActivationDialog = true
                }
            }
        }

        // Check公告
        if (config.announcementEnabled && isActivated && config.announcementTitle.isNotEmpty()) {
            val ann = Announcement(
                title = config.announcementTitle,
                content = config.announcementContent,
                linkUrl = config.announcementLink.ifEmpty { null },
                showOnce = config.announcementShowOnce
            )
            showAnnouncementDialog = announcement.shouldShowAnnouncement(-1L, ann)
        }

        // Set屏幕方向（根据应用类型判断）
        // ★ 新版：优先使用 orientationMode（支持 7 种模式），
        //       向后兼容旧版只有 landscapeMode 布尔值的 APK
        val validOrientationModes = setOf("PORTRAIT", "LANDSCAPE", "REVERSE_PORTRAIT", "REVERSE_LANDSCAPE", "SENSOR_PORTRAIT", "SENSOR_LANDSCAPE", "AUTO")
        val resolvedOrientationMode = when (appType) {
            "HTML", "FRONTEND" -> config.htmlConfig.landscapeMode.let { if (it) "LANDSCAPE" else "PORTRAIT" }
            "IMAGE", "VIDEO" -> if (config.mediaConfig.landscape) "LANDSCAPE" else "PORTRAIT"
            "GALLERY" -> config.galleryConfig.orientation.uppercase().let { if (it == "LANDSCAPE") "LANDSCAPE" else "PORTRAIT" }
            "WORDPRESS" -> config.wordpressConfig.landscapeMode.let { if (it) "LANDSCAPE" else "PORTRAIT" }
            "NODEJS_APP" -> config.nodejsConfig.landscapeMode.let { if (it) "LANDSCAPE" else "PORTRAIT" }
            "PHP_APP" -> config.phpAppConfig.landscapeMode.let { if (it) "LANDSCAPE" else "PORTRAIT" }
            "PYTHON_APP" -> config.pythonAppConfig.landscapeMode.let { if (it) "LANDSCAPE" else "PORTRAIT" }
            "GO_APP" -> config.goAppConfig.landscapeMode.let { if (it) "LANDSCAPE" else "PORTRAIT" }
            else -> {
                // WEB 应用：优先使用新的 orientationMode（支持全部 7 种模式）
                val orientMode = config.webViewConfig.orientationMode.uppercase()
                if (orientMode in validOrientationModes) orientMode
                else if (config.webViewConfig.landscapeMode) "LANDSCAPE" else "PORTRAIT"
            }
        }
        
        when (resolvedOrientationMode) {
            "LANDSCAPE" -> {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            "REVERSE_PORTRAIT" -> {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            }
            "REVERSE_LANDSCAPE" -> {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            }
            "SENSOR_PORTRAIT" -> {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            "SENSOR_LANDSCAPE" -> {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            "AUTO" -> {
                // Auto rotation: respects the system auto-rotate setting
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
            }
            else -> {
                if (TvUtils.isTv(context)) {
                    // TV 设备不锁定方向，保持默认横屏
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } else {
                    // 手机/平板锁定为竖屏模式
                    @SuppressLint("SourceLockedOrientationActivity")
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }

        // Start画面已在同步初始化阶段处理
        AppLogger.d("ShellActivity", "LaunchedEffect: showSplash=$showSplash, splashCountdown=$splashCountdown")
        
    }

    // 强制运行副作用管理（逻辑已提取到 ShellForcedRunState.kt）
    ForcedRunEffects(
        state = forcedRunState,
        config = config.forcedRunConfig,
        isActivated = isActivated,
        context = context,
        onForcedRunStateChanged = onForcedRunStateChanged
    )

    // Start画面倒计时（仅用于图片类型，视频类型由播放器控制）
    LaunchedEffect(showSplash, splashCountdown) {
        // Video类型不使用倒计时，由视频播放器控制结束
        if (config.splashType == "VIDEO") return@LaunchedEffect
        
        if (showSplash && splashCountdown > 0) {
            delay(1000L)
            splashCountdown--
        } else if (showSplash && splashCountdown <= 0) {
            showSplash = false
            // 恢复原始方向
            if (originalOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity.requestedOrientation = originalOrientation
                originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
    
    // ===== 背景音乐播放器（逻辑已提取到 ShellBgmPlayer.kt）=====
    val bgmState = rememberBgmPlayerState(context, config)

    // WebView回调（逻辑已提取到 ShellWebViewCallbacks.kt）
    val webViewCallbacks = remember {
        createShellWebViewCallbacks(
            context = context,
            config = config,
            webViewRefProvider = { webViewRef },
            currentUrlProvider = { currentUrl },
            longPressHandler = longPressHandler,
            handleShowCustomView = onShowCustomView,
            handleHideCustomView = onHideCustomView,
            handleFileChooser = onFileChooser,
            updateLoading = { isLoading = it },
            updateUrl = { currentUrl = it },
            updateTitle = { pageTitle = it },
            updateProgress = { loadProgress = it },
            updateError = { errorMessage = it },
            updateNavigation = { back, forward -> canGoBack = back; canGoForward = forward },
            updateWebViewRef = { webViewRef = it },
            notifyRecreationKeyIncrement = { webViewRecreationKey++ },
            notifyLongPressMenu = { result, x, y ->
                longPressResult = result
                longPressTouchX = x
                longPressTouchY = y
                showLongPressMenu = true
            }
        )
    }

    // 转换配置（逻辑已提取到 ShellWebViewConfig.kt）
    val webViewConfig = buildWebViewConfig(config)

    val webViewManager = remember { 
        com.webtoapp.core.webview.WebViewManager(context, adBlocker)
    }

    // Yes否隐藏工具栏（全屏模式）
    val hideToolbar = config.webViewConfig.hideToolbar
    val hideBrowserToolbar = config.webViewConfig.hideBrowserToolbar
    // 下拉刷新开关
    val swipeRefreshEnabled = config.webViewConfig.swipeRefreshEnabled

    LaunchedEffect(hideToolbar) {
        onFullscreenModeChanged(hideToolbar)
    }
    
    // 关闭启动画面的回调（提前定义）
    val closeSplash = {
        showSplash = false
        // 恢复原始方向
        if (originalOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            activity.requestedOrientation = originalOrientation
            originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 整体容器，确保启动画面覆盖在 Scaffold 之上
    // 使用 fillMaxSize 确保内容铺满整个屏幕（包括状态栏区域）
    Box(modifier = Modifier.fillMaxSize()) {
    
    // Scaffold 布局（逻辑已提取到 ShellScaffoldLayout.kt）
    ShellScaffoldLayout(
        config = config,
        appType = appType,
        hideToolbar = hideToolbar,
        hideBrowserToolbar = hideBrowserToolbar,
        isLoading = isLoading,
        loadProgress = loadProgress,
        pageTitle = pageTitle,
        currentUrl = currentUrl,
        errorMessage = errorMessage,
        isActivationChecked = isActivationChecked,
        isActivated = isActivated,
        forcedRunActive = forcedRunActive,
        forcedRunBlocked = forcedRunBlocked,
        forcedRunBlockedMessage = forcedRunBlockedMessage,
        forcedRunRemainingMs = forcedRunRemainingMs,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        webViewRecreationKey = webViewRecreationKey,
        webViewRef = webViewRef,
        webViewConfig = webViewConfig,
        webViewCallbacks = webViewCallbacks,
        webViewManager = webViewManager,
        deepLinkUrl = deepLinkUrl,
        bgmState = bgmState,
        swipeRefreshEnabled = swipeRefreshEnabled,
        isRefreshing = isRefreshing,
        onRefresh = { isRefreshing = false },
        onWebViewCreated = onWebViewCreated,
        onWebViewRefUpdated = { webViewRef = it },
        onShowActivationDialog = { showActivationDialog = true },
        onErrorDismiss = { errorMessage = null },
        onActivityFinish = { activity.finish() },
        statusBarHeightDp = statusBarHeightDp
    )

    // Activation码对话框（逻辑已提取到 ShellDialogs.kt）
    if (showActivationDialog) {
        ShellActivationDialog(
            config = config,
            onDismiss = { showActivationDialog = false },
            onActivated = {
                isActivated = true
                showActivationDialog = false
                // Check公告
                if (config.announcementEnabled && config.announcementTitle.isNotEmpty()) {
                    val ann = Announcement(
                        title = config.announcementTitle,
                        content = config.announcementContent,
                        linkUrl = config.announcementLink.ifEmpty { null },
                        showOnce = config.announcementShowOnce
                    )
                    showAnnouncementDialog = kotlinx.coroutines.runBlocking { announcement.shouldShowAnnouncement(-1L, ann) }
                }
            }
        )
    }

    // Announcement对话框（逻辑已提取到 ShellDialogs.kt）
    if (showAnnouncementDialog && config.announcementTitle.isNotEmpty()) {
        ShellAnnouncementDialog(
            config = config,
            onDismiss = { showAnnouncementDialog = false }
        )
    }
    
    // 强制运行权限引导对话框（逻辑已提取到 ShellDialogs.kt）
    if (forcedRunState.showForcedRunPermissionDialog && config.forcedRunConfig != null) {
        ShellForcedRunPermissionDialog(
            config = config,
            forcedRunActive = forcedRunActive,
            onDismiss = { forcedRunState.showForcedRunPermissionDialog = false }
        )
    }

    // Start画面覆盖层（在 Box 内，覆盖在 Scaffold 之上）
    AnimatedVisibility(
        visible = showSplash,
        enter = fadeIn(animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(300))
    ) {
        ShellSplashOverlay(
            splashType = config.splashType,
            countdown = splashCountdown,
            videoStartMs = config.splashVideoStartMs,
            videoEndMs = config.splashVideoEndMs,
            fillScreen = config.splashFillScreen,
            enableAudio = config.splashEnableAudio,
            // 点击跳过（仅当启用时）
            onSkip = if (config.splashClickToSkip) { closeSplash } else null,
            // Play完成回调（始终需要）
            onComplete = closeSplash
        )
    }
    
    // 长按菜单（逻辑已提取到 ShellLongPressMenu.kt）
    if (showLongPressMenu && longPressResult != null) {
        ShellLongPressMenu(
            menuStyle = config.webViewConfig.longPressMenuStyle,
            result = longPressResult!!,
            touchX = longPressTouchX,
            touchY = longPressTouchY,
            longPressHandler = longPressHandler,
            onDismiss = {
                showLongPressMenu = false
                longPressResult = null
            }
        )
    }
    
    // Status bar背景覆盖层
    // Show overlay when: fullscreen with status bar visible, OR non-fullscreen with custom status bar config
    val hasCustomStatusBar = statusBarBackgroundType != "COLOR" || statusBarBackgroundColor != null || statusBarHeightDp > 0
    val showStatusBarOverlay = (hideToolbar && config.webViewConfig.showStatusBarInFullscreen) || (!hideToolbar && hasCustomStatusBar)
    if (showStatusBarOverlay) {
        // Force status bar icon color to match overlay background
        val isLightOverlayBackground = remember(statusBarBackgroundColor) {
            if (statusBarBackgroundColor != null) {
                try {
                    val color = android.graphics.Color.parseColor(
                        if (statusBarBackgroundColor!!.startsWith("#")) statusBarBackgroundColor else "#$statusBarBackgroundColor"
                    )
                    com.webtoapp.ui.shared.WindowHelper.isColorLight(color)
                } catch (e: Exception) { false }
            } else false
        }
        // Use native WindowInsetsController API (bypasses compat layer issues)
        SideEffect {
            val activity = context as? android.app.Activity ?: return@SideEffect
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val controller = activity.window.insetsController
                if (isLightOverlayBackground) {
                    controller?.setSystemBarsAppearance(
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    )
                } else {
                    controller?.setSystemBarsAppearance(
                        0,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                val flags = activity.window.decorView.systemUiVisibility
                activity.window.decorView.systemUiVisibility = if (isLightOverlayBackground) {
                    flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else {
                    flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
            }
        }
        com.webtoapp.ui.components.StatusBarOverlay(
            show = true,
            backgroundType = statusBarBackgroundType,
            backgroundColor = statusBarBackgroundColor,
            backgroundImagePath = statusBarBackgroundImage,
            alpha = statusBarBackgroundAlpha,
            heightDp = statusBarHeightDp,
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
    
    // App Links setup banner
    if (showAppLinksBanner) {
        AppLinksBanner(
            signingFingerprint = appSigningFingerprint,
            onEnable = {
                showAppLinksBanner = false
                openAppDefaultSettings(context)
            },
            onDismiss = {
                showAppLinksBanner = false
                context.getSharedPreferences("shell_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("app_links_banner_dismissed", true).apply()
            }
        )
    }

    } // 关闭外层 Box
}

/**
 * Returns true if the app is already the verified/selected default handler for its domains,
 * meaning the banner does not need to be shown.
 *
 * On Android 12+ (API 31+) the user MUST manually enable "Supported web addresses" in Settings
 * even with a valid assetlinks.json, so we use DomainVerificationManager to check.
 * Below Android 12, autoVerify=true handles verification automatically — no manual step needed.
 */
private fun isAppLinksVerified(context: android.content.Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
        // Pre-Android-12: autoVerify handles it automatically, no manual step required
        return true
    }
    return try {
        val mgr = context.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
            ?: return false
        val info = mgr.getDomainVerificationUserState(context.packageName) ?: return false
        // Check if any domain is selected (user-selected) or verified (autoVerify passed)
        val states = info.hostToStateMap
        states.values.any { state ->
            state == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
            state == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * Returns the SHA-256 fingerprint of this APK's signing certificate, formatted as AA:BB:CC:...
 * This is what must appear in assetlinks.json for App Links verification to pass.
 */
@Suppress("DEPRECATION")
private fun getAppSigningFingerprint(context: android.content.Context): String? {
    return try {
        val pm = context.packageManager
        val signingInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            val info = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        } ?: return null

        val cert = java.security.cert.CertificateFactory.getInstance("X509")
            .generateCertificate(signingInfo.toByteArray().inputStream())
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        digest.joinToString(":") { "%02X".format(it) }
    } catch (_: Exception) {
        null
    }
}

/**
 * Opens the system screen where the user can enable "Supported web addresses" for this app.
 * Uses ACTION_APP_OPEN_BY_DEFAULT_SETTINGS on Android 12+, falls back to app info screen.
 */
private fun openAppDefaultSettings(context: android.content.Context) {
    try {
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.content.Intent(
                android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}")
            )
        } else {
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}")
            )
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) { }
}

/**
 * Banner shown once to guide user to enable App Links in system settings.
 * Also shows the APK signing fingerprint so the user can verify it matches assetlinks.json.
 */
@Composable
private fun BoxScope.AppLinksBanner(
    signingFingerprint: String?,
    onEnable: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var fingerprintCopied by remember { mutableStateOf(false) }

    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .padding(12.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = Strings.appLinksSetupTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                androidx.compose.material3.IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Text(
                text = Strings.appLinksBannerDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            // Show this APK's signing fingerprint so the user can verify it matches assetlinks.json
            if (signingFingerprint != null) {
                Text(
                    text = Strings.appLinksFingerprintLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(signingFingerprint))
                            fingerprintCopied = true
                        }
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                            androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = signingFingerprint,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (fingerprintCopied) Icons.Outlined.CheckCircle else Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = onEnable,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(Strings.appLinksBannerButton)
            }
        }
    }
}
