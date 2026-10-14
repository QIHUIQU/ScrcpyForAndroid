package io.github.miuzarte.scrcpyforandroid.scrcpy

/**
 * 应用流转（虚拟显示）会话的参数决策。
 *
 * 官方 server v5.0 语义：虚拟显示被移除时，默认连同上面的应用一起销毁
 * （`vd_destroy_content` 默认开启，对应 VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL）。
 * 「再次切换 / 切回镜像」= 停止旧会话 → 旧虚拟显示被移除；若不处理，流转中的应用会被杀
 * （用户观感：闪退回桌面或多任务）。
 *
 * 因此流转会话固定以 `vd_destroy_content=false` 启动：虚拟显示关闭时应用被移到主屏
 * 继续运行，而不是被销毁。
 *
 * 纯函数（无 Android 依赖），供 JVM 单测覆盖。
 */
object TransferOptions {
    /** 未单独配置时的默认虚拟显示尺寸 */
    const val DEFAULT_VD_SIZE = "768x1024"

    /** 宽/高允许范围（守卫 server 侧参数；超出时回落默认值） */
    private val SIZE_RANGE = 100..8192
    private val VD_SIZE_PATTERN = Regex("^(\\d{2,5})\\s*[xX×]\\s*(\\d{2,5})$")

    /** 规范化用户输入（"宽x高"）；非法输入返回 null。 */
    fun normalizeVdSize(input: String?): String? {
        val match = VD_SIZE_PATTERN.matchEntire(input?.trim().orEmpty()) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        if (width !in SIZE_RANGE || height !in SIZE_RANGE) return null
        return "${width}x${height}"
    }

    /**
     * 计算流转 / 切回镜像会话的最终参数。
     *
     * @param currentNewDisplay 当前会话的 new_display（用户显式配置时优先，维持既有语义）
     * @param configuredVdSize 用户手动设置的流转分辨率（全屏快捷面板）；非法值回落默认
     * @param transferStartApp null = 切回镜像；否则 = 要流转到虚拟显示的包名
     */
    fun plan(
        currentNewDisplay: String,
        configuredVdSize: String,
        transferStartApp: String?,
    ): Plan =
        if (transferStartApp != null) {
            Plan(
                newDisplay = currentNewDisplay.ifBlank {
                    normalizeVdSize(configuredVdSize) ?: DEFAULT_VD_SIZE
                },
                startApp = transferStartApp,
                keepVdContent = true,
                transfer = true,
            )
        } else {
            Plan(newDisplay = "", startApp = "", keepVdContent = null, transfer = false)
        }

    /**
     * @property keepVdContent true → 会话必须以 vd_destroy_content=false 启动；
     *   null → 维持 base 取值（切回镜像没有虚拟显示，该项无意义）
     */
    data class Plan(
        val newDisplay: String,
        val startApp: String,
        val keepVdContent: Boolean?,
        val transfer: Boolean,
    )
}
