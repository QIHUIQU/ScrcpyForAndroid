# 第16轮：崩溃后自动拉起回远控（AccessibilityService 主机制）

日期：2026-10-14 PDT；设备日志 2026-10-15 CST。分支 `feat/eink-port-20261013`，接手 HEAD `dbf1f06`。
设备：控制端 PAPER_7 `10.0.0.237:5555`；被控端 vivo V2436A `10.0.0.232:7777`（LAN 会话目标，profile `6835e614-78a9-4115-8a04-fb2a08c72c5c`）。

用户本轮明确要求（原话）：「我建议直接增加无障碍功能，原项目也是通过无障碍来自启动的」；「采用 AccessibilityService 作为崩溃后自动拉起回远控的主机制」；「这个功能很简单，不需要参考原项目」；按 Android 官方标准实现。**本轮不翻任何原项目源码**。

官方依据（主源）：Android「Activity security」页（后台启动限制与例外：`https://developer.android.com/guide/components/activities/secure-bal`）与 `AccessibilityService` API 参考/开发者指南（`https://developer.android.com/reference/android/accessibilityservice/AccessibilityService`）。要点：①自 Android 10 起后台启动 Activity 受限，例外之一是「应用被一个已获得后台启动权限的服务绑定」；②无障碍服务的生命周期完全由系统管理，用户在系统设置开启后系统绑定并在进程死亡后重新绑定，`onServiceConnected` 回调在**新进程**里触发。

## 0. 改动前实读恢复计数（定向，不输出其它敏感 prefs）

改动前用 `run-as` 实读控制端 `shared_prefs/crash_recovery.xml`（仅恢复键）：
- `handled=1792043453564` → 设备 13:50:53.564 CST（正是用户原报告窗口的崩溃）。
- `attempts=1792043491538` → 设备 13:51:31.538 CST，**单条**。
- `active=true`、`task_removed=false`、`target_host=10.0.0.232/port=7777/type=LAN`。

结论：10 分钟窗口内**只有 1 次**尝试，**3 次上限并未耗尽**；13:50 复现窗口里没有“上限抑制”。“真正抑制位置”不在计数上限。证据 `findings-20261014-round16/pre-change-crash-recovery-prefs.xml`。

## 1. 粘性 ProcessKeepAliveService 全链、当时是否在运行/重启、am crash 与真实未捕获异常的差异

- manifest：`services.keepalive.ProcessKeepAliveService`，`foregroundServiceType="specialUse"`，`exported=false`；`onStartCommand` 返回 `START_STICKY`。会话启动时由 `NativeCoreFacade.onScrcpySessionStarted` 调 `ProcessKeepAliveService.start`。周期兜底 `KeepAliveJobService`（JobId `0xEC02`，15 min，`setPersisted(true)`）。
- 改动前复现（v15 仍装机时）证明**粘性重启确实会发生，但很晚**：`am crash` → 14:14:56.901 FATAL → 进程/任务由系统与已排定的周期 Job 于 14:16:05 起复生 → 14:16:09.604 `ProcessKeepAlive recovery attempt: reason=4 (1/3)` → 14:16:10.258 `crash recovery: reconnecting 10.0.0.232:7777` → 14:16:13.696 `PB-1 session started`。即**从崩溃到真正回远控约 74 s**。证据 `PRE-CHANGE-v15-crash-logcat.txt`。
- `am crash` 与真实未捕获异常：`am crash` 走 AMS 崩溃上报（日志 `Showing crash dialog for package ...`），默认**弹崩溃对话框**；对话框存在期间进程被挂起而未立即被杀（本轮实测：对话框在时进程存活、原生会话线程继续出帧）。真实未捕获异常同样触发 `FATAL EXCEPTION`/`Force finishing`，随后也是同一套 AMS 处理。差别只在触发方式；对恢复链而言二者都表现为“进程即将/已经死亡 + `ApplicationExitInfo.REASON_CRASH`(=4)”。以 `hide_error_dialogs=1` 强制无对话框后，`am crash` 会真正杀进程并触发 `START_STICKY` 复生（见 §4）。

## 2. 上限、同一死亡去重、成功复位、合理限流

