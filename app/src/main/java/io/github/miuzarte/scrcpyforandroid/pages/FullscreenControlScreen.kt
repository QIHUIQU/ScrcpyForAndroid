package io.github.miuzarte.scrcpyforandroid.pages

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.miuzarte.scrcpyforandroid.NativeCoreFacade
import io.github.miuzarte.scrcpyforandroid.R
import io.github.miuzarte.scrcpyforandroid.constants.UiSpacing
import io.github.miuzarte.scrcpyforandroid.password.rememberPasswordPickerEntries
import io.github.miuzarte.scrcpyforandroid.scaffolds.SuperTextField
import io.github.miuzarte.scrcpyforandroid.scrcpy.ClientOptions
import io.github.miuzarte.scrcpyforandroid.scrcpy.GamepadHid
import io.github.miuzarte.scrcpyforandroid.scrcpy.GamepadInputHandler
import io.github.miuzarte.scrcpyforandroid.scrcpy.GammaSettings
import io.github.miuzarte.scrcpyforandroid.scrcpy.VideoScaleSettings
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import io.github.miuzarte.scrcpyforandroid.scrcpy.TouchEventHandler
import io.github.miuzarte.scrcpyforandroid.scrcpy.TransferOptions
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.services.LocalInputService
import io.github.miuzarte.scrcpyforandroid.services.LocalSnackbarController
import io.github.miuzarte.scrcpyforandroid.storage.AppSettings
import io.github.miuzarte.scrcpyforandroid.storage.Settings
import io.github.miuzarte.scrcpyforandroid.storage.Storage.appSettings
import io.github.miuzarte.scrcpyforandroid.util.Debouncer
import io.github.miuzarte.scrcpyforandroid.widgets.*
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text

