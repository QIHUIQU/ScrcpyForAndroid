# 接手入口（HANDOFF）— ScrcpyForAndroid × EasyControl E-Ink 移植

> 新窗口/新 Agent 读这一份就能开工。规范版本见 `~/.hermes/standards/PROJECT_STANDARD.md`（《长期项目创建规范》v0.2）。本项目四份核心文档：`flow/VISION.md`、`flow/进展.md`（PROGRESS）、`flow/踩坑记录.md`（PITFALLS）、`flow/CURRENT_STATE.md`。

## 1. 项目用途 / 适用规范 / 永久约束 / 当前范围

- 用途：把 EasyControl 在 e-ink 平板 + 国产手机上的能力移植并修到 ScrcpyForAndroid 远控客户端；控制端 PAPER_7（`10.0.0.237:5555`，USB `T2327BCZ0030`），被控端 vivo V2436A（`10.0.0.232:7777`）。
- 永久约束：只做用户明确授权范围；不顺手删/改；不新增依赖或第二套机制；不读/输任何密码/PIN/密钥、不绕过真安全锁；交付必须真实（未测写未测）。
- 当前范围：**本分支只修 bug**（新功能走新分支 + 独立包名，见 `artifacts/NEXT-PHASE-新分支与独立包名.md`）。

## 2. 当前阶段与最后一次实际动作

- 阶段：**v15 候选已交付**；控制端已装最终v15 cb87b57f…；旧v14复现、初稿两轮A/B、最终包正常面板复位复验分开记录。
- 最后一次实际完成：本轮「支付后不再自动黑屏」修复 —— 源码 + 单测（先红后绿）+ 双 clean 构建 + 范围守卫 + 真机 A/B + 四件套交付 + `flow/` 留痕。验证程度：设备端 A/B 已闭环（见 §5），用户自然使用复验仍待用户。

## 3. 重要决定与理由（不能重复踩的坑）

- 「支付后黑屏失效」的根因是 PB-1 闩锁只有锁态跳变一条复位通路，**不是 v14 回归**（`git diff` 逐文件已排除）。修法必须跟随**被控端真实显示**、不能用触控/时间猜。
- 黑屏效果以SF物理 `powerMode=Off`验证，结合PB-1事件；server INFO关屏行只在原日志级别允许时辅助；`mWakefulness`/`dumpsys display state`/`screencap` **都不是**判据。
- 已确认Off后的新On复位；首次Off被快速点亮漏采时，五秒命令宽限后仍需真实On才复位，不得永久等Off。Mutex串行采样/决策，未知不复位。
- 用户交互设计不靠猜测：对照参考实现或用户原话。
- 失败记录/踩坑与原话不覆盖，见 `flow/踩坑记录.md`。

## 4. 文件索引 / 仓库 / 分支

- 仓库：`~/Documents/scrcpy-port`（origin `QIHUIQU/ScrcpyForAndroid`，本轮gh回读isPrivate=false，现为公开fork；未改可见性、不称私有备份；upstream `Miuzarte/ScrcpyForAndroid`），分支 `feat/eink-port-20261013`；基线 `357f3a9`；backup 分支 `pre-review-fixes-20261014` 与标签 `v11-candidate` = `44a03b3`（不动）。
- 交付目录：`~/Documents/scrcpy-port-artifacts/`（候选四件套 + rollback/ + findings-20261014-roundNN/ + 各轮报告）。
- 本轮关键文件：`app/src/main/java/io/github/miuzarte/scrcpyforandroid/services/blackout/{PaymentBlackoutStateMachine,ControlledLockReader,PaymentBlackoutCoordinator}.kt`；守卫 `scripts/verify_round15_scope.py`；报告 `flow/round15-payment-blackout.md`。
- 设备状态读取：`adb -s 10.0.0.237:5555 …`（控制端）/ `adb -s 10.0.0.232:7777 …`（被控端）。

## 5. 环境条件与完成判据

- 构建：`export JAVA_HOME=$(/usr/libexec/java_home -v 21) ANDROID_HOME=/opt/homebrew/share/android-commandlinetools; ./gradlew :app:clean :app:assembleDebug :app:testDebugUnitTest`（`submodule/miuix` 需要 `ANDROID_HOME`）。
- 单测/守卫：`./gradlew :app:testDebugUnitTest`；`python3 scripts/verify_round15_scope.py`；留痕自检 `python3 ~/.hermes/scripts/project_team.py docs-check --root ~/Documents/scrcpy-port`。
- 已核实：双 clean 五 APK 逐字节一致、22 套件 120 例 0 失败、签名同旧证书、server 字节不变、守卫通过、装包回读 hash 一致。
- 未完成/待用户：`验收清单.md` P22.1 自然使用复验；P21/P19 未测项；旧 P17/P18 回归。
- 仍需条件：**无**（设备已交回并可用；被控端解锁需用户本人 PIN/手表信任，操作员不得代输）。

## 6. 下一项具体任务与启动方法

1. PAPER_7已装最终包cb87b57f…，无需重装首稿；如另需安装，候选固定路径仍为 ~/Documents/scrcpy-port-artifacts/ScrcpyForAndroid-EinkPort-candidate.apk。
2. 按 `验收清单.md` **P22.1**（重点「本人点亮并普通使用手机后回平板仍会自动黑屏」）复验；日志关键字 `PB-1 `、`Device display turned off`。
3. 结果带时间/步骤/hash 回对话，由执行会话接手；失败附 `dumpsys SurfaceFlinger --displays` 的 powerMode 与 PB-1 事件序列。

## 7. 继承资料 vs 已重新检查 vs 未验证

- 继承：EasyControl 的行为口径（PB-1 / ControlledLockWake / M08），历史各轮报告与 P 段验收要求。
- 本轮已重新检查：v13→v14 改动面（排除回归）、`services/blackout/**` 全部赋值与调用者、被控端 SF 物理面板电源行为、v8/v12 两处旧修是否仍在。
- 未验证：见 `flow/CURRENT_STATE.md` 与 `flow/round15-payment-blackout.md` §6（真解锁后的完整本地使用形态、`D>0` 新路径真机、折叠态切换期间行为、快速唤醒窗、会话代际与关屏采样竞态真机/故障注入）。
