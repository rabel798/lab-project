package com.example.empay.db

import com.example.empay.auth.UserAccount

interface UserDao {
    fun insertOrUpdateUser(user: UserAccount, isRemembered: Boolean = true): Long
    fun getUserByUsernameOrPhone(query: String): UserAccount?
    fun getAllUsers(): List<UserAccount>
    fun getRememberedUser(): UserAccount?
    fun setRemembered(userId: Long, isRemembered: Boolean)
    fun clearAllRemembered()
}
