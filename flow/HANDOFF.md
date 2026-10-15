# 接手入口（HANDOFF）— ScrcpyForAndroid × EasyControl E-Ink 移植

> 新窗口/新 Agent 读这一份就能开工。规范版本见 `~/.hermes/standards/PROJECT_STANDARD.md`（《长期项目创建规范》v0.3）。本项目四份核心文档：`flow/VISION.md`、`flow/进展.md`（PROGRESS）、`flow/踩坑记录.md`（PITFALLS）、`flow/CURRENT_STATE.md`。

## 1. 项目用途 / 适用规范 / 永久约束 / 当前范围

- 用途：把 EasyControl 在 e-ink 平板 + 国产手机上的能力移植并修到 ScrcpyForAndroid 远控客户端；控制端 PAPER_7（`10.0.0.237:5555`，USB `T2327BCZ0030`），被控端 vivo V2436A（`10.0.0.232:7777`）。
- 永久约束：只做用户明确授权范围；不顺手删/改；不新增依赖或第二套机制；不读/输任何密码/PIN/密钥、不绕过真安全锁；交付必须真实（未测写未测）。
- 当前范围：**本分支只修 bug**（新功能走新分支 + 独立包名，见 `artifacts/NEXT-PHASE-新分支与独立包名.md`）。

## 2. 当前阶段与最后一次实际动作

- 阶段：**v16.2 候选已交付**；控制端已装 v16.2 `7d08d716…`（`pm path` 回读 base.apk SHA256 一致）。活跃会话崩溃→自动回全屏远控页、连续两次崩溃、无会话崩溃→只主界面不重连、真实上滑划掉→不拉起，**全部真机实测**；未授权仅只读复核并引用历史 P23.1。
- 最终锚点：`flow/round19-final-review.md`（构建源b9dcad7，7d08d716最终包；双clean各125例；最终真机矩阵在final-review/device）。B仅人工广播触发手势能力探针，自动触发闭环尚待设计验证，未经用户确认不落地。C结论仅限已核包；最终补改为主会话自审，未取得独立QA。
- 最后一次实际完成：本轮续「崩溃恢复直达远控画面 + 如实到达信号」——真机定位两条缺陷（①到达信号用 SharedPreferences 跨进程不可见 ⇒ 误报 `not observed` 多发降级通知；②重建的 `:a11y` 进程里 `isEnabled` 误判无障碍未启用 ⇒ 已授权恢复错误降级），分别改**跨进程普通文件** `files/recovery_arrival` 与 **`isEnabled` 三级回退**；补四级日志 `dispatch/Activity到达/会话建立/画面可见`；未新造导航、未改 UnlockJump。源码 + 单测 + 双 clean + 范围守卫 + 真机五场景 + 四件套交付 + `flow/` 留痕。任务 B（无障碍手势关闭弹窗）仅真机探针、**未落地产品**。
- 验证程度：A 全部真机闭环；B 实验成立但按授权边界未落地；系统崩溃弹窗在 v16.2 上仍需一次点按（如实登记）。

## 3. 重要决定与理由（不能重复踩的坑）

