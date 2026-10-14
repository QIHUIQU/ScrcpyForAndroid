package io.github.miuzarte.scrcpyforandroid.password

enum class PasswordCreatedState {
    AuthenticatedCreated,
    UnauthenticatedCreated,
    AuthenticatedCreatedModified,
}

val PasswordCreatedState.hasAuthenticatedOrigin: Boolean
    get() = this != PasswordCreatedState.UnauthenticatedCreated

/**
 * 无人值守自动解锁的条目选择口径（安全边界）：
 * 仅当存在“名称与设备序列号完全相同、创建时未启用认证”的唯一条目时返回该条目；
 * 认证创建/被改条目、重名歧义、序列号缺失（blank/unknown/n-a）一律返回 null（不动作）。
 */
object PasswordAutofillPolicy {
    fun forDevice(entries: List<PasswordEntry>, serial: String): PasswordEntry? =
        if (serial.isBlank() || serial.equals("unknown", ignoreCase = true) ||
            serial.equals("n/a", ignoreCase = true)) null else entries.singleOrNull {
            it.name == serial && it.cipherText != null &&
                it.createdWithAuth == PasswordCreatedState.UnauthenticatedCreated
        }
}

data class PasswordEntry(
    val id: String,
    val name: String,
    val cipherText: CharArray?,
    val createdWithAuth: PasswordCreatedState,
)