抽出的纯判据（JVM 可测）`CrashRecoveryPolicy.shouldAttempt`：仅当 ①reason ∈ {CRASH, CRASH_NATIVE, ANR, LOW_MEMORY}；②`exitTimestamp > lastHandled`（同一死亡不重复）；③`now - exitTimestamp ∈ [0, 10min]`（陈旧/时钟回拨不触发）；④近 10 分钟尝试数 < 3。任一不满足即不触发。**时间本身永不复位/不重连**；只有「新的、未处理过的异常退出」才恢复。
“成功复位”指：恢复链路走完 `session start succeeded`（只有 `start()` 成功返回才算），随后 `RecoveryIntent` 目标在正常停止/划掉时清除，普通启动不武装。

实测（本轮，最终包）：连续崩溃时系统自身把 `START_STICKY` 复生退避到 **30 分钟**（`Scheduling restart of crashed service ... in 1800000ms`）；本代码在 10 分钟内第 4 次尝试被 `CrashRecovery: recovery suppressed: 3 attempts in 10 minutes` 拦下。**不存在无穷崩溃循环**。证据 `v16-degraded-final.txt`、cap 段。

## 3. crash dialog 存在/点空白 dismiss 的系统后果

- 对话框存在 → 进程被挂起、原生会话线程可能仍在出帧；`START_STICKY` 与周期 Job 的复生时机被显著推迟（本轮实测：有对话框时不弹窗控件/无人工干预下进程约 68 s 才复生，且同类会话退避可到 30 min）。
- 用户「点空白/关闭」使对话框消失 → AMS 终结崩溃进程 → 之后才轮到复生与恢复判定。用户原话现象（“点了空白，对话框消失，app 没自动拉起”）正是**阻断在对话框/复生延迟**这一段的观感。
- 结论：crash dialog 不影响 `START_STICKY`/BAL 的**语义**，但显著影响**时机**；这也是本轮把触发源改到**系统绑定的无障碍服务**（系统复生更及时、且具备后台启动例外）的核心理由。

## 4. BAL、通知权限/渠道/系统抑制、为何无通知；dispatch≠到达，post≠可见

- BAL：官方例外「应用被已获得后台启动权限的服务绑定」。无障碍服务由系统绑定，天然带该例外；普通前台服务的后台 `startActivity` 仍可能被静默拦截。因此恢复入口在无障碍启用时由**无障碍服务上下文发起**。
- 本代码把四阶段**分开记录**，不混淆：`recovery attempt`（判据通过）→ `recovery launch dispatched` / 或 `recovery notification post=.. visible=..` → `recovery MainActivity arrival observed / not observed` → `crash recovery: reconnecting` + `session start succeeded` → 之后才是前台串流。
- 通知渠道复用 `process_keep_alive`（IMPORTANCE_LOW，`ensureChannel` 幂等）；降级通知 id `0xEC03`（=60419）。通知权限：设备实测 `POST_NOTIFICATIONS: granted=true`、`areNotificationsEnabled()=true`，故本轮降级通知 `post=true visible=true` 并实际出现在通知栏（dumpsys 见 `id=60419`）。**无通知权限时**：`visible=false` 会记入日志，且设置页会显示引导；绝不谎称可见。
- 「为何历史无通知」：旧实现只用保活前台服务的常驻通知（`0xEC01`），且不区分 post/可见；本轮新增独立的、带 restore extra 的「点通知回到远控」降级通知。

## 5. 13:51:31.551 带 extras 第二启动的真实调用源

改动前计数与日志对齐后**定因**：`attempts` 唯一时间戳 `13:51:31.538`，与日志 `13:51:31.551 START flg0x14000000 MainActivity(has extras) ... from uid10184` **相差 13 ms**，且 `from uid10184` 为本应用自身 uid。这正是 `ProcessKeepAliveService.tryRecover` 的顺序：先写 `HANDLED_EXIT/ATTEMPTS`（同一 `apply()`），随后 `startActivity(MainActivity + CLEAR_TOP + NEW_TASK + recover extra)`。
即：**该“自发第二启动”就是粘性/兜底触发源发出的恢复派发**（触发源在进程内，故 uid 相同），不是外部广告/安全软件行为。
为什么“手动启动后才走”：用户先在 13:51:27.539 手动拉起（`flg0x10300000`），进程 16451 于 13:51:27.555 起；“粘性/Job 触发”落到同一进程后才于 13:51:31 派发恢复。而本轮同构复现（v15，§1）显示这条链**能**走通，只是**迟到约 74 s**——观感即“没有自动拉起”。注：`flow/进展.md` 曾记为“该窗口无 recovery attempt 日志”，而**持久化计数证明该尝试确实发生**，差异归因于当时抓取的 logcat 未含对应 tag 行/缓冲滚动；以持久化计数为准，且不与用户原话冲突。

