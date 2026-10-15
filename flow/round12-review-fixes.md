# 第12轮代码审查修复执行记录（v12）

日期：2026-10-14 PDT。设备日志为 2026-10-15 CST，两者是同一时段的时区差，历史记录不改日期。
基线：44a03b3 / v11 3e130b9cee894cd45ac210baa992e58038a9a585d0086e9ca64965191283fa71。构建源提交：754a06d8be300deda0d243e8f7e5be784f3e6f73。
范围：P0七项、P1九项、P2六项、Q2；Q1保持所见即所录。P2-3按真实引用收窄，P1-8当前死视图无用户可见改善。
22个按问题提交 + 1个源码守卫提交。P0-3/P1-6共享注入接口与成功判据，合在一个提交避免中间状态不兼容；逐项仍独立登记。
实施：DeepSeek 子代理分片；整合与逐文件复核：GPT 执行会话；子代理未宣称真机或构建通过，以下结果由执行会话实际运行。

## 验证结果

- 两次 `./gradlew :app:clean :app:assembleDebug :app:testDebugUnitTest` 全通过，5个 APK 全部逐字节一致。
- JVM 20套件103例，0失败0错误；逐函数对照 XML，97个基线用例全部实际执行。新增：decoder 策略4例、保活协程循环2例。
- `python3 scripts/verify_round12_scope.py` 通过：1×触控业务路径/序列化逐字节对照；录像变换、PB-1语义、密码库、UnlockJump、现行ScrcpyIME和All-Options文件不变。
- PAPER 7 已覆盖安装 v12，pull实际安装 base.apk 后 SHA256一致。冷任务前无App pid；强制周期任务一次，pid 5247 打 job run，无当次 UninitializedPropertyAccessException/FATAL。
- 最小一次启动点击恢复会话；观察原有分辨率降级 2200x2480→1816x2048，stop→start完成，videoFeed usable=true；未主动制造早退竞态。
- 未重启设备、未锁/解锁触发密码、不作多指/大规模手势。VD按钮两次最小点击在屏底手势区未命中，没有修改尺寸或用户设置。
- 未测：真实重启、自动凭据/PB-1联动、VD直接保存、decoder构造失败/饥饿竞态、启动/录制器失败资源计数、IME失败反馈、网络抖动真机、密码/剪贴板安全UI、缩放取消/预览平移。见 artifacts/验收清单.md P19。

## 逐项依据、根因、风险与验证

### P0-1 · commit 03eb917
- 依据：方案 §1/§2/§3/§5 对应 P0-1；基线 `KeepAliveJobService.kt:24–26`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/services/keepalive/KeepAliveJobService.kt:26`。
- 根因：冷任务的第二次 Storage 读取落在 runCatching 外；运行时 context 未初始化。
- 改法：读取前 init；复用同步 initialValue。
- 风险：低：只补初始化顺序。
- 验证/遗留：真机冷进程 job 已实测：08:07:50.969 CST，pid 5247，job run enabled=true；无本次崩溃。

### P0-2 · commit ddfde8e
- 依据：方案 §1/§2/§3/§5 对应 P0-2；基线 `BootRescheduleReceiver.kt + Manifest:98–104`；现定位 `app/src/main/AndroidManifest.xml:16`。
- 根因：重复开机接收器读取未初始化 Storage；持久任务已承担跨重启恢复。
- 改法：删除 receiver 文件/声明；保留持久任务需要的 RECEIVE_BOOT_COMPLETED 权限。
- 风险：低：依赖已存在 setPersisted(true)。
- 验证/遗留：APK manifest/安装回读已核验 receiver 不存在；实际设备重启未测，交用户方便时测。

### P0-3 · commit 922587c
- 依据：方案 §1/§2/§3/§5 对应 P0-3；基线 `Scrcpy.kt:406–424 × ControlledLockWakeController.kt:99–110`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/services/lockwake/ControlledLockWakeController.kt:106`。
- 根因：自动凭据文本与回车被 PB-1 当用户用完操作。
- 改法：注入入口默认 notifyBlackout=true；自动三条消息 false，仍经 InjectionController 清零。
- 风险：低：手动默认值和 PB-1 状态机不变。
- 验证/遗留：源码守卫已核验；自动凭据实测未测，由用户解锁并比对 credential submitted 后无紧邻 PB-1 fire。

