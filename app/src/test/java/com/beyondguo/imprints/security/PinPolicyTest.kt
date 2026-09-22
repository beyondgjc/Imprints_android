package com.beyondguo.imprints.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PinPolicyTest {
    @Test fun acceptsExactlySixDigits() {
        assertTrue(PinPolicy.isValid("000000"))
        assertTrue(PinPolicy.isValid("123456"))
        assertFalse(PinPolicy.isValid("12345"))
        assertFalse(PinPolicy.isValid("1234567"))
        assertFalse(PinPolicy.isValid("12345a"))
        assertFalse(PinPolicy.isValid("12 456"))
        assertFalse(PinPolicy.isValid(""))
    }

    @Test fun requireValidThrowsChineseMessage() {
        val error = assertThrows(IllegalArgumentException::class.java) { PinPolicy.requireValid("12") }
        assertEquals("请输入 6 位数字口令", error.message)
    }

    @Test fun backoffStartsAtFifthAttemptAndCapsAtFiveMinutes() {
        val now = 1_700_000_000_000L
        assertEquals(0L, PinPolicy.blockedUntil(0, now))
        assertEquals(0L, PinPolicy.blockedUntil(4, now))
        assertEquals(now + 5_000L, PinPolicy.blockedUntil(5, now))
        assertEquals(now + 10_000L, PinPolicy.blockedUntil(6, now))
        assertEquals(now + 300_000L, PinPolicy.blockedUntil(100, now))
    }
}
