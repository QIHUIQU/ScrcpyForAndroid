package io.github.miuzarte.scrcpyforandroid.scrcpy

/**
 * DisplayRotationReader
 *
 * Read-only observation of a display's rotation on the controlled device over the existing
 * session adb shell channel. Used by [Scrcpy.rotateDevice]'s fallback: some devices (e.g. vivo
 * foldables — the inner screen ships with `fixed-to-user-rotation=disabled`) accept the
 * TYPE_ROTATE_DEVICE control message but silently ignore the user-rotation change, so the
 * caller verifies whether the rotation actually moved before engaging a workaround.
 *
 * Parses `dumpsys window displays`, which prints one block per display:
 *
 * ```
 *   Display: mDisplayId=0 (organized)
 *   overrideConfig={... mDisplayRotation=ROTATION_90 ... mRotation=ROTATION_90 ...}
 *   ...
 *     mRotation=1 mDeferredRotationPauseCount=0
 * ```
 *
 * Only the standalone `mRotation=<digit>` field counts; the `ROTATION_x` spellings inside
 * overrideConfig lines are not a rotation value. Anything missing or unparseable is null and
 * callers must not act on it.
 */
object DisplayRotationReader {
    /** Read-only query run over the existing session adb shell channel. */
    const val READ_CMD = "dumpsys window displays"

    // Block header, e.g. "  Display: mDisplayId=0 (organized)".
    private val BLOCK_START = Regex("""^\s*Display: mDisplayId=(\d+)(?:\s|\(|$)""")

    // Standalone rotation field, e.g. "    mRotation=1 mDeferredRotationPauseCount=0".
    private val ROTATION = Regex("""(?:^|\s)mRotation=(\d)(?:\s|$)""")

    /** @return rotation (0..3) of [displayId], or null when absent/unparseable. */
    fun parseRotation(output: String?, displayId: Int = 0): Int? {
        if (output.isNullOrEmpty()) return null
        var inBlock = false
        for (line in output.lineSequence()) {
            val header = BLOCK_START.find(line)
            if (header != null) {
                inBlock = header.groupValues[1] == displayId.toString()
                continue
            }
            if (!inBlock) continue
            val match = ROTATION.find(line) ?: continue
            val value = match.groupValues[1].toInt()
            if (value in 0..3) return value
        }
        return null
    }
}
