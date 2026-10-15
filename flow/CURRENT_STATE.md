# 当前回顾（CURRENT_STATE）— ScrcpyForAndroid × EasyControl E-Ink 移植

- 最后更新：2026-10-15 PDT（设备时间 CST）
- 当前阶段：**v16.2 候选 `dac1976f…`（崩溃恢复「直达全屏远控画面」+ 如实到达信号）已产出并已装控制端；活跃会话崩溃→自动回全屏页、连续两次崩溃、无会话崩溃→只主界面不重连、真实上滑划掉→不拉起，全部真机实测；未授权仅只读复核并引用历史 P23.1**
- 分支：`feat/eink-port-20261013`；本轮接手 HEAD `d385b18`；本轮修复见 `flow/round19-crash-recovery.md`
- 主要阻碍：**无真阻塞**。唯一待用户决定项 = 产品是否采纳「无障碍手势自动关闭崩溃弹窗」（任务 B 探针已证可行，改动已撤回，见 `findings-20261015-round19/probe/`）；被控端解锁依赖用户自己的 PIN/手表信任，操作员不代输凭据。
- 交付实际根：/Users/qifam/Documents/scrcpy-port-artifacts/；文中artifacts/为该外部目录简称，不是项目内子目录。
- 接手者先读：`flow/进展.md`（顶部第19轮交接 → 第16轮…）→ `flow/round19-crash-recovery.md` → `flow/踩坑记录.md`（顶部 round19 + 纪律）→ `flow/CURRENT_STATE.md`（本文件）→ `flow/VISION.md` → `artifacts/BUILD-INFO.md` + `artifacts/验收清单.md`（P23 为当前入口）
- 最容易继续的一项：控制端已装 v16.2（`dac1976f…`，回读一致）；请用户按 `验收清单.md` P23 复验「活跃会话崩溃→自动回全屏远控画面」。

## 逐项回应愿景

| 愿景编号 | 目标 | 当前状态 | 事实或证据 | 尚存差距 | 下一步 |
|---|---|---|---|---|---|
| V01 | 平板稳定当控制端：画面不糊、触控不误触 | 部分实现（历史回归均已修，用户已自测关键项） | v11 双指拖动误触/旋转模糊两个回归真机闭环；本次代理复现操作中画面正常（帧率 10~22fps 波动） | e-ink 截图不可靠、FCS 按钮栏位置随场景变，操作前必须 dump | 回归 P1/P17/P18（用户可免 P18.1） |
| V02 | 支付/临时使用后自动黑屏可重复 | **修复已实现；面板信号路径真机通过，完整流程待验** | v14复现；初稿v15两轮；最终cb87b57f…设备12:57:15.449面板复位→12:57:18.633 fire→12:57:20.590 SF Off，power仍Awake，final-device-steps.log | 完整本人使用、快速窗、D>0、折叠态、调度故障注入待验 | 用户按 P22.1 自然复验 |
| V03 | 被控端锁屏时控制端自动唤醒（真安全锁不绕过） | 已实现（历史真机验证） | `ControlledLockWake` 日志链 + 判据 v3（真安全锁拒 dismiss） | 自动解锁「提交→解锁成功」按安全边界不代验 | 用户实测端到端解锁 |
| V04 | 常用动作真机可用 | 大部分已实现 | 旋转（P17 v10）、流转（P9/P10/P16）、虚拟键（P13）、IME（P16.4）、剪贴板/密码（P12/P16.3） | 各点历史未测项仍挂在对应 P 段 | 按各自 P 段复测 |
| V05 | 会话异常（崩溃/断连/被划掉）后能安全回到远控或明确降级，不误连、不惊扰 | **部分实现（v16.2：活跃会话崩溃→自动回全屏远控页真机通过；连续两次/无会话/划掉均实测）** | `:a11y` 看门狗 16 ms 触发 → `recovery attempt 1/3 source=watchdog` → `recovery launch dispatched` → `recovery launch arrived` → `recovery MainActivity arrival observed`（无降级通知）→ `session established` → `fullscreen route requested` → `fullscreen control page composed`，前台=全屏页、被控端 display=3；真实划掉 → `task removed by user` + `gate declined` 不拉起；无会话崩溃无动作、`reconnecting` 计数 0 | 用户侧「停在设备列表」未在修复前独立复现（四级日志已补齐）；系统崩溃弹窗在 v16.2 上仍需一次点按（探针证明可自动关闭但未落地）；跨 OEM/`:a11y` 重武装时序未测 | 用户按 P23 自然复验 |
| V06 | 长期可接手、每次交付可回退可核实 | 已实现 | `flow/` 四份核心文档（进展/踩坑/VISION/CURRENT_STATE）+ `flow/round15-payment-blackout.md`；候选四件套 + rollback/ 逐字节留档 + final-verification.json | 每轮仍靠人（AI）主动维护 | 每轮收口跑 `project_team.py docs-check --root ~/Documents/scrcpy-port` |

