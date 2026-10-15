#!/usr/bin/env python3
"""Source/serialized-payload invariants against v11; not an Android runtime test."""
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
    touch = JAVA + "scrcpy/TouchEventHandler.kt"
    old, new = before(touch), now(touch)
    # Remove only the new constructor parameter, gate and scaled CANCEL block.
    new = re.sub(r"    /\*\* 双指平移开关：[^\n]*\*/\n    private val panEnabled: Boolean = false,\n", "", new)
    new = new.replace("        if (!panEnabled) return false\n", "")
    cancel = re.search(r"            if \(event.actionMasked == MotionEvent.ACTION_CANCEL\) \{.*?\n            \}\n", new, re.S)
    assert cancel, "Scaled CANCEL branch missing"
    new = new[:cancel.start()] + new[cancel.end():]
    assert new == old, "Touch code changed outside the three authorized additions"
    pan = functions(now(touch), "handlePanGesture")[0]
    assert pan.index("if (scale <= 1f || isMouseLikeEvent(event)) return false") < pan.index("if (!panEnabled)") < pan.index("MotionEvent.ACTION_CANCEL")
    assert pan.index("MotionEvent.ACTION_CANCEL") < pan.index("if (!panning)")
    assert "panEnabled = false" in now(JAVA + "widgets/DeviceWidgets.kt")
    assert "panEnabled = true" in now(JAVA + "pages/FullscreenControlScreen.kt")
    print("PASS: 1x business path identical; scaled CANCEL precedes pan start; preview/fullscreen gate explicit")

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
    for path in frozen:
        path = JAVA + path
        assert before(path) == now(path), path
    print("PASS: recorder transforms, PB-1 semantics, unlock triggers, crypto, IME buffer and All-Options frozen")

    assert not (ROOT / (JAVA + "services/keepalive/BootRescheduleReceiver.kt")).exists()
    manifest = now("app/src/main/AndroidManifest.xml")
    assert "BootRescheduleReceiver" not in manifest
    assert "android.permission.RECEIVE_BOOT_COMPLETED" in manifest
    assert ".setPersisted(true)" in now(JAVA + "services/keepalive/ProcessKeepAliveScheduler.kt")
    job = now(JAVA + "services/keepalive/KeepAliveJobService.kt")
    assert job.index("AppRuntime.init(applicationContext)") < job.index("val enabled = Storage.appSettings")
    print("PASS: cold job init order; duplicate boot receiver absent; persisted-job permission retained")

    wake = now(JAVA + "services/lockwake/ControlledLockWakeController.kt")
    assert "InjectionController.inject(password, notifyBlackout = false)" in wake
    assert wake.count("notifyBlackout = false))") == 3
    assert "password.fill('\\u0000')" in now(JAVA + "password/InjectionController.kt")
    print("PASS: all automatic credential messages suppress PB-1; existing wiping entry retained")
    print("PASS: scope verification complete (source proof only; device interaction remains separately recorded)")


if __name__ == "__main__":
    check()
