# 第19轮：崩溃恢复「直达远控画面」+ 无障碍手势关闭弹窗探针（v16.2）

日期：2026-10-15 PDT（设备时间 CST）。分支 `feat/eink-port-20261013`。接手 HEAD `d385b18`（其上的 `84792a3`/`d385b18` 为已提交的流程/中断登记文档）。
设备：控制端 PAPER_7 `10.0.0.237:5555`（Android 14）；被控端 vivo V2436A `10.0.0.232:7777`（Android 16，序列号 `10AF6D0441003F5`）。
交付根：`/Users/qifam/Documents/scrcpy-port-artifacts`。证据根：`findings-20261015-round19/`。

用户本轮要求（原话要点）：「崩溃后自动拉起**成功**（会话也恢复成功），**但界面停在主界面，没有回到远控画面**」
且 **误报** `recovery MainActivity arrival not observed` 多发了一条降级通知；「**不接受**降级为通知手动跳转，也不接受进主界面手动跳转」；
「停止运行的弹窗应被屏蔽或自动关闭」；授权先真机验证「用现有无障碍服务加手势能力点掉崩溃弹窗」（零新权限），**成立再决定是否改产品**。
任务 A（正式修复）与任务 B（实验）为本人负责；任务 C（原项目包体核实）由另一子代理负责。

---

## 1. 用户报告 + 现场证据（2026-10-15 16:08 CST / PDT 01:08，接手前已采集，勿重复）

```
16:08:52.369  ActivityManager: Killing 24179:… (adj 50): crash
16:08:52.382  CrashRecoveryWatchdog(23745): watchdog onUnbind; evaluating crash recovery   ← 看门狗 13ms 触发 ✓
16:08:52.434  CrashRecovery(23745): recovery attempt 1/3 source=watchdog reason=4
16:08:52.484  ActivityTaskManager: START u0 {… .MainActivity (has extras)} …
16:08:52.485  CrashRecovery(23745): recovery launch dispatched (source=watchdog); awaiting MainActivity arrival
16:08:54.072  MainActivity(24371): recovery launch arrived; restore intent armed          ← 自动拉起成功 ✓
16:08:55.488  W/CrashRecovery(23745): recovery MainActivity arrival not observed; posting notification fallback  ← ⚠️ 误报
16:08:55.499  CrashRecovery(23745): recovery notification post=true visible=true           ← 多余通知
16:09:04.779  Scrcpy(24371): start(): Session started successfully - device=V2436A, …       ← 会话真恢复 ✓
```
前台（用户观察）：`topResumedActivity = …/.MainActivity`；用户观感「停在设备列表」。

## 2. 根因（真机定位，均可复现）

### R1（**确认**）到达误报 = 跨进程用 SharedPreferences
`CrashRecovery.dispatchAutoRelaunch` 在 **`:a11y` 进程** 把 `launch_pending=true` 写进 `SharedPreferences`；
`MainActivity.handleRecoveryIntent` 在 **主进程** 把它清 false；而 **SharedPreferences 不支持跨进程**（各进程各持一份内存缓存），
`:a11y` 3 秒后读到的仍是自己的旧值 ⇒ 明明已到达却判 `not observed` 并多发一条降级通知。
- v16（`35c2f08d…`）真机复现（本次，`device-evidence/oldbuild-crashpath.txt`）：
  `17:24:48.335 MainActivity: recovery launch arrived` → `17:24:49.783 CrashRecovery: recovery MainActivity arrival not observed`。
- 本次修复前用 `am start --ez recover_session true` 直灌也复现（`repro1`/`repro3`）。

**修法**：到达信号改走**跨进程普通文件** `files/recovery_arrival`（与既有 `recovery_gate` 同一原理，任意进程读盘都是最新值）。
格式 `P<dispatchToken>`=已派发未到达、`A<ms>`=已到达。派发前 `markPending(token)`，MainActivity 消费时 `markArrived()`，
延迟检查 `isPending(token)`；会话真结束/划掉 `clear()`。纯判据 `RecoveryArrival.arrivalPending(content, token)` 有 JVM 用例。

