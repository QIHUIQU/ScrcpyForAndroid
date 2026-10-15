# 第15轮 · PB-1「支付后不再自动黑屏」根因、修法与验证（v15）

- 轮次：第15轮（feat/eink-port-20261013，初始 HEAD `8f4bc83` = v14 tip）
- 用户原话：「候选包测试发现的 BUG：之前修复好的问题：支付场景的自动黑屏，再次失效了。」（即：手机直接用于支付后回到控制端，被控端不再自动黑屏）
- 授权范围：只修「支付后再次黑屏」；不动缩放/手势/面板/流转/保活/现有自动解锁/密码库/IME，不加依赖。

## 1. 现象与既有证据（主会话已抓，未重复全量采集）

控制端 PAPER_7 日志 `artifacts/findings-20261013/paper7-v6-logcat.txt`（持续录制，本轮不清不打断），设备时间 2026-10-15 CST，控制端 pid 9020：

```
11:36:49.745  PB-1 event=arm      state=ARMED blacked=false D=0 Tf=30
11:36:54.861  PB-1 event=rewake   state=ARMED blacked=false D=0 Tf=30
11:37:25.326 … 11:41:29.565        event=defer ×9（每约 30.5s）
11:41:43.733  PB-1 event=fire     state=ARMED blacked=true  D=0 Tf=30
（此后 20+ 分钟只有）
              PB-1 event=noop:fallback_blacked state=ARMED blacked=true   （1/s，数千次）
              PB-1 event=noop:op_blacked_noop  state=ARMED blacked=true   （数百~数千次）
```

同一会话在 11:18:16 / 11:18:28 也 fire 过；11:36:49 的 `arm`（被控端重新锁屏）才把 `blacked` 清回 false，使 11:41:43 能再 fire。**11:41:43 之后被控端一直处于「已解锁、用户在用」的状态，没有再次发生锁态变化，于是 `blacked` 再也没被清回。** 被判读为「黑屏标记与被控端真实显示状态失同步」。

## 2. 根因（代码级，逐条核实；不是时间巧合）

### 2.1 `blacked` 的完整生命周期

`services/blackout/PaymentBlackoutStateMachine.kt` 中 `blacked` 是「一回合只黑一次」的闩锁：

| 赋值 | 位置（v14 行号） |
|---|---|
| `blacked = true` 唯一入口 | `applyBlackout()`（`fire` / `fallback` 都走它） |
| `blacked = false` | `onControlledLocked()`（LOCKED 观察）、`onControlledRewake()`（非 PENDING 的 UNLOCKED 观察）、`onSessionLost()`、`onControlOp()` 里经 `applyBlackout` 前不涉及 |
| 读 | `onControlOp()` 的 `if (state != ARMED || blacked) → noop:op_blacked_noop`；`onTick()` 的 `if (blacked) → noop:fallback_blacked` |

**唯一的清 false 信号 = 锁态观察**，而观察来自 `PaymentBlackoutCoordinator.pollLoop()` 每 5 tick（≈5s）一次的 `dumpsys window policy` 的 `showing=`，并且**只在 `state != lastDispatched`（状态发生跳变）时才派发事件**（去抖）。`readLockState()` 失败一律 UNKNOWN、不动作。

⇒ 结论：`blacked` 的可复位性完全挂在「被控端锁态发生 LOCKED↔UNLOCKED 跳变」上。**用户直接用手机（支付）时手机本来就是解锁的、屏幕本来就点亮的**；这次使用不产生任何锁态跳变，于是闩锁永久卡住，之后每一次控制端操作都是 `op_blacked_noop`、每一次兜底检查都是 `fallback_blacked`，功能在整个会话里彻底失效。这就是用户看到的「回到控制端不再自动黑屏」。

### 2.2 为什么「外部手机点亮/使用」完全没被观察

全仓（v14）`services/blackout/*` 只读一个信号：`dumpsys window policy` 的 `showing=`。**没有任何路径读被控端真实显示状态**；`ControlledLockWake`（锁屏唤醒）只在「控制端解锁」时触发，与本节无关。因此缺的正是这条信号。设备实测（见 §5）证实：vivo 折叠屏真实面板电源只在 `dumpsys SurfaceFlinger --displays` 的 `powerMode` 上变化——`dumpsys power` 的 `mWakefulness`、`dumpsys display` 的 `DisplayDeviceInfo.state`、`screencap` 在 PB-1 黑屏期间**全都保持「亮」**（与 round10 记录一致），所以只有 SF 面板电源可用。

