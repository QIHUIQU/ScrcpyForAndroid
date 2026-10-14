package io.github.miuzarte.scrcpyforandroid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import io.github.miuzarte.scrcpyforandroid.i18n.LocalizedActivity
import io.github.miuzarte.scrcpyforandroid.pages.StreamScreen
import io.github.miuzarte.scrcpyforandroid.services.AppScreenOn
import io.github.miuzarte.scrcpyforandroid.services.AppVisibility
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
        // 串流界面已在前台: 任何待消费的"回前台"事件视为已落地,
        // 避免用户随后退出串流时被 MainActivity 的 re-entry 检查回弹
        AppVisibility.consumeReentry()
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
    }
}
