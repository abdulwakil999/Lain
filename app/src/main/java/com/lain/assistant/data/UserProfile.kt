package com.lain.assistant.data

enum class Gender { MALE, FEMALE }

data class UserProfile(
    val name: String,
    val age: Int,
    val gender: Gender,
    val nickname: String
) {
    companion object {
        val EMPTY = UserProfile(name = "", age = 0, gender = Gender.FEMALE, nickname = "")
    }
}
