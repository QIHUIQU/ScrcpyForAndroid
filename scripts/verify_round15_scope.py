#!/usr/bin/env python3
"""Round15 source invariants: PB-1 blackout-latch reset by the controlled device's real panel power.

Round15 is an explicitly authorized PB-1 change (the round14 guard froze
`services/blackout/PaymentBlackoutStateMachine.kt` byte-identical to v11; that freeze is lifted
here for exactly the authorized delta and for nothing else). Everything the earlier guards froze
stays frozen, and the change surface of `app/src/main` + `app/src/test` against the v14 tip
(8f4bc83) must be exactly the expected file set.

This is a source/bytes guard only: it does NOT exercise Compose recomposition, pointer dispatch,
SurfaceFlinger or a real device (device evidence lives in the round15 findings directory).
"""
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE_V11 = "44a03b3"
BASE_V14 = "8f4bc83"
JAVA = "app/src/main/java/io/github/miuzarte/scrcpyforandroid/"
TEST = "app/src/test/java/io/github/miuzarte/scrcpyforandroid/"

PBSM = JAVA + "services/blackout/PaymentBlackoutStateMachine.kt"
READER = JAVA + "services/blackout/ControlledLockReader.kt"
COORD = JAVA + "services/blackout/PaymentBlackoutCoordinator.kt"


def at(rev, path):
    return subprocess.check_output(["git", "show", f"{rev}:{path}"], cwd=ROOT).decode()


def before(path, rev=BASE_V11):
    return at(rev, path)


def now(path):
    return (ROOT / path).read_text()


def functions(text, name):
    found = []
    for match in re.finditer(r"\bfun " + name + r"\(", text):
        opening = text.index("{", match.end())
        depth = 1
        end = opening + 1
        while depth:
            depth += (text[end] == "{") - (text[end] == "}")
            end += 1
        found.append(text[match.start():end])
    return found


def strip(text, snippet, count=1, label=""):
    """Remove `count` exact copies of snippet; returns the remainder."""
    assert text.count(snippet) == count, f"{label}: expected {count} copies, found {text.count(snippet)}"
    return text.replace(snippet, "", count)


def check_change_surface():
    """app/src/main + app/src/test may change ONLY in the three authorized blackout files (+tests)."""
    main_changed = subprocess.check_output(
        ["git", "diff", "--name-only", BASE_V14, "--", "app/src/main"], cwd=ROOT,
    ).decode().split()
    test_changed = subprocess.check_output(
        ["git", "diff", "--name-only", BASE_V14, "--", "app/src/test"], cwd=ROOT,
    ).decode().split()
    assert sorted(main_changed) == sorted([PBSM, READER, COORD]), main_changed
    assert sorted(test_changed) == sorted([
        "app/src/test/java/io/github/miuzarte/scrcpyforandroid/services/blackout/PaymentBlackoutStateMachineTest.kt",
        "app/src/test/java/io/github/miuzarte/scrcpyforandroid/services/blackout/ControlledLockReaderTest.kt",
    ]), test_changed
    print("PASS: round15 change surface is exactly the three blackout sources + two blackout tests")


