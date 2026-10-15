package io.github.miuzarte.scrcpyforandroid.services.keepalive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 崩溃自愈守卫的非平凡判据 (纯 JVM, 不依赖 Android):
 * 异常退出武装才连 / 普通启动不误连 / 已有会话不重连 / 开关关闭不连 /
 * 划掉不自动拉起 / 无持久目标只进主界面 / 意图单次消费。
 */
class RecoveryIntentPolicyTest {
    private fun restore(
        sessionActive: Boolean = false,
        crashRecoveryEnabled: Boolean = true,
        taskRemoved: Boolean = false,
        targetRestorable: Boolean = true,
    ): Boolean = RecoveryIntentPolicy.consumeIfRestorable(
        sessionActive = sessionActive,
        crashRecoveryEnabled = crashRecoveryEnabled,
        taskRemoved = taskRemoved,
        targetRestorable = targetRestorable,
    )

    @Test
    fun autoRestoreGate() {
        // 普通启动 / 意图未武装 ⇒ 不连
        assertFalse("plain launch must not auto-connect", restore())

        // 已有一个活跃会话 ⇒ 无目标可恢复
        RecoveryIntentPolicy.arm()
        assertFalse("active session must not re-restore", restore(sessionActive = true))

        // 崩溃恢复开关关闭 ⇒ 不连
        RecoveryIntentPolicy.arm()
        assertFalse("disabled toggle must not restore", restore(crashRecoveryEnabled = false))

        // 用户主动划掉 ⇒ 不自动拉起
        RecoveryIntentPolicy.arm()
        assertFalse("swiped-away must not auto-pull", restore(taskRemoved = true))

        // 崩溃前无活跃会话 (无持久目标) ⇒ 只进主界面
        RecoveryIntentPolicy.arm()
        assertFalse("no target must not connect", restore(targetRestorable = false))

        // 异常退出恢复: 唯一放行组合
        RecoveryIntentPolicy.arm()
        assertTrue("armed abnormal recovery must restore", restore())

        // 恢复意图单次消费 ⇒ 重复消费不再生效
        assertFalse("intent must be single-shot", restore())
    }
}
