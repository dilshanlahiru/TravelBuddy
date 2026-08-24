package com.lahiru.travelbuddy

import com.lahiru.travelbuddy.ui.room.RoomCodeValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomCodeValidatorTest {
    @Test
    fun acceptsFourToSixDigits() {
        assertTrue(RoomCodeValidator.isValid("1234"))
        assertTrue(RoomCodeValidator.isValid("12345"))
        assertTrue(RoomCodeValidator.isValid("123456"))
    }

    @Test
    fun normalizesSurroundingWhitespace() {
        assertEquals("123456", RoomCodeValidator.normalize(" 123456 "))
        assertTrue(RoomCodeValidator.isValid(" 123456 "))
    }

    @Test
    fun rejectsInvalidCodes() {
        assertFalse(RoomCodeValidator.isValid("123"))
        assertFalse(RoomCodeValidator.isValid("1234567"))
        assertFalse(RoomCodeValidator.isValid("12A456"))
        assertFalse(RoomCodeValidator.isValid(""))
    }
}
