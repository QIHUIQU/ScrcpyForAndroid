package io.github.miuzarte.scrcpyforandroid.pages

import android.content.pm.ActivityInfo
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.miuzarte.scrcpyforandroid.NativeCoreFacade
import io.github.miuzarte.scrcpyforandroid.StreamActivity
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.services.LocalSnackbarController
import io.github.miuzarte.scrcpyforandroid.services.SnackbarController
import io.github.miuzarte.scrcpyforandroid.services.keepalive.CrashRecoveryPolicy
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import io.github.miuzarte.scrcpyforandroid.ui.createThemeController
import io.github.miuzarte.scrcpyforandroid.widgets.VideoOutputTarget
import io.github.miuzarte.scrcpyforandroid.widgets.VideoOutputTargetState
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun StreamScreen(activity: StreamActivity) {
    val scrcpy = AppRuntime.scrcpy
    if (scrcpy == null) {
        // 会话已被释放: 直接收尾, 不要 NPE 崩溃
        LaunchedEffect(Unit) { activity.finish() }
        return
    }
    val asBundle by Storage.appSettings.bundleState.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            VideoOutputTargetState.set(VideoOutputTarget.PREVIEW)
        }
    }

    val themeController = remember(
        asBundle.themeBaseIndex,
        asBundle.monet,
        asBundle.monetSeedIndex,
        asBundle.monetPaletteStyle,
        asBundle.monetColorSpec,
    ) {
        asBundle.createThemeController()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val snackbarController = remember(snackbarScope, snackbarHostState) {
        SnackbarController(scope = snackbarScope, hostState = snackbarHostState)
    }
    DisposableEffect(snackbarHostState) {
        val unregister = AppRuntime.registerSnackbarHostState(snackbarHostState)
        onDispose(unregister)
    }

    MiuixTheme(
        controller = themeController,
    ) {
        CompositionLocalProvider(
            LocalSnackbarController provides snackbarController,
        ) {
            FullscreenControlRoute(
                scrcpy = scrcpy,
                onBack = activity::finish,
            )
        }
    }
}

@Composable
fun FullscreenControlRoute(
    scrcpy: Scrcpy,
    onBack: () -> Unit,
    autoExitOnStop: Boolean = false,
) {
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val asBundle by Storage.appSettings.bundleState.collectAsState()
    val currentSession by scrcpy.currentSessionState.collectAsState()
    val restartInProgress by NativeCoreFacade.restartInProgress.collectAsState()

    LaunchedEffect(currentSession, restartInProgress) {
        // 重连切换期间 currentSession 会短暂为 null, 此时不能退出串流界面
        if (currentSession == null && !restartInProgress) {
            onBack()
        }
    }

    // 阶段 4：复用两种全屏入口的共同路由，covered composition 不冒充前台画面。
    val routeState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val remotePageVisible = CrashRecoveryPolicy.remotePageVisible(
        sessionReady = currentSession != null,
        routeResumed = routeState == Lifecycle.State.RESUMED,
        windowFocused = LocalWindowInfo.current.isWindowFocused,
    )
    LaunchedEffect(remotePageVisible) {
        if (remotePageVisible) {
            Log.i("FullscreenControlRoute", "fullscreen control page visible (sessionReady=true routeResumed=true windowFocused=true)")
        }
    }

    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(lifecycleOwner, autoExitOnStop) {
        if (!autoExitOnStop) {
            onDispose {}
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    onBack()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    FullscreenControlScreen(
        scrcpy = scrcpy,
        onBack = onBack,
        onVideoSizeChanged = { width, height ->
            activity?.requestedOrientation =
                fullscreenRequestedOrientation(
                    width = width,
                    height = height,
                    ignoreSystemRotationLock = asBundle.fullscreenControlIgnoreSystemRotationLock,
                )
        },
    )
}

private fun fullscreenRequestedOrientation(
    width: Int,
    height: Int,
    ignoreSystemRotationLock: Boolean,
) = if (width >= height) {
    if (ignoreSystemRotationLock)
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    else
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
} else {
    if (ignoreSystemRotationLock)
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    else
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
}
