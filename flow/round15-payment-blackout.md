# 第15轮：支付后自动黑屏失效（v15 最终候选）

日期：2026-10-14 PDT；设备日志：2026-10-15 CST。初始 HEAD 8f4bc83；最终构建源 8053b79d791f4c3c0465714ec649a08fa7419288。

## 1. 用户报告、证据与根因

用户原话：「候选包测试发现的 BUG：之前修复好的问题：支付场景的自动黑屏，再次失效了。」

既有证据为 findings-20261013/paper7-v6-logcat.txt，未重新全量采集、未清日志：pid9020，11:36:49.745 arm、11:36:54.861 rewake，随后 defer 九次至11:41:29.565；11:41:43.733 fire 后长期出现 fallback_blacked/op_blacked_noop。用户简报的2807/1223是其提供的统计，不作为本轮精确复算结果。结论取事件迁移与本轮A/B，不依赖该计数。

v14 的 blacked 完整生命周期：
- 唯一置 true：applyBlackout（fire、fallback共同调用）。
- 清 false：初始化、onControlledLocked、非PENDING的onControlledRewake、onSessionLost。
- 读：onControlOp 中 blacked 导致 op_blacked_noop；onTick 中导致 fallback_blacked。
- LOCKED/UNLOCKED 来自每五个tick的 showing= 采样，仅在相对 lastDispatched 发生跳变时派发；操作时还做一次锁态补检。
- 因而外部把真实面板点亮、但 showing 未改变时，没有任何显示信号可清闩锁。这是本轮复现并修复的根因，不把用户实际支付时的每一步锁态当作已完整捕获。

重要观测修正：Awake 不等于物理面板亮。PB-1 是 SurfaceFlinger 级关闭，最终包实测面板均Off时 mWakefulness 仍Awake；dumpsys display 的framework状态与screencap也不能判定该黑屏。实际效果用 dumpsys SurfaceFlinger --displays 的物理 powerMode 验证，同时留PB-1事件与power输出。

## 2. v13/v14、v8、v12逐项关联