@SuppressLint("NewApi")
@Composable
fun FullscreenControlScreen(
    scrcpy: Scrcpy,
    onBack: () -> Unit,
    onVideoSizeChanged: (width: Int, height: Int) -> Unit,
) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val snackbarController = LocalSnackbarController.current

    val taskScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    val currentSession by scrcpy.currentSessionState.collectAsState()
    val listingsRefreshBusy by scrcpy.listings.refreshBusyState.collectAsState()
    val listingsRefreshVersion by scrcpy.listings.refreshVersionState.collectAsState()

    val restartInProgress by NativeCoreFacade.restartInProgress.collectAsState()

    LaunchedEffect(currentSession, restartInProgress) {
        // 重连切换期间 currentSession 会短暂为 null, 此时不能退出串流界面
        if (currentSession == null && !restartInProgress) onBack()
    }

    // 预取应用/最近任务列表 (非阻塞): 打开串流界面即后台拉取, 供"全部应用"/一键流转即时使用
    LaunchedEffect(scrcpy) {
        withContext(Dispatchers.IO) {
            if (scrcpy.listings.recentTasks.isEmpty()) {
                runCatching { scrcpy.listings.getRecentTasks(forceRefresh = true) }
                    .onFailure { Log.w("FullscreenControlPage", "prefetch recentTasks failed", it) }
            }
            if (scrcpy.listings.apps.isEmpty()) {
                runCatching { scrcpy.listings.getApps(forceRefresh = true) }
                    .onFailure { Log.w("FullscreenControlPage", "prefetch apps failed", it) }
            }
        }
    }

    val asBundleShared by appSettings.bundleState.collectAsState()
    val asBundleSharedLatest by rememberUpdatedState(asBundleShared)
    var asBundle by rememberSaveable(asBundleShared) { mutableStateOf(asBundleShared) }
    val asBundleLatest by rememberUpdatedState(asBundle)
    LaunchedEffect(asBundleShared) {
        if (asBundle != asBundleShared) {
            asBundle = asBundleShared
        }
    }
    LaunchedEffect(asBundle) {
        delay(Settings.BUNDLE_SAVE_DELAY)
        if (asBundle != asBundleSharedLatest) {
            appSettings.saveBundle(asBundle)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            taskScope.launch {
                appSettings.saveBundle(asBundleLatest)
            }
        }
    }

    val buttonItems = remember(asBundle.virtualButtonsLayout) {
        VirtualButtonActions.parseStoredLayout(asBundle.virtualButtonsLayout)
    }
    // 停靠栏: 按用户排序切分成"栏上按钮 / 更多菜单"
    val virtualButtonLayout = remember(buttonItems) {
        VirtualButtonActions.splitLayout(buttonItems)
    }
    // 悬浮球: 与停靠栏同一份用户排序, 但动作合并回一条列表并去掉"更多"这个开关本身
    val floatingActions = remember(buttonItems) {
        VirtualButtonActions.mergedOrder(
            items = buttonItems,
            excluded = setOf(VirtualButtonAction.MORE),
        )
    }
    val fullscreenDebugInfo = asBundle.fullscreenDebugInfo
    val showFullscreenVirtualButtons = asBundle.showFullscreenVirtualButtons
    val fullscreenVirtualButtonHeight = asBundle.fullscreenVirtualButtonHeightDp.dp
    val fullscreenVirtualButtonDockSetting = remember(asBundle.fullscreenVirtualButtonDock) {
        AppSettings.FullscreenVirtualButtonDock.fromStoredValue(
            asBundle.fullscreenVirtualButtonDock,
        )
    }
    var displayRotation by remember(activity) {
        mutableIntStateOf(activity?.display?.rotation ?: Surface.ROTATION_0)
    }
    DisposableEffect(activity, context) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (displayManager == null || activity == null) {
            onDispose {}
        } else {
            val listener = object: DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit

                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == activity.display?.displayId) {
                        displayRotation = activity.display?.rotation ?: Surface.ROTATION_0
                    }
                }
            }
            displayRotation = activity.display?.rotation ?: Surface.ROTATION_0
            displayManager.registerDisplayListener(listener, null)
            onDispose {
                displayManager.unregisterDisplayListener(listener)
            }
        }
    }
    val fullscreenVirtualButtonPhysicalDock = remember(fullscreenVirtualButtonDockSetting) {
        when (fullscreenVirtualButtonDockSetting) {
            AppSettings.FullscreenVirtualButtonDock.FOLLOW_TOP,
            AppSettings.FullscreenVirtualButtonDock.FOLLOW_BOTTOM,
            AppSettings.FullscreenVirtualButtonDock.FOLLOW_LEFT,
            AppSettings.FullscreenVirtualButtonDock.FOLLOW_RIGHT,
                -> null

            AppSettings.FullscreenVirtualButtonDock.FIXED_TOP -> VirtualButtonBar.FullscreenDock.TOP
            AppSettings.FullscreenVirtualButtonDock.FIXED_BOTTOM -> VirtualButtonBar.FullscreenDock.BOTTOM
            AppSettings.FullscreenVirtualButtonDock.FIXED_LEFT -> VirtualButtonBar.FullscreenDock.LEFT
            AppSettings.FullscreenVirtualButtonDock.FIXED_RIGHT -> VirtualButtonBar.FullscreenDock.RIGHT
        }
    }
    val fullscreenVirtualButtonDock = remember(
        fullscreenVirtualButtonDockSetting,
        displayRotation,
    ) {
        when (fullscreenVirtualButtonDockSetting) {
            AppSettings.FullscreenVirtualButtonDock.FOLLOW_TOP -> VirtualButtonBar.FullscreenDock.TOP
            AppSettings.FullscreenVirtualButtonDock.FOLLOW_BOTTOM -> VirtualButtonBar.FullscreenDock.BOTTOM
            AppSettings.FullscreenVirtualButtonDock.FOLLOW_LEFT -> VirtualButtonBar.FullscreenDock.LEFT
            AppSettings.FullscreenVirtualButtonDock.FOLLOW_RIGHT -> VirtualButtonBar.FullscreenDock.RIGHT
            AppSettings.FullscreenVirtualButtonDock.FIXED_TOP -> dockForFixedPhysicalEdge(
                physicalDock = VirtualButtonBar.FullscreenDock.TOP,
                displayRotation = displayRotation,
            )

            AppSettings.FullscreenVirtualButtonDock.FIXED_BOTTOM -> dockForFixedPhysicalEdge(
                physicalDock = VirtualButtonBar.FullscreenDock.BOTTOM,
                displayRotation = displayRotation,
            )

            AppSettings.FullscreenVirtualButtonDock.FIXED_LEFT -> dockForFixedPhysicalEdge(
                physicalDock = VirtualButtonBar.FullscreenDock.LEFT,
                displayRotation = displayRotation,
            )

            AppSettings.FullscreenVirtualButtonDock.FIXED_RIGHT -> dockForFixedPhysicalEdge(
                physicalDock = VirtualButtonBar.FullscreenDock.RIGHT,
                displayRotation = displayRotation,
            )
        }
    }
    val fullscreenVirtualButtonReverseOrder = remember(
        fullscreenVirtualButtonPhysicalDock,
        displayRotation,
    ) {
        fullscreenVirtualButtonPhysicalDock
            ?.let { physicalDock ->
                isFixedDockOrderReversed(
                    physicalDock = physicalDock,
                    displayRotation = displayRotation,
                )
            }
            ?: false
    }

    val bar = remember(virtualButtonLayout) {
        VirtualButtonBar(
            outside = virtualButtonLayout.first,
            more = virtualButtonLayout.second,
        )
    }
    // 悬浮球与停靠栏是同一个组件的两种画法: 球把全部动作收进菜单, 所以没有"栏上按钮"
    val ballBar = remember(floatingActions) {
        VirtualButtonBar(
            outside = emptyList(),
            more = floatingActions,
        )
    }
    val recentTasks = remember(listingsRefreshVersion) { scrcpy.listings.recentTasks }
    val apps = remember(listingsRefreshVersion) { scrcpy.listings.apps }

    var currentFps by remember { mutableFloatStateOf(0f) }
    var showRecentTasksSheet by rememberSaveable { mutableStateOf(false) }
    var showAllAppsSheet by rememberSaveable { mutableStateOf(false) }
    var imeRequestToken by rememberSaveable { mutableIntStateOf(0) }
    var gammaPanelVisible by rememberSaveable { mutableStateOf(false) }
    var screenScalePanelVisible by rememberSaveable { mutableStateOf(false) }

    // Round14: EasyControl's 2s dismissal, reset after interaction for our slider panels.
    // Observe panels only, never consume events; held fingers pause even without MOVE events.
    var panelTouchesDown by remember { mutableIntStateOf(0) }
    var panelInteraction by remember { mutableIntStateOf(0) }
    LaunchedEffect(gammaPanelVisible, screenScalePanelVisible, panelTouchesDown, panelInteraction) {
        if ((!gammaPanelVisible && !screenScalePanelVisible) || panelTouchesDown > 0) return@LaunchedEffect
        delay(2_000)
        gammaPanelVisible = false
        screenScalePanelVisible = false
    }
    val panelTouchObserver = Modifier.pointerInput(Unit) {
        var previousDown = 0
        try {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitPointerEvent(PointerEventPass.Initial).changes.count { it.pressed }
                    panelTouchesDown += down - previousDown
                    previousDown = down
                    panelInteraction++
                }
            }
        } finally {
            panelTouchesDown -= previousDown
            panelInteraction++
        }
    }

    var vdResolutionPanelVisible by rememberSaveable { mutableStateOf(false) }
    var vdWidthInput by rememberSaveable { mutableStateOf("768") }
    var vdHeightInput by rememberSaveable { mutableStateOf("1024") }

    DisposableEffect(activity) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            val restoreWindow = activity?.window
            if (restoreWindow != null) {
                WindowInsetsControllerCompat(restoreWindow, restoreWindow.decorView).show(
                    WindowInsetsCompat.Type.systemBars(),
                )
                WindowCompat.setDecorFitsSystemWindows(restoreWindow, true)
            }
        }
    }

    LaunchedEffect(currentSession?.width, currentSession?.height) {
        val session = currentSession ?: return@LaunchedEffect
        onVideoSizeChanged(session.width, session.height)
    }

    // 打开 VD 分辨率面板时用当前生效值播种输入框: 否则不清空、直接点「保存」会按初始
    // "768"/"1024" 落库, 把用户已设的尺寸改回默认(用户可见的数据丢失) (P0-5)
    LaunchedEffect(vdResolutionPanelVisible) {
        if (vdResolutionPanelVisible) {
            val size = TransferOptions.normalizeVdSize(asBundle.transferVdSize)
                ?: TransferOptions.DEFAULT_VD_SIZE
            vdWidthInput = size.substringBefore('x')
            vdHeightInput = size.substringAfter('x')
        }
    }

    DisposableEffect(Unit) {
        val listener: (Float) -> Unit = { fps ->
            currentFps = fps
        }
        NativeCoreFacade.addVideoFpsListener(listener)
        onDispose {
            NativeCoreFacade.removeVideoFpsListener(listener)
        }
    }

    suspend fun sendBackOrTurnScreenOn() {
        runCatching {
            withContext(Dispatchers.IO) {
                scrcpy.pressBackOrTurnScreenOn(KeyEvent.ACTION_DOWN)
                scrcpy.pressBackOrTurnScreenOn(KeyEvent.ACTION_UP)
            }
        }.onFailure { e ->
            Log.w("FullscreenControlPage", "send back failed", e)
        }
    }

    BackHandler(enabled = true) {
        if (asBundle.fullscreenControlBackToDevice && currentSession != null)
            taskScope.launch { sendBackOrTurnScreenOn() }
        else onBack()
    }

    suspend fun refreshApps() {
        runCatching {
            withContext(Dispatchers.IO) {
                scrcpy.listings.getApps(forceRefresh = true)
            }
        }.onFailure { error ->
            AppRuntime.snackbar(R.string.fullscreen_failed_app_list)
            Log.w("FullscreenControlPage", "refreshApps failed", error)
        }
    }

    suspend fun refreshRecentTasks() {
        runCatching {
            withContext(Dispatchers.IO) {
                scrcpy.listings.getRecentTasks(forceRefresh = true)
            }
        }.onFailure { error ->
            AppRuntime.snackbar(R.string.fullscreen_failed_tasks)
            Log.w("FullscreenControlPage", "refreshRecentTasks failed", error)
        }
    }

    suspend fun commitImeText(text: String) {
        submitImeText(
            scrcpy = scrcpy,
            text = text,
            keyInjectMode = currentSession?.keyInjectMode ?: ClientOptions.KeyInjectMode.MIXED,
        ) { error, useClipboardPaste ->
            Log.w("FullscreenControlPage", "commitImeText failed", error)
            AppRuntime.snackbar(
                if (useClipboardPaste) R.string.fullscreen_paste_non_ascii
                else R.string.fullscreen_text_input_failed,
            )
        }
    }

    val virtualButtonHostScope = rememberCoroutineScope()

    // 虚拟按钮的宿主动作: 该界面上能把动作落到哪里, 由这里决定
    // 列表状态一律现读, 宿主对象会跨组合存活, 捕获快照会让 isEmpty() 停在首次组合的画面
    val virtualButtonHost = remember(onBack) {
        object: VirtualButtonHost {
            override fun handleExitFullscreen() = onBack()

            override fun handleGamma() {
                gammaPanelVisible = !gammaPanelVisible
            }

            override fun handleRotateScreen() {
                taskScope.launch {
                    runCatching {
                        scrcpy.rotateDevice()
                    }.onSuccess {
                        AppRuntime.snackbar(R.string.fullscreen_rotate_sent)
                    }.onFailure { error ->
                        Log.w("FullscreenControlPage", "rotateDevice failed", error)
                        AppRuntime.snackbar(R.string.fullscreen_rotate_failed)
                    }
                }
            }

            override fun handleScreenScale() {
                screenScalePanelVisible = !screenScalePanelVisible
            }

            override fun handleVdResolution() {
                vdResolutionPanelVisible = !vdResolutionPanelVisible
            }

            override fun handleAppTransfer() {
                if (NativeCoreFacade.isAppTransferSession()) {
                    AppRuntime.snackbar(R.string.fullscreen_transfer_to_mirror_reconnecting)
                    NativeCoreFacade.requestAppTransferReconnect(transferStartApp = null)
                } else {
                    // 一键流转: 不弹选择列表, 流转"最近使用"的应用
                    // (原项目=搬移当前任务; 官方 server 无任务栈能力, 降级为在虚拟显示中冷启动该应用)
                    taskScope.launch {
                        // Always query the device: cachedRecentTasks survives reconnects and
                        // otherwise pins every subsequent transfer to the first chosen app.
                        val packageName = runCatching {
                            scrcpy.listings.getRecentTasks(forceRefresh = true).firstOrNull()?.packageName
                        }.onFailure {
                            Log.w("FullscreenControlPage", "transfer: getRecentTasks failed", it)
                        }.getOrNull()
                        Log.i("FullscreenControlPage", "transfer: freshly selected app=$packageName")
                        if (packageName == null) {
                            AppRuntime.snackbar(R.string.fullscreen_transfer_no_recent_app)
                        } else {
                            AppRuntime.snackbar(R.string.fullscreen_transfer_to_vd_reconnecting)
                            NativeCoreFacade.requestAppTransferReconnect(transferStartApp = packageName)
                        }
                    }
                }
            }

            override fun handleShowRecentTasks() {
                showRecentTasksSheet = true
                if (
                    scrcpy.listings.recentTasks.isEmpty() &&
                    !scrcpy.listings.refreshBusyState.value
                ) {
                    taskScope.launch {
                        refreshApps()
                        refreshRecentTasks()
                    }
                }
            }

            override fun handleShowAllApps() {
                showAllAppsSheet = true
                if (
                    scrcpy.listings.apps.isEmpty() &&
                    !scrcpy.listings.refreshBusyState.value
                ) {
                    taskScope.launch {
                        refreshApps()
                    }
                }
            }

            override fun handleToggleIme() {
                imeRequestToken++
            }

            override fun handlePasteLocalClipboard() {
                taskScope.launch {
                    val session = currentSession ?: return@launch
                    val text = LocalInputService.getClipboardText(activity ?: return@launch)
                        ?.takeIf { it.isNotBlank() }
                    if (text == null) {
                        AppRuntime.snackbar(R.string.fullscreen_clipboard_empty)
                        return@launch
                    }
                    val useLegacyPaste = session.legacyPaste
                    runCatching {
                        withContext(Dispatchers.IO) {
                            if (useLegacyPaste) scrcpy.injectText(text)
                            else scrcpy.setClipboard(text, paste = true)
                        }
                    }.onSuccess { sent ->
                        // P1-6: 控制通道不可用时返回 false ⇒ 给可见失败反馈, 不再静默当成成功
                        if (!sent) {
                            Log.w("FullscreenControl", "pasteLocalClipboard: control channel unavailable")
                            AppRuntime.snackbar(
                                if (useLegacyPaste) R.string.fullscreen_legacy_paste_failed
                                else R.string.fullscreen_clipboard_sync_failed,
                            )
                        }
                    }.onFailure { error ->
                        Log.w("FullscreenControl", "pasteLocalClipboard failed", error)
                        AppRuntime.snackbar(
                            if (useLegacyPaste) R.string.fullscreen_legacy_paste_failed
                            else R.string.fullscreen_clipboard_sync_failed,
                        )
                    }
                }
            }
        }
    }

    fun handleButtonAction(action: VirtualButtonAction) {
        VirtualButtonActions.perform(
            // 主线程作用域: 宿主动作直接落界面状态, 按键下发在回调内部切到 IO
            scope = virtualButtonHostScope,
            action = action,
            host = virtualButtonHost,
            onInjectKeycode = { keycode ->
                runCatching {
                    withContext(Dispatchers.IO) {
                        scrcpy.injectKeycode(0, keycode)
                        scrcpy.injectKeycode(1, keycode)
                    }
                }.onFailure { e ->
                    Log.w(
                        "FullscreenControlPage",
                        "sendKeycode failed for keycode=$keycode",
                        e,
                    )
                }
            },
        )
    }
    suspend fun startApp(packageName: String) =
        runCatching {
            withContext(Dispatchers.IO) {
                scrcpy.startApp(packageName)
            }
        }.onFailure { error ->
            AppRuntime.snackbar(
                R.string.fullscreen_start_app_failed,
                error.message ?: error.javaClass.simpleName,
            )
        }

    // 虚拟按钮级联菜单二级的密码列表; 无法拉起验证时这里只会给出不可点击的提示项
    val passwordPickerEntries = rememberPasswordPickerEntries()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarController.hostState) },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            val session = currentSession ?: return@Box
            FullscreenControlPage(
                scrcpy = scrcpy,
                session = session,
                onDismiss = onBack,
                showDebugInfo = fullscreenDebugInfo,
                currentFps = currentFps,
                gamepadDeviceName = asBundle.gamepadDeviceName.ifBlank { GamepadHid.NAME },
                imeRequestToken = imeRequestToken,
                enableBackHandler = false,
                stretchFill = asBundle.stretchFill,
                onImeCommitText = ::commitImeText,
                onInjectTouch = { action, pointerId, x, y, pressure, actionButton, buttons ->
                    withContext(Dispatchers.IO) {
                        scrcpy.injectTouch(
                            action = action,
                            pointerId = pointerId,
                            x = x,
                            y = y,
                            screenWidth = session.width,
                            screenHeight = session.height,
                            pressure = pressure,
                            actionButton = actionButton,
                            buttons = buttons,
                        )
                    }
                },
                onBackOrScreenOn = { action ->
                    withContext(Dispatchers.IO) { scrcpy.pressBackOrTurnScreenOn(action) }
                },
            )

            if (showFullscreenVirtualButtons) {
                bar.Fullscreen(
                    modifier = Modifier.align(
                        when (fullscreenVirtualButtonDock) {
                            VirtualButtonBar.FullscreenDock.TOP -> Alignment.TopCenter
                            VirtualButtonBar.FullscreenDock.BOTTOM -> Alignment.BottomCenter
                            VirtualButtonBar.FullscreenDock.LEFT -> Alignment.CenterStart
                            VirtualButtonBar.FullscreenDock.RIGHT -> Alignment.CenterEnd
                        },
                    ),
                    dock = fullscreenVirtualButtonDock,
                    reverseOrder = fullscreenVirtualButtonReverseOrder,
                    thickness = fullscreenVirtualButtonHeight,
                    onAction = ::handleButtonAction,
                    passwordChildren = passwordPickerEntries,
                )
            }

            if (asBundle.showFullscreenFloatingButton) {
                ballBar.FloatingBall(
                    modifier = Modifier.fillMaxSize(),
                    onAction = ::handleButtonAction,
                    passwordChildren = passwordPickerEntries,
                )
            }

            AppListBottomSheet(
                show = showRecentTasksSheet,
                title = stringResource(R.string.bottomsheet_recent_tasks),
                loadingText = stringResource(R.string.bottomsheet_loading_tasks),
                emptyText = stringResource(R.string.bottomsheet_no_tasks),
                entries = recentTasks.map { task ->
                    val app = scrcpy.listings.findCachedApp(task.packageName)
                    AppListEntry(
                        key = task.packageName,
                        title = app?.label?.takeIf { it.isNotBlank() } ?: task.packageName,
                        summary = if (app?.label != null) task.packageName else null,
                        system = app?.system,
                        onClick = {
                            showRecentTasksSheet = false
                            taskScope.launch { startApp(task.packageName) }
                        },
                    )
                },
                refreshBusy = listingsRefreshBusy,
                onDismissRequest = { showRecentTasksSheet = false },
                onRefresh = {
                    taskScope.launch(Dispatchers.Main) {
                        refreshApps()
                        refreshRecentTasks()
                    }
                },
            )

            AppListBottomSheet(
                show = showAllAppsSheet,
                title = stringResource(R.string.bottomsheet_all_apps),
                loadingText = stringResource(R.string.bottomsheet_loading_apps),
                emptyText = stringResource(R.string.bottomsheet_no_apps),
                entries = apps.map { app ->
                    AppListEntry(
                        key = app.packageName,
                        title = app.label?.takeIf { it.isNotBlank() } ?: app.packageName,
                        summary = if (app.label != null) app.packageName else null,
                        system = app.system,
                        onClick = {
                            showAllAppsSheet = false
                            taskScope.launch { startApp(app.packageName) }
                        },
                    )
                },
                refreshBusy = listingsRefreshBusy,
                onDismissRequest = { showAllAppsSheet = false },
                onRefresh = {
                    taskScope.launch(Dispatchers.Main) {
                        refreshApps()
                    }
                },
            )

            if (gammaPanelVisible) {
                Column(
                    modifier = Modifier
                        .then(panelTouchObserver)
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 120.dp)
                        .width(320.dp)
                        .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.vb_gamma) +
                            " " + GammaSettings.format(asBundle.gammaValue),
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                    Slider(
                        value = asBundle.gammaValue,
                        onValueChange = { raw ->
                            panelInteraction++
                            val value = GammaSettings.progressToGamma(
                                GammaSettings.gammaToProgress(raw),
                            )
                            asBundle = asBundle.copy(gammaValue = value)
                            NativeCoreFacade.setGamma(value)
                        },
                        valueRange = GammaSettings.MIN_GAMMA..GammaSettings.MAX_GAMMA,
                        steps = GammaSettings.SEEK_MAX - 1,
                    )
                }
            }

            if (screenScalePanelVisible) {
                Column(
                    modifier = Modifier
                        .then(panelTouchObserver)
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 120.dp)
                        .width(320.dp)
                        .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.vb_screen_scale) +
                            " " + VideoScaleSettings.format(asBundle.videoScale),
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                    Slider(
                        value = asBundle.videoScale,
                        enabled = !asBundle.videoScaleLocked,
                        onValueChange = { value ->
                            panelInteraction++
                            asBundle = asBundle.copy(videoScale = value)
                            NativeCoreFacade.setVideoScale(value)
                        },
                        valueRange = VideoScaleSettings.MIN_SCALE..VideoScaleSettings.MAX_SCALE,
                        steps = VideoScaleSettings.SEEK_MAX - 1,
                    )
                    TextButton(
                        enabled = !asBundle.videoScaleLocked,
                        text = stringResource(R.string.fullscreen_scale_reset),
                        onClick = {
                            panelInteraction++
                            asBundle = asBundle.copy(videoScale = VideoScaleSettings.DEFAULT_SCALE)
                            NativeCoreFacade.setVideoScale(VideoScaleSettings.DEFAULT_SCALE)
                        },
                    )
                    // 用户要求: 把设置页的「锁定画面倍率」搬进缩放面板; 同一 asBundle(DataStore) 源,
                    // 点按即时生效且持久。锁定时上方滑块/复位置灰, 本键保留可点以便解锁。
                    TextButton(
                        text = stringResource(
                            if (asBundle.videoScaleLocked) R.string.fullscreen_scale_unlock
                            else R.string.fullscreen_scale_lock,
                        ),
                        onClick = {
                            panelInteraction++
                            asBundle = asBundle.copy(videoScaleLocked = !asBundle.videoScaleLocked)
                        },
                    )
                }
            }

            if (vdResolutionPanelVisible) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 120.dp)
                        .width(320.dp)
                        .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.vd_resolution_title),
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = stringResource(R.string.vd_resolution_current, asBundle.transferVdSize),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                    // 预设: 点一下直接保存
                    listOf(
                        listOf("768x1024", "1024x768"),
                        listOf("800x1280", "1080x1920"),
                    ).forEach { rowPresets ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            rowPresets.forEach { preset ->
                                TextButton(
                                    text = preset,
                                    onClick = {
                                        asBundle = asBundle.copy(transferVdSize = preset)
                                        AppRuntime.snackbar(R.string.vd_resolution_saved)
                                    },
                                )
                            }
                        }
                    }
                    // 自定义宽 x 高
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SuperTextField(
                            value = vdWidthInput,
                            onValueChange = { vdWidthInput = it.filter(Char::isDigit).take(5) },
                            onFocusLost = {},
                            label = stringResource(R.string.vd_resolution_width),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "×",
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        SuperTextField(
                            value = vdHeightInput,
                            onValueChange = { vdHeightInput = it.filter(Char::isDigit).take(5) },
                            onFocusLost = {},
                            label = stringResource(R.string.vd_resolution_height),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TextButton(
                        text = stringResource(R.string.cd_save),
                        onClick = {
                            val size = TransferOptions.normalizeVdSize("${vdWidthInput}x${vdHeightInput}")
                            if (size == null) {
                                AppRuntime.snackbar(R.string.vd_resolution_invalid)
                            } else {
                                asBundle = asBundle.copy(transferVdSize = size)
                                AppRuntime.snackbar(R.string.vd_resolution_saved)
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun dockForFixedPhysicalEdge(
    physicalDock: VirtualButtonBar.FullscreenDock,
    displayRotation: Int,
) = when (displayRotation) {
    Surface.ROTATION_0 -> physicalDock
    Surface.ROTATION_90 -> when (physicalDock) {
        VirtualButtonBar.FullscreenDock.TOP -> VirtualButtonBar.FullscreenDock.LEFT
        VirtualButtonBar.FullscreenDock.BOTTOM -> VirtualButtonBar.FullscreenDock.RIGHT
        VirtualButtonBar.FullscreenDock.LEFT -> VirtualButtonBar.FullscreenDock.BOTTOM
        VirtualButtonBar.FullscreenDock.RIGHT -> VirtualButtonBar.FullscreenDock.TOP
    }

    Surface.ROTATION_180 -> when (physicalDock) {
        VirtualButtonBar.FullscreenDock.TOP -> VirtualButtonBar.FullscreenDock.BOTTOM
        VirtualButtonBar.FullscreenDock.BOTTOM -> VirtualButtonBar.FullscreenDock.TOP
        VirtualButtonBar.FullscreenDock.LEFT -> VirtualButtonBar.FullscreenDock.RIGHT
        VirtualButtonBar.FullscreenDock.RIGHT -> VirtualButtonBar.FullscreenDock.LEFT
    }

    Surface.ROTATION_270 -> when (physicalDock) {
        VirtualButtonBar.FullscreenDock.TOP -> VirtualButtonBar.FullscreenDock.RIGHT
        VirtualButtonBar.FullscreenDock.BOTTOM -> VirtualButtonBar.FullscreenDock.LEFT
        VirtualButtonBar.FullscreenDock.LEFT -> VirtualButtonBar.FullscreenDock.TOP
        VirtualButtonBar.FullscreenDock.RIGHT -> VirtualButtonBar.FullscreenDock.BOTTOM
    }

    else -> physicalDock
}

private enum class DockDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    TOP_TO_BOTTOM,
    BOTTOM_TO_TOP,
}

private fun isFixedDockOrderReversed(
    physicalDock: VirtualButtonBar.FullscreenDock,
    displayRotation: Int,
): Boolean {
    val visualDock = dockForFixedPhysicalEdge(
        physicalDock = physicalDock,
        displayRotation = displayRotation,
    )
    val visualDirection = rotateDockDirection(
        direction = physicalDockBaseDirection(physicalDock),
        displayRotation = displayRotation,
    )
    return when (visualDock) {
        VirtualButtonBar.FullscreenDock.TOP,
        VirtualButtonBar.FullscreenDock.BOTTOM,
            -> visualDirection == DockDirection.RIGHT_TO_LEFT

        VirtualButtonBar.FullscreenDock.LEFT,
        VirtualButtonBar.FullscreenDock.RIGHT,
            -> visualDirection == DockDirection.BOTTOM_TO_TOP
    }
}

private fun physicalDockBaseDirection(
    physicalDock: VirtualButtonBar.FullscreenDock,
) = when (physicalDock) {
    VirtualButtonBar.FullscreenDock.TOP -> DockDirection.RIGHT_TO_LEFT
    VirtualButtonBar.FullscreenDock.BOTTOM -> DockDirection.LEFT_TO_RIGHT
    VirtualButtonBar.FullscreenDock.LEFT -> DockDirection.TOP_TO_BOTTOM
    VirtualButtonBar.FullscreenDock.RIGHT -> DockDirection.BOTTOM_TO_TOP
}

private fun rotateDockDirection(
    direction: DockDirection,
    displayRotation: Int,
) = when (displayRotation) {
    Surface.ROTATION_0 -> direction
    Surface.ROTATION_90 -> when (direction) {
        DockDirection.LEFT_TO_RIGHT -> DockDirection.BOTTOM_TO_TOP
        DockDirection.RIGHT_TO_LEFT -> DockDirection.TOP_TO_BOTTOM
        DockDirection.TOP_TO_BOTTOM -> DockDirection.LEFT_TO_RIGHT
        DockDirection.BOTTOM_TO_TOP -> DockDirection.RIGHT_TO_LEFT
    }

    Surface.ROTATION_180 -> when (direction) {
        DockDirection.LEFT_TO_RIGHT -> DockDirection.RIGHT_TO_LEFT
        DockDirection.RIGHT_TO_LEFT -> DockDirection.LEFT_TO_RIGHT
        DockDirection.TOP_TO_BOTTOM -> DockDirection.BOTTOM_TO_TOP
        DockDirection.BOTTOM_TO_TOP -> DockDirection.TOP_TO_BOTTOM
    }

    Surface.ROTATION_270 -> when (direction) {
        DockDirection.LEFT_TO_RIGHT -> DockDirection.TOP_TO_BOTTOM
        DockDirection.RIGHT_TO_LEFT -> DockDirection.BOTTOM_TO_TOP
        DockDirection.TOP_TO_BOTTOM -> DockDirection.RIGHT_TO_LEFT
        DockDirection.BOTTOM_TO_TOP -> DockDirection.LEFT_TO_RIGHT
    }

    else -> direction
}

@Composable
fun FullscreenControlPage(
    scrcpy: Scrcpy,
    session: Scrcpy.Session.SessionInfo,
    onDismiss: () -> Unit,
    showDebugInfo: Boolean,
    currentFps: Float,
    gamepadDeviceName: String = GamepadHid.NAME,
    imeRequestToken: Int = 0,
    enableBackHandler: Boolean = true,
    interactive: Boolean = true,
    stretchFill: Boolean = false,
    onImeCommitText: suspend (String) -> Unit,
    onInjectTouch: suspend (
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        pressure: Float,
        actionButton: Int,
        buttons: Int,
    ) -> Unit,
    onBackOrScreenOn: suspend (action: Int) -> Unit,
) {
    BackHandler(enabled = enableBackHandler, onBack = onDismiss)

    val coroutineScope = rememberCoroutineScope()

    var touchAreaSize by remember { mutableStateOf(IntSize.Zero) }

    val activePointerIds = remember { linkedSetOf<Int>() }
    val activePointerPositions = remember { linkedMapOf<Int, Offset>() }
    val activePointerDevicePositions = remember { linkedMapOf<Int, Pair<Int, Int>>() }
    val pointerLabels = remember { linkedMapOf<Int, Int>() }

    var nextPointerLabel by remember { mutableIntStateOf(1) }
    var activeTouchCount by remember { mutableIntStateOf(0) }
    var activeTouchDebug by remember { mutableStateOf("") }
    var gamepadDebug by remember { mutableStateOf("") }

    val touchEventHandler = remember(session, touchAreaSize, stretchFill) {
        TouchEventHandler(
            coroutineScope = coroutineScope,
            session = session,
            touchAreaSize = touchAreaSize,
            stretchFill = stretchFill,
            activePointerIds = activePointerIds,
            activePointerPositions = activePointerPositions,
            activePointerDevicePositions = activePointerDevicePositions,
            pointerLabels = pointerLabels,
            nextPointerLabel = nextPointerLabel,
            mouseHoverEnabled = session.mouseHover,
            onInjectTouch = onInjectTouch,
            onBackOrScreenOn = onBackOrScreenOn,
            onActiveTouchCountChanged = { activeTouchCount = it },
            onActiveTouchDebugChanged = { activeTouchDebug = it },
            onNextPointerLabelChanged = { nextPointerLabel = it },
        )
    }

    val gamepadDeviceNameLatest = rememberUpdatedState(gamepadDeviceName)
    val gamepadHandler = remember(session.gamepadEnabled, scrcpy) {
        if (session.gamepadEnabled) {
            GamepadInputHandler(
                scope = coroutineScope,
                deviceName = { gamepadDeviceNameLatest.value },
                onUhidCreate = { id, vendorId, productId, name, reportDesc ->
                    scrcpy.uhidCreate(id, vendorId, productId, name, reportDesc)
                },
                onUhidInput = { id, data -> scrcpy.uhidInput(id, data) },
                onUhidDestroy = { id -> scrcpy.uhidDestroy(id) },
                onDebugChanged = { gamepadDebug = it },
            )
        } else null
    }

    DisposableEffect(gamepadHandler) {
        onDispose {
            gamepadHandler?.destroy()
            gamepadDebug = ""
        }
    }

    val resizeDebouncer = remember {
        Debouncer(300L) { w, h ->
            coroutineScope.launch(Dispatchers.IO) {
                scrcpy.resizeDisplay(w, h)
            }
        }
    }

    DisposableEffect(resizeDebouncer) {
        onDispose {
            resizeDebouncer.cancel()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (interactive)
                    Modifier.pointerInteropFilter { event ->
                        touchEventHandler.handleMotionEvent(event)
                    }
                else Modifier,
            )
            .onSizeChanged { size ->
                touchAreaSize = size
                if (scrcpy.flexDisplay) {
                    val sessionKnown = scrcpy.currentSessionState.value?.let {
                        it.width > 0 && it.height > 0
                    } ?: false
                    if (sessionKnown) {
                        resizeDebouncer.invoke(size.width, size.height)
                    }
                }
            },
    ) {
        val sessionAspect =
            if (session.height == 0) 16f / 9f
            else session.width.toFloat() / session.height.toFloat()

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .then(
                    if (stretchFill)
                        Modifier.fillMaxSize()
                    else if (sessionAspect > (maxWidth.value / maxHeight.value))
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(sessionAspect)
                    else
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(sessionAspect),
                ),
        ) {
            ScrcpyVideoSurface(
                modifier = Modifier
                    .fillMaxSize(),
                primary = true,
                session = session,
                imeRequestToken = imeRequestToken,
                onImeCommitText = onImeCommitText,
                onImeDeleteSurroundingText = { beforeLength, afterLength ->
                    submitImeDeleteSurroundingText(
                        scrcpy = scrcpy,
                        beforeLength = beforeLength,
                        afterLength = afterLength,
                    )
                },
                onImeKeyEvent = { event ->
                    submitImeKeyEvent(
                        scrcpy = scrcpy,
                        event = event,
                        keyInjectMode = session.keyInjectMode,
                        forwardKeyRepeat = session.forwardKeyRepeat,
                    )
                },
                gamepadCaptureEnabled = session.gamepadEnabled,
                onGamepadKeyEvent = gamepadHandler?.let { handler ->
                    { event -> handler.handleKeyEvent(event) }
                },
                onGamepadMotionEvent = gamepadHandler?.let { handler ->
                    { event -> handler.handleGenericMotionEvent(event) }
                },
            )
        }

        if (showDebugInfo) Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = UiSpacing.ContentVertical, top = UiSpacing.ContentVertical)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = UiSpacing.ContentVertical, vertical = UiSpacing.Medium),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.Tiny)) {
                Text(
                    text = stringResource(
                        R.string.fullscreen_debug_resolution,
                        session.width,
                        session.height,
                    ),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        R.string.fullscreen_debug_fps,
                        currentFps.coerceAtLeast(0f),
                    ),
                    color = Color.White,
                    fontSize = 13.sp,
                )
                Text(
                    text = stringResource(
                        R.string.fullscreen_debug_touches,
                        activeTouchCount,
                    ),
                    color = Color.White,
                    fontSize = 13.sp,
                )
                if (activeTouchDebug.isNotEmpty()) Text(
                    text = activeTouchDebug,
                    color = Color.White,
                    fontSize = 13.sp,
                )
                if (gamepadDebug.isNotEmpty()) Text(
                    text = gamepadDebug,
                    color = Color.White,
                    fontSize = 13.sp,
                )
            }
        }
    }

}
