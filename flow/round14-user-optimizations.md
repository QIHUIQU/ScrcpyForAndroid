# 第14轮 · 用户优化与恢复修复（v14）

## 范围与版本

- 用户授权：0 恢复P1-5 CANCEL、1 缩放面板锁定键、1b 方案C取消面板吞触控并交互后2秒收起、2 崩溃后重连回远控。原话、边界与验证在本报告逐条绑定；P2/Q2/菜单/其它优化均未做。
- 基线a560eeb（v13构建源2e6053a）；分支feat/eink-port-20261013。v14构建源102edb94f8545e6f174a2c2d9d6b53926d6a4ef9；后续仅交付文档提交不改变APK。
- APK：/Users/qifam/Documents/scrcpy-port-artifacts/ScrcpyForAndroid-EinkPort-candidate.apk；SHA256 aacfc2b2b42da5206727b1f83bc80e8a4f3d4ee1a4f1b816654e79ceafc7550a；49,107,281 B。包名/版本号不变。

## 0 P1-5 re-restore（不是新发明）

依据：用户给出的2026-10-14参考实现核对：moonlight-android VideoZoomGestureOverlay.java:102–120/148–185、Game.java:5559–5601不吞面板首点；EasyControl FullActivity.java:182–205与ClientView.java:280–294视频区触摸直通。用户看到的“下一次整段吞”是我们TouchEventHandler.kt基线:78的多指CANCEL未复位panning，而非参考实现的面板设计。用户对交互设计的猜测不能替代对参考实现的核对。

实现：scrcpy/TouchEventHandler.kt:78–87恢复非1×的≥2指CANCEL分支，先以UiMotionActions.CANCEL取消已转发指针，再panning=false、previousCentroid=null、return true；不会进入平移开始分支。1×/mouse原guard在它之前。既有平移开始的pan start: cancel N forwarded pointer(s)日志保留。其它普通CANCEL/UP路径不扩大修改。

提交1232256，标题显式re-restore + 2026-10-14 reference audit。ScaledPanCancelInvariantTest一例在JVM执行，断言CANCEL处理先于平移锁存、CANCEL非UP、两状态先清后return、下一单指可落原路径。这是JVM源码不变量，不是Android MotionEvent行为模拟；真机取消后下一单指下发尚未测。

## 1 缩放面板锁键

用户原话：“设置里的锁定画面缩放倍率应该放到远控界面的画面缩放面板里：缩放面板加一个锁定键”。基线FullscreenControlScreen.kt:688/697只按asBundle.videoScaleLocked禁滑块与复位，SettingsScreen.kt:582–583已有同源开关。

实现：FullscreenControlScreen.kt:734–744增加现有TextButton，锁定/解锁文案随状态变化，点按切换同一asBundle.videoScaleLocked；原DataStore saveBundle路径不变，无新设置键。滑块与复位仍enabled=!videoScaleLocked，锁键本身始终可用。values与values-zh仅各增两串。设置页原开关保留；是否移除由用户决定。

提交9c7a1dd；源码守卫核同源/双禁用/设置入口保留，编译通过。两入口真机互相同步、重进/重启持久化尚未测，不以源码推理冒充实测。

## 1b 方案C：不吞视频触摸 + 两秒自动收起

用户最终指定：2秒，从最近一次交互结束算，持续触控期间不收起，两面板对称；禁止新组件/抽象。删除原FullscreenControlScreen.kt:992–1010/1133–1152的PanelDismissTouchGate及调用/旧注释/四例专属单测。当前FullscreenControlPage:1028–1030直达handleMotionEvent，整段函数与v11逐字节相同。

FullscreenControlScreen.kt:253–282以LaunchedEffect(两面板各自可见状态、panelTouchesDown、panelInteraction)+delay(2_000)处理闲置；panelTouchObserver只挂两面板Column（:665–708），Initial pass读事件、不consume，不挂视频根节点。活动指针增减计数，finally释放CANCEL/移除状态；按住静止或持续拖动都暂停计时，最后抬手再计足2秒。两滑块/复位/锁键四个回调也递增交互计数，涵盖键盘/无障碍非触摸调用；每个面板打开/关闭独立作为effect键，避免OR可见总值不变时不重置。

提交ec871e4；旧verify_round13_scope.py被本轮verify_round14_scope.py取代（66809d2及102edb9补守卫），明确旧P20.3/4/5吞手势判据作废。源码守卫与编译通过，Compose命中/定时精度/持续拖动真机尚未测。

与原EasyControl差异：原barViewTimer显示后sleep2000ms，不因交互重置（按钮网格）；本项目按用户决定增加“交互结束重置”，以适配滑块。没有照搬旧“首点吞”自创行为。

## 2 崩溃自愈回远控

用户原话：“每次崩溃重启APP后，只进入主界面，没有自动回到远控”。根因：基线ProcessKeepAliveService.tryRecover:75–101只有拉MainActivity；目标AppRuntime.currentConnectionTarget只在内存，冷进程scrcpy未启动，UnlockJumpController.tryJump的sessionActive=false，不能自动回全屏。