def check_pb1_delta():
    """Only the authorized display-observation delta differs from v14; nothing else."""
    v14 = before(PBSM, BASE_V14)
    text = now(PBSM)
    text = strip(text, """    /** Controlled device's real panel power (SurfaceFlinger `powerMode`), see [onDisplayObserved]. */
    enum class DisplayObservation { UNKNOWN, ON, OFF }

""", label="enum")
    text = strip(text, "\n *\n * v15 boundary: the [blacked] gate is also cleared by the controlled device's REAL panel power\n"
                        " * (see [onDisplayObserved]) — a lock-state observation alone left it stuck whenever the user used\n"
                        " * the phone directly and left it unlocked (the payment flow), silently killing every later\n"
                        " * blackout for the rest of the session.", label="kdoc")
    text = strip(text, "    private var blackoutConfirmed = false\n", label="field")
    text = strip(text, "\n        blackoutConfirmed = false", 4, label="resets")
    start = text.index("    /**\n     * DISPLAY: the controlled device's real panel power")
    end = text.index("    /** CONTROL_OP:")
    text = text[:start] + text[end:]
    assert text == v14, "PaymentBlackoutStateMachine.kt changed beyond the authorized display-observation delta"
    print("PASS: PB-1 state machine differs from v14 only by the authorized panel-power delta")

    coord = now(COORD)
    coord = strip(coord, """        // A latched blackout may already be over: the user used the phone directly and brought the
        // panel back. Sample the real panel power before the op so the "returned to the control
        // side" operation can black out again right away instead of waiting for the next poll.
        if (m.blacked()) {
            applyResult(m.onDisplayObserved(readDisplayObservation(), now))
        }
""", label="op-sample")
    coord = strip(coord, """                // While a blackout is latched, watch the real panel power (same poll, no new timer):
                // an external wake must clear the latch, otherwise the blackout never comes back.
                if (m.blacked()) {
                    applyResult(m.onDisplayObserved(readDisplayObservation(), now))
                }
""", label="poll-sample")
    coord = strip(coord, """    private suspend fun readDisplayObservation(): PaymentBlackoutStateMachine.DisplayObservation {
        return try {
            ControlledLockReader.parseDisplayPower(
                NativeAdbService.shell(ControlledLockReader.DISPLAY_POWER_CMD),
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            PaymentBlackoutStateMachine.DisplayObservation.UNKNOWN
        }
    }

""", label="reader-helper")
    coord = strip(coord, ' && event != "noop:blackout_off_still"', label="quiet-list")
    assert coord == before(COORD, BASE_V14), "coordinator changed beyond the authorized sampling delta"
    reader = now(READER)
    # the display parser itself is the authorized new capability; assert the rest of the reader is intact
    assert "fun parse(output: String?): PaymentBlackoutStateMachine.LockObservation" in reader
    assert "showing=(true|false)" in reader
    print("PASS: coordinator differs from v14 only by the authorized panel sampling; lock reader intact")


