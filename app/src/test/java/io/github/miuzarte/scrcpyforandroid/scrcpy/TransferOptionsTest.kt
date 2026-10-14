package io.github.miuzarte.scrcpyforandroid.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流转会话参数决策的契约测试。
 *
 * 回归点：流转会话必须以 vd_destroy_content=false 启动（虚拟显示关闭时应用移到主屏而不是被杀），
 * 且流转尺寸按 「All Options 显式配置 > 用户手动设置 > 默认 768x1024」 的优先级取值。
 */
class TransferOptionsTest {

    @Test
    fun transferSessionKeepsVdContent() {
        val plan = TransferOptions.plan(
            currentNewDisplay = "",
            configuredVdSize = "768x1024",
            transferStartApp = "com.example.app",
        )

        assertTrue(plan.transfer)
        assertEquals("com.example.app", plan.startApp)
        assertEquals("768x1024", plan.newDisplay)
        // 必须以保留内容启动，否则再次切换时应用被销毁（A 回归缺陷）
        assertEquals(true, plan.keepVdContent)
    }

    @Test
    fun transferUsesConfiguredVdSizeWhenOptionBlank() {
        val plan = TransferOptions.plan(
            currentNewDisplay = "",
            configuredVdSize = "800x1280",
            transferStartApp = "com.example.app",
        )

        assertEquals("800x1280", plan.newDisplay)
    }

    @Test
    fun transferKeepsExplicitAllOptionsVdSize() {
        // All Options 里显式配置的 new_display 优先级最高，维持既有语义
        val plan = TransferOptions.plan(
            currentNewDisplay = "1080x1920",
            configuredVdSize = "800x1280",
            transferStartApp = "com.example.app",
        )

        assertEquals("1080x1920", plan.newDisplay)
    }

    @Test
    fun transferFallsBackToDefaultForInvalidConfiguredSize() {
        for (invalid in listOf("", "   ", "garbage", "10x10", "99999x100", "768*1024")) {
            val plan = TransferOptions.plan(
                currentNewDisplay = "",
                configuredVdSize = invalid,
                transferStartApp = "com.example.app",
            )
            assertEquals(
                "非法配置 '$invalid' 应回落默认尺寸",
                TransferOptions.DEFAULT_VD_SIZE,
                plan.newDisplay,
            )
        }
    }

    @Test
    fun mirrorBranchClearsVdAndDoesNotForceFlag() {
        val plan = TransferOptions.plan(
            currentNewDisplay = "768x1024",
            configuredVdSize = "800x1280",
            transferStartApp = null,
        )

        assertFalse(plan.transfer)
        assertEquals("", plan.newDisplay)
        assertEquals("", plan.startApp)
        // 切回镜像没有虚拟显示，不覆盖 vdDestroyContent（维持 base）
        assertNull(plan.keepVdContent)
    }

    @Test
    fun normalizeVdSizeAcceptsCommonFormats() {
        assertEquals("768x1024", TransferOptions.normalizeVdSize("768x1024"))
        assertEquals("768x1024", TransferOptions.normalizeVdSize(" 768 x 1024 "))
        assertEquals("768x1024", TransferOptions.normalizeVdSize("768X1024"))
        assertEquals("768x1024", TransferOptions.normalizeVdSize("768×1024"))
        assertEquals("1024x768", TransferOptions.normalizeVdSize("1024x768"))
    }

    @Test
    fun normalizeVdSizeRejectsBadInput() {
        for (invalid in listOf(null, "", "abc", "768", "768x", "x1024", "10x10", "99999x100", "768.5x1024")) {
            assertNull("'$invalid' 不应通过校验", TransferOptions.normalizeVdSize(invalid))
        }
    }
}
