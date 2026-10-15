# 第13轮范围收敛与验证（v13候选）

- 本机日期：2026-10-14 PDT；设备只读采集：2026-10-15 CST。基线96730b0，分支feat/eink-port-20261013，构建源2e6053a952b8cc136e86a5253e9fe3406deffacd。
- 只执行用户四项；不新增依赖/定时器/架构，不重构，不改MENU，不安装或驱动真机。历史P19.8/P19.10/P19.12/P19.14按本轮用户决定被替代，P20为当前判据。
- 所有下述路径均相对app/src/main/java/io/github/miuzarte/scrcpyforandroid/；测试路径相对app/src/test/java/io/github/miuzarte/scrcpyforandroid/。

## 1 回退：保留历史，八条独立revert

依据：用户“P2从未授权执行”“Q2是小问题不用改”“P19.8不是bug，沿用原设计”。全部无冲突，无额外修补；主会话逐文件比较revert与原提交的反向增删序列（保留顺序/重复行），八条均完全相等。

| 项 | 原提交 | revert | 恢复位置/验证 |
|---|---|---|---|
| Q2 | 78c19ad | 273241a | TouchEventHandler.kt构造/handlePanGesture、DeviceWidgets.kt:292、FullscreenControlScreen.kt:930：去除panEnabled，全屏/预览恢复原路径 |
| P2-6 | 13c1e0e | cd3f34f | storage/BundleSync.kt，rememberBundleState原实现恢复；文件与44a03b3完全一致 |
| P2-5 | e79f849 | 5a1c847 | services/keepalive/KeepAliveJobService.kt:23-26、ProcessKeepAliveService.kt:49,62：恢复loadBundle；P0-1先init保留，job文件与03eb917完全一致 |
| P2-4 | 8e4ed74 | 099b50a | storage/AppSettings.kt的adbMdnsLanDiscovery、pages/DeviceTabViewModel.kt:1026,1253，两处原读取恢复 |
| P2-3 | 38e95d3 | 549788a | storage/Settings.kt观察/Compose/registry原接口恢复，真实get/set保留；文件与44a03b3一致 |
| P2-2 | 343db6d | c55b426 | services/keepalive/ProcessKeepAliveRules.kt及其测试，shouldSchedule/周期常量恢复；与44a03b3一致 |
| P2-1 | ea63342 | cca7ddd | NativeCoreFacade.kt:95-99，恢复close()；保留同文件其它已授权修复 |
| P1-5 | f70e426 | 13d3319 | scrcpy/TouchEventHandler.kt:73起，移除该CANCEL分支，文件与44a03b3逐字节一致 |

恢复纯属收敛授权，不把删除/优化再次混进其它修复。重复主线程读盘随P2-5恢复；这是用户要求的原状态，不另行优化。v11平移语义完整保留；显式面板收起不能再依赖残留panning。此前P1-5的边角行为不再列为必修缺陷。

## 2 P1-7 即时复探（71a15ca）

依据：用户“连续3次约多6秒，太长了”。位置services/DeviceAdbBackgroundRunner.kt:36-49：首次失败在同一轮立即第二次keepAliveCheck，期间没有delay(intervalMs)；成功则保持连接，第二次失败才到既有shouldAutoReconnect/reconnect。删除threshold/counter，不新增定时器或抽象。

超时链：pages/DeviceTabViewModel.kt:37-38,1210-1212（间隔3000ms、探测1500ms）→services/DeviceAdbAutoReconnectManager.kt:47-51→ConnectionController.kt:102-105→DeviceAdbConnectionCoordinator.kt:142-148的withTimeoutOrNull。额外等待预算是一次1500ms探测，不再两次3秒间隔；这是配置/源码预算，不冒充真机测得的断线时延。

验证：services/DeviceAdbBackgroundRunnerTest.kt五例实际执行：正常不判死、首次失败复探成功、连续两次失败进入判死决策、300ms周期内复探间隙<150ms、周期等待可取消。真实断网未测；JVM使用既有测试方式断言判死决策，不执行依赖Dispatchers.Main的重连回调。原探测runCatching的取消处理未改，不趁机扩大修复范围。

## 3 缩放/gamma面板显式收起（83a2b88）

