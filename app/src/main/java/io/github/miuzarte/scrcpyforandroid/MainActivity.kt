package io.github.miuzarte.scrcpyforandroid

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.miuzarte.scrcpyforandroid.i18n.LocalizedActivity
import io.github.miuzarte.scrcpyforandroid.pages.MainScreen
import io.github.miuzarte.scrcpyforandroid.password.BiometricGate
import io.github.miuzarte.scrcpyforandroid.password.PasswordRepository
import io.github.miuzarte.scrcpyforandroid.password.hasAuthenticatedOrigin
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.services.AppVisibility
import io.github.miuzarte.scrcpyforandroid.services.AppScreenOn
import io.github.miuzarte.scrcpyforandroid.services.UnlockJumpController
import io.github.miuzarte.scrcpyforandroid.services.keepalive.ProcessKeepAliveScheduler
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import kotlinx.coroutines.runBlocking

// 生物认证需要 FragmentActivity
class MainActivity: LocalizedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyMainOrientationPolicy()

        // no logEvent before context init
        AppRuntime.init(applicationContext)
        AppScreenOn.register(window)
        if (Storage.appSettings.bundleState.value.processKeepAliveEnabled) {
            ProcessKeepAliveScheduler.ensureScheduled(applicationContext)
        }

        runBlocking {
            PasswordRepository.refresh()
            // 认证不可用时, 清除经认证创建的密码
            if (!BiometricGate.canAuthenticate()) {
                PasswordRepository.getAll()
                    .filter { it.createdWithAuth.hasAuthenticatedOrigin && it.cipherText != null }
                    .forEach { PasswordRepository.markInvalid(it.id) }
            }
        }

        // 请求附近设备/局域网 mDNS 发现所需的运行时权限
        requestNearbyDevicePermissions()
        requestNotificationPermissionIfNeeded()

        enableEdgeToEdge()

        setContent {
            MainScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        applyMainOrientationPolicy()

        // 回到前台(解锁/从后台打开)且会话活跃: 直达串流界面.
        // 会话不活跃或串流已在前台时不做任何事, 绝不把用户弹到别处.
        val reentry = AppVisibility.consumeReentry()
        val sessionActive = AppRuntime.scrcpy?.isStarted() == true
        val streamForeground = StreamActivity.isActive()
        val jump = reentry && sessionActive && !streamForeground &&
            Storage.appSettings.bundleState.value.autoJumpOnUnlock
        Log.i(
            UnlockJumpController.TAG,
            "main_resume: reentry=$reentry sessionActive=$sessionActive " +
                "streamForeground=$streamForeground -> ${if (jump) "jump" else "noop"}",
        )
        if (jump) {
            startActivity(StreamActivity.createIntent(this))
        }
    }

    override fun onDestroy() {
        AppScreenOn.unregister(window)
        // Activity 重建 (配置变更) 不能收尾会话, 只有真正退出才释放
        if (isFinishing) AppRuntime.releaseSession()
        super.onDestroy()
    }

    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.w(
                    "MainActivity",
                    "本地网络权限被拒绝，局域网设备发现可能不可用",
                )
            }
        }

    /**
     * 请求 Android 17+ 本地网络访问运行时权限
     * 授权后 NsdManager 才能正常扫描局域网 ADB 设备
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.w("MainActivity", "通知权限被拒绝：保活服务仍运行，但通知不可见")
            }
        }

    /** Android 13+ 通知权限（保活前台服务通知可见性；拒绝不阻断） */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return
        notificationPermissionLauncher.launch(permission)
    }

    private fun requestNearbyDevicePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return

        val permission = Manifest.permission.ACCESS_LOCAL_NETWORK
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return

        localNetworkPermissionLauncher.launch(permission)
    }

    private fun applyMainOrientationPolicy() {
        val aspectRatio = currentDisplayAspectRatio()
        requestedOrientation =
            if (aspectRatio > PHONE_LANDSCAPE_LOCK_ASPECT_RATIO)
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            else
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun currentDisplayAspectRatio(): Float {
        val bounds =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                windowManager.maximumWindowMetrics.bounds
            else resources.displayMetrics.let { metrics ->
                Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            }

        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        return maxOf(width, height).toFloat() / minOf(width, height).toFloat()
    }

    private companion object {
        private const val PHONE_LANDSCAPE_LOCK_ASPECT_RATIO = 16f / 9f
    }
}
