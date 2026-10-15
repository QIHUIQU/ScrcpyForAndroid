package io.github.miuzarte.scrcpyforandroid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import io.github.miuzarte.scrcpyforandroid.i18n.LocalizedActivity
import io.github.miuzarte.scrcpyforandroid.pages.StreamScreen
import io.github.miuzarte.scrcpyforandroid.services.AppScreenOn
import java.lang.ref.WeakReference

// 画中画已整体移除 (2026-10-14): 不再进入 PiP, 也不注册任何 PiP 动作接收器.
// 如需恢复, 见 git 历史中的 feat(remove-pip) 提交前版本.
class StreamActivity: LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentActivityRef = WeakReference(this)
        AppScreenOn.register(window)
        setContent {
            StreamScreen(activity = this)
        }
    }

    override fun onResume() {
        super.onResume()
        // 四级日志第 4 段: 全屏远控页(独立 StreamActivity)已前台可见
        Log.i("StreamActivity", "fullscreen control page visible (stream activity)")
    }

    override fun onDestroy() {
        currentActivityRef?.get()
            ?.takeIf { it === this }
            ?.let { currentActivityRef = null }
        AppScreenOn.unregister(window)
        super.onDestroy()
    }

    companion object {
        private var currentActivityRef: WeakReference<StreamActivity>? = null

        fun createIntent(context: Context): Intent {
            return Intent(context, StreamActivity::class.java)
        }

        fun isActive(): Boolean = currentActivityRef?.get() != null

        /** 串流界面是否处于前台（RESUMED）——"已在远控前台则不跳"的判据。 */
        fun isForeground(): Boolean =
            currentActivityRef?.get()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
    }
}
