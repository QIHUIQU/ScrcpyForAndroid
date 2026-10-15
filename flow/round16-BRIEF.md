# Round16 执行简报（用户授权，2026-10-14 PDT / 设备2026-10-15）

## 接手与分工
主会话负责用户沟通/最终外部复核；执行子代理负责调查、最小修复、测试、设备验证、打包、留痕、提交推送。现有项目flow/team.json不存在（历史也明确无团队），不新建队、不改其他profile。源仓 /Users/qifam/Documents/scrcpy-port，分支feat/eink-port-20261013，接手HEAD dbf1f06，工作树起始干净。 artifacts=/Users/qifam/Documents/scrcpy-port-artifacts。
先读flow/进展.md、flow/踩坑记录.md、artifacts/验收清单.md P21/P22、现有入口/规范。当前候选v15 universal SHA256 cb87b57f9f43816293f1508da21bdf1d5f11d930ecd8aeb8c3ae4c974646c81f 已装控制端。
用户确认P22黑屏复位通过，本轮绝不改PB-1；顶部文档更新该反馈，不伪造全部边角已验。

## 设备与授权
控制端PAPER_7 10.0.0.237:5555（USB T2327BCZ0030可用时），被控端vivo V2436A 10.0.0.232:7777（USB 10AF6D0441003F5）。adb devices已见两机在线。用户已授权本轮覆盖安装、崩溃、真机A/B、连续崩溃与划掉测试；无需反复确认。不得清空用户数据/日志、改密码库、代输入凭据、真实支付。尽量少UI，不复测已过缩放/多指，不改黑屏/触控/缩放/面板/流转/心跳/IME/密码/解锁跳回既有语义。仅崩溃恢复链路变更。

## 已有失败证据（勿重复采集原窗口）
用户原话：「我没有点对话框，是点了空白处，对话框就消失了，然后 app 并没有自动拉起，是我自己手动点开应用的」。活跃会话执行 adb -s 10.0.0.237:5555 shell am crash io.github.miuzarte.scrcpyforandroid。
设备13:50:37.780 FATAL EXCEPTION main pid16262；13:50:37.799 Force finishing MainActivity；13:50:37.828 Showing crash dialog；13:50:38.304 top resumed state loss timeout；13:50:42.868 Window unresponsive；13:50:43.040 Crashing app skipping ANR；13:50:48.421 destroy timeout；13:50:53.559 Killing16262 crash；13:50:53.770 obituary。之后用户点空白关对话框，app未启动。
13:51:27.539 START MAIN/LAUNCHER flg0x10300000 MainActivity是用户手动点开；13:51:27.555 Start proc16451；13:51:31.294 Displayed +3s770ms；13:51:31.551 START flg0x14000000 MainActivity(has extras) LAUNCH_MULTIPLE from uid10184为应用自发第二启动（必须定因）。
该窗口无ProcessKeepAliveService recovery attempt / recovery launch dispatched / MainActivity recovery launch arrived / DeviceTabViewModel session start succeeded / Notification；仅进程起来后KeepAliveScheduler16451 job scheduled(interval900000)。被控端scrcpy虚拟屏归零。

## 必须逐项查明并给证据
1 粘性ProcessKeepAliveService manifest、进程、onStartCommand返回值、启动调用全链；当时是否在运行、是否重启，am crash系统抑制与真实未捕获异常的区别。不得把假设写成根因。
2 改动前实读run-as SharedPreferences恢复计数当前值（仅定向crash recovery keys，不输出其他敏感prefs），检查3次/10min是否耗尽及真正抑制位置。同一死亡去重、成功后复位、合理限流，历史额度不能挡正常单次恢复，不能无穷崩溃循环。
3 crash dialog存在/点空白dismiss的系统后果，是否影响sticky/BAL，有无依赖不点击。官方源码/文档与真机分别记录。
4 BAL限制、通知权限/渠道/系统抑制、为何无通知，复现可见的真实证据；startActivity返回≠前台已到达，通知post≠用户可见。
5 13:51:31.551带extras第二启动真实调用源，为什么手动启动后才走。

## 目标与验收
活跃会话崩溃→自动回到远控；若系统确实限制后台启动，则及时明确通知一键回远控，日志/报告必须写已降级、未自动回远控，不能只dispatch就宣告成功。无会话崩溃→只主界面；用户主动划掉→不拉起（包括job兜底）；正常停止/主动退出不误重连；保留现设置门。
先完整追代码/全部caller，查现成原生机制，最小解决，不新架构/依赖/投机定时器。用户pony ultra：只写必须代码，但不得简化掉明确要求与检查。
新增/更新可运行单测覆盖决策分支，先失败后成功如可行。真机A/B至少活跃会话崩溃、无会话崩溃、连续两次崩溃、划掉不拉起；划掉需合理观察且验证job门，不拿两秒空白宣称长期。真实未捕获异常与am crash差异如存在也要验证。无设备/凭据则实标未验证，不假造结果；用户授权中途审核修复自行解决到候选产出。
PB-1等禁止面做源码精确冻结，继承现verify_round15_scope.py等实际守卫（先读适配），不把单测/源码当真机。保持server字节不变/签名兼容。

## 交付
v15 APK+BUILD-INFO+SHA256SUMS+原验收清单四件套原字节移/复制rollback（先hash确认），不得覆盖旧备份。v16 universal candidate APK、SHA256SUMS、BUILD-INFO.md；双clean ./gradlew :app:clean :app:assembleDebug :app:testDebugUnitTest，ANDROID_HOME=/opt/homebrew/share/android-commandlinetools，保留两次完整构建日志与JUnit XML，程序核套件/用例/失败/错误/跳过及五APK逐字节一致。原用例保留，范围守卫、签名、installed base.apk hash回读。候选目录只最终齐套刷新，中间测试包用findings，防自动装机看护扰动。
artifacts/验收清单.md新增P23步骤、判据、实测/待测分列（替代旧P21.6-8/旧计数口径），更新功能与修复总清单.md。flow/round16-crash-recovery.md逐项五问根因/修法/证据/风险/提交锚点；flow/进展.md和踩坑记录.md置顶按既有字段合同填写。CURRENT_STATE/HANDOFF缺失，应按现有flow形态最小补充对应关系/接手入口，不重复历史。VISION若已有flow/charter沿用。规范见/Users/qifam/.hermes/standards/PROJECT_STANDARD.md；实跑python3 /Users/qifam/.hermes/scripts/project_team.py docs-check --root /Users/qifam/Documents/scrcpy-port。
提交并push origin feat/eink-port-20261013；提交信息写根因修法，不重写旧历史/移动备份标签，推后ls-remote回读。APK/大二进制/敏感证据不入源码仓，检查.gitignore。
最终停在v16已装、可引导用户P23复测的合理现场；汇报真实末态。禁止无关修复，发现另记。

## 独立审查接口
另一个子代理仅做Android机制只读调查，不用设备、不改代码；主会话可追加其报告。执行完成先回报具体commit、报告、APK完整hash、单测与真机矩阵、未验证项，不自行语音播报。主会话再派独立QA审查并最终外核；若QA指出阻断，由执行线修至闭环。不要声称有未实际发生的独立QA。
