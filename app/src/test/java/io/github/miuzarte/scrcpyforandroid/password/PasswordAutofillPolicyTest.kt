package io.github.miuzarte.scrcpyforandroid.password

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordAutofillPolicyTest {
    @Test fun exactDeviceOnlyAndNeverAuthenticatedEntry() {
        val a = PasswordEntry("a", "SERIAL-A", charArrayOf('1'), PasswordCreatedState.UnauthenticatedCreated)
        val b = PasswordEntry("b", "SERIAL-B", charArrayOf('2'), PasswordCreatedState.AuthenticatedCreated)
        assertEquals(a, PasswordAutofillPolicy.forDevice(listOf(a, b), "SERIAL-A"))
        assertNull(PasswordAutofillPolicy.forDevice(listOf(a, b), "SERIAL-B"))
        assertNull(PasswordAutofillPolicy.forDevice(listOf(a, b), "SERIAL-C"))
        assertNull(PasswordAutofillPolicy.forDevice(listOf(a, a), "SERIAL-A"))
    }

    @Test fun rejectsMissingOrPlaceholderSerials() {
        val a = PasswordEntry("a", "SERIAL-A", charArrayOf('1'), PasswordCreatedState.UnauthenticatedCreated)
        assertNull(PasswordAutofillPolicy.forDevice(listOf(a), ""))
        assertNull(PasswordAutofillPolicy.forDevice(listOf(a), "unknown"))
        assertNull(PasswordAutofillPolicy.forDevice(listOf(a), "N/A"))
    }
}
