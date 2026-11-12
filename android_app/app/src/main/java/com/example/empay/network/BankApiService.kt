package com.example.empay.network

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Retrofit HTTP contract for the Central Laptop Bank (app.py).
 */
interface BankApiService {

    @GET("/api/user")
    suspend fun getUser(): Response<BankUserResponse>

    @GET("/api/users/phone/{phone}")
    suspend fun getUserByPhone(@Path("phone") phone: String): Response<BankUserResponse>
}