## 本次新增（第19轮）一句话

v16.2：崩溃恢复的判据从「MainActivity 到达」升为「会话建立且全屏远控页前台/可见」，并补齐四级可审计日志；同时真机定位并修掉两条缺陷——①到达信号又踩「SharedPreferences 不跨进程」（`:a11y` 写、主进程清 ⇒ 已到达仍误报 `not observed` 多发降级通知），改普通文件 `files/recovery_arrival`；②重建出来的 `:a11y` 进程里 `isEnabled` 误判无障碍未启用导致已授权恢复错误降级，改三级回退（本进程连接标志 → 管理器列表 → `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`，只读不代授权）。未新造导航（复用既有全屏入口）、未改 UnlockJump。真机：活跃崩溃→自动回全屏页、连续两次、无会话、真实划掉全部通过；未授权仅只读复核。任务 B 探针证明无障碍手势可在 <1 s 自动关闭系统崩溃弹窗，但**按授权边界未落地产品**（v16.2 上仍需一次点按）。完整证据在 `findings-20261015-round19/`。

## 本次新增（第16轮续）一句话

只读审计确认「同进程无障碍在崩溃后不会被系统重绑」（AOSP `mCrashedServices`），且粘性服务在崩溃对话框路径被 `allowRestart=false` 掐掉 ⇒ 主进程一死无任何触发源。修法：无障碍服务 + 新增看门狗放**独立进程 `:a11y`**，会话期间主进程绑定它，主进程死亡由 AMS 解绑/销毁触发的 `onUnbind`/`onDestroy` 在仍存活的 `:a11y` 里接手（真机 24 ms，旧链约 74 s），并走同一 `CrashRecovery` 判据与拉起入口；跨进程门槛/去重用普通文件（SharedPreferences 跨进程不可见，真机已实测导致「划掉后仍恢复」与「同次崩溃重复计数」，均已修）。只改崩溃恢复接线 + 设置引导 + manifest/XML/strings，PB-1（P22）与其它交互逐字节冻结。双 clean 各 22 套件 123 例全绿、五包一致，守卫/签名/server/装机回读通过；未授权崩溃、真实点通知、连续两次、真实划掉、无会话崩溃全部真机实测。完整证据在 /Users/qifam/Documents/scrcpy-port-artifacts/findings-20261014-round16/；旧 v16 原字节留同目录 initial-v16/。

## 本次新增（第15轮）一句话

最终源8053b79：同一PB-1状态机由真实SF物理面板复位，补齐首次OFF采样前快速点亮的永久卡死窗口（五秒命令宽限后仍需真实ON），采样与决策串行、未知显示保守。只改3源+2测试，双clean各22套件120例全绿、五包一致；签名/server/范围守卫通过。首稿独立QA，最终主会话补改与自审，不宣称第二次独立审查。完整证据在 /Users/qifam/Documents/scrcpy-port-artifacts/findings-20261014-round15/，交接与未测按P22。源码与交付报告推送锚点见final-verification.json。
