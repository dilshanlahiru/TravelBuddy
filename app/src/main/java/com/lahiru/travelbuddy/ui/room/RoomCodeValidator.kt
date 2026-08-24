package com.lahiru.travelbuddy.ui.room

object RoomCodeValidator {
    private val roomCodePattern = Regex("\\d{4,6}")

    fun normalize(value: String): String = value.trim()

    fun isValid(value: String): Boolean = roomCodePattern.matches(normalize(value))
}