### 2.3 与 v13→v14 改动的关系（用户说「之前修好的问题再次失效」，需排除回归）

逐条核对 `git log/diff a560eeb..102edb9 -- app/src/main`（v13→v14 全部改动）：

```
NativeCoreFacade.kt / MainActivity.kt / DeviceTabViewModel.kt / FullscreenControlScreen.kt
TouchEventHandler.kt / ProcessKeepAliveService.kt / strings(.xml, values-zh)
```
**`services/blackout/**` 与 `scrcpy/Scrcpy.kt` 一行都没动**（v14 只做：删自创 PanelDismissTouchGate 改 2 秒交互收起；re-restore P1-5 多指 CANCEL；缩放锁键；崩溃后回远控）。`onControlOp()` 的调用者（`injectTouch/injectKeycode/injectText/injectScroll`）与 arm/defer 判定在 v13→v14 逐函数相同。**⇒ 不是 v14 引入的回归**：这是 PB-1 状态机自 v8 起就存在的同一处缺口（v8 只修了「PENDING 被 rewake 取消」，协议里当时的语义是「新回合=锁态跳变」，用真实设备一测就暴露）。

### 2.4 与 v8 / v12 两处旧修的关系（同一处状态机，同处收口，不叠补丁）

- v8 `4cbcf2e`：PENDING 期间 `rewake` 只 `noop:rewake_pending_keep`（唯一取消=session_lost）。本轮**保留**，单测继续覆盖。
- v12 `922587c`(P0-3)：自动解锁注入走 `notifyBlackout = false`，PB-1 不被系统注入误触发。本轮**保留**，范围守卫逐条断言。
- 本轮不新增第二套状态机/第二套定时器：缺口就在同一个 `blacked` 闩锁上，直接在共享状态机 + 现有采样路径收口。

## 3. 修法（最小影响面）

**语义**：`blacked` 是「本回合已黑屏」的闩锁；一回合结束的判据从「只是锁态跳变」扩为「被控端真实面板重新点亮（用户又用了手机）」。黑屏是真黑（SF 面板关），所以真实面板就是最权威、也唯一可观测的信号。

1. `services/blackout/ControlledLockReader.kt`：新增只读命令与解析器
   - `DISPLAY_POWER_CMD = "dumpsys SurfaceFlinger --displays"`
   - `parseDisplayPower()` → `DisplayObservation{UNKNOWN, ON, OFF}`：
     - 只统计**物理显示块内**的 `powerMode=`（`Virtual Display` 块——scrcpy/厂商虚拟屏——一律忽略）；
     - 全部物理显示 `Off` ⇒ `OFF`；任一物理显示非 Off ⇒ `ON`；一行都没解析到/输出形状不认识 ⇒ `UNKNOWN`。
     - 多物理显示（折叠屏内外屏）按「保守」处理：拿不准就是 UNKNOWN，调用方绝不动作。
2. `services/blackout/PaymentBlackoutStateMachine.kt`：新增事件 `onDisplayObserved(obs, nowMs)` 与 `blackoutConfirmed` 标志
   - `blacked=false` 时：面板采样完全惰性（`noop:display_on_noop` / `display_off_noop`），不 arm、不 defer、不 fire；
   - `blacked=true` 且采样 `OFF` ⇒ `blackout_confirmed`（黑屏确实生效）；
   - `blacked=true`、已确认、采样 `ON` ⇒ **`rewake_display_on`：清闩锁**（=回合结束，下一次操作可再次黑屏），并像 rewake 一样重启兜底计时；
   - `blacked=true`、**尚未**确认、采样 `ON` ⇒ `noop:display_on_preconfirm_noop`——这是「命令已下发但面板还没关」的**陈旧/竞态样本**（`fire` 与真正的 SF 关屏之间约 35ms），**绝不清闩锁**，否则每次操作都会重复关屏；
   - `UNKNOWN` ⇒ 一律 `noop:display_unknown_noop`，绝不复位、绝不重复关屏；
   - `blackoutConfirmed` 在 `applyBlackout()`/`onControlledLocked()`/`onControlledRewake()`/`onSessionLost()` 全部清零（会话代际 = 新机器，新回合必须重新确认）。
3. `services/blackout/PaymentBlackoutCoordinator.kt`：复用**既有**采样路径，不新增定时器/抽象
   - 5s 轮询里：`if (m.blacked()) onDisplayObserved(readDisplayObservation())`（只在闩锁期内读，正常路径零额外开销）；
   - 操作路径 `dispatchControlOp()`：闩锁期内先采一次面板，再走原来的锁态补检与 `onControlOp` —— 保证「回到控制端」的第一下操作就能立刻再次黑屏，不必等下一次轮询；
   - `readDisplayObservation()` 失败 ⇒ UNKNOWN（同锁态 reader 的降级口径）；`noop:blackout_off_still` 加入静默名单（避免 1/s 级日志噪声）。

