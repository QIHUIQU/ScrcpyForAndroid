# BUILD-INFO — ScrcpyForAndroid × EasyControl E-Ink v15 最终候选

- 日期：2026-10-14 PDT；设备日志2026-10-15 CST。
- APK：ScrcpyForAndroid-EinkPort-candidate.apk，universal四ABI，49,123,665 B。
- SHA256：cb87b57f9f43816293f1508da21bdf1d5f11d930ecd8aeb8c3ae4c974646c81f
- 构建源：8053b79d791f4c3c0465714ec649a08fa7419288；分支feat/eink-port-20261013。后续文档不改变APK。
- 包名io.github.miuzarte.scrcpyforandroid；versionName0.7.0/versionCode52；minSdk26/targetSdk37；未增依赖、未改构建配置。工具链沿用v14。

## 唯一修复范围

支付后不再自动黑屏：旧blacked闩锁只靠锁态跳变或会话结束复位；手机真实面板被外部点亮但showing不变时，后续op全部noop。v13→v14的面板/CANCEL/崩溃恢复改动未改这条复位路径，不认定由v14引入。

三个blackout源文件、两个原单测文件：
- 读取SF物理面板powerMode；虚拟显示忽略；未知、缺失、重复字段不推断为ON/OFF。
- 同一状态机增加真实OFF确认→ON复位。若快速点亮使OFF未被采到，五秒命令在途宽限后仍需新鲜ON样本才复位，不能永久卡住；未知或OFF不因时间复位。
- 原轮询/操作采样复用，同一Mutex串行采样→迁移→动作，旧machine结果不应用到新会话。原操作500ms节流保留，无新定时器。
- v8 PENDING承诺、v12自动注入notifyBlackout=false保留；其它触控/缩放/面板/流转/保活/密码/IME行为未改。

完整报告：第15轮支付黑屏复位与验证报告.md（同仓库flow/round15-payment-blackout.md）。

## 实际构建验证

命令两次：
`export JAVA_HOME=$(/usr/libexec/java_home -v 21) ANDROID_HOME=/opt/homebrew/share/android-commandlinetools; ./gradlew :app:clean :app:assembleDebug :app:testDebugUnitTest`

- final-clean1/2：每次22套件120例，失败/错误/跳过均0；五APK全字节一致。v14原108例全保留，新增12例。
- 最终补改先红后绿：首稿实现上快速唤醒/未知模式两个测试真实失败，修后27个blackout用例通过。早期红桩六例失败仅作为初稿记录。
- 范围守卫verify_round15_scope.py通过，v14保护全部继承；精确冻结当前三源文件快照、原锁态parse不变、触控协议及其它功能冻结。
- apksigner V2验证通过；证书SHA256 c8c1b8b58c6f1f927293e03a1564c5666398e3442edc009fadb4e1c9cf736126，同v14。
- 内置server不变，SHA256 26cbc9ad0aced6c2282455bef4fb43462605c1f8758c74b4ab1dbf818c229daa。
- 初稿有独立QA；最终补改因子代理额度用尽由主会话完成并自审，不冒称最终版另获独立QA。Mutex调度故障注入未测。

## 真机验证与明确未测

- v14 aacfc2b2…已复现：外部面板On、锁态不变，下一操作op_blacked_noop。
- 初稿v15 2c0e5aae…跑过两轮面板复位后再次fire；原件与证据单独留initial-v15和ab-*，不混记最终包。
- 最终v15 cb87b57f…已覆盖安装，设备base.apk完整hash回读一致。设备12:57:15.449 rewake_display_on→12:57:18.633 fire→12:57:20.590 confirmed；该回合showing=true不变，SF物理On→两个Off，mWakefulness仍Awake。完整逐步前/后输出见final-device-steps.log。
- 最终会话沿用用户log_level=warn，无INFO级server关屏日志；最终效果用SF Off+PB-1事件，不借旧包INFO行证明最终包。
- 快速唤醒窗真机尝试未隔离成功（一次未亮屏、另一次夹杂锁态变化/操作），仅单测边界通过，真机待验证。不是宣称快速窗实测通过。
- 本人解锁后的完整本地使用/支付形态、D>0新路径、折叠内外屏切换、自动凭据端到端、命令排队超过五秒与会话代际竞态均待验证。未输入或读取凭据，未做真实支付。
- 用户下一步：平板已装最终包，按P22本人解锁手机并普通使用后回平板，确认再次自动黑屏；反馈时间/步骤给本对话，由主会话接手。

## 回退与证据

v14原四件套留rollback/ScrcpyForAndroid-EinkPort-v14-aacfc2b2.apk、BUILD-INFO-v14.md、SHA256SUMS-v14、验收清单-v14.md；更早版本保留。旧备份分支与v11标签44a03b3不动。
证据根findings-20261014-round15/：final-clean1/2.log及APK/XML、final-build-verification.json、followup-red/green*、final-device-steps.log、final-pb1-filtered.log、initial-independent-qa.md、final-signature.txt、final-verification.json。最终提交/远端锚点由最后一份json记录。
