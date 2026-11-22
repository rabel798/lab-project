package com.example.empay.db

import com.example.empay.auth.UserAccount

interface UserDao {
    fun insertOrUpdateUser(user: UserAccount, isRemembered: Boolean = false): Long
    fun getUserByUsernameOrPhone(query: String): UserAccount?
    fun getUserById(id: Long): UserAccount?
    fun getAllUsers(): List<UserAccount>
    fun searchUsers(query: String): List<UserAccount>
    fun getRememberedUser(): UserAccount?
    fun setRemembered(userId: Long, isRemembered: Boolean)
    fun clearAllRemembered()
    fun updateUserName(userId: Long, newName: String, previousNames: String): Boolean
}