改动面（`git diff --name-only 8f4bc83 -- app/src`）**恰好 3 个源文件 + 2 个测试文件**，由守卫脚本断言。

**已知边界（如实）**：若某设备的面板从关到开都在同一个 5s 采样窗口内完成、且此前从未采到过 `OFF`，则本轮不会复位（与修复前同效，不引入新行为）；若该机型只让**被镜像**的那块物理屏灭、另一块物理屏始终亮，则「全部物理屏 Off」永不成立 ⇒ 同样保持保守不复位。两者都只会「少复原」、不会「乱关屏」。

## 4. 单测（先红后绿）

- 新增 6 例 `PaymentBlackoutStateMachineTest`：`displayBackOnAfterConfirmedBlackoutFiresAgain`（核心：fire→真 off→外部 on→再操作→再 fire）、`opWhilePanelStillOffDoesNotFireAgain`（未变 off 不得重复 fire）、`panelOnBeforeBlackoutWasSeenOffNeverResets`（陈旧样本不得误复位）、`unknownDisplayObservationNeverResets`（未知采样不重置）、`displayObservationsAreInertWhileNotBlacked`、`sessionLostClearsBlackoutConfirmation`（会话代际）+ `pendingCommitmentAndDisplaySamplesInteractSafely`（v8 PENDING 承诺不回归，同时喂面板采样）。
- 新增 2 例 `ControlledLockReaderTest`：真实 vivo `SurfaceFlinger --displays` 形状（内屏 2480x2200 + 外屏 1172x2748 + scrcpy 虚拟屏）的物理/虚拟屏区分与保守降级。
- **先红后绿**：把 `onDisplayObserved` 临时替换为修复前的等价桩（`noop("display_stub_noop")`）后跑 blackout 用例 ⇒ **6 例 ComparisonFailure（真 FAIL）**；恢复真实实现 ⇒ 20/20 状态机例 + 4/4 reader 例全绿。证据 `findings-20261014-round15/redgreen/{red-run.log,red-xml/,green-run.log,green-xml/}`（红桩替换当场回滚，未留在提交里）。
- 不把源码不变量/纯逻辑测试冒称 MotionEvent/Compose/Android 端到端验证。

## 5. 真机 A/B（同一套脚本、同样步骤；v14 复现 → v15 通过）

脚本 `findings-20261014-round15/ab-run.sh`（同一份脚本先后在 v14 与 v15 上跑）：

```
[A] 锁被控端 (input keyevent 26)              => arm
[B] 唤醒被控端 (input keyevent 224)           => rewake
[C] 控制端点视频区 (input tap) = CONTROL_OP#1  => fire + server "Device display turned off"
[D] 等一次采样（黑屏确认）
[E] 外部唤醒（keyevent 26,26，锁态不变）        => 物理面板 powerMode 回到 On
[F] 等一次 5s 轮询
[G] 控制端再点 = CONTROL_OP#2                  => v14 无反应 / v15 再次黑屏
```

| | v14（`aacfc2b2`） | v15（`2c0e5aae`） |
|---|---|---|
| 装包回读 base.apk sha256 | `aacfc2b2…` 一致 | `2c0e5aae…` 一致 |
| arm | 12:23:42.512 | 12:26:44.194 |
| fire#1 | 12:24:01.018 + server `Device display turned off` 12:24:01.039 | 12:27:02.165 + server `Device display turned off` |
| 外部唤醒后面板 | `4630946685006800004 powerMode=On`（showing 不变） | 同（showing 不变） |
| 面板信号事件 | 无（v14 无该路径） | **12:27:20.489 `rewake_display_on state=ARMED blacked=false`** |
| CONTROL_OP#2 | **12:24:29.317 `noop:op_blacked_noop`（没有再次黑屏 = 复现 BUG）** | **12:27:21.786 `fire` + server `Device display turned off` 12:27:21.818（再次黑屏 = 修复）** |