以v13交付a560eeb（构建源2e6053a）对比v14构建源102edb9，逐项查git diff及调用者：
- 删除PanelDismissTouchGate、改交互后两秒收起：没有修改blackout服务或Scrcpy注入钩子；旧gate也不清blacked。
- P1-5多指CANCEL恢复：修改局部非1×手势清态，不修改blackout复位或arm/defer。
- 崩溃后恢复：复用session起停；重建machine会清态，而非维持本轮日志中同一machine的永久闩锁。
- services/blackout/**及scrcpy/Scrcpy.kt在v13→v14无diff；不能据“在v14发现”认定由v14这三项改动引入。该缺口至少在已检查的v8/v13/v14中存在，未追溯宣称最早起源。
- v8 4cbcf2e只解决PENDING被rewake取消；本轮保留rewake_pending_keep，不动倒计时承诺。
- v12 P0-3 922587c用notifyBlackout=false隔离自动凭据注入；三处仍保留，不把系统自动注入当用户操作。守卫检查通过，端到端自动凭据仍留用户验收。

## 3. 最小修法与复核后收口

只改三个blackout源文件与两个原测试文件，无新依赖、不改触控/缩放/面板/流转/保活/IME/密码业务：

1. ControlledLockReader：增加只读SF命令与物理面板解析。数字Display块必须各有一个可识别powerMode；虚拟显示不计；全部Off→OFF，任一明确非Off模式→ON；未知token、缺字段、重复字段、未知格式→UNKNOWN。原锁态parse函数字节不变。
2. 同一PaymentBlackoutStateMachine增加onDisplayObserved：blackout确认OFF后看到新ON即rewake_display_on清blacked；OFF保持闩锁；UNKNOWN永不复位。
3. 首稿要求“必须先采到OFF”，独立QA指出快速关→开可能整个落在五秒采样窗内，导致永久等确认。最终版新增本回合五秒命令在途宽限：未确认的即时ON不复位；宽限后仍采到真实ON则允许清闩锁，防止永久卡死。时间本身不复位，UNKNOWN/真实OFF都不走该通路。每次fire重置宽限。
4. coordinator复用原五秒轮询与500ms节流操作路径，黑屏闩锁期才增加面板查询。两入口使用同一coroutine Mutex把采样→迁移→应用串行化，防止旧ON越过新OFF/下一回合；IO后核machine身份，不让已替换会话的旧读结果应用到当前会话。不新增定时器。

设计边界：五秒是有界命令宽限，不是服务器执行确认；若命令排队超过五秒且面板仍ON，后续可能重新提交一次。该极端未真机制造。多屏中另一块常亮时不再永久等“全Off”，但折叠切换与其它厂商兼容仍待验证。保持原LOCKED fallback语义：外部亮屏后若锁态仍LOCKED且一直无操作，Tf到期仍可兜底黑屏，不伪装为UNLOCKED。

## 4. 实际测试与范围验证

首稿：22套件117例通过，红桩六例失败后恢复变绿。其独立QA报告只针对e5b08e6/d49c0b9，保存在 findings-20261014-round15/initial-independent-qa.md；不能当最终补改已获独立重审。

最终补改：先加快速唤醒/未知解析测试，真实实现首稿上两个测试失败（followup-red.log、followup-red-xml）；修后22个状态机用例与5个reader用例全绿（followup-green*）。新增三例，最终相对v14原108例保留全部，新增12例，总120例。

最终实际两次：
`export JAVA_HOME=$(/usr/libexec/java_home -v 21) ANDROID_HOME=/opt/homebrew/share/android-commandlinetools; ./gradlew :app:clean :app:assembleDebug :app:testDebugUnitTest`
- 每次22套件120例，失败/错误/跳过均0。
- 五个APK两次全字节相同；final-clean1/2.log及对应APK/XML原件保存。
- V2签名通过，同旧证书c8c1b8b5…；内置server保持原字节。
- verify_round15_scope.py：继承v14所有保护，额外冻结本次三个PB-1源文件精确快照、改动面恰3源+2测试；原锁态解析不变，v8/v12保留，触控/协议/录像/解锁/密码/IME/All-Options冻结。无新计时循环。
- 并发Mutex是源码路径复核与编译/真机运行验证，未做Android协程调度/会话切换故障注入；不称已有纯状态机测试覆盖全部并发竞态。

## 5. 真机A/B及最终包复验（分包记账）

设备：PAPER_7 10.0.0.237:5555；vivo V2436A 10.0.0.232:7777。

旧包v14 aacfc2b2…：ab-v14.log中fire 12:24:01.018后，外部面板On且showing不变，下一操作12:24:29.317为op_blacked_noop，失效复现。

首稿v15 2c0e5aae…：ab-v15-verify.log第一回合全程showing=true，12:22:03.340 rewake_display_on→12:22:11.245 fire及server关屏，SF物理Off；第二回合12:22:34.365面板复位时showing=true、12:22:44.029再次fire，操作后showing=false，不能称整段锁态均不变。ab-v15b有showing true→false，故不单独作为“锁态不变”的证明。

最终包v15 cb87b57f…：覆盖安装回读base.apk与最终包哈希完全一致。final-device-steps.log包含每步变更前/后SF、power、policy、PB-1片段及命令输出：
- 12:57:05.186 fire；12:57:10.305 blackout_confirmed。
- 手机外部点亮后12:57:15.449 rewake_display_on；下一控制操作12:57:18.633 fire，12:57:20.590再确认OFF。
- 该正常回合snapshot的showing均true；SF从物理On再到两个物理Off，power仍Awake，双观测支持本次复位通路真实有效。
- 快速唤醒进行了两次有限尝试，但一次没有真正亮起面板，另一次有锁态变化/其它操作事件，未形成“无OFF采样、无锁态变化”的干净真机实验；如实标该窄窗仅JVM定时断言通过、真机待用户复验，不拿尝试标题当通过证据。
- 最终会话server沿用用户log_level=warn，未出现INFO级Device display turned off；未擅改设置来制造日志。最终黑屏效果依据SF Off + PB-1 fire/confirmed，早期首稿INFO证据与最终包分开。
- 收尾input keyevent223后mWakefulness=Asleep、showing=true、物理面板Off，控制端留最终包。未清日志、未真实支付、未输入或读取PIN/密码、未动账户页面。

## 6. 交付与剩余验收

候选：/Users/qifam/Documents/scrcpy-port-artifacts/ScrcpyForAndroid-EinkPort-candidate.apk
SHA256 cb87b57f9f43816293f1508da21bdf1d5f11d930ecd8aeb8c3ae4c974646c81f，49,123,665 B。
v14原APK/BUILD-INFO/SHA256SUMS/验收四件套在rollback，旧版本不删；首稿v15在findings/initial-v15，不能与最终包混记。

BUILD-INFO/SHA256SUMS/P22/功能与修复总清单、flow进展/踩坑/CURRENT_STATE/HANDOFF已同步；源码与文档提交推送，最终远端读回见final-verification.json。

待用户：按P22本人解锁手机、普通本地使用后回平板，重复确认再次黑屏；不要求真的付钱。另测快速拿回（五秒内）、D>0、折叠内外屏切换。完整支付形态、自动解锁端到端与调度故障注入未验，不宣称全部真机通过。反馈时间/步骤给当前对话，由主会话接手。