## 6. 修法（最小、无新依赖、无新架构）

- 新增 `services/recovery/CrashRecoveryAccessibilityService`（`AccessibilityService`）：manifest 加 `android.permission.BIND_ACCESSIBILITY_SERVICE` 与 `res/xml/accessibility_service_config.xml`；配置仅 `description` + `canRetrieveWindowContent="false"`，**不声明 eventTypes/feedbackType/flags**，`onAccessibilityEvent` 空、`onInterrupt` 空；不读窗口、不取截图、不监听输入或采集数据。`onServiceConnected`（系统在主进程死亡后复生本进程时回调）→ 调用共享恢复入口；`onTaskRemoved` 落 `TASK_REMOVED`（保证“划掉不拉起”对主触发源同样成立）。
- 抽出 `services/keepalive/CrashRecovery`（共享判据 + 单一起拉入口）。保活前台服务 / 周期 Job 改为**同一路径的回退触发源**，不再各自实现。无障碍启用 → 由服务上下文 `startActivity(MainActivity restore intent)`；未启用 → 只发可点通知并记录 `degraded / 未自动回远控`（不谎称成功）。
- MainActivity 既有 `EXTRA_RECOVER_SESSION` 处理与 `DeviceTabViewModel.autoRestoreActiveSessionIfRequested` 复用不改：恢复仍走“既有连接/启动/全屏入口”，不新建第二套导航。
- 设置页新增无障碍状态项：`isEnabled`（系统设置启用）+ `connected`（已连接）分别检测、一键跳 `ACTION_ACCESSIBILITY_SETTINGS`、隐私说明、通知权限缺失引导。**不新增偏好开关**（沿用既有 `crash_recovery_enabled` 与系统授权两者共同门槛）。
- 未动 PB-1（P22）与任何触控/缩放/面板/流转/心跳/IME/密码/解锁语义；未加依赖/定时器/新框架。

### 行为变化（如实登记）
本轮按简报「未启用则通知等真实降级」实现：**无障碍未启用时不再自动拉起**，只发“点通知回到远控”。这会相对 v15（无授权时靠粘性/Job 迟到约 74 s 自动拉起）改变未授权用户的行为。理由与依据：本机制被指定为“主机制”，且要求“未授权=及时通知 + 不得谎称已自动回远控”。若希望保留旧兜底，需要主会话/用户明确追加授权（本执行线未自行扩大）。

## 7. 证据清单（`artifacts/findings-20261014-round16/`）

- `pre-change-crash-recovery-prefs.xml`：改动前恢复计数实读。
- `PRE-CHANGE-v15-crash-logcat.txt`：改动前（v15 装机）崩溃→恢复约 74 s 全链。
- `v16-degraded-final.txt`：v16 未授权降级（attempt 3/3 → degraded → notification post=true visible=true）。
- `v16-restore-logcat.txt`：v16 通知退路目标（同 extra）→ armed → reconnecting → session start succeeded。
- `final-clean1.log` / `final-clean2.log`：两次 `:app:clean :app:assembleDebug :app:testDebugUnitTest`。
- `clean1/` `clean2/`（各 5 APK）、`clean1-xml/` `clean2-xml/`（各 22 套件 JUnit XML）。
- `final-verification.json`：提交/推送/远端读回锚点。
- 范围守卫：`scripts/verify_round16_scope.py`；报告：本文件（仓库内）与 `第16轮崩溃恢复无障碍自动拉起与验证报告.md`（交付目录镜像）。

## 8. 风险与未验证（不假造）

- **无障碍“已授权自动回远控”未测**：需用户本人在系统设置手动开启（应用/操作员不用 adb settings/appops 代授权）。阻塞时不停等，已交付未授权降级路径候选。
- **划掉不拉起 / 无会话崩溃**：判定逻辑由 JVM 单测（`RecoveryPolicyTest`、`RecoveryIntentPolicyTest`）与源码守卫覆盖；本设备无 `task remove` 命令、且不动 UI 无法程序化制造任务划除，真机划除项待用户/后续窗口。IP 级单测不等于端到端。
- 源码守卫/单测**不等于**真机 Compose/无障碍管理器行为；crash dialog 的分期与 AMS 退避时机在不同会话间不一致。
- 无障碍服务由系统加载 XML 配置的实际生效（出现在系统无障碍列表）为只读 `dumpsys` 未见明确条目，需用户开启时最终确认。
