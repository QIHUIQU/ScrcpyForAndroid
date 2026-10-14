package io.github.miuzarte.scrcpyforandroid.nativecore

/**
 * 显示输出的表面优先级策略（纯逻辑，JVM-testable）。
 *
 * 背景：应用内同时存在两个 ScrcpyVideoSurface——全屏控制页（StreamActivity/MainScreen 的
 * FullscreenControlRoute）与设备卡预览（MainActivity 设备列表）。两者都会在生命周期/重组
 * 事件里调用 NativeCoreFacade.attachVideoSurface。历史实现是"最后 attach 的赢"，在活动切换、
 * 解锁跳转、重连抖动时预览表面会抢走解码输出——画面被渲染到被盖住的主界面里，
 * 全屏表现为"有声音没画面"（真机 2026-10-14 日志：viewport=436x1024 的预览表面成为输出）。
 *
 * 规则：全屏（primary）优先；预览（preview）只在没有可用 primary 时才会成为输出。
 */
object VideoSurfacePolicy {
    /** attach 请求时是否应把输出切到该表面。 */
    fun shouldSwitchOnAttach(requestIsPrimary: Boolean, primaryValid: Boolean): Boolean =
        requestIsPrimary || !primaryValid

    /** detach(primary) 后是否回退到预览表面。 */
    fun shouldFallbackToPreview(detachedWasPrimary: Boolean, previewValid: Boolean): Boolean =
        detachedWasPrimary && previewValid
}
