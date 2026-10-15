#!/usr/bin/env python3
"""HISTORICAL SNAPSHOT (superseded by scripts/verify_round19_scope.py).

Round16 source invariants: crash-recovery auto-relaunch via an AccessibilityService.

Round19 changed the crash-recovery surface again (cross-process arrival file, `isEnabled` fallback,
four-stage logging), so this file's change-surface assertion no longer matches the tree. It is kept
as the Round16 record; the CURRENT gate is `scripts/verify_round19_scope.py` (it carries every
Round16 protection forward).

Round16 (user-directed) adds an AccessibilityService as the PRIMARY crash-recovery trigger and
routes every trigger through one shared decision/relaunch path. Only the crash-recovery wiring,
the settings guidance, the manifest, the accessibility XML and strings may move.

Still frozen (P22 and every earlier protection): the PB-1 blackout sources/tests are byte-identical
to the v15 tip, and every surface the earlier guards froze stays byte-identical to v11.

This is a source/bytes guard only: it does NOT exercise Compose, an AccessibilityManagerService
rebind, SurfaceFlinger or a real device (device evidence lives in the round16 findings directory).
"""
import hashlib
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE_V11 = "44a03b3"
BASE_ROUND16 = "dbf1f06d595183454c3bb114f87360d67272f684"
JAVA = "app/src/main/java/io/github/miuzarte/scrcpyforandroid/"
TEST = "app/src/test/java/io/github/miuzarte/scrcpyforandroid/"

PBSM = JAVA + "services/blackout/PaymentBlackoutStateMachine.kt"
READER = JAVA + "services/blackout/ControlledLockReader.kt"
COORD = JAVA + "services/blackout/PaymentBlackoutCoordinator.kt"

A11Y = JAVA + "services/recovery/CrashRecoveryAccessibilityService.kt"
WATCHDOG = JAVA + "services/recovery/RecoveryWatchdogService.kt"
CRASH = JAVA + "services/keepalive/CrashRecovery.kt"
KEEPALIVE = JAVA + "services/keepalive/ProcessKeepAliveService.kt"
SETTINGS = JAVA + "pages/SettingsScreen.kt"
MANIFEST = "app/src/main/AndroidManifest.xml"
A11Y_XML = "app/src/main/res/xml/accessibility_service_config.xml"

SERVER_ASSET = "app/src/main/assets/bin/scrcpy-server-v5.0"
SERVER_SHA256 = "26cbc9ad0aced6c2282455bef4fb43462605c1f8758c74b4ab1dbf818c229daa"

EXPECTED_MAIN_CHANGES = {
    MANIFEST,
    A11Y_XML,
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values-zh/strings.xml",
    SETTINGS,
    KEEPALIVE,
    CRASH,
    A11Y,
    WATCHDOG,
    JAVA + "pages/DeviceTabViewModel.kt",
    JAVA + "NativeCoreFacade.kt",
}
EXPECTED_TEST_CHANGES = {
    TEST + "services/keepalive/RecoveryPolicyTest.kt",
}


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


def changed_under(prefix):
    tracked = subprocess.check_output(
        ["git", "diff", "--name-only", BASE_ROUND16, "--", prefix], cwd=ROOT,
    ).decode().split()
    untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "--", prefix], cwd=ROOT,
    ).decode().split()
    return sorted(set(tracked) | set(untracked))


def check_round16_change_surface():
    main_changed = changed_under("app/src/main")
    test_changed = changed_under("app/src/test")
    assert main_changed == sorted(EXPECTED_MAIN_CHANGES), main_changed
    assert test_changed == sorted(EXPECTED_TEST_CHANGES), test_changed
    print("PASS: round16 change surface is exactly crash-recovery wiring + manifest/XML/strings/settings")


def check_pb1_frozen():
    """P22: the PB-1 blackout sources and their tests are byte-identical to the v15 tip."""
    for path in (
        PBSM, READER, COORD,
        TEST + "services/blackout/PaymentBlackoutStateMachineTest.kt",
        TEST + "services/blackout/ControlledLockReaderTest.kt",
    ):
        assert now(path) == at(BASE_ROUND16, path), path
    assert hashlib.sha256((ROOT / PBSM).read_bytes()).hexdigest() == \
        "284168335ac9a73c8bbb181e688d01f0932bfe3d90af795913da072e6176f46f"
    print("PASS: P22 PB-1 sources/tests byte-identical to v15; blackout machine untouched")


