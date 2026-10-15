# Round19 执行合同（用户授权简报转录整理）

项目 /Users/qifam/Documents/scrcpy-port，分支 feat/eink-port-20261013，接手 HEAD 84792a3810ed138e24f7742685c98daff73aa327。交付根 /Users/qifam/Documents/scrcpy-port-artifacts。
本轮用户明确执行授权，不要只出方案。PONYTAIL ultra：理解整条调用链、枚举调用方后用已有入口最小根因修复，不造抽象依赖，不省验证。中文报告，长报告落文件。

## 先读与流程
先读 flow/踩坑记录.md 顶部“任务卡不得随意打断+中断登记”、flow/进展.md、flow/round16-crash-recovery.md、artifacts/验收清单.md P23，flow/HANDOFF.md / VISION.md / CURRENT_STATE.md 和 ~/.hermes/standards/PROJECT_STANDARD.md。本项目无 flow/team.json，沿用历史子代理执行，不创建新团队、不碰原 EasyControl 的队伍。
实况2026-10-15 01:50 PDT：HEAD与分支吻合，但工作树 flow/踩坑记录.md 已有16行未提交“流程/中断登记”（这是用户给定的必须保留资料，不回滚）。先对现有原件/差异、目标源文件和交付四件套做快照，记录继承脏改动。stash@{0} On feat/eink-port-20261013: round17-wip-killed-20261015，属于别任务，绝不pop/改/删，保存并在最终校验OID未变。可将继承文档改动单独提交以留存，但明确来源，别覆盖。
任务开工后持续推进，真实阻塞才停；必须先落交接（做到哪、改文件、未提交保存点、下一步/判据）及flow/踩坑记录中断登记。不要因估计时长主动停任务。
主代理负责最终核验，实施与调查委派。不要修改其他 profile 或 ~/.hermes/profiles/private/。

## 设备与授权
控制端 PAPER_7 10.0.0.237:5555，被控端 vivo V2436A 10.0.0.232:7777（USB也有10AF6D0441003F5，所有adb显式-s）。当前控制端已装v16重出35c2f08db480a2cfac508744576e32bdc12c8361f66336ef9be51ba3baff9721。主代理只读复核 accessibility_enabled=1，enabled_accessibility_services=io.github.miuzarte.scrcpyforandroid/io.github.miuzarte.scrcpyforandroid.services.recovery.CrashRecoveryAccessibilityService，hide_error_dialogs=null。
用户已手动开启无障碍，不代改settings/appops授权，不触碰密码/PIN，不泄露凭据，持续日志不清不打断。任何已有会话/日志按当前包分段。
真机测试前必须调用 ~/.local/bin/alert 纯中文口播：即将开始、测什么、估计多久、用户做什么（建议无需操作先别碰设备，有凭据由本人处理）、谁接手；测试结束再播结果与下一步；交付最后由主代理另外播一次。脚本是否真正出声可看 ~/.hermes/logs/alert-last.ts mtime。实验/候选装机和崩溃均已获本轮授权。若授权因覆盖安装被系统撤回，停在用户本人授权，绝不代开。

## A 必做：恢复后直达远控
用户实测2026-10-15 16:08 CST（PDT01:08）：:a11y看门狗13ms触发，MainActivity到达，约10秒后Scrcpy start(): Session started successfully - device=V2436A，被控VD=3；但UI停设备列表，且误报recovery MainActivity arrival not observed而多发通知。
恢复成功必须“会话建立且全屏远控页在前台/可见”，不是MainActivity到达或仅isStarted。复用P4解锁跳回/正常远控既有路由与全屏入口，不新造导航，不改UnlockJump既有行为；只借原项目MainActivity.changeToFull直达语义，不需要翻源代码。
intent消费后等待异步会话就绪，再请求既有全屏路由。日志明确四级 dispatch / Activity到达 / 会话建立 / 画面可见（远控页前台）。修跨进程到达误报，实际到达不得not observed/降级通知；真降级保留并如实记录。
保持：无会话崩溃不重连、只落主界面（现有无会话不自动拉起，手动开是主界面）；划掉不拉起；未授权通知保留但不是主路径。

