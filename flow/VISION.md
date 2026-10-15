# 启动愿景 — ScrcpyForAndroid × EasyControl E-Ink 移植（分支 feat/eink-port-20261013）

> 本文件按《长期项目创建规范》v0.2 的最小要求补齐；**只写有真实依据的内容**，用户原话保留来源与日期，AI 整理的说明单独标注。项目此前没有 VISION.md，本文件依据 `flow/进展.md`、`flow/踩坑记录.md`、`~/Documents/scrcpy-port-artifacts/` 里的历史记录（2026-10-13 起）回溯整理，不编造用户未说过的目标。

## 1. 项目名称与建立背景

- 项目：ScrcpyForAndroid × EasyControl E-Ink 移植（仓库 `~/Documents/scrcpy-port`，分支 `feat/eink-port-20261013`，fork 自 `Miuzarte/ScrcpyForAndroid`，私有远端 `QIHUIQU/ScrcpyForAndroid`）。
- 建立日期：2026-10-13（fork + 克隆 + 分支建立，见 `flow/进展.md` 日志）。
- 背景：把 EasyControl（原项目）在 **e-ink 平板 + 国产手机** 这套真实设备上验证过的能力，移植/移植后再修到 ScrcpyForAndroid 客户端上，用作日常远控工具；控制端主要是 PAPER_7 电纸书平板，被控端是 vivo V2436A（折叠屏，Android 16/OriginOS 6）。

## 2. 参考项目（来源与核验程度）

- EasyControl / `Easycontrol_For_Car`（Java，含 server 侧）：作为**行为参照**（PB-1 支付黑屏状态机、ControlledLockWake 判据 v3、M08 熄屏口径等均以它为准）。多处结论来自对它的只读调研（`~/Documents/easycontrol-eink/research/`、`~/Documents/easycontrol-rlcd-b/research/`），并有明确的“只在真机验证过才可信”的口径。
- 上游 `Miuzarte/ScrcpyForAndroid`：基线锁定 `357f3a9`（不跟 renovate 分支）。

## 3. 可区分的愿景（编号）

| 编号 | 愿景 | 观察方式/验收依据 |
|---|---|---|
| V01 | 这台 e-ink 平板能稳定当「远控控制端」用：画面清晰不糊、手势/触控不误触被控端 | 真机观感 + `drop packet`/`replayBootstrap`/`pan start` 等日志；历史 P1/P18 系列 |
| V02 | 被控端「用完后自动黑屏（不锁屏）」在支付/临时使用场景可用且可重复 | PB-1 日志链（`arm/complete/fire/dewake_display_on/op_blacked_noop`）+ 被控端 SF 物理 `powerMode=Off` + server `Device display turned off`；P5/P22 |
| V03 | 被控端锁屏时能从控制端自动唤醒（真安全锁不绕过） | `ControlledLockWake` 日志链 + `dumpsys trust` 判据；P12 |
| V04 | 常用动作（旋转屏幕 / 应用流转 / 虚拟键 / IME / 剪贴板 / 密码自动输入）在真机上真的可用 | 每个特性都有自己的真机判据与历史 P 编号 |
| V05 | 会话异常（崩溃/断连/被划掉）后能安全回到远控或明确降级，不误连、不惊扰 | 恢复日志链 + 前台 dumpsys；P15/P21 |
| V06 | 项目长期可接手：任何新窗口/新 Agent 读文件即可继续；每次交付可回退、可核实 | `flow/` 四份核心文档 + `artifacts/` 候选包四件套 + rollback/ + final-verification.json |

## 4. 永久约束（历轮反复确认，用户明确）

- 只做**用户明确授权**的范围；未授权的删除/优化不得顺手做（第13轮原话教训）。
- 用户交互决策不能被 AI 猜测替代，必须对照参考实现或用户原话。
- 不新增依赖、不新增抽象/第二套机制；改动最小影响面。
- 安全边界：不读取/输入任何密码/PIN/密钥；真安全锁不绕过；自动解锁只走用户已有授权配置。
- 交付必须真实：未测就写未测；不把源码检查/JVM 测试当端到端验证；不虚报测试数。
- 真机操作前后留证据；持续录制日志不清不打断。

## 5. 当前范围与明确暂不开展

- 当前分支只修 bug；新功能（WLAN 直连/BLE/低功耗）在**独立新分支 + 独立包名**后再开（`artifacts/NEXT-PHASE-新分支与独立包名.md`）。
- 暂不开展：MENU 虚拟键语义变更（仅记录）、VD 旋转（设备限制）、e-ink 平板上的长时间手势复测（用户已自行复测缩放，见 `artifacts/USER-NOTE-跳过缩放复测.md`）。

## 6. 愿景修订记录

| 日期 | 变化 | 原因 |
|---|---|---|
| 2026-10-14 | V02 的验收判据从「`dumpsys power` 是否 Awake」修订为「SF 物理面板 `powerMode` + server 日志」 | round10 实测证实 PB-1 黑屏是 SurfaceFlinger 面板级，框架屏态/Awake/screencap 全程不变；第15轮再次确认并写进 `flow/进展.md` 观测口径 |
| 2026-10-14 | 新增 V06 | 用户要求长期项目持久化 + 私有仓库版本备份（《长期项目创建规范》） |