def check_accessibility_service():
    svc = now(A11Y)
    assert "class CrashRecoveryAccessibilityService : AccessibilityService()" in svc
    assert "override fun onServiceConnected()" in svc
    assert re.search(r"override fun onAccessibilityEvent\(event: AccessibilityEvent\?\) = Unit", svc)
    assert "override fun onInterrupt() = Unit" in svc
    # delegate to the single shared decision + relaunch path (primary trigger)
    assert 'CrashRecovery.tryLaunch(applicationContext, source = "accessibility")' in svc
    assert svc.count("CrashRecovery.") == 2  # tryLaunch + onUserSwipedAway
    # 划掉不拉起 must be honored by the primary trigger too (system rebinds an enabled a11y service)
    assert "CrashRecovery.onUserSwipedAway(applicationContext)" in svc
    assert "override fun onTaskRemoved(" in svc
    # enabled-state detection for the settings page ("enabled" != "connected")
    assert "fun isEnabled(context: Context): Boolean" in svc
    assert "getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)" in svc
    # the connection state is published onto the shared prefs by the :a11y process and read back
    assert "fun isConnected(context: Context): Boolean" in svc
    assert "RecoveryPrefs.A11Y_CONNECTED" in svc
    # cross-process rule: the :a11y process must never touch the DataStore
    for banned in ("Storage.", "loadBundle", "appSettings"):
        assert banned not in svc, banned
    # privacy: no event listening / screen reading / data collection declared or used
    for banned in ("getRootInActiveWindow", "getWindows(", "takeScreenshot", "canRetrieveWindowContent"):
        assert banned not in svc, banned
    print("PASS: accessibility service is the primary trigger, empty event callback, no input/screen/data")


def check_accessibility_xml_and_manifest():
    xml = now(A11Y_XML)
    assert 'android:canRetrieveWindowContent="false"' in xml
    for banned in ("accessibilityEventTypes", "accessibilityFeedbackType", "accessibilityFlags",
                   "accessibilityEventType"):
        assert banned not in xml, banned
    manifest = now(MANIFEST)
    assert "android.permission.BIND_ACCESSIBILITY_SERVICE" in manifest
    assert "android.accessibilityservice.AccessibilityService" in manifest
    assert "@xml/accessibility_service_config" in manifest
    assert ".services.recovery.CrashRecoveryAccessibilityService" in manifest
    # the a11y service AND its watchdog live in the dedicated :a11y process (survive the main crash)
    a11y_block = manifest[manifest.index(".services.recovery.CrashRecoveryAccessibilityService"):]
    a11y_block = a11y_block[:a11y_block.index("</service>")]
    assert 'android:process=":a11y"' in a11y_block
    assert ".services.recovery.RecoveryWatchdogService" in manifest
    assert manifest.count('android:process=":a11y"') == 2
    print("PASS: manifest declares BIND_ACCESSIBILITY_SERVICE + config; a11y + watchdog in :a11y")


def check_dedicated_process_watchdog():
    """The only trigger that survives a crash: a watchdog in :a11y, woken by the service lifecycle."""
    dog = now(WATCHDOG)
    assert "class RecoveryWatchdogService : Service()" in dog
    # event-driven death signal: AMS unbinds/destroys the watchdog when its client process dies;
    # NO timers, NO polling, NO binder-death token (device test: linkToDeath was silently unlinked
    # and did not fire, while the lifecycle signal did).
    assert 'recover("onUnbind")' in dog and 'recover("onDestroy")' in dog
    for banned in ("linkToDeath", "putBinder", "deathRecipient"):
        assert banned not in dog, banned
    for banned in ("Timer", "schedule(", "addOnUncaughtException", "Thread.sleep", "JobScheduler", "while (true"):
        assert banned not in dog, banned
    assert "fun arm(context: Context)" in dog and "fun disarm(context: Context)" in dog
    assert "BIND_AUTO_CREATE" in dog
    # the watchdog process being reaped must not silently cost the session its trigger
    assert "onServiceDisconnected" in dog and "private fun reArm()" in dog
    # cross-process state (device-found): SharedPreferences is NOT shared between processes, so the
    # gate must be re-read from a plain file and the dedup/cap must stay in-process.
    assert "RecoveryGate.read(applicationContext)" in dog
    assert "WatchdogRecoveryState" in dog and "state.reset()" in dog
    assert "EXTRA_SESSION_STARTED" in dog
    # it hands off to the exact same single shared path / entry
    assert "CrashRecovery.tryLaunchInWatchdog(applicationContext, source = \"watchdog\"" in dog
    # armed only for the session lifetime, from the session lifecycle
    facade = now(JAVA + "NativeCoreFacade.kt")
    started = functions(facade, "onScrcpySessionStarted")[0]
    assert "RecoveryWatchdogService.arm(AppRuntime.context)" in started
    stopped = functions(facade, "onScrcpySessionStopped")[0]
    assert "RecoveryWatchdogService.disarm(AppRuntime.context)" in stopped
    print("PASS: dedicated :a11y watchdog is the crash-surviving trigger (lifecycle signal, no polling)")


