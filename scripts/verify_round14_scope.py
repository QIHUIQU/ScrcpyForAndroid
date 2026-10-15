#!/usr/bin/env python3
"""Round14 source/serialized-payload invariants against v11; not an Android runtime test.

Round14 supersedes the Round13 P19.8 video-area "dismiss gate" (that gate is deleted per the
new user decision) and instead adds: (1) a symmetric 2s idle auto-dismiss for the scale/gamma
panels, reset by any panel touch and paused while a finger is down; (2) a scale-lock toggle in
the scale panel backed by the same asBundle/DataStore source; (3) the lock key moved out of
Settings is kept there for now (removal left to the user). Except the explicitly re-restored P1-5 CANCEL block, Round13 reverts
must stay byte-identical to v11 (44a03b3); the frozen recorder/PB-1/crypto/UnlockJump/
IME/All-Options payload must stay byte-identical too. This is a source/bytes guard only: it does
NOT exercise Compose recomposition, pointer dispatch or a real device.
"""
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "44a03b3"
JAVA = "app/src/main/java/io/github/miuzarte/scrcpyforandroid/"


def before(path):
    return subprocess.check_output(["git", "show", f"{BASE}:{path}"], cwd=ROOT).decode()


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


def check():
    # Round14 re-restores P1-5 per the 2026-10-14 audit. Strip ONLY that block:
    # the rest of TouchEventHandler (including all 1x handling) must still equal v11.
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

    # --- Round13 revert of the six P2 deletions ---
    assert "suspend fun close()" in now(JAVA + "NativeCoreFacade.kt"), "P2-1 close() must be restored"
    rules = now(JAVA + "services/keepalive/ProcessKeepAliveRules.kt")
    assert "MIN_PERIODIC_INTERVAL_MS" in rules and "fun shouldSchedule(" in rules, "P2-2 must be restored"
    settings = now(JAVA + "storage/Settings.kt")
    assert "fun observe(" in settings and "fun asState(" in settings and "fun asMutableState(" in settings, "P2-3 must be restored"
    assert "adbMdnsLanDiscovery" in now(JAVA + "storage/AppSettings.kt"), "P2-4 must be restored"
    assert "loadBundle()" in now(JAVA + "services/keepalive/ProcessKeepAliveService.kt"), "P2-5 must be restored"
    assert "fun <T> rememberBundleState(" in now(JAVA + "storage/BundleSync.kt"), "P2-6 must be restored"
    print("PASS: P2-1..P2-6 deletions reverted (facade close, keepalive rules, settings APIs, LAN flag, settings reads, bundle helper)")

    # --- P0-1 must survive the P2-5 revert: runtime init precedes any Storage read ---
    job = now(JAVA + "services/keepalive/KeepAliveJobService.kt")
    assert job.index("AppRuntime.init(applicationContext)") < job.index("Storage.appSettings"), \
        "cold job must init AppRuntime before touching Storage"
    assert job.index("AppRuntime.init(applicationContext)") < job.index("loadBundle()"), \
        "runtime init must precede the restored settings read"
    assert not (ROOT / (JAVA + "services/keepalive/BootRescheduleReceiver.kt")).exists()
    manifest = now("app/src/main/AndroidManifest.xml")
    assert "BootRescheduleReceiver" not in manifest
    assert "android.permission.RECEIVE_BOOT_COMPLETED" in manifest
    assert ".setPersisted(true)" in now(JAVA + "services/keepalive/ProcessKeepAliveScheduler.kt")
    print("PASS: cold job init order kept through the P2-5 revert; duplicate boot receiver absent; persisted-job permission retained")

    # --- P1-7: a failed probe retries in place; no 3x interval wait ---
    runner = now(JAVA + "services/DeviceAdbBackgroundRunner.kt")
    assert "KEEP_ALIVE_FAILURE_THRESHOLD" not in runner, "3-consecutive-failure gate must be gone"
    assert runner.count("keepAliveCheck(host, port)") == 2, "failed probe must retry once in place"
    assert runner.index("keepAliveCheck(host, port)") < runner.index("shouldAutoReconnect()"), \
        "retry must happen before the reconnect decision"
    assert "1_500L" in now(JAVA + "pages/DeviceTabViewModel.kt"), "probe timeout must stay bounded (~1.5s)"
    print("PASS: P1-7 failed probe retried in place (<= ~1.5s extra detect delay); existing reconnect path retained")

    # --- Round14 UI: the Round13 P19.8 gate is deleted (superseded by the new user decision) ---
    page = now(JAVA + "pages/FullscreenControlScreen.kt")
    assert "PanelDismissTouchGate" not in page, "Round13 dismissal gate must be gone from the fullscreen page"
    assert "shouldConsume" not in page and "onDismissPanels" not in page, "Round13 gate hooks must be gone"
    assert "panelsVisible" not in page, "Round13 panelsVisible gate param must be gone"
    assert "video-area touch dismissed scale/gamma panel" not in page, "Round13 dismiss log must be gone"
    assert not (ROOT / "app/src/test/java/io/github/miuzarte/scrcpyforandroid/pages/PanelDismissTouchGateTest.kt").exists(), \
        "Round13 gate test must be deleted"
    assert "panEnabled" not in page, "Q2 pan gate must not be back in the fullscreen page"
    # the video-area interop filter forwards unconditionally again (no panel-visibility gate)
    assert "Modifier.pointerInteropFilter { event ->" in page and \
        "touchEventHandler.handleMotionEvent(event)" in page, "video-area touch must forward as before"
    print("PASS: Round13 P19.8 video-area dismissal gate and its test are deleted; video touch forwards unconditionally")

    # --- Round14 UI: symmetric 2s idle auto-dismiss, paused while a finger is down ---
    assert "LaunchedEffect(gammaPanelVisible, screenScalePanelVisible, panelTouchesDown, panelInteraction)" in page
    assert "delay(2_000)" in page
    dismiss = functions(page, "FullscreenControlScreen")[0]
    assert "if ((!gammaPanelVisible && !screenScalePanelVisible) || panelTouchesDown > 0) return@LaunchedEffect" in page
    assert "gammaPanelVisible = false" in dismiss and "screenScalePanelVisible = false" in dismiss
    assert page.count(".then(panelTouchObserver)") == 2, "observer belongs only on the two panels"
    assert "awaitPointerEvent(PointerEventPass.Initial).changes.count { it.pressed }" in page
    assert "panelTouchesDown += down - previousDown" in page
    assert "panelTouchesDown -= previousDown" in page, "CANCEL/removal must release held state"
    assert page.count("panelInteraction++") == 6, "touch events/finally plus four callbacks must reset idle"
    assert ".consume()" not in dismiss, "panel observer must not steal child gestures"
    print("PASS: scale/gamma panels auto-dismiss after 2s of no touch; held/dragged fingers never close them")

    # --- Round14 UI: scale-lock toggle on the same asBundle source; slider/reset gated by it ---
    assert "videoScaleLocked = !asBundle.videoScaleLocked" in page, \
        "scale panel must toggle videoScaleLocked on tap (same DataStore source)"
    assert page.count("enabled = !asBundle.videoScaleLocked") == 2, \
        "slider and reset must both be disabled while locked"
    assert "R.string.fullscreen_scale_lock" in page and "R.string.fullscreen_scale_unlock" in page, \
        "lock button label must reflect lock state"
    assert "pref_title_video_scale_locked" in now(JAVA + "pages/SettingsScreen.kt"), \
        "Settings lock key kept for now (removal left to the user)"
    for rel in ("values", "values-zh"):
        strings = now("app/src/main/res/" + rel + "/strings.xml")
        assert 'name="fullscreen_scale_lock"' in strings and 'name="fullscreen_scale_unlock"' in strings, rel
    # existing authorized P0-5 / P1-6 fixes must still be present
    assert "TransferOptions.normalizeVdSize(asBundle.transferVdSize)" in page
    assert "control channel unavailable" in page
    print("PASS: scale-lock toggle added to the scale panel (same source, slider/reset greyed); Settings key retained")

    # Crash recovery must record only active targets and reuse the existing connect/start/UI chain.
    recovery = now(JAVA + "services/keepalive/ProcessKeepAliveService.kt")
    vm = now(JAVA + "pages/DeviceTabViewModel.kt")
    main = now(JAVA + "MainActivity.kt")
    facade = now(JAVA + "NativeCoreFacade.kt")
    assert 'if (!p.getBoolean(RecoveryPrefs.ACTIVE, false) || p.getBoolean(RecoveryPrefs.TASK_REMOVED, false)) return null' in recovery
    assert 'if (port !in 1..65535) return null' in recovery
    assert recovery.count('.putExtra(EXTRA_RECOVER_SESSION, true)') == 2
    assert 'recovery MainActivity arrival not observed (possibly BAL); tap foreground notification' in recovery
    assert 'override fun onNewIntent(intent: Intent)' in main
    assert 'recoveryIntent.removeExtra(ProcessKeepAliveService.EXTRA_RECOVER_SESSION)' in main
    assert 'RecoveryIntentPolicy.requests.collect' in vm
    restore = functions(vm, 'restoreActiveSession')[0]
    assert restore.index('connectWithTimeout(') < restore.index('handleAdbConnected(') < restore.index('startScrcpySession(')
    assert 'startScrcpySession(openFullscreen = true, scrcpyProfileId = session.profileId)' in restore
    assert 'fullscreen launch failed; tap foreground notification to retry' in vm
    stopped = functions(facade, 'onScrcpySessionStopped')[0]
    assert stopped.index('if (!isRestarting)') < stopped.index('RecoveryIntent.clear(')
    assert 'RecoveryIntent.clear(this)' in recovery and 'recent.size >= 3' in recovery
    print('PASS: active target recovery and notification fallback reuse existing session/fullscreen entry; swipe/cap/BAL guards retained')

    scrcpy = JAVA + "scrcpy/Scrcpy.kt"
    for name in ("injectTouch", "injectScroll", "writePosition", "encodeUnsignedFixedPoint16", "encodeSignedFixedPoint16"):
        assert functions(before(scrcpy), name) == functions(now(scrcpy), name), name
    # Packet field order, action and coordinates remain byte-for-byte identical.
    print("PASS: touch/scroll forwarding and serialized payload functions identical to v11")

    frozen = (
        "nativecore/PersistentVideoRenderer.kt",
        "services/blackout/PaymentBlackoutStateMachine.kt",
        "services/UnlockJumpController.kt",
        "password/PasswordRepository.kt",
        "widgets/ScrcpyInputSurfaceView.kt",
        "scrcpy/ClientOptions.kt",
        "scrcpy/ServerParams.kt",
    )
    for rel in frozen:
        path = JAVA + rel
        assert before(path) == now(path), rel
    print("PASS: recorder transforms, PB-1 semantics, unlock triggers, crypto, IME buffer and All-Options frozen")

    wake = now(JAVA + "services/lockwake/ControlledLockWakeController.kt")
    assert "InjectionController.inject(password, notifyBlackout = false)" in wake
    assert wake.count("notifyBlackout = false))") == 3
    assert "password.fill('\\u0000')" in now(JAVA + "password/InjectionController.kt")
    print("PASS: all automatic credential messages suppress PB-1; existing wiping entry retained")
    print("PASS: Round14 scope verification complete (source proof only; device interaction remains separately recorded)")


if __name__ == "__main__":
    check()