### R2（**确认**）重建出来的 `:a11y` 进程里 `isEnabled` 误判为「无障碍未启用」⇒ 错误降级为通知
`CrashRecoveryAccessibilityService.isEnabled` 只看 `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)`。
真机实测该进程内缓存可能为空 ⇒ 已授权的恢复被降级：
```
17:06:29.331 W/CrashRecovery(25540): recovery degraded (source=watchdog): accessibility not enabled; auto relaunch skipped
17:11:15.367 W/CrashRecovery(25540): recovery degraded (source=watchdog): accessibility not enabled; auto relaunch skipped
```
（同一时刻 `settings get secure accessibility_enabled` = 1、`enabled_accessibility_services` 含本服务 ⇒ 纯属进程内缓存问题。
原 `:a11y` 进程 23745 上则返回 true，故用户 16:08 那条 `dispatch` 能出——这就是「时灵时不灵」的来源。）

**修法**：按可信度回退——① 本进程无障碍服务连接标志（`@Volatile serviceConnected`，`onServiceConnected/onUnbind/onDestroy` 维护，
这正是「持有后台启动特权」的同一进程内真值）→ ② 管理器列表 → ③ 用户授权设置权威副本 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`。
只读取用户已给的授权，不代授权。

### R3（**未能独立复现，如实登记**）「会话恢复但不进远控画面」
- 现状：`restoreActiveSession` 走 `startScrcpySession(openFullscreen=true)` → 既有 `fullscreenRequests` → 设备页
  `openFullscreenControl()`（本机 `fullscreen_compatibility_mode=true` ⇒ `rootNavigator.push(RootScreen.FullscreenControl)`）。
- 本机 A/B：**修复前**的 `35c2f08d…` 在「直灌恢复意图」与「崩溃后恢复」两条路径上，会话恢复后**都进到了全屏远控页**
  （界面层证据：`uiautomator` 文本 `分辨率：908x1024 / 帧率 / 触点`，即 `FullscreenControlPage` 调试层）。
- 因此**无法**把用户的「停在设备列表」归因为全屏入口本身的代码缺陷；最可能是异步恢复约 10 秒窗口内的**观感**（用户看到通知与设备列表时，会话尚未建立）。
- 本轮按合同要求**补齐判据与可审计日志**（不改既有导航、不改 UnlockJump）：新增四级日志
  `dispatch`（CrashRecovery）→ `Activity 到达`（MainActivity）→ `会话建立`（DeviceTabViewModel `session established`）→
  `画面可见`（`DeviceTabScreen: fullscreen route requested` + `FullscreenRoute/StreamActivity page visible`）。
  修复后可完整看到链路：`launch dispatched → launch arrived → session established → fullscreen route requested → page composed`。

**结论（诚实口径）**：R1、R2 是**确认并已修**的真机缺陷；「恢复后是否回到远控」在**修复后的候选**上已端到端真机通过
（会话建立且全屏页在前台可见）。未复现到「会话恢复却永不进全屏」的独立缺陷，故不冒称已修一个未复现的 bug；
新增四级日志保证用户下次实测能一眼看出是哪一段没到。

## 3. 任务 A 真机矩阵（修复后候选 `47f122f4…`；均 `am crash <主进程 pid>` + **真实点击对话框外空白**关系统弹窗）

| 场景 | 判据 | 结果 | 证据 |
|---|---|---|---|
| 活跃会话崩溃 → 自动回全屏 | 四级日志齐全 + 全屏页前台可见 | **通过** | `fix-crashpath.txt`：`17:22:21.459 watchdog onUnbind`→`attempt 1/3 source=watchdog`→`17:22:23.201 recovery launch arrived`→**`17:22:24.600 recovery MainActivity arrival observed`**（无降级通知）→`17:22:27.114 Session started`→`17:22:27.559 fullscreen route requested`→`17:22:28.665 page composed`；UI=全屏页；被控端 scrcpy display=3 |
| 连续两次崩溃 | 两次各 1/3、会话各恢复、均回全屏 | **通过** | `fix-two-crashes.txt`：`17:26:36.977` 与 `17:27:18.802` 各 `attempt 1/3`；均 `arrival observed` + `session start succeeded` + `page composed`；UI=全屏页 |
| 无会话崩溃 → 仅主界面不重连 | 不自动拉起；`reconnecting` 计数 0 | **通过** | `fix-nosession-crash2.txt`：崩溃后前台=launcher（未自动拉起）；手动开启落设备列表；`crash recovery: reconnecting` 计数 **0**；被控端 display=0 |
| 最近任务真实上滑划掉 → 不拉起 | `task removed by user`；门槛 `011…`；随后看门狗 `gate declined` | **通过** | `fix-nosession-crash2` 前的划掉：prefs `active=false/task_removed=true`、`recovery_gate=0111792056438794`、`17:28:19.047 task removed by user`；`17:28:19.468 watchdog onUnbind; gate declined (active=false removed=true enabled=true)` |
| 未授权降级 | **只读**复核 + 引用历史 P23.1 | **只读通过 / 不代切授权** | `settings get secure accessibility_enabled=1`、`enabled_accessibility_services=…CrashRecoveryAccessibilityService`、`hide_error_dialogs=null`；未做任何授权变更。历史 P23.1（v16）已真机验证未授权降级为通知 |

- **本次 A 测试的弹窗处理（如实标注）**：v16.2 **不包含**手势关闭弹窗能力 ⇒ 上述 A 矩阵关系统弹窗用的是
  **真实 `input tap` 点框外空白**（=用户当前的手动动作），**不是**无障碍手势成功。弹窗「仍需一次点按」在 v16.2 上**如实记为未解决**；
  可否自动关闭见任务 B（探针证明可行，但**未落地产品**）。
- 全程：未代开无障碍、未 `settings put`、`hide_error_dialogs` 全程 `null`、未输入任何凭据。

## 4. 任务 B（实验，证据在 `findings-20261015-round19/probe/PROBE-README.md`）

- 结论：**成立**。在**现有**无障碍服务上只加 `canPerformGestures="true"` + 探针广播触发 `dispatchGesture` 点弹窗外的空白，
  真机实测弹窗 **t+0.97 s 消失**（预先固定 N=5 s），logcat 有 `accepted=true` / `gesture completed`，应用侧无 `SecurityException`，
  有界面层前后证据（`probe-dialog-before.png` / `probe-dialog-after.png`）。**未用 `adb input tap` 冒充。**
- **不落地产品**：探针改动已从工作树撤回；探针 APK `54f9aa91…` 与源码单独留 `probe/`；v16.2 仍以 A 正式修复交付，
  **产品是否采用自动点按待用户决定**（最小落地面与隐私/设置变更建议见 PROBE-README）。

## 5. 改动面（只碰崩溃恢复接线 + 恢复后导航日志 + 设置无关文件；未新增依赖/第二套机制）

- `services/keepalive/CrashRecovery.kt`：新增 `RecoveryArrival`（跨进程到达文件）；`dispatchAutoRelaunch` 改用它。
- `services/keepalive/ProcessKeepAliveService.kt`：`RecoveryIntent.clear` 调 `RecoveryArrival.clear`；删未用的 `LAUNCH_PENDING`。
- `MainActivity.kt`：消费恢复意图时 `RecoveryArrival.markArrived(this)`。
- `services/recovery/CrashRecoveryAccessibilityService.kt`：`serviceConnected` 进程内标志 + `isEnabled` 三级回退。
- `pages/DeviceTabViewModel.kt`：四级日志（`session established` + `fullscreen request dispatched`）。
- `pages/DeviceTabScreen.kt` / `pages/StreamScreen.kt` / `StreamActivity.kt`：四级日志第 4 段（全屏页可见）。
- `app/src/test/.../RecoveryPolicyTest.kt`：新增到达纯判据用例（JVM）。
- `scripts/verify_round19_scope.py`：本轮范围守卫（承载 round16 全部保护 + 本轮不变量）；`verify_round16_scope.py` 转为历史快照。
- **未动**：PB-1（P22 三源两测试逐字节冻结）、触控/缩放/面板/流转/保活心跳/IME/密码/UnlockJump、1× 原路径与协议、`stash@{0}`。

## 6. 构建与交付

- 双 clean `./gradlew :app:clean :app:assembleDebug :app:testDebugUnitTest` ×2（见 §8 实际结果）。
- 正式候选 **v16.2**：`ScrcpyForAndroid-EinkPort-candidate.apk` + `SHA256SUMS` + `BUILD-INFO.md`；旧四件套原字节移入 `rollback/`。

## 7. 风险与未验证（不假造）

- 用户侧「停在设备列表」未在修复前独立复现（见 R3）；四级日志已补齐以便下次定位。
- v16.2 上系统崩溃弹窗**仍需一次点按**（任务 B 已验证可自动关闭，但未落地）。
- 跨 OEM 行为、`:a11y` 被系统回收后重武装的真机时序、会话代际/调度竞态未测。
- 冲突/未决：无（授权边界按用户原话保守执行；未授权场景只读）。

## 8. 实际结果锚点（构建完成后回填）