def check():
    # --- round14 protections carried over unchanged (v13 reverts / P0-1 / P1-7 / UI / recovery) ---
    touch = now(JAVA + "scrcpy/TouchEventHandler.kt")
    pan = functions(touch, "handlePanGesture")[0]
    cancel_start = pan.index("            if (event.actionMasked == MotionEvent.ACTION_CANCEL)")
    cancel_end = pan.index("            if (!panning)", cancel_start)
    cancel = pan[cancel_start:cancel_end]
    assert "handleCancelAction(calculateContentBounds(), UiMotionActions.CANCEL)" in cancel
    assert cancel.index("panning = false") < cancel.index("previousCentroid = null") < cancel.index("return true")
    assert "UiMotionActions.UP" not in cancel and "panning = true" not in cancel
    assert touch.replace(cancel, "", 1) == before(JAVA + "scrcpy/TouchEventHandler.kt")
    assert now(JAVA + "widgets/DeviceWidgets.kt") == before(JAVA + "widgets/DeviceWidgets.kt")
    assert "panEnabled" not in touch
    print("PASS: only non-1x multi-pointer CANCEL restored; all other touch/preview bytes equal v11")

    assert "suspend fun close()" in now(JAVA + "NativeCoreFacade.kt"), "P2-1 close() must be restored"
    rules = now(JAVA + "services/keepalive/ProcessKeepAliveRules.kt")
    assert "MIN_PERIODIC_INTERVAL_MS" in rules and "fun shouldSchedule(" in rules, "P2-2 must be restored"
    settings = now(JAVA + "storage/Settings.kt")
    assert "fun observe(" in settings and "fun asState(" in settings and "fun asMutableState(" in settings, "P2-3"
    assert "adbMdnsLanDiscovery" in now(JAVA + "storage/AppSettings.kt"), "P2-4 must be restored"
    assert "loadBundle()" in now(JAVA + "services/keepalive/ProcessKeepAliveService.kt"), "P2-5 must be restored"
    assert "fun <T> rememberBundleState(" in now(JAVA + "storage/BundleSync.kt"), "P2-6 must be restored"
    job = now(JAVA + "services/keepalive/KeepAliveJobService.kt")
    assert job.index("AppRuntime.init(applicationContext)") < job.index("Storage.appSettings")
    assert job.index("AppRuntime.init(applicationContext)") < job.index("loadBundle()")
    assert not (ROOT / (JAVA + "services/keepalive/BootRescheduleReceiver.kt")).exists()
    manifest = now("app/src/main/AndroidManifest.xml")
    assert "BootRescheduleReceiver" not in manifest
    assert "android.permission.RECEIVE_BOOT_COMPLETED" in manifest
    assert ".setPersisted(true)" in now(JAVA + "services/keepalive/ProcessKeepAliveScheduler.kt")
    runner = now(JAVA + "services/DeviceAdbBackgroundRunner.kt")
    assert "KEEP_ALIVE_FAILURE_THRESHOLD" not in runner
    assert runner.count("keepAliveCheck(host, port)") == 2
    assert "1_500L" in now(JAVA + "pages/DeviceTabViewModel.kt")
    print("PASS: P2 reverts, cold-job init order, P1-7 in-place retry and boot permission retained")

    page = now(JAVA + "pages/FullscreenControlScreen.kt")
    assert "PanelDismissTouchGate" not in page
    assert "shouldConsume" not in page and "onDismissPanels" not in page and "panelsVisible" not in page
    assert "panEnabled" not in page
    assert "LaunchedEffect(gammaPanelVisible, screenScalePanelVisible, panelTouchesDown, panelInteraction)" in page
    assert "delay(2_000)" in page
    assert "videoScaleLocked = !asBundle.videoScaleLocked" in page
    print("PASS: round14 panel dismissal/lock behaviour and Q2/P19.8 rejection retained")

    facade = now(JAVA + "NativeCoreFacade.kt")
    vm = now(JAVA + "pages/DeviceTabViewModel.kt")
    recovery = now(JAVA + "services/keepalive/ProcessKeepAliveService.kt")
    assert 'if (!p.getBoolean(RecoveryPrefs.ACTIVE, false) || p.getBoolean(RecoveryPrefs.TASK_REMOVED, false)) return null' in recovery
    assert 'if (port !in 1..65535) return null' in recovery
    assert recovery.count('.putExtra(EXTRA_RECOVER_SESSION, true)') == 2
    restore = functions(vm, 'restoreActiveSession')[0]
    assert restore.index('connectWithTimeout(') < restore.index('handleAdbConnected(') < restore.index('startScrcpySession(')
    stopped = functions(facade, 'onScrcpySessionStopped')[0]
    assert stopped.index('if (!isRestarting)') < stopped.index('RecoveryIntent.clear(')
    print("PASS: round14 crash-recovery target restore and fullscreen fallback retained")

    for name in ("injectTouch", "injectScroll", "writePosition", "encodeUnsignedFixedPoint16", "encodeSignedFixedPoint16"):
        assert functions(before(JAVA + "scrcpy/Scrcpy.kt"), name) == functions(now(JAVA + "scrcpy/Scrcpy.kt"), name), name
    print("PASS: touch/scroll forwarding and serialized payload functions identical to v11")

    # --- v15: PB-1 latch reset by the real panel power (explicitly authorized this round) ---
    pbsm = now(PBSM)
    assert "enum class DisplayObservation { UNKNOWN, ON, OFF }" in pbsm
    assert "@Synchronized fun onDisplayObserved(obs: DisplayObservation, nowMs: Long): Result" in pbsm
    body = functions(pbsm, "onDisplayObserved")[0]
    assert 'obs == DisplayObservation.UNKNOWN -> noop("display_unknown_noop"' in body
    assert "!blackoutConfirmed -> noop(\"display_on_preconfirm_noop\"" in body
    assert body.index("blacked = false") < body.index('result(EMPTY, "rewake_display_on"')
    assert "blackoutConfirmed = true" in body and 'result(EMPTY, "blackout_confirmed"' in body
    # the reset must only follow a *confirmed* blackout: an ON before any OFF can never clear the latch
    assert body.index("!blackoutConfirmed ->") < body.index("blacked = false")
    assert pbsm.count("blackoutConfirmed = false") == 6  # field + 5 reset/transition sites
    # v8 commitment kept: PENDING rewake is still a no-op, only session_lost cancels
    assert 'return noop("rewake_pending_keep", tf)' in pbsm
    assert "state = State.IDLE" in functions(pbsm, "onSessionLost")[0]
    assert PaymentBlackoutStateMachineFrozen()
    print("PASS: PB-1 blacked latch resets on the real panel (off-confirmed) only; v8 commitment kept")

    coord = now(COORD)
    assert "if (m.blacked()) {\n            applyResult(m.onDisplayObserved(readDisplayObservation(), now))" in coord
    assert "ControlledLockReader.parseDisplayPower" in coord
    assert coord.count("readDisplayObservation()") == 3  # definition + op-time + poll-time
    # no new timer: the sampling rides on the existing 1s tick / 5-tick poll
    assert coord.count("delay(") == 1 and "private const val TICK_MS = 1_000L" in coord
    reader = now(READER)
    assert 'const val DISPLAY_POWER_CMD = "dumpsys SurfaceFlinger --displays"' in reader
    assert "fun parseDisplayPower(output: String?): PaymentBlackoutStateMachine.DisplayObservation" in reader
    assert "VIRTUAL_DISPLAY_HEADER" in reader and "inPhysicalBlock" in reader
    print("PASS: coordinator samples the panel on the existing poll/op path (no new timer, no new abstraction)")

    # --- automatic credential injection must still suppress PB-1 (v12 P0-3) ---
    wake = now(JAVA + "services/lockwake/ControlledLockWakeController.kt")
    assert "InjectionController.inject(password, notifyBlackout = false)" in wake
    assert wake.count("notifyBlackout = false))") == 3
    assert "password.fill('\\u0000')" in now(JAVA + "password/InjectionController.kt")
    print("PASS: all automatic credential messages still suppress PB-1 (P0-3 mutual exclusion)")

    check_change_surface()
    check_pb1_delta()

    # --- everything else the earlier guards froze stays byte-identical to v11 ---
    frozen = (
        "nativecore/PersistentVideoRenderer.kt",
        "services/UnlockJumpController.kt",
        "password/PasswordRepository.kt",
        "widgets/ScrcpyInputSurfaceView.kt",
        "scrcpy/ClientOptions.kt",
        "scrcpy/ServerParams.kt",
    )
    for rel in frozen:
        path = JAVA + rel
        assert before(path) == now(path), rel
    print("PASS: recorder transforms, unlock triggers, crypto, IME buffer and All-Options frozen")
    print("PASS: Round15 scope verification complete (source proof only; device interaction separately recorded)")


def PaymentBlackoutStateMachineFrozen():
    """The only mid-file body changed in the state machine is the authorized event itself."""
    pbsm = now(PBSM)
    for name in ("onControlledLocked", "onControlledRewake", "onControlOp", "onTick", "onSessionLost"):
        new_body = functions(pbsm, name)[0]
        old_body = functions(before(PBSM, BASE_V14), name)[0]
        if new_body != old_body:
            # only the confirmation reset may be added inside these transitions
            assert new_body.replace("        blackoutConfirmed = false\n", "", 1) == old_body, name
    return True


if __name__ == "__main__":
    check()