def check_shared_recovery_path():
    crash = now(CRASH)
    # one decision + one relaunch entry, reused by every trigger
    assert "internal object CrashRecoveryPolicy" in crash
    assert "fun shouldAttempt(" in crash
    for gate in ("isAbnormalExit(reason)", "exitTimestampMs > lastHandledExitMs",
                 "in 0L..RECENT_WINDOW_MS", "recentAttemptCount < MAX_ATTEMPTS"):
        assert gate in crash, gate
    assert "const val MAX_ATTEMPTS = 3" in crash
    assert "CrashRecoveryPolicy.shouldAttempt(" in crash
    # unauthorized path must degrade to a tappable notification, never claim auto-relaunch
    assert "CrashRecoveryAccessibilityService.isEnabled(context)" in crash
    # the :a11y trigger reads the cross-process gate from shared prefs, never the DataStore
    assert "CrashRecoveryPolicy.shouldConsider(" in crash
    assert "RecoveryPrefs.ENABLED_MIRROR" in crash
    assert "Storage." not in crash and "appSettings" not in crash
    # same-death dedup must hold ACROSS trigger sources (device-found: watchdog + sticky FGS each
    # counted the same crash) => the handled timestamp is shared through the cross-process file
    assert "RecoveryGate.recordHandled(" in crash and "handledExitMs" in crash
    assert "maxOf(store.lastHandledExitMs(), gate.handledExitMs)" in crash
    assert "postFallbackNotification(" in crash
    assert "accessibility not enabled; " in crash
    assert "recovery MainActivity arrival not observed" in crash
    assert "recovery launch dispatched" in crash
    assert "recovery attempt " in crash
    # one relaunch entry carrying the recovery extra
    assert crash.count(".putExtra(ProcessKeepAliveService.EXTRA_RECOVER_SESSION, true)") == 1
    # the notification fallback distinguishes post from visible, never lies about visibility
    assert "recovery notification post=" in crash and "visible=" in crash
    print("PASS: single shared decision (abnormal/expiry/cap) + relaunch, honest degraded notification")

    fg = now(KEEPALIVE)
    assert 'CrashRecovery.tryLaunch(this, source = "keepalive_service")' in fg
    assert "CrashRecovery.onUserSwipedAway(this)" in fg
    # round14 recovery-object invariants retained
    assert 'if (!p.getBoolean(RecoveryPrefs.ACTIVE, false) || p.getBoolean(RecoveryPrefs.TASK_REMOVED, false)) return null' in fg
    assert 'if (port !in 1..65535) return null' in fg
    assert fg.count(".putExtra(EXTRA_RECOVER_SESSION, true)") == 1  # the ongoing FGS notification
    print("PASS: foreground service/job backstops delegate to the same path; RecoveryIntent intact")

    settings = now(SETTINGS)
    assert "AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS" in settings
    assert "CrashRecoveryAccessibilityService.isEnabled(context)" in settings
    assert "CrashRecoveryAccessibilityService.isConnected(context)" in settings
    assert "R.string.pref_crash_recovery_notification_denied" in settings
    print("PASS: settings page detects enabled/connected, one-tap system settings, notification guidance")

    # a11y rebind after a crash must not be over-claimed (independent review, AOSP mCrashedServices);
    # the surviving trigger is the dedicated process, not a same-process rebind.
    svc = now(A11Y)
    assert "mCrashedServices" in svc and "重绑时跳过" in svc
    assert ":a11y" in svc and "独立进程" in svc
    # the gate mirror is written where the session starts, so the :a11y process sees it on disk
    assert "RecoveryPrefs.ENABLED_MIRROR" in now(KEEPALIVE)

    # success-reset: a completed restore clears the attempt budget (history must not block one recovery)
    vm = now(JAVA + "pages/DeviceTabViewModel.kt")
    restore = functions(vm, "restoreActiveSession")[0]
    assert restore.index("session start succeeded") < restore.index("remove(RecoveryPrefs.ATTEMPTS)")
    print("PASS: a11y rebind not over-claimed; successful restore resets the attempt budget")


