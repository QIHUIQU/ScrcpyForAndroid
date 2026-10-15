# BUILD-INFO — ScrcpyForAndroid × EasyControl E-Ink v16.2 最终候选

- 日期：2026-10-15 PDT；设备日志CST。
- 文件：ScrcpyForAndroid-EinkPort-candidate.apk，universal四ABI，49,146,351 B。
- SHA256：`7d08d7167ccf61328bc94c6e28fdb1a8575eb598e22203c1525327bdfa897379`。
- 分支：`feat/eink-port-20261013`；构建源提交 `b9dcad7f62926c0f23df3412892567f22931f550`（后续仅文档收口）。
- 包名 `io.github.miuzarte.scrcpyforandroid`；versionName/code仍为0.7.0/52；v16.2为项目候选别名，沿历史口径，不改构建配置/依赖。
- 已装PAPER_7，`pm path`拉回APK与正式候选完整SHA256相同。

## 本轮做了什么

1. 到达误报根因：`:a11y`写、主进程清的SharedPreferences无法跨进程同步。到达信号改既有普通文件模式 `files/recovery_arrival`，派发/到达/会话建立/远控页前台可见分段记录。缺失、损坏文件不报已到达；清账和较新派发不由旧检查乱发通知；真正未观察到仍保留降级通知。
2. 已授权误降级：无障碍启用检测由进程连接标志、管理器列表、只读系统已启用组件设置依次核验；不代改授权。
3. 仍复用原 `startScrcpySession(openFullscreen=true)`，等异步scrcpy.start返回后走既有buffered fullscreenRequests与原全屏路由，无新导航机制、未改P4/UnlockJump。
4. 最终复核纠正「composition=可见」：两种入口共用FullscreenControlRoute，必须有会话+该路由RESUMED+窗口有焦点才打第4级 `fullscreen control page visible (sessionReady=true routeResumed=true windowFocused=true)`。MainActivity到达或会话start本身不是恢复成功。

## 最终构建与签名

两次命令：
`export JAVA_HOME=$(/usr/libexec/java_home -v 21) ANDROID_HOME=/opt/homebrew/share/android-commandlinetools; ./gradlew :app:clean :app:assembleDebug :app:testDebugUnitTest`

- 每次22套件125例，0失败/0错误/0跳过；原123例保留，本轮最终新增2例（到达判据、前台可见判据）。
- 五个APK两次完整字节相同，证据：`findings-20261015-round19/final-review/build/result.json`、两份完整构建日志、真实JUnit XML与APK。
- apksigner V2校验通过，同旧证书SHA256 `c8c1b8b58c6f1f927293e03a1564c5666398e3442edc009fadb4e1c9cf736126`。
- 内置 `assets/bin/scrcpy-server-v5.0` 原字节不变，SHA256 `26cbc9ad0aced6c2282455bef4fb43462605c1f8758c74b4ab1dbf818c229daa`。
- `python3 scripts/verify_round19_scope.py` 已实跑通过，保留PB-1/1×触控/协议/面板/流转/心跳/IME/密码/UnlockJump等保护，探针不在产品内。守卫是源码证据，不替代真机。

## 最终包真机（非借首稿样本）

证据：`findings-20261015-round19/final-review/device/`，完整命令、matrix.json、窗口/Activity/UI XML与logcat均留存。

- 连续两次活跃会话崩溃：四级日志到位，CST18:19:08.525与18:19:39.852出现会话+路由RESUMED+窗口焦点可见日志，全屏调试层/前台证据成立，无not observed与多余通知。
- 无会话崩溃：25秒有界观察不拉起、不重连；手动打开只在主界面。
- 最近任务真实上滑：task removed + gate declined，65秒有界观察不拉起，持久gate继续阻止后续job。不冒称完整15分钟周期真机观察。
- 未授权：只读复核+引用历史P23.1，不代撤销授权重测。安装后有一次系统重绑窗口accessibility_enabled短暂=0，等待后自行=1且bound，再开始崩溃测试。
- 测试前后实际alert播报；结束已用正常启动按钮恢复远控会话，当前无障碍=1、hide_error_dialogs=null。

重要限制：正式v16.2仍需要真实点击一次系统崩溃弹窗外空白（A测试用了input tap），之后不用点通知或手动进入远控；不是从崩溃开始零人工操作。

## B/C 与未验证边界

- B探针：现有服务加canPerformGestures，人工广播触发dispatchGesture后accepted/completed，实施者记录约0.97秒内弹窗消失（预先N=5秒）。探针APK `54f9aa91…` 与源码/日志独立保存在probe/，未进入正式包。
- 仅手势作用能力成立，自动触发闭环未验证：样本中先手势关框再Killing再watchdog，不能把watchdog说成已验证的提前关框触发。是否正式落地待用户确认，并先解决触发与严格场景限定，不盲点其它窗口。
- C包体核实：33条路径去重20个包体；已查原易控主包/rlcdB的27条路径（15个包体）未发现系统可绑定无障碍服务，另核官方release/PIP；完整包名版本/manifest证据见package-audit/REPORT.md。外盘归档未穷举，不推广为所有版本都没有，不推断用户混淆。
- 用户原报「会话恢复仍停设备列表」独立根因本轮未复现，不能用等待观感代替用户观察。最终指定场景通过，但自然复验、跨OEM、:a11y重武装、会话代际竞态仍待验证。
- A/B实施和C调查各一个子代理；额外独立QA因本次子代理预算被拒绝，最终补改与自审/复验由主会话完成，未冒称独立QA。

## 回退与继续

- v16原四件套与APK `35c2f08d…` 留rollback，v15及更早未动。
- v16.2首稿 `dac1976f…` 原APK/交付文档另存rollback的 `*-v16.2-initial*`；首稿构建/真机证据保留旧build/、device-evidence/，不冒充最终包。
- round17 stash OID `1ebff2072294bad8bb778a422eef2d3708d84307` 未动。
- 当前验收入口：验收清单.md P23；完整最终收口：仓库 `flow/round19-final-review.md`。
- 下一步：用户自然复验P23、反馈时间回本对话，由主Agent接手；自动关窗正式落地待用户决定。