## B 先实验再决定产品
用户只授权先在现有无障碍服务上最小 canPerformGestures + dispatchGesture 真机探针，用于自动点掉系统“应用已停止运行”弹窗（关闭应用或框外空白）。AOSP不承诺TYPE_SYSTEM_ERROR可命中，不能用源码/单测替代真机。
成功=预先固定N秒内框自行消失+logcat手势派发/完成记录，无SecurityException/拒绝；必须界面层前后证据，不接受adb input点击冒充dispatchGesture成功。失败=框仍在/还需人点。先确定N（如5秒）写证据，不事后改口径。
实验可用内部probe包/临时独立worktree或明确暂存补丁，候选根不要提前刷新。先记录已装探针完整hash和能力。不得改用户无障碍授权；安装后只读复核。
用户说“成立再决定是否改产品”：B实验若成功，记录最小仅崩溃弹窗场景落地建议、设置/隐私说明需要哪些变更，但未经用户进一步确认不把自动点按作为正式产品主路径。最终v16.2以A正式修复交付，B实验补丁与APK单独留evidence/probe，明确产品落地待用户决定；必要最小声明只在probe。若对该授权边界有疑义保持保守不扩大。
B失败：D只出替代方案，不实施。可列hide_error_dialogs 1等，但全局副作用且项目明令禁，须单独用户批准。绝不自行settings put。v16.2上系统弹窗若仍需点击如实写未解决，不冒称全自动恢复通过。

## C 并行包体核实（另一调查子代理负责）
原项目最新候选包到底有无无障碍，以APK不是仓库结论。另一子代理独占报告 /Users/qifam/Documents/scrcpy-port-artifacts/findings-20261015-round19/package-audit/REPORT.md 与其原始证据，不改仓库/候选，不安装不点设备，仅adb pm path/pull。实施者不要重复扫APK，收尾引用该报告；若报告尚未到可通知主代理汇总，不能编造。

## 真机矩阵
前后播报。前置 accessibility_enabled=1 /组件/实际连接状态/hash。
必须：活跃会话崩溃自动回全屏（会话日志+Activity/Compose路由与焦点/前台/表面证据；不能只App前台）；连续两次崩溃；无会话崩溃->仅主界面不重连；最近任务真实上滑划掉->不拉起（沿历史有界观察窗口，不看两秒就说过）；未授权只读核路径+引用历史P23.1，不代切授权。崩溃注入am crash主进程pid，旧做法真实点击对话框外空白。B实验期间先让dispatchGesture做，不用adb点冒充成功；失败后为A分离测试可真实input点外空白，必须标注A当次需要点击不能称弹窗已解决。
不触碰PB1/触控/缩放/面板/流转/保活心跳/IME/密码任何行为，1x原路径/协议逐字节不变。不新依赖/第二App/无界轮询/新机制。不关闭全局错误弹窗。临时测试产物不混正式包。

## 构建交付
最小可运行回归检查覆盖非平凡新逻辑，保留历史123例，范围守卫按本轮更新但不得放松保护、明确允许精确恢复导航改动。双 clean ./gradlew :app:clean :app:assembleDebug :app:testDebugUnitTest，两次每个APK逐字节对比、测试真实XML数、签名同旧证书/server bytes。环境按HANDOFF JAVA21 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools。
正式候选v16.2（若别号给理由）固定 ScrcpyForAndroid-EinkPort-candidate.apk + SHA256SUMS + BUILD-INFO.md；替换前旧四件套原字节rollback，不能覆盖旧档。不提前刷新候选目录触发可能的自动装机看护，内部包在findings。
更新验收清单P23，A全屏判据/步骤/实际结果，B独列结论和未落地状态；功能与修复总清单.md。更新flow/进展、踩坑（保留开头纪律）、round16-crash-recovery（补轮19纠正历史状态）、CURRENT_STATE/HANDOFF受影响项；新flow/round19-crash-recovery.md集中完整报告，含根因/调用链/各日志级证据/覆盖与未测。
本轮交付报告及验收/BUILD-INFO副本应入仓flow或轻量交付目录，APK/大二进制不入Git。小阶段提交，最终push origin当前分支，读回ls-remote=HEAD。docs-check实跑，若继承文档规则冲突如实记录，不乱改无关文件。
最终回主代理：报告绝对路径、提交/远端SHA、候选路径完整hash、真机矩阵每项通过/失败/待验、A是否需手动关框、B是否成功及产品是否落地、测试/双构建实际结果、stash OID及继承脏文档去向、阻塞与未决。所有外部操作附可回读证据。