- 「支付后黑屏失效」的根因是 PB-1 闩锁只有锁态跳变一条复位通路，**不是 v14 回归**（`git diff` 逐文件已排除）。修法必须跟随**被控端真实显示**、不能用触控/时间猜。
- 黑屏效果以SF物理 `powerMode=Off`验证，结合PB-1事件；server INFO关屏行只在原日志级别允许时辅助；`mWakefulness`/`dumpsys display state`/`screencap` **都不是**判据。
- 已确认Off后的新On复位；首次Off被快速点亮漏采时，五秒命令宽限后仍需真实On才复位，不得永久等Off。Mutex串行采样/决策，未知不复位。
- 用户交互设计不靠猜测：对照参考实现或用户原话。
- **跨进程只信普通文件**：`SharedPreferences` 不支持跨进程（各进程各持缓存）。本项目已两次踩同一个坑——`recovery_gate`（划掉后仍恢复/同次崩溃重复计数）与本轮 `files/recovery_arrival`（已到达仍误报 `not observed`）。**凡"两个回调/两个进程都要看"的状态，一律走普通文件。**
- **无障碍「已启用」判据不要只看进程内管理器列表**：重建出来的 `:a11y` 进程里 `AccessibilityManager.getEnabledAccessibilityServiceList` 可能为空 ⇒ 已授权恢复被误降级；要回退到本进程连接标志与 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`。**只读用户已给授权，绝不代授权。**
- 失败记录/踩坑与原话不覆盖，见 `flow/踩坑记录.md`。

## 4. 文件索引 / 仓库 / 分支

- 仓库：`~/Documents/scrcpy-port`（origin `QIHUIQU/ScrcpyForAndroid`，本轮gh回读isPrivate=false，现为公开fork；未改可见性、不称私有备份；upstream `Miuzarte/ScrcpyForAndroid`），分支 `feat/eink-port-20261013`；基线 `357f3a9`；backup 分支 `pre-review-fixes-20261014` 与标签 `v11-candidate` = `44a03b3`（不动）。
- 交付目录：`~/Documents/scrcpy-port-artifacts/`（候选四件套 + rollback/ + findings-20261014-roundNN/ + 各轮报告）。
- 本轮关键文件：`services/keepalive/CrashRecovery.kt`（共享判据/入口 + 跨进程门槛 `RecoveryGate` + **本轮跨进程到达 `RecoveryArrival`**）、`services/recovery/CrashRecoveryAccessibilityService.kt`（本进程连接标志 + `isEnabled` 三级回退）、`services/recovery/RecoveryWatchdogService.kt`（独立 `:a11y` 看门狗）、`MainActivity.kt`（消费恢复意图 + `RecoveryArrival.markArrived`）、`services/keepalive/ProcessKeepAliveService.kt`（门槛落盘）、`NativeCoreFacade.kt`（会话开始 arm / 结束 disarm）、`pages/DeviceTabViewModel.kt`（四级日志之 `session established` / `fullscreen request dispatched`）、`pages/DeviceTabScreen.kt`（路由请求）+ `pages/StreamScreen.kt`（会话+路由RESUMED+窗口焦点后才报「画面可见」，两入口共用；StreamActivity原字节未改）；守卫 `scripts/verify_round19_scope.py`（当前门；`verify_round16_scope.py` 已转历史快照）；报告 `flow/round19-crash-recovery.md`；探针 `artifacts/findings-20261015-round19/probe/`。
- 设备状态读取：`adb -s 10.0.0.237:5555 …`（控制端）/ `adb -s 10.0.0.232:7777 …`（被控端）。

## 5. 环境条件与完成判据

- 构建：`export JAVA_HOME=$(/usr/libexec/java_home -v 21) ANDROID_HOME=/opt/homebrew/share/android-commandlinetools; ./gradlew :app:clean :app:assembleDebug :app:testDebugUnitTest`（`submodule/miuix` 需要 `ANDROID_HOME`）。
- 单测/守卫：`./gradlew :app:testDebugUnitTest`；`python3 scripts/verify_round19_scope.py`（当前门，承载 round16 全部保护 + 本轮不变量 + 断言探针不在产品内；`verify_round16_scope.py` 与 round15 守卫为历史快照，不再作为当前门）；留痕自检 `python3 ~/.hermes/scripts/project_team.py docs-check --root ~/Documents/scrcpy-port`。
- 已核实：双 clean 五 APK 逐字节一致、**22 套件 125 例** 0 失败/错误/跳过、签名同旧证书 `c8c1b8b5…`、server 字节不变、`verify_round19_scope.py` 通过；装包回读 hash 一致；真机活跃崩溃→自动回全屏页（四级日志齐全 + UI=全屏页 + 被控端 display=3）/连续两次崩溃/无会话崩溃（无动作、`reconnecting` 计数 0）/真实划掉（`gate declined` 不拉起）。
- 未完成/待用户：`验收清单.md` P23 用户自然复验（重点 P23.2 活跃崩溃→自动回全屏画面）；P22/P21/P19 未测项；旧 P17/P18 回归。
- 仍需条件：**无**（设备已可用；被控端解锁需用户本人 PIN/手表信任，操作员不得代输）。

## 6. 下一项具体任务与启动方法

1. PAPER_7 已装 v16.2 最终包 `7d08d716…`，无需重装；候选固定路径仍为 ~/Documents/scrcpy-port-artifacts/ScrcpyForAndroid-EinkPort-candidate.apk。
2. 按 `验收清单.md` **P23**：活跃会话崩溃后应**自动回到全屏远控画面**（四级日志 `recovery launch dispatched` → `recovery launch arrived` → `session established` → `fullscreen route requested` → `FullscreenControlRoute fullscreen control page visible (sessionReady=true routeResumed=true windowFocused=true)`）；日志关键字 `CrashRecovery`、`CrashRecoveryWatchdog`、`recovery `、`crash recovery`、`fullscreen`、`StreamActivity`。
3. 结果带时间/步骤/hash 回对话，由执行会话接手；失败附 `CrashRecovery`/`RecoveryWatchdog`/`MainActivity`/`DeviceTabViewModel`/`FullscreenControlRoute` 片段。

## 7. 继承资料 vs 已重新检查 vs 未验证

- 继承：EasyControl 的行为口径（PB-1 / ControlledLockWake / M08），历史各轮报告与 P 段验收要求。
- 本轮已重新检查：崩溃恢复全链路（`:a11y` 看门狗→`CrashRecovery`→`MainActivity`→`DeviceTabViewModel.restoreActiveSession`→既有全屏入口）在真机逐段核对；旧包 `35c2f08d` 的到达误报在真机复现（`oldbuild-crashpath.txt`）；`isEnabled` 在重建 `:a11y` 进程里误判的现场（`repro3`/`repro` 日志）。
- 未验证：跨 OEM 行为；`:a11y` 被系统回收后重武装的真机时序；会话代际/调度竞态；**系统崩溃弹窗自动关闭未落地**（任务 B 探针成立但按授权边界未产品化，见 `findings-20261015-round19/probe/`）；用户侧「停在设备列表」未独立复现。见 `flow/round19-crash-recovery.md` §7 与 `flow/CURRENT_STATE.md`；PB-1 侧剩余见 `flow/round15-payment-blackout.md` §6。