### P0-4 · commit 8efa935
- 依据：方案 §1/§2/§3/§5 对应 P0-4；基线 `NativeCoreFacade.kt:510–512`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/NativeCoreFacade.kt:546`。
- 根因：两个早退遗漏 restart 标志复位。
- 改法：唯一 restart 汇聚点早退清标志/日志；不在 stopped 回调清。
- 风险：低：保留重连间隙语义。
- 验证/遗留：正常 stop/start 降级链真机通过且恢复 usable=true；早退竞态未主动制造，交日志观察。

### P0-5 · commit 7f99dd7
- 依据：方案 §1/§2/§3/§5 对应 P0-5；基线 `FullscreenControlScreen.kt:251–252`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/pages/FullscreenControlScreen.kt:281`。
- 根因：输入框始终从默认尺寸开始，直接保存覆盖已设值。
- 改法：打开面板从当前配置规范化播种宽高，复用 TransferOptions 校验。
- 风险：低：仅面板打开时。
- 验证/遗留：编译/源码通过；真机面板按钮两次最小点击被底部手势区吞掉，未完成直接保存验证，交用户 P19.5。

### P0-6 · commit 4fb3164
- 依据：方案 §1/§2/§3/§5 对应 P0-6；基线 `MainActivity.kt:44–52`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/MainActivity.kt:48`。
- 根因：损坏密码库的 getAll 抛出未捕获异常，主线程启动崩溃。
- 改法：复用 refresh/entriesState；启动工作转 IO，并保护认证清理写入异常。
- 风险：低：损坏不销毁库、不改加密。
- 验证/遗留：正常启动已实测；损坏库未测，不破坏用户密码库。

### P0-7 · commit 520a552
- 依据：方案 §1/§2/§3/§5 对应 P0-7；基线 `SettingsScreen.kt:1437`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/services/LocalInputService.kt:58`。
- 根因：私钥剪贴板未标敏感，系统预览可显示。
- 改法：默认 sensitive=false；仅 private 导出标 true。
- 风险：低：标记不是加密/读取权限控制。
- 验证/遗留：编译/调用点和 APK 代码通过；未实际复制用户私钥。

### P1-1 · commit 6d35226
- 依据：方案 §1/§2/§3/§5 对应 P1-1；基线 `Scrcpy.kt:224/258–313/330–344`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/scrcpy/Scrcpy.kt:333`。
- 根因：启动后段失败丢失 stop 通道与尚未登记的 MP4 recorder。
- 改法：recorder 先登记；catch 非取消清理解码器、会话、录制器和剪贴板；保持原异常。
- 风险：低：只改启动失败清理，不改录像变换。
- 验证/遗留：正常启动真机通过；录制器失败/启动取消故障注入未测。

### P1-2 · commit e90337d
- 依据：方案 §1/§2/§3/§5 对应 P1-2；基线 `Scrcpy.kt:1184–1322`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/scrcpy/Scrcpy.kt:1202`。
- 根因：握手失败之前创建的 shell/socket/log thread 未归属 activeSession。
- 改法：局部记账逐项 close/interrupt；清部分 activeSession；dummy-byte 读取异常也关该 socket。
- 风险：低：不改 ADB 传输策略/超时。
- 验证/遗留：编译/全调用链审查通过；失败握手泄漏计数未实测。

### P1-3 · commit 714ee8e
- 依据：方案 §1/§2/§3/§5 对应 P1-3；基线 `VideoDecoderController.kt:298–303`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/nativecore/VideoDecoderController.kt:13`。
- 根因：构造错误只日志；createDecoderByType 的异常还可绕过 DecoderException。
- 改法：统一报告构造结果，复用 handleDecoderError；8秒/最多3次；成功清计数，构造创建异常纳入报告。
- 风险：中：8秒内重复失败直接止住，不设延迟重试队列；无 generation 改造。
- 验证/遗留：4个 JVM 策略例通过，正常构造真机通过；MTK 构造失败未注入。

### P1-4 · commit a16aaee
- 依据：方案 §1/§2/§3/§5 对应 P1-4；基线 `MediaCodecVideoDecoder.kt:126–130`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/nativecore/MediaCodecVideoDecoder.kt:133`。
- 根因：feed 持监视器等待；原 synchronized release 无法提前发布 released。
- 改法：发布 released 后再取锁；等待/排空退出；已替换 decoder 不作运行时错误。
- 风险：低：保留 codec 操作串行；不承诺 vendor stop/release 调用时延。
- 验证/遗留：源码并发链审查/编译通过；2秒饥饿等待竞态未测。

