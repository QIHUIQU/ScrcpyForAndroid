# Round19 最终复核与收口（v16.2 最终候选）

日期：2026-10-15 PDT；设备日志 CST。此文件的最终包与测试锚点覆盖 `round19-crash-recovery.md` 早先 dac1976f 首稿的交付锚点；历史原始证据不删、不混包。

## 交付身份

- 构建源：`b9dcad7f62926c0f23df3412892567f22931f550`，分支 `feat/eink-port-20261013`；后续仅文档提交以 `git log` / 远端回读为准。
- 候选：`/Users/qifam/Documents/scrcpy-port-artifacts/ScrcpyForAndroid-EinkPort-candidate.apk`。
- SHA256：`7d08d7167ccf61328bc94c6e28fdb1a8575eb598e22203c1525327bdfa897379`；49,146,351 B。`pm path` 拉回的安装件同 hash。
- v16.2 是项目候选别名，Android versionName/code 仍为 0.7.0/52（沿历史交付口径），未改构建配置。
- v16 原件 `35c2f08d…` 仍在 rollback；本轮首稿 `dac1976f…` 与其交付文档另保留 `rollback/*-v16.2-initial*`，不覆盖既有回退件。

## 最终复核发现及最小修正

1. 首稿把 `LaunchedEffect(Unit)` 的「已合成」日志写成「画面可见」。本项目此前已证明 covered entry 仍可留在 composition，故该日志不满足用户指定判据。
   - 删除单独 StreamActivity.onResume 的无会话可见日志；两种全屏入口共用原 `FullscreenControlRoute`。
   - 仅在 `currentSession != null`、该路由 lifecycle=RESUMED、`LocalWindowInfo.isWindowFocused=true` 同时满足时记录 `fullscreen control page visible (sessionReady=true routeResumed=true windowFocused=true)`。
   - 不新增路由、不改 P4/UnlockJump/触控/面板等逻辑。启动仍由既有 `scrcpy.start()` 返回后发 `Channel.BUFFERED` 的 fullscreenRequests，页面消费后使用既有全屏入口；不是在 MainActivity 到达时抢跑导航。
2. 首稿到达文件读取失败/null 会被「不是 pending」误判为 arrived。
   - 区分 NOT_OBSERVED / ARRIVED / SUPERSEDED：缺失、损坏、旧到达值均不能证明到达；清账或新派发取消旧检查，不伪称 arrived，也不乱发旧通知；真未观察到仍发通知。
   - JVM 检查覆盖缺失/损坏/旧值/清账/新令牌，并新增可见判据的全部布尔组合检查。
3. 用户原报「会话已建立仍停设备列表」未独立复现，根因仍未知。不能用「大概只是等了十秒」代替用户观察；本次修复前后成功样本也不能证明该历史故障不存在。

补充事实边界：无障碍管理器“返回空列表”有现场证据，但本轮未隔离其缓存/feedback筛选等底层原因；确认的是不能单凭该列表断言用户撤销授权，不把具体缓存机制当成已经证实。

## 最终候选真机结果（不是借首稿样本）

证据根：`/Users/qifam/Documents/scrcpy-port-artifacts/findings-20261015-round19/final-review/device/`；`matrix.json`、`commands.log`、每案 logcat / activities / windows / UI XML。

| 案例 | 最终包实测结果 | 证据 |
|---|---|---|
| 活跃会话崩溃一 | dispatch 18:19:01.253 → Main 到达 18:19:02.801 → arrival observed 18:19:04.259 → session established 18:19:07.048 → 路由 RESUMED+窗口焦点+会话的可见日志 18:19:08.525；UI 有全屏分辨率/帧率/触点层，App topResumed，无降级通知 | crash1-* |
| 连续第二次崩溃 | dispatch 18:19:32.667 → 到达 18:19:34.188 → observed 18:19:35.671 → session 18:19:38.416 → 可见 18:19:39.852；同样 UI 与窗口证据，无多余通知 | crash2-* |
| 最近任务真实上滑 | 18:20:17.496 task removed；gate=0111792059572490，watchdog gate declined；有限观察 65 秒未重拉，持久门槛供后续 job 继续拒绝；不声称穷尽 15 分钟周期实测 | recents-ui.xml / swipe-* |
| 无会话崩溃 | 先核主界面空闲/启动按钮；崩后 25 秒无 dispatch、无 reconnect、launcher 在前台；手动启动仅主界面 | no-session-* |
| 未授权 | 未撤销用户授权；只读核当前已授权，引用旧 P23.1 真机记录。包更新后设置短暂=0，正常等待系统重绑后自行=1，再开始崩溃注入；未代开授权 | accessibility-after-install.txt / commands.log + 历史 P23.1 |

