# 第16轮（续）：崩溃后自动拉起回远控 —— 独立 `:a11y` 进程 + 跨进程门槛

> **第19轮（2026-10-15）补充与更正**：本文件对 v16 的描述仍成立（独立 `:a11y`、跨进程 `recovery_gate`、共享入口），
> 但 §4 的「跨进程状态不可见」经验在第19轮**再踩一次**——新增的到达信号 `launch_pending` 又用了 SharedPreferences
> （`:a11y` 写、主进程清）⇒ 误报 `recovery MainActivity arrival not observed` 多发降级通知；已改为普通文件 `files/recovery_arrival`。
> 同时真机发现**重建出来的 `:a11y` 进程里 `isEnabled` 会误判无障碍未启用**（管理器列表为空）导致已授权恢复错误降级，已三级回退。
> 用户「恢复后停在设备列表」在本轮**未能独立复现**（v16 直灌与崩溃路径都能到全屏页）；第19轮补齐四级日志
> `dispatch / Activity 到达 / 会话建立 / 画面可见`。**最终可见判据（会话+路由RESUMED+窗口焦点）、7d08d716候选与最终包真机矩阵以 `flow/round19-final-review.md` 为准；本轮过程见 `flow/round19-crash-recovery.md`**，本文件保留为 v16 历史。

日期：2026-10-14 PDT；设备日志 2026-10-15 CST。分支 `feat/eink-port-20261013`。
设备：控制端 PAPER_7 `10.0.0.237:5555`；被控端 vivo V2436A `10.0.0.232:7777`（LAN，profile `6835e614-78a9-4115-8a04-fb2a08c72c5c`）。

用户本轮要求（原话）：「我建议直接增加无障碍功能，原项目也是通过无障碍来自启动的」；「采用 AccessibilityService
作为崩溃后自动拉起回远控的主机制」；「这个功能很简单，不需要参考原项目」。**不翻任何原项目源码。**

> 本文件是「续完 round16」的权威结论。接手基线 `dbf1f06` 上先产出过一版 v16（`43775698…`），其 APK、
> 构建日志与设备证据原字节留 `findings-20261014-round16/initial-v16/`。本轮在此基础上补齐根因缺口，
> 产出新候选 `35c2f08d…`。**旧版对同进程无障碍的机制描述已被本轮推翻**，以下为新结论。

## 0. 只读审计结论（先查真实调用链，再动手）

1. **触发源调用链穷举**（`git grep`）：`CrashRecovery.tryLaunch` 的调用者只有
   `CrashRecoveryAccessibilityService.onServiceConnected`、`ProcessKeepAliveService.onStartCommand`
   （粘性 FGS 复生 / 周期 job→FGS 落地）。真正的会话重连只由 `DeviceTabViewModel.autoRestoreActiveSessionIfRequested`
   → `RecoveryIntentPolicy.consumeIfRestorable` 一处放行；`MainActivity.handleRecoveryIntent` 负责武装。
   **所有触发源都在主进程里** —— 这是根因所在，不是 BAL。
2. **同进程无障碍在崩溃后不成立（重点复核项）**：AOSP 把「进程已死的无障碍服务」记入 `mCrashedServices`
   （`AccessibilityUserState.serviceDisconnectedLocked`）并在 `updateServicesLocked` 跳过重绑；`mCrashedServices`
   只在用户开关切换、包变更或成功重绑后清除——**没有定时/延迟重绑**。故把 `onServiceConnected` 当崩溃后主触发源
   **不成立**（同进程方案）。BAL 特权也随该进程消失。
3. **旧版文档自相矛盾**：`flow/round16-crash-recovery.md` §6 与 `CrashRecovery` KDoc 仍写「系统在崩溃后复生并回调
   `onServiceConnected`」，而同一轮的 `android-mechanism-review.md` 已给出反证。本轮把实现与文档一起改成**独立进程**，
   不再靠"系统会重绑"的假设。
