package io.github.miuzarte.scrcpyforandroid.services.lockwake

/**
 * 被控端锁屏态检测 ⇒ 自动唤醒（+条件解锁）——原项目 EasyControl R16-P15/P18/P20
 * (ControlledLockWake v3) 的 Kotlin 移植（纯逻辑，JVM-testable）。
 *
 * 三段口径（与原项目写死口径一致）：
 *  - 检测 = 只读 `dumpsys window policy`（showing= / secure= / mSimSecure=，须在
 *    KeyguardServiceDelegate 行之后）+ `dumpsys trust`（deviceLocked= / trustState= /
 *    strongAuthRequired=）；取数失败 / 无命中一律 UNKNOWN ⇒ 不动作；
 *  - 唤醒 = 既有控制消息 7 的 STATE_ON（scrcpy setDisplayPower(on=true)）；唤醒未送达 ⇒ 不 dismiss；
 *  - 解锁 = `wm dismiss-keyguard`（单次、条件、非重试）；仅当判据 v3 认定无"真安全锁"。
 *
 * dismiss 判据 v3 真值表（原项目 R16-P20 写死）：
 *   sent==0                                                          ⇒ NONE(SENT_ZERO)
 *   样本为空                                                          ⇒ NONE(V2_CONSERVATIVE)
 *   任一样本有真安全锁（deviceLocked=1 ∨ mSimSecure=true ∨ strongAuth≠0x0）⇒ NONE(REAL_SECURE)
 *   两样本决定性六字段不一致                                            ⇒ NONE(V3_INCONSISTENT)
 *   state != LOCKED                                                   ⇒ NONE(NOT_LOCKED)
 *   LOCKED ∧ secure=false                                             ⇒ DISMISS(AUTHORIZED)
 *   LOCKED ∧ secure=true ∧ deviceLocked=0 ∧ mSimSecure=false
 *           ∧ trustState=TRUSTED ∧ strongAuthRequired=0x0             ⇒ DISMISS(AUTHORIZED)  # vivo 现状
 *   其余                                                              ⇒ NONE(V2_CONSERVATIVE)
 */
object ControlledLockWake {
    enum class LockState { LOCKED, UNLOCKED, UNKNOWN }

    const val LOCK_STATE_CMD = "dumpsys window policy"
    const val TRUST_STATE_CMD = "dumpsys trust"
    const val DISMISS_KEYGUARD_CMD = "wm dismiss-keyguard"

    const val REASON_AUTHORIZED = "AUTHORIZED"
    const val REASON_SENT_ZERO = "SENT_ZERO"
    const val REASON_REAL_SECURE = "REAL_SECURE"
    const val REASON_V3_INCONSISTENT = "V3_INCONSISTENT"
    const val REASON_NOT_LOCKED = "NOT_LOCKED"
    const val REASON_V2_CONSERVATIVE = "V2_CONSERVATIVE"

    private val SHOWING = Regex("(?:^|\\s)showing=(true|false)(?:\\s|$)")
    private val SECURE = Regex("(?:^|\\s)secure=(true|false)(?:\\s|$)")
    private val SIM_SECURE = Regex("(?:^|\\s)mSimSecure=(true|false)(?:\\s|$)")
    private val DEVICE_LOCKED = Regex("(?:^|[\\s,])deviceLocked=([01])(?:[\\s,]|$)")
    private val TRUST_STATE = Regex("(?:^|[\\s,])trustState=([A-Za-z_]+)(?:[\\s,]|$)")
    private val STRONG_AUTH = Regex("(?:^|[\\s,])strongAuthRequired=(0x[0-9a-fA-F]+)(?:[\\s,]|$)")

    /** 一次只读取样（六字段；两轮取样用于 v3 一致性门）。 */
    data class Sample(
        val state: LockState,
        val secure: String,
        val simSecure: String,
        val deviceLocked: String,
        val trustState: String,
        val strongAuthRequired: String,
    ) {
        /** 是否指向真安全锁（任一命中即不得当噪声；n/a 缺失不算）。 */
        val hasRealSecureLock: Boolean
            get() = deviceLocked == "1" || simSecure == "true" ||
                (strongAuthRequired.startsWith("0x") && strongAuthRequired.length > 2 &&
                    !strongAuthRequired.equals("0x0", ignoreCase = true))

        fun sameDecisiveFields(other: Sample): Boolean =
            state == other.state && secure == other.secure && simSecure == other.simSecure &&
                deviceLocked == other.deviceLocked && trustState == other.trustState &&
                strongAuthRequired == other.strongAuthRequired

        fun describe(): String =
            "state=" + state.name + ",secure=" + secure + ",simSecure=" + simSecure +
                ",deviceLocked=" + deviceLocked + ",trustState=" + trustState +
                ",strongAuthRequired=" + strongAuthRequired
    }

