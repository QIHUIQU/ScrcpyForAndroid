#!/usr/bin/env python3
"""Round13 source/serialized-payload invariants against v11; not an Android runtime test.

Round13 reverts Q2 (78c19ad) and P1-5 (f70e426) plus the six P2 deletions, and adds the
P1-7 in-place probe retry and the FullscreenControlScreen scale/gamma panel-dismiss gate.
Everything those reverts touch must be byte-identical to v11 (44a03b3); the frozen
recorder/PB-1/crypto/UnlockJump/IME/All-Options payload must stay byte-identical too.
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
    # --- Round13 revert of Q2 + P1-5: touch/preview back to v11 byte-for-byte ---
    for rel in ("scrcpy/TouchEventHandler.kt", "widgets/DeviceWidgets.kt"):
        path = JAVA + rel
        assert now(path) == before(path), rel + " must be byte-identical to v11 after the reverts"
    assert "panEnabled" not in now(JAVA + "scrcpy/TouchEventHandler.kt")
    print("PASS: TouchEventHandler/DeviceWidgets byte-identical to v11 (Q2 pan gate and P1-5 CANCEL block removed)")

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

    # --- FullscreenControlScreen: only the authorized panel-dismiss gate (plus P0-5/P1-6) ---
    page = now(JAVA + "pages/FullscreenControlScreen.kt")
    assert "panEnabled" not in page, "Q2 pan gate must not be back in the fullscreen page"
    assert "class PanelDismissTouchGate" in page and "PanelDismissTouchGate()" in page
    assert "panelsVisible: Boolean" in page and "panelsVisible = gammaPanelVisible || screenScalePanelVisible" in page
    assert "onDismissPanels = {" in page
    assert "gammaPanelVisible = false" in page and "screenScalePanelVisible = false" in page
    assert "vdResolutionPanelVisible = false" not in page, "VD panel is out of scope for the dismiss gate"
    assert page.index("shouldConsume(") < page.index("handleMotionEvent(event)"), \
        "gate must intercept before forwarding to the touch handler"
    # existing authorized P0-5 / P1-6 fixes must still be present
    assert "TransferOptions.normalizeVdSize(asBundle.transferVdSize)" in page
    assert "control channel unavailable" in page
    print("PASS: FullscreenControlScreen gate dismisses only scale+gamma, closes both, consumes the gesture before forwarding")

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
    print("PASS: Round13 scope verification complete (source proof only; device interaction remains separately recorded)")


if __name__ == "__main__":
    check()