4. **设备只读状态**：v16 旧包 base.apk SHA256 回读一致；`enabled_accessibility_services=null`、
   `accessibility_enabled=0`（**无障碍未授权**，需用户手动开启，禁止 adb 代授权）；
   `hide_error_dialogs=null`（已复原）；`crash_recovery` prefs 中 `attempts` 已清（成功复位生效）。
5. **可做范围**：机制修复 + 构建/单测/守卫 + 未授权路径全部真机项 + 文档/交付；
   **不可做**：已授权自动回远控的真机验证（需用户本人授权）。

## 1. 根因（分层，逐条与证据对齐）

- **R1 崩溃对话框路径掐掉粘性服务**：点「关闭/空白」→ `CANCEL` → `FORCE_QUIT` →
  `removeProcessLocked(..., allowRestart=false)` → `killServicesLocked(app,false)` → `bringDownServiceLocked`。
  `START_STICKY` 也一起停。真机实测：`ActivityManager: Killing <pid> … (adj 50): crash` 后**没有**粘性 FGS 复生。
- **R2 同进程无障碍不会被重绑**（§0.2）。
- **R3 主进程死亡后无存活触发源**：R1+R2 之后只剩 15 分钟持久化 job；改动前同构复现（v15 装机）恢复链能通但
  **迟到约 74 s**，观感即「没有自动拉起」。用户 13:50:37 崩溃 → 13:51:27 手动点开，正落在这个空窗里。
- **R4 跨进程状态不可见**（本轮新发现，见 §4）：`SharedPreferences` 不支持跨进程；把门槛读放在 `:a11y` 会读到
  旧值，导致**划掉后仍恢复**；同一崩溃会被两个触发源各记一次。

## 2. 修法（最小机制，只动崩溃恢复接线 + 设置引导 + manifest/XML/strings）

- 新增 `services/recovery/RecoveryWatchdogService`，与 `CrashRecoveryAccessibilityService` 一起放进
  **独立进程 `:a11y`**（manifest `android:process=":a11y"`）。
- 会话开始（`NativeCoreFacade.onScrcpySessionStarted`）主进程 `arm()`：`startService` + `bindService`
  看门狗；会话结束 `disarm()`。
- 主进程死亡 ⇒ AMS 解绑并销毁看门狗 ⇒ `:a11y` 进程里的 `onUnbind`/`onDestroy` 被回调 ⇒ 转交共享入口
  `CrashRecovery.tryLaunchInWatchdog`。**事件驱动，无轮询/无线程/无定时器/无新权限。**
  无障碍已启用时 `:a11y` 本身持有后台启动特权（应用被系统服务绑定），`startActivity(MainActivity+recover extra)`
  不被 BAL 拦；未启用则如实降级为可点通知。
- 触发源收敛：看门狗、无障碍服务、粘性 FGS、周期 job 全部走同一 `CrashRecovery` 判据与同一拉起入口。
- **跨进程门槛文件** `files/recovery_gate`（`active|taskRemoved|enabled|handled`）：主进程在
  会话开始/停止/用户划掉时 `publish`；任意进程每次 `read` 都读盘，因此永远是新值。
- 看门狗自带内存去重/额度，**每次会话开始复位**（会话开始 = 上次恢复成功或用户手动启动）。
- 无障碍服务本身不再读 DataStore（跨进程不安全）；`:a11y` 不碰 DataStore。
- 未动 PB-1（P22）、触控/缩放/面板/流转/心跳/IME/密码/解锁；未加依赖、未加定时器。

### 行为变化（如实登记）

- 无障碍未启用时**仍不自动拉起**，只发「点通知回到远控」——与用户本轮「未授权及时通知并如实降级」一致。
- **旧断言作废**：旧版称「同进程 a11y 崩后会被系统重绑」；本轮以独立进程取代，并保留"若系统重连则也走同一入口"
  的快速路径（`onServiceConnected` 仍转交共享入口）。

## 3. 证据（`findings-20261014-round16/`）