重要：A 的崩溃测试仍由真实 `input tap` 点框外空白关闭系统弹窗，随后无需点通知或手动导航即可恢复全屏。不能称「从崩溃开始零人工操作」：v16.2 正式包仍未落地自动关窗。

测试前后都实际调用 alert；结束时使用正常「启动」按钮恢复用户远控会话，末态全屏 UI=分辨率908x1024/帧率/触点，gate=101…、accessibility_enabled=1、hide_error_dialogs=null。未代输凭据，未清日志、未改用户授权或全局隐藏错误弹窗。

## B：能力探针成立，不等于完整自动触发闭环成立

原始探针日志经主会话读回：17:34:24.438 dispatchGesture accepted=true → 17:34:24.502 completed → 同时 Killing 主进程28468 → 17:34:24.519 watchdog onUnbind。没有 adb input tap 冒充手势。

- 已验证：人工发一次实验广播后，现有无障碍服务可以派发手势点掉这台 PAPER_7 的系统崩溃弹窗；实施者记录约0.97秒内窗口消失（N=5秒预先固定）。
- 未验证：App 自身在弹窗出现时自动触发该手势的闭环；跨机型可靠性；无副作用的精确弹窗限定。
- 关键时序限制：这条样本里「点击」发生在「进程被系统收掉 / watchdog」之前。因此不能把“等 watchdog 收到死亡通知再点”当作已验证可行的触发方案；可能形成“等关框才死、等死才关框”的循环依赖。正式方案须先解决提前触发和严格场景限定，不加无界轮询，不把盲点其它窗口当最小安全落地。
- 用户仅授权先验证再决定。产品未加入 canPerformGestures / dispatchGesture，探针源码与 APK 留独立 probe/；D 的失败替代路径条件未触发，没有执行 hide_error_dialogs。
- 图片人工复核尝试被视觉服务403拦住，主会话未能独立目视前后PNG；可核的派发/完成/系统移窗日志已读回，前后PNG保留给用户。未伪报独立目视检查。

## C：包体核验范围与准确口径

清单共有33个文件记录，按SHA去重为20个包体；原EasyControl主包及rlcdB为27条路径/15个不同包体，另有PIP变体1个包体。不能把33条路径称33个独立版本。

主会话重新解析已装原EasyControl、rlcdB以及官方V1.6.10 manifest：前两者各2个普通service，但无 BIND_ACCESSIBILITY_SERVICE / 无障碍服务 action；官方包0个service。对应包名/版本/完整hash见 `package-audit/REPORT.md`。已查包没有发现可由系统绑定的无障碍服务，不仅凭仓库源码推断。不能据此说用户一定混淆了包，也不能推广成所有未取得的历史/外盘归档版本都不含。

上游最新release查询已联网复核：EasyControl V1.6.10（2024-07-13），ScrcpyForAndroid v0.7.1（2026-10-14）；原始包已下载核manifest。外置盘仅有界扫描，backup归档未穷举，此覆盖缺口保留。

## 构建、范围、验证形式

最终两次 clean build 各22套件125例，零失败/错误/跳过；五APK全字节一致（不是只看解压内容）。证据 `final-review/build/result.json`、两次完整日志与真实XML；本轮原123例保留，最终加2例。

签名V2验证通过，同旧证书 SHA256 `c8c1b8b58c6f1f927293e03a1564c5666398e3442edc009fadb4e1c9cf736126`。内置server与旧包逐字节相同。范围守卫 `scripts/verify_round19_scope.py` 真跑通过，1×/触控协议/PB-1/IME/密码/UnlockJump等保护保留，StreamActivity最终与轮前逐字节相同。

执行形式如实：A/B由一个实现子代理、C由另一个调查子代理完成。申请额外独立QA被本次一次性任务子代理预算拒绝；最终小补改、构建、真机复验和自审由主会话执行，不称独立QA通过。未改代理配置或支付设置。

`stash@{0}` round17 OID始终为 `1ebff2072294bad8bb778a422eef2d3708d84307`；初始中断纪律脏文档由既有 d385b18 收存，未覆盖、未pop stash。快照：`final-review/snapshot/`。

## 停手原因、未决与下一步

A 当前最终包指定场景通过，B能力探针/C包体核实已完成；本轮授权范围收口，无WIP丢失，不是中断。B正式产品化待用户明确决定，不能把“继续推进”当作默许。用户先用已装最终包自然复验P23；有故障带时间回主对话由主Agent接手。原历史停列表问题根因、跨OEM、:a11y被回收后的重武装与会话代际竞态仍待验。