### P1-5 · commit f70e426
- 依据：方案 §1/§2/§3/§5 对应 P1-5；基线 `TouchEventHandler.kt:78–102`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/scrcpy/TouchEventHandler.kt:82`。
- 根因：缩放双指 CANCEL 被当平移开始，panning 卡 true。
- 改法：旧 scale≤1 原样早退后，双指 CANCEL 先取消并清平移状态。
- 风险：低：只 >1× 可达，1× 不新增行为。
- 验证/遗留：逐字节源码/协议守卫通过；未自动驱动多指，交用户取消后单指验收。

### P1-6 · commit 922587c
- 依据：方案 §1/§2/§3/§5 对应 P1-6；基线 `Scrcpy.kt:1610–1625`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/pages/ImeTextSubmission.kt:32`。
- 根因：控制 writer 吞错，文本/粘贴上层假成功。
- 改法：writer Boolean 透传；IME、全屏及设备页粘贴在 false 时可见失败，不写成功日志。
- 风险：低：成功只指消息写出，不承诺 server 应用。
- 验证/遗留：编译/三入口调用链审查通过；断通道 UI 反馈未实测。

### P1-7 · commit 5e53b87
- 依据：方案 §1/§2/§3/§5 对应 P1-7；基线 `DeviceAdbBackgroundRunner.kt:36–53`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/services/DeviceAdbBackgroundRunner.kt:35`。
- 根因：单次探测失败立即走拆链重连。
- 改法：连续3次才判死，探测成功/重连成功复位；不改探测本身。
- 风险：低：真实断线检出约多6秒。
- 验证/遗留：2个真实协程循环 JVM 例通过（成功复位、一次失败不判死）；真机断网未测。

### P1-8 · commit cc9071a
- 依据：方案 §1/§2/§3/§5 对应 P1-8；基线 `TerminalInputView.kt:54–66`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/widgets/TerminalInputView.kt:58`。
- 根因：旧隐藏编辑器拒组合态；但该类当前全仓零引用。
- 改法：按授权照搬现有完整编辑器/finishComposingText 提交，未改 SuperTextField 或活跃 Termux 路径。
- 风险：低：当前未使用，不能宣称解决现页面中文问题。
- 验证/遗留：编译通过；IME 实测未测；活跃 TerminalView 已是完整编辑器模式。

### P1-9 · commit 6407d83
- 依据：方案 §1/§2/§3/§5 对应 P1-9；基线 `LockscreenPasswordActivity.kt:644`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/LockscreenPasswordActivity.kt:645`。
- 根因：密码明文随 rememberSaveable 写入 Activity state。
- 改法：只将密码缓冲改 remember；名称缓冲保持可保存。
- 风险：低：重建丢未提交密码是预期。
- 验证/遗留：编译/源码核验；保存状态 dump 未实测，不读取用户密码。

### P2-1 · commit ea63342
- 依据：方案 §1/§2/§3/§5 对应 P2-1；基线 `NativeCoreFacade.kt:89–94`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/NativeCoreFacade.kt:45`。
- 根因：close 无任何调用者。
- 改法：删除 close，无新增替代生命周期。
- 风险：低：纯减法。
- 验证/遗留：全仓引用核对与双 clean 编译通过。

### P2-2 · commit 343db6d
- 依据：方案 §1/§2/§3/§5 对应 P2-2；基线 `ProcessKeepAliveRules.kt:17–24`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/services/keepalive/ProcessKeepAliveRules.kt:18`。
- 根因：无业务调用的 shouldSchedule 与重复常量。
- 改法：删除 helper/重复常量；保留 interval 下限单测。
- 风险：低：纯减法。
- 验证/遗留：原 intervalRespectsPlatformLowerBound 与所有97基线例仍通过。

### P2-3 · commit 38e95d3
- 依据：方案 §1/§2/§3/§5 对应 P2-3；基线 `Settings.kt:59–91/136–159`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/storage/Settings.kt:57`。
- 根因：registry/Compose/observe 等零调用；方案将 get/set 一并视为死代码的假设不成立。
- 改法：删除确认无调用部分；get/set 及底层读写保留，ADB 密钥与 Profiles 都需要。
- 风险：低：收窄删除范围，不重构实际调用方。
- 验证/遗留：尝试移除 get 后编译失败指向 DirectAdbClient:246等；恢复后双 clean/JVM 全绿。