| 场景 | 证据 | 结果 |
|---|---|---|
| 活跃会话崩溃（未授权） | `device-matrix-final.txt:4019-4044` | 主进程死后 24 ms：`watchdog onUnbind` → `recovery attempt 1/3 source=watchdog reason=4` → `notification post=true visible=true`；前台 launcher，未自动回远控 |
| 真实点击降级通知 | `device-matrix-final.txt:4676-6885` | 通知栏**真实点按** → `recovery launch arrived` → `crash recovery: reconnecting 10.0.0.232:7777` → `session start succeeded` |
| 连续两次崩溃 | `device-matrix-final.txt:8028-8102` | 第二次也 `recovery attempt 1/3`（会话开始复位额度），`keepalive_service` 侧无重复计数 |
| 真实划掉任务 | `device-t4-t5.txt` | 最近任务真实上滑 → `task removed by user`；`active=false/task_removed=true`；门槛文件 `011…`；随后崩溃只 `gate declined (active=false removed=true enabled=true)`，**不拉起** |
| 无会话崩溃 → 只主页 | `device-t4-t5.txt` | 未启会话时崩溃：无任何恢复动作；手动点开落 MainActivity；`crash recovery: reconnecting` 计数 0 |
| 同次退出跨触发源去重 | `device-matrix-final.txt` | 看门狗记 `handled` 后，粘性 FGS 不再重复处理同一次崩溃（旧版会重复） |
| 构建 | `final-clean1.log` / `final-clean2.log` / `clean1*` / `clean2*` | 两次 clean：22 套件 123 例 0 失败/错误/跳过；五 APK 逐字节一致 |
| 守卫 | `scripts/verify_round16_scope.py` | 通过（含 P22 冻结、独立进程、跨进程门槛、跨触发源去重、共享判据断言） |

设备未改动项：`hide_error_dialogs` 全程 `null`；未用 `settings put`/`appops` 代开无障碍；未输入任何凭据。

## 4. 本轮新发现的两个真机缺陷（已修，记为踩坑）

1. **`Binder.linkToDeath` 会被 GC 静默解除**：先用「主进程交 Binder 令牌 + 看门狗 `linkToDeath`」做死亡通知，
   真机日志 `JavaBinder: BinderProxy is being destroyed but the application did not call unlinkToDeath …`
   —— arming 后约 25 s BinderProxy 被回收，死亡回调再也不来。改用 AMS 解绑/销毁服务的
   `onUnbind`/`onDestroy` 作死亡信号（真机实测可靠，5–24 ms）。
2. **SharedPreferences 跨进程不可见**：门槛读放在 `:a11y` 时读到旧 `active=true`，导致**用户已划掉仍被恢复**；
   同一次崩溃还被看门狗与粘性 FGS 各记一次。改为普通文件 + 共享 `handled`（§2）。

## 5. 风险与未验证（不假造）

- **无障碍「已授权自动回远控」未测**：设备 `accessible_enabled=0`、`enabled_accessibility_services=null`；
  **必须用户在系统设置手动开启**（应用/操作员不代授权）。开启后 `:a11y` 持 BAL 特权并由本进程
  `startActivity` 的路径已实现并源码守卫，但**未真机验证**——真机需用户授权后才能确认 OEM 是否放行。
- 上限/防循环：判据单测覆盖；成功复位为设计行为（会话开始即复位额度），故**额度用于约束「同一会话内无成功启动的
  重复尝试」**，真正的崩溃循环还依赖同次退出跨触发源去重与系统侧崩溃/ANR 保护。此语义与旧版主进程路径一致。
- 未测：跨 OEM 行为、`:a11y` 被系统回收后重武装的真机时序、会话代际与调度竞态。

## 6. 提交与交付锚点

- 源码/守卫/文档提交与 origin 回读锚点见 `findings-20261014-round16/final-verification.json`。
- 交付：`artifacts/ScrcpyForAndroid-EinkPort-candidate.apk`（`35c2f08d…`，49,129,967 B）+ `SHA256SUMS` +
  `BUILD-INFO.md` + `验收清单.md`（P23）；旧 v16 留 `findings-20261014-round16/initial-v16/`；v15 rollback 未动。