判据（每步都留了命令输出与日志片段，见 `findings-20261014-round15/ab-v14.log`、`ab-v15b.log`、`ab-v15-verify.log`、`v14-repro-pb1-log.txt`）：
- 黑屏权威 = `dumpsys SurfaceFlinger --displays` 的物理 `powerMode=Off` + server 日志 `Device display turned off`；
- `dumpsys power mWakefulness`、`dumpsys display` 的 `state`、`screencap` 同时留档，但**不作为黑屏判据**（黑屏期间它们都还是「亮」）；
- 至少两个外部唤醒回合都验证通过（`ab-v15-verify.log`：12:22:03.340 `rewake_display_on`→12:22:11.245 `fire`；12:22:34.365 `rewake_display_on`→12:22:44.029 `fire`；另有 `ab-v15b.log` 12:27:20.489→12:27:21.786）。
- 全程无真实支付、无账号数据、无凭据输入；「本地使用」用物理面板点亮（power 键）模拟，未绕过任何真安全锁（操作员从未输入 PIN/密码）。

## 6. 构建与守卫

- `export JAVA_HOME=$(/usr/libexec/java_home -v 21) ANDROID_HOME=/opt/homebrew/share/android-commandlinetools; ./gradlew :app:clean :app:assembleDebug :app:testDebugUnitTest` **两次**，均 BUILD SUCCESSFUL；每次 22 套件 **117 例** 0 失败/错误/跳过；五个 APK 逐字节一致；universal `2c0e5aae…` / 49,107,281 B。
- 与 v14 XML 比对：旧 108 例全保留、**删除 0**、新增 9 例（7 状态机 + 2 reader）。测试数/结果无虚报，原始 XML 在 `clean1/test-results`、`clean2/test-results`。
- 签名 V2 通过、证书 SHA256 `c8c1b8b5…`（同 v13/v12/v11）；内置 server `26cbc9ad…` 字节不变；包名/versionCode/targetSdk 不变。
- 守卫：`scripts/verify_round15_scope.py`（取代 `verify_round14_scope.py`，旧守卫保留在 git 历史）。**不是删保护**：Round13 reverts / P0-1 冷启动 / P1-7 原地复探 / Round14 面板与恢复行为 / 1× 触控与序列化 / 录像·UnlockJump·crypto·IME·All-Options 冻结全部继承，并新增本轮断言：
  - `app/src` 改动面**恰为** 3 个 blackout 源文件（其余任何文件多改一行即失败）；
  - PB-1 状态机相对 v14 **只差**授权的那段（把授权 delta 逐段剥离后必须与 v14 字节相同）；
  - 协调器相对 v14 只差授权采样、且**无新增定时器**（`delay(` 仍 1 处、`TICK_MS` 未变）；
  - `blacked` 复位必须「先见 Off 再 Off→On」；v8 `rewake_pending_keep` 与 P0-3 `notifyBlackout=false`（3 处）仍在。

## 7. 交付物

- 候选包：`~/Documents/scrcpy-port-artifacts/ScrcpyForAndroid-EinkPort-candidate.apk`（v15 universal，`2c0e5aae…`）+ `SHA256SUMS` + `BUILD-INFO.md`。
- v14 四件套原字节留档：`rollback/ScrcpyForAndroid-EinkPort-v14-aacfc2b2.apk`、`BUILD-INFO-v14.md`、`SHA256SUMS-v14`、`验收清单-v14.md`（`cmp` 逐字节一致）。
- 验收：`验收清单.md` 新增 **P22**；`功能与修复总清单.md` 登记 B14。
- 证据：`findings-20261014-round15/`（clean1/clean2 五包与 XML、build-verification.json、testcase-comparison.json、device-verification.json、redgreen/、ab-*.log、install-v15.log、apk-signature.txt、apk-manifest.txt、scope-verification.log、docs-check.log、final-verification.json）。
- 机器可读收口：`findings-20261014-round15/final-verification.json`。

## 8. 未验证 / 未做（如实）

- 未做真实支付，未接触任何账号/支付页面；「本地使用」以物理面板点亮模拟。
- 未能复现「被控端真解锁后本地使用」的完整形态：该 vivo 的解锁依赖用户自己的 PIN/手表信任，操作员不输入凭据；本轮用「物理点亮面板 + 锁态不变」的等价信号验证。
- 真机上只验证了用户当前 `D=0`；`D>0` 的新路径只有单测覆盖。
- 折叠态切换（内外屏互换）期间闩锁期的行为未在真机验证；多物理屏保守策略（不确认即不复位）可能让部分机型的复位不生效（只会少复位）。
- 会话代际竞态、命令下发/关屏采样竞态只有单测覆盖，未在真机主动制造。
- 未顺手修任何其它问题（本轮零额外改动，守卫已断言改动面）。