链路：
1. NativeCoreFacade.kt:307–311会话启动时记录活跃目标host/port/type/profile到既有RecoveryPrefs；:378–385正常stop清除目标并停FGS，内部isRestarting的stop→start保留意图。
2. ProcessKeepAliveService.kt:32–137同一prefs持久目标与单次恢复门；load要求ACTIVE且非TASK_REMOVED，校验port范围、未知type拒绝。tryRecover:168–207仍按异常退出原因/10分钟新鲜期/3次上限判定，发恢复extra拉Main；常驻通知:238–247带相同extra，不新增导航实现。划掉:210–219清目标+禁止恢复。
3. MainActivity.kt:75–88在onCreate/onNewIntent消费extra并武装既有进程内恢复意图；移除extra避免配置重建重复武装。普通启动无extra，不自动连。
4. DeviceTabViewModel.kt:319–325消费恢复请求，:877–962读取目标，通过原connectWithTimeout→handleAdbConnected→startScrcpySession(openFullscreen=true, profileId)→fullscreenRequests→原openStreamActivity/兼容全屏入口。失败记录并留主界面，不虚构start成功；取消向上传播。通知点击遇已有会话只发原全屏请求，不重连，不受autoJumpOnUnlock关闭影响。
5. BAL：拉起返回只记dispatched；MainActivity实际到达另记arrival。3秒内未观察到Main到达只报“possibly BAL; tap foreground notification”，不擅断就是BAL。共享openStreamActivity捕获启动异常记录通知重试，正常返回也只记dispatch，不称已在前台。最终到达须真机dumpsys/前台画面判定。

提交8aa3597 + 102edb9。RecoveryIntentPolicyTest一例纯JVM执行覆盖普通启动/已有会话/关闭/划掉/无目标拒绝、合法恢复放行、重复消费拒绝；共享源码守卫覆盖持久门/目标校验/主界面消费/既有链复用/次数与划掉/BAL日志。没有改UnlockJumpController/解锁原触发规则。

已知降级：USB冷进程缺可复用句柄时记录skip并留主界面（不自动请求USB权限）；本轮目标是当前LAN控制链。临时VD/流转覆盖参数未另序列化，恢复采用目标绑定的已保存scrcpy profile，不承诺恢复旧VD实例/旧app运行状态。网络地址/端口改变时本次恢复失败走既有提示/人工重连。通知权限关闭时通知降级入口也不可保证可见。均不隐藏为已通过。

## 构建、范围与实机证据

- 实际两次./gradlew :app:clean :app:assembleDebug :app:testDebugUnitTest；均22套件108例0失败/错误/跳过，五个APK完整bytes一致。
- 与v13实际XML比对：保留106例，按用户删除PanelDismissTouchGate四例，新增CANCEL源码不变量/恢复门各一例，未跳过其它旧测试。证据testcase-comparison.json。
- apksigner通过，同v13/v12 Debug证书c8c1b8…；server字节仍26cbc9ad…；未改变工具链、版本号/包名/依赖。
- verify_round14_scope.py通过；去掉本轮唯一CANCEL分支后TouchEventHandler整文件= v11，DeviceWidgets整文件= v11；FullscreenControlPage整函数= v11。触控协议、渲染/录像、PB-1、密码库、现行IME、UnlockJump、All-Options被冻结；P2/Q2撤回状态保留，P1-7提速保留。
- 两位实施worker分UI/恢复，主会话复核并补完CANCEL/交互重置与通知退路；受一次性子代理预算限制，未另派独立QA，不冒称独立QA通过。主会话逐链自审+真实构建/单测/守卫完成。
- 一次预检因git diff --check指出UI文件末尾空行而跳过&&后的环境export，随后Gradle报SDK location not found；已修末尾空行，显式export后预检与两次clean均成功。未改构建配置，不把环境失败当代码成功。
- 2026-10-14 18:37 PDT只读回验，设备2026-10-15 09:37:20 CST；控制端pid5247、已装hash仍4979a806…（v12），没有安装/触控/崩溃/断网/重启/清日志/强制job。定向logcat为空，不用空日志证明稳定或新功能成功。
- 所有v14真机交互、锁态持久、CANCEL后下一单指、活跃/无会话崩溃、划掉、BAL/通知点击均未测，入口P21，用户结束当前稳定性测试后择时验证。安装会终止v12当前会话，分包分段记录。

证据：/Users/qifam/Documents/scrcpy-port-artifacts/findings-20261014-round14/（clean1/2日志/APK/XML、build-verification.json、testcase-comparison.json、scope-verification.log、source-anchors.json、device-verification.json、apk-signature.txt/apk-manifest.txt）。v13四件套原字节留rollback/。最终推送及docs-check另见final-verification.json。

官方机制核对：Android Developers MotionEvent API与Activity security（secure-bal）页，采集于2026-10-14；CANCEL作废当前手势、BAL允许静默拒绝，因此不以startActivity正常返回当成功。参考移植项目行号依据本轮用户已核实的调研材料，不另称本会话重新运行过那些子代理。