### P2-4 · commit 8e4ed74
- 依据：方案 §1/§2/§3/§5 对应 P2-4；基线 `AppSettings.kt:324–328/449/531/618 + 两读点`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/pages/DeviceTabViewModel.kt:1026`。
- 根因：LAN discovery 恒 true，无 UI。
- 改法：删除字段/定义/落库映射；两读点 true。
- 风险：低：常量语义不变；历史持久化字段不作迁移。
- 验证/遗留：双 clean/JVM 编译通过，源码无剩余引用。

### P2-5 · commit e79f849
- 依据：方案 §1/§2/§3/§5 对应 P2-5；基线 `ProcessKeepAliveService.kt:61–63 + KeepAliveJobService.kt:24–25`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/services/keepalive/ProcessKeepAliveService.kt:61`。
- 根因：createBundleState initialValue 已读盘，cold 默认值注释误导。
- 改法：删除服务/job 两处重复 loadBundle，不改 initialValue。
- 风险：低：少两次主线程读盘。
- 验证/遗留：源码链核实，冷 job 真机通过。

### P2-6 · commit 13c1e0e
- 依据：方案 §1/§2/§3/§5 对应 P2-6；基线 `BundleSync.kt:71–100`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/storage/BundleSync.kt:25`。
- 根因：rememberBundleState 零引用。
- 改法：删除 helper/专属 imports，保留已用 BundleSyncDelegate。
- 风险：低：纯减法；不迁移其他同步块。
- 验证/遗留：引用核对与双 clean 编译通过。

### Q2 · commit 78c19ad
- 依据：方案 §1/§2/§3/§5 对应 Q2；基线 `方案 §5 Q2 × PreviewCard/FullscreenControlPage 调用点`；现定位 `app/src/main/java/io/github/miuzarte/scrcpyforandroid/scrcpy/TouchEventHandler.kt:28`。
- 根因：预览/全屏共用全局 pan，卡片平移可顶偏全屏。
- 改法：参数默认关，预览显式 false、全屏显式 true；gate 位于原 scale≤1 早退之后。
- 风险：低：只缩放平移范围变；预览普通多指触控保持。
- 验证/遗留：源码/1×不变量通过；预览与全屏交互真机未测，用户 P19.12。

## 被证伪假设与保留边界

1. “Settings.get/set都零调用”被编译证伪：DirectAdbClient与ScrcpyProfiles真实使用；只删未用面，不为凑删除数改调用方。
2. “等待条件加 !released 即可”被取锁顺序证伪：release原 @Synchronized 不能发布标志；前移发布并排除已被替换decoder的假错误。
3. “自动解锁另走直接inject即可”未采纳：仍通过原 InjectionController 清零；自动文本/ENTER均禁止触发PB-1。
4. “失败资源只在activeSession清理即可”被开流时序证伪：shell/初始socket可能在activeSession登记前失败；局部资源账+握手异常close覆盖。
5. “TerminalInputView就是当前终端入口”被全仓引用核对证伪：现行入口为Termux TerminalView。按明确范围修旧类，不另改活跃路径。
6. 首次采集设备date含空格格式被远端shell拆开，logcat -T无效；没有拿空日志认定通过，改用明确设备时间重读并保存 cold-job-confirmed.log。
7. E-ink底部导航区继续吞VD按钮点击；不扩大设备驱动，UI保存项目未测交用户。

所有方案 §4冻结项未顺手做；Q1录像变换文件逐字节不变；无传输层/注入串行化/decoder generation重构、无新依赖。

## 证据入口

- `flow/round12-commits.json`：提交映射；`scripts/verify_round12_scope.py`：可重跑源码不变量。
- `/Users/qifam/Documents/scrcpy-port-artifacts/findings-20261014-round12/`：build-verification.json、clean-build-1/、双构建日志、jvm-test-results/、cold-job-confirmed.log、minimal-reconnect.log、device-verification.json。
- Android官方 API已核对：JobInfo.Builder.setPersisted(boolean)跨重启恢复仍需 RECEIVE_BOOT_COMPLETED；敏感剪贴板属性只防系统可视预览，不是权限/加密。
- 上游/范围外问题未改：server警告 Unknown option legacy_paste、原有编译警告与底边命中问题。