    /** dismiss 门结果（授权与否 + 可 grep 的原因码）。 */
    data class Gate(val authorized: Boolean, val reason: String)

    /** 解析 `showing=`（主判据；KeyguardServiceDelegate 行之后首个完整取值）。 */
    fun parseWindowPolicyState(output: String?): LockState {
        val value = firstValueAfterDelegate(output, SHOWING) ?: return LockState.UNKNOWN
        return if (value == "true") LockState.LOCKED else LockState.UNLOCKED
    }

    fun parseSecure(output: String?): String = firstValueAfterDelegate(output, SECURE) ?: "n/a"

    fun parseSimSecure(output: String?): String = firstValueAfterDelegate(output, SIM_SECURE) ?: "n/a"

    fun parseDeviceLocked(output: String?): String = firstMatch(output, DEVICE_LOCKED) ?: "n/a"

    fun parseTrustState(output: String?): String = firstMatch(output, TRUST_STATE) ?: "n/a"

    fun parseStrongAuthRequired(output: String?): String = firstMatch(output, STRONG_AUTH) ?: "n/a"

    /** 解析一次只读输出对 ⇒ 一个 Sample。 */
    fun parseSample(windowPolicyOutput: String?, trustOutput: String?): Sample = Sample(
        state = parseWindowPolicyState(windowPolicyOutput),
        secure = parseSecure(windowPolicyOutput),
        simSecure = parseSimSecure(windowPolicyOutput),
        deviceLocked = parseDeviceLocked(trustOutput),
        trustState = parseTrustState(trustOutput),
        strongAuthRequired = parseStrongAuthRequired(trustOutput),
    )

    fun shouldAttemptEpisode(attempted: Boolean, nowMs: Long, lastAttemptMs: Long): Boolean =
        !attempted && nowMs - lastAttemptMs >= 1_000L

    /** v3 dismiss 门（纯函数；真值表见类注释）。 */
    fun dismissGate(samples: List<Sample>, sentSessions: Int): Gate {
        if (sentSessions <= 0) return Gate(false, REASON_SENT_ZERO)
        if (samples.isEmpty()) return Gate(false, REASON_V2_CONSERVATIVE)
        if (samples.any { it.hasRealSecureLock }) return Gate(false, REASON_REAL_SECURE)
        if (samples.drop(1).any { !samples[0].sameDecisiveFields(it) }) {
            return Gate(false, REASON_V3_INCONSISTENT)
        }
        val a = samples[0]
        if (a.state != LockState.LOCKED) {
            val reason = if (a.state == LockState.UNKNOWN) REASON_V2_CONSERVATIVE else REASON_NOT_LOCKED
            return Gate(false, reason)
        }
        if (a.secure == "false") return Gate(true, REASON_AUTHORIZED)
        val noRealSecureLock = a.deviceLocked == "0" && a.simSecure == "false" &&
            a.trustState == "TRUSTED" && a.strongAuthRequired == "0x0"
        if (a.secure == "true" && noRealSecureLock) return Gate(true, REASON_AUTHORIZED)
        return Gate(false, REASON_V2_CONSERVATIVE)
    }

    /** KeyguardServiceDelegate 行（含本行）之后首个完整取值；未出现/无命中 ⇒ null（降级 UNKNOWN）。 */
    private fun firstValueAfterDelegate(output: String?, pattern: Regex): String? {
        if (output.isNullOrEmpty()) return null
        var inDelegate = false
        for (rawLine in output.split('\n')) {
            val line = rawLine.trim()
            if (!inDelegate) {
                if (line.startsWith("KeyguardServiceDelegate")) {
                    inDelegate = true
                    pattern.find(line)?.let { return it.groupValues[1] }
                }
                continue
            }
            pattern.find(line)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** 整段输出首个完整取值（dumpsys trust 无 delegate 结构）。 */
    private fun firstMatch(output: String?, pattern: Regex): String? {
        if (output.isNullOrEmpty()) return null
        for (rawLine in output.split('\n')) {
            pattern.find(rawLine.trim())?.let { return it.groupValues[1] }
        }
        return null
    }
}