def check():
    # --- v15 PB-1 real-panel reset (unchanged; P22 frozen) ---
    pbsm = now(PBSM)
    assert "enum class DisplayObservation { UNKNOWN, ON, OFF }" in pbsm
    assert "@Synchronized fun onDisplayObserved(obs: DisplayObservation, nowMs: Long): Result" in pbsm
    body = functions(pbsm, "onDisplayObserved")[0]
    assert 'obs == DisplayObservation.UNKNOWN -> noop("display_unknown_noop"' in body
    assert "nowMs - blackoutAtMs < DISPLAY_COMMAND_GRACE_MS" in body
    assert 'DISPLAY_COMMAND_GRACE_MS = 5_000L' in pbsm
    assert 'return noop("rewake_pending_keep", tf)' in pbsm
    coord = now(COORD)
    assert coord.count("decisionMutex.withLock {") == 2
    assert coord.count("delay(") == 1 and "private const val TICK_MS = 1_000L" in coord
    print("PASS: v15 PB-1 real-panel reset and v8 pending-commitment retained (source frozen)")

    # --- round14 protections carried over unchanged (v13 reverts / P0-1 / P1-7 / UI) ---
    touch = now(JAVA + "scrcpy/TouchEventHandler.kt")
    pan = functions(touch, "handlePanGesture")[0]
    cancel_start = pan.index("            if (event.actionMasked == MotionEvent.ACTION_CANCEL)")
    cancel_end = pan.index("            if (!panning)", cancel_start)
    cancel = pan[cancel_start:cancel_end]
    assert "handleCancelAction(calculateContentBounds(), UiMotionActions.CANCEL)" in cancel
    assert "UiMotionActions.UP" not in cancel and "panning = true" not in cancel
    assert touch.replace(cancel, "", 1) == before(JAVA + "scrcpy/TouchEventHandler.kt")
    assert now(JAVA + "widgets/DeviceWidgets.kt") == before(JAVA + "widgets/DeviceWidgets.kt")
    print("PASS: only non-1x multi-pointer CANCEL restored; all other touch/preview bytes equal v11")

    assert "suspend fun close()" in now(JAVA + "NativeCoreFacade.kt"), "P2-1 close() must be restored"
    rules = now(JAVA + "services/keepalive/ProcessKeepAliveRules.kt")
    assert "MIN_PERIODIC_INTERVAL_MS" in rules and "fun shouldSchedule(" in rules, "P2-2 must be restored"
    settings_storage = now(JAVA + "storage/Settings.kt")
    assert "fun observe(" in settings_storage and "fun asState(" in settings_storage, "P2-3"
    assert "adbMdnsLanDiscovery" in now(JAVA + "storage/AppSettings.kt"), "P2-4 must be restored"
    assert "loadBundle()" in now(KEEPALIVE), "P2-5 must be restored"
    assert "fun <T> rememberBundleState(" in now(JAVA + "storage/BundleSync.kt"), "P2-6 must be restored"
    job = now(JAVA + "services/keepalive/KeepAliveJobService.kt")
    assert job.index("AppRuntime.init(applicationContext)") < job.index("Storage.appSettings")
    assert not (ROOT / (JAVA + "services/keepalive/BootRescheduleReceiver.kt")).exists()
    manifest = now(MANIFEST)
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
    assert "LaunchedEffect(gammaPanelVisible, screenScalePanelVisible, panelTouchesDown, panelInteraction)" in page
    assert "delay(2_000)" in page
    print("PASS: round14 panel dismissal/lock behaviour and Q2/P19.8 rejection retained")

    # round14 crash-recovery target restore + fullscreen fallback retained in the view model
    facade = now(JAVA + "NativeCoreFacade.kt")
    vm = now(JAVA + "pages/DeviceTabViewModel.kt")
    restore = functions(vm, "restoreActiveSession")[0]
    assert restore.index("connectWithTimeout(") < restore.index("handleAdbConnected(") < restore.index("startScrcpySession(")
    stopped = functions(facade, "onScrcpySessionStopped")[0]
    assert stopped.index("if (!isRestarting)") < stopped.index("RecoveryIntent.clear(")
    print("PASS: crash-recovery target restore and fullscreen fallback retained")

    for name in ("injectTouch", "injectScroll", "writePosition",
                 "encodeUnsignedFixedPoint16", "encodeSignedFixedPoint16"):
        assert functions(before(JAVA + "scrcpy/Scrcpy.kt"), name) == functions(now(JAVA + "scrcpy/Scrcpy.kt"), name), name
    print("PASS: touch/scroll forwarding and serialized payload functions identical to v11")

    # --- automatic credential injection must still suppress PB-1 (v12 P0-3) ---
    wake = now(JAVA + "services/lockwake/ControlledLockWakeController.kt")
    assert "InjectionController.inject(password, notifyBlackout = false)" in wake
    assert wake.count("notifyBlackout = false))") == 3
    print("PASS: automatic credential injection still suppresses PB-1 (P0-3 mutual exclusion)")

    # --- round16: the accessibility trigger + shared path ---
    check_accessibility_service()
    check_accessibility_xml_and_manifest()
    check_dedicated_process_watchdog()
    check_shared_recovery_path()
    check_round16_change_surface()
    check_pb1_frozen()

    # --- server asset bytes unchanged ---
    assert hashlib.sha256((ROOT / SERVER_ASSET).read_bytes()).hexdigest() == SERVER_SHA256
    print("PASS: bundled scrcpy server bytes unchanged")

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
    print("PASS: Round16 scope verification complete (source proof only; device interaction separately recorded)")


if __name__ == "__main__":
    check()
