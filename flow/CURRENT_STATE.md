# 当前回顾（CURRENT_STATE）— ScrcpyForAndroid × EasyControl E-Ink 移植

- 最后更新：2026-10-14 PDT（设备时间 2026-10-15 CST）
- 当前阶段：**v16候选`43775698…`已产出并已装控制端；未授权降级+通知退路已真机实测，“已授权自动回远控”待用户手动开启无障碍后验证**
- 分支：`feat/eink-port-20261013`；本轮接手 HEAD `dbf1f06`；本轮修复提交见 `flow/进展.md` 第16轮条目与 `flow/round16-crash-recovery.md`
- 主要阻碍：唯一需要用户参与的是「在系统设置手动开启无障碍后复验崩溃自动回远控」（操作员不代授权）；被控端解锁依赖用户自己的 PIN/手表信任，操作员不代输凭据。
- 交付实际根：/Users/qifam/Documents/scrcpy-port-artifacts/；文中artifacts/为该外部目录简称，不是项目内子目录。
- 接手者先读：`flow/进展.md`（顶部第16轮交接 → 第15轮…）→ `flow/踩坑记录.md`（顶部 v16 → 历史）→ `flow/CURRENT_STATE.md`（本文件）→ `flow/VISION.md` → `flow/round16-crash-recovery.md` → `artifacts/BUILD-INFO.md` + `artifacts/验收清单.md`（P23 为当前入口）
- 最容易继续的一项：控制端已装最终包；请用户按 `验收清单.md` P23 在系统设置开启无障碍后复验崩溃自动回远控。

## 逐项回应愿景

| 愿景编号 | 目标 | 当前状态 | 事实或证据 | 尚存差距 | 下一步 |
|---|---|---|---|---|---|
| V01 | 平板稳定当控制端：画面不糊、触控不误触 | 部分实现（历史回归均已修，用户已自测关键项） | v11 双指拖动误触/旋转模糊两个回归真机闭环；本次代理复现操作中画面正常（帧率 10~22fps 波动） | e-ink 截图不可靠、FCS 按钮栏位置随场景变，操作前必须 dump | 回归 P1/P17/P18（用户可免 P18.1） |
| V02 | 支付/临时使用后自动黑屏可重复 | **修复已实现；面板信号路径真机通过，完整流程待验** | v14复现；初稿v15两轮；最终cb87b57f…设备12:57:15.449面板复位→12:57:18.633 fire→12:57:20.590 SF Off，power仍Awake，final-device-steps.log | 完整本人使用、快速窗、D>0、折叠态、调度故障注入待验 | 用户按 P22.1 自然复验 |
| V03 | 被控端锁屏时控制端自动唤醒（真安全锁不绕过） | 已实现（历史真机验证） | `ControlledLockWake` 日志链 + 判据 v3（真安全锁拒 dismiss） | 自动解锁「提交→解锁成功」按安全边界不代验 | 用户实测端到端解锁 |
| V04 | 常用动作真机可用 | 大部分已实现 | 旋转（P17 v10）、流转（P9/P10/P16）、虚拟键（P13）、IME（P16.4）、剪贴板/密码（P12/P16.3） | 各点历史未测项仍挂在对应 P 段 | 按各自 P 段复测 |
| V05 | 会话异常（崩溃/断连/被划掉）后能安全回到远控或明确降级，不误连、不惊扰 | 部分实现（v16 加入无障碍主机制；未授权降级已真机实测，已授权自动回远控待用户授权后验证） | 共享 `CrashRecovery` 判据 + 单一起拉入口；未授权崩溃实测 `recovery attempt 3/3 → degraded … notification_posted=true`，通知 `0xEC03` 存在、未自动回远控；同 extra 的 restore intent→`session start succeeded` 实测串流；成功恢复后计数清零 | 已授权自动回远控、无会话崩溃、主动划掉为真机待验（P23） | 用户按 P23 复验 |
| V06 | 长期可接手、每次交付可回退可核实 | 已实现 | `flow/` 四份核心文档（进展/踩坑/VISION/CURRENT_STATE）+ `flow/round15-payment-blackout.md`；候选四件套 + rollback/ 逐字节留档 + final-verification.json | 每轮仍靠人（AI）主动维护 | 每轮收口跑 `project_team.py docs-check --root ~/Documents/scrcpy-port` |

## 本次新增（第16轮）一句话

按用户明确要求新增 `AccessibilityService` 作崩溃后自动拉起主机制（官方标准、不翻原项目），并把各触发源收敛到共享 `CrashRecovery` 判据与单一起拉入口；无障碍未启用时降级为可点「点通知回到远控」通知并如实记录，设置页做启用/已连接检测与引导。补恢复成功复位计数。只改崩溃恢复接线 + 设置引导 + manifest/XML/strings，PB-1（P22）与其它交互逐字节冻结。双 clean 各 22 套件 122 例全绿、五包一致，守卫/签名/server/装机回读通过；未授权降级与通知退路真机实测，已授权自动回远控待用户手动开启无障碍后验证。独立只读机制复核以 AOSP 反证「崩溃后 a11y 不保证重绑」，已并入注释与报告，不写成既有事实。完整证据在 /Users/qifam/Documents/scrcpy-port-artifacts/findings-20261014-round16/，交接与未测按 P23。

## 本次新增（第15轮）一句话

最终源8053b79：同一PB-1状态机由真实SF物理面板复位，补齐首次OFF采样前快速点亮的永久卡死窗口（五秒命令宽限后仍需真实ON），采样与决策串行、未知显示保守。只改3源+2测试，双clean各22套件120例全绿、五包一致；签名/server/范围守卫通过。首稿独立QA，最终主会话补改与自审，不宣称第二次独立审查。完整证据在 /Users/qifam/Documents/scrcpy-port-artifacts/findings-20261014-round15/，交接与未测按P22。源码与交付报告推送锚点见final-verification.json。
