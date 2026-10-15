# 当前回顾（CURRENT_STATE）— ScrcpyForAndroid × EasyControl E-Ink 移植

- 最后更新：2026-10-14 PDT（设备时间 2026-10-15 CST）
- 当前阶段：**v15 候选已交付并已在控制端实测 A/B 通过；等待用户自然使用复验**
- 分支：`feat/eink-port-20261013`；初始 HEAD `8f4bc83`(v14 tip)；本轮修复提交见 `flow/进展.md` 第15轮条目与 `final-verification.json`
- 主要阻碍：无阻塞。唯一需要用户参与的是「自然使用里复验 P22」与旧 P19/P21 未测项；被控端解锁依赖用户自己的 PIN/手表信任，操作员不代输凭据。
- 接手者先读：`flow/进展.md`（顶部第15轮交接 → 第14轮…）→ `flow/踩坑记录.md`（顶部 v15 → 历史）→ `flow/CURRENT_STATE.md`（本文件）→ `flow/VISION.md` → `artifacts/BUILD-INFO.md` + `artifacts/验收清单.md`（P22 为当前入口）→ `artifacts/findings-20261014-round15/final-verification.json`
- 最容易继续的一项：让用户装 `artifacts/ScrcpyForAndroid-EinkPort-candidate.apk`（v15，覆盖安装）并按 `验收清单.md` P22.1 复验「支付后回到控制端仍会自动黑屏」。

## 逐项回应愿景

| 愿景编号 | 目标 | 当前状态 | 事实或证据 | 尚存差距 | 下一步 |
|---|---|---|---|---|---|
| V01 | 平板稳定当控制端：画面不糊、触控不误触 | 部分实现（历史回归均已修，用户已自测关键项） | v11 双指拖动误触/旋转模糊两个回归真机闭环；本次代理复现操作中画面正常（帧率 10~22fps 波动） | e-ink 截图不可靠、FCS 按钮栏位置随场景变，操作前必须 dump | 回归 P1/P17/P18（用户可免 P18.1） |
| V02 | 支付/临时使用后自动黑屏可重复 | **已实现并真机验证（本轮）** | v14 同步骤 `op_blacked_noop`（复现）→ v15 同步骤 `rewake_display_on`→`fire`+`Device display turned off`，两个外部唤醒回合均通过；`findings-20261014-round15/ab-*.log` | 真解锁后的完整本地使用形态、`D>0` 真机、折叠态切换期间行为未测 | 用户按 P22.1 自然复验 |
| V03 | 被控端锁屏时控制端自动唤醒（真安全锁不绕过） | 已实现（历史真机验证） | `ControlledLockWake` 日志链 + 判据 v3（真安全锁拒 dismiss） | 自动解锁「提交→解锁成功」按安全边界不代验 | 用户实测端到端解锁 |
| V04 | 常用动作真机可用 | 大部分已实现 | 旋转（P17 v10）、流转（P9/P10/P16）、虚拟键（P13）、IME（P16.4）、剪贴板/密码（P12/P16.3） | 各点历史未测项仍挂在对应 P 段 | 按各自 P 段复测 |
| V05 | 异常后安全回到远控或明确降级 | 部分实现（v14 引入，真机项未测） | 纯恢复判据 + 双 clean 构建已测；`恢复复用既有连接/启动/全屏入口` 源码判据通过 | 崩溃/划掉/BAL 真机链路未测（P21.6–P21.8） | 按 P21 在约定窗口验 |
| V06 | 长期可接手、每次交付可回退可核实 | 已实现 | `flow/` 四份核心文档（进展/踩坑/VISION/CURRENT_STATE）+ `flow/round15-payment-blackout.md`；候选四件套 + rollback/ 逐字节留档 + final-verification.json | 每轮仍靠人（AI）主动维护 | 每轮收口跑 `project_team.py docs-check --root ~/Documents/scrcpy-port` |

## 本次新增（第15轮）一句话

用户报「支付场景的自动黑屏再次失效」。实测根因**不是 v14 回归**：PB-1 的「一回合只黑一次」闩锁 `blacked` 只有一条复位通路（被控端锁态跳变），用户直接用手机（解锁+点亮、不产生锁态跳变）后闩锁永久卡死。修法 = 把「被控端真实面板电源（SF 物理 `powerMode`）」接进同一状态机与同一采样路径：先确认黑屏生效（全部物理屏 Off），再见面板 On 就清闩锁（`rewake_display_on`），下一次操作可再黑屏；未确认/UNKNOWN 绝不复位。单测先红后绿、双 clean 构建、范围守卫、真机 A/B 全通过；交付 v15。