依据：用户“第一次单击收起缩放滑块面板，这种好的设计包括伽马值调节滑块”。位置pages/FullscreenControlScreen.kt:248-249,535-539：以gammaPanelVisible || screenScalePanelVisible为唯一触发状态，回调同时关闭两面板，不关VD面板。:992-1010视频区pointerInteropFilter先过门，再调用原handleMotionEvent；:1133-1151单一swallowing状态保留整段手势直到UP/CANCEL。

首次视频区ACTION_DOWN收起面板且不转发；MOVE/追加指针/UP/CANCEL一并吞掉，避免孤立UP。后一手势在无面板时走原触控路径。面板Slider/TextButton为较晚绘制的兄弟视图，门只接视频区事件；滑块仍可操作的结论目前来自源码路径而非真机命中验证。

验证：pages/PanelDismissTouchGateTest.kt四例实际执行，覆盖无面板透明、整段消费含追加指针、CANCEL结束、下一次普通点击不消费。1×无面板无新增触控业务；TouchEventHandler.kt和DeviceWidgets.kt整体与v11逐字节相等，触摸/滚动序列化未改。没有为了测试主动驱动用户设备；两个面板的“关闭且被控端无点击→再点正常”和实际滑块命中留P20.3/P20.4。

日志：关闭时新增FullscreenControlPage的`video-area touch dismissed scale/gamma panel; gesture swallowed`；应与被控端无动作及无该手势FullscreenTouch转发日志共同核对，只有关闭日志不够证明通过。

## 4 菜单只回答，不改代码

widgets/VirtualButtons.kt:102-106定义MENU，behavior=INJECT_KEYCODE，keycode=UiAndroidKeycodes.MENU；constants/UiAndroidKeycodes.kt:11为82。不是本客户端“更多”，而是被控端旧Android菜单键。现代应用多使用应用内动作栏/三点菜单；前台应用不处理该键就没有可见效果，并非所有Android10+都绝对失效。旧应用/自行处理MENU的场景仍可有用。本轮保留原样，去留放进进展“待用户决定”；未为验证而注入键码。官方核对：Android KeyEvent API的KEYCODE_MENU常量及Android Developers Blog的Say Goodbye to the Menu Button。

## 构建/范围/交付证据

- 源码守卫提交2e6053a：scripts/verify_round13_scope.py（替代v12脚本；旧脚本可在754a06d历史版本查阅）。保留录像、PB-1、UnlockJump、密码库、现行IME、All-Options及触控/滚动协议冻结检查。
- 独立QA只读，无阻断；主会话再验八条ordered patch逆向完全一致、job=P0-1、MENU与v11相同、server字节未变。
- `./gradlew :app:clean :app:assembleDebug :app:testDebugUnitTest`两次；每次21套件110例、0失败/错误/跳过；5个APK完整bytes相同，非仅解压比对。
- universal：49,106,397 B，sha256963ed113687ebf183b1347490cd706a155bb55ecc99fe868b65ff1589ab6b52a；签名V2校验通过，与v12同Debug证书c8c1b8b58c6f1f927293e03a1564c5666398e3442edc009fadb4e1c9cf736126。
- v12 APK/BUILD-INFO/验收清单/SHA256SUMS先留档rollback/；历史包不删除；备份分支及标签不移动。
- 2026-10-14 18:06:46 PDT / 2026-10-15 09:06:45 CST只读回验：控制端pid5247，仍装v12，设备安装包sha2564979a806…全值与已存包一致。没有安装、点击、force-stop、断网、重启、清logcat、强制job或改设置。当前稳定性观察未主动打断。
- 外部证据：/Users/qifam/Documents/scrcpy-port-artifacts/findings-20261014-round13/（双clean日志/XML、build-verification.json、revert-verification.json、scope-verification.log、QA、签名/manifest、device-verification.json）。交付BUILD-INFO及P20逐条注明未测。

## 未测/下一接手

v13尚未装机；面板端到端、滑块命中、真实断线检出及重连实机未测。用户完成v12当前稳定性观察后自行选择切换窗口，再按P20轻量验证；失败附版本哈希、两个点击时间/步骤与相关日志，由本执行会话接手。此前P19其它未测项继续按其原范围，不为本轮擅自造故障。
