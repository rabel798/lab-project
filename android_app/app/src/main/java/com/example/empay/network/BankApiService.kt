package com.example.empay.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit HTTP contract for the Central Laptop Bank (app.py).
 */
interface BankApiService {

    @GET("/api/user")
    suspend fun getUser(): Response<BankUserResponse>

    @GET("/api/users")
    suspend fun getAllUsers(): Response<List<BankUserResponse>>

    @GET("/api/users/search")
    suspend fun searchUsers(@Query("q") query: String): Response<List<BankUserResponse>>

    @GET("/api/users/{id}")
    suspend fun getUserById(@Path("id") id: Long): Response<BankUserResponse>

    @GET("/api/users/phone/{phone}")
    suspend fun getUserByPhone(@Path("phone") phone: String): Response<BankUserResponse>

    @POST("/api/user/transfer-to-emergency")
    suspend fun transferToEmergency(
        @Body request: TransferEmergencyRequest
    ): Response<TransferEmergencyResponse>

    @POST("/api/user/add-funds")
    suspend fun addFunds(
        @Body request: AddFundsRequest
    ): Response<AddFundsResponse>

    @POST("/api/user/update-name")
    suspend fun updateUserName(
        @Body request: UpdateUserNameRequest
    ): Response<UpdateUserNameResponse>

    @POST("/api/user/register")
    suspend fun registerUser(
        @Body request: RegisterUserRequest
    ): Response<RegisterUserResponse>

    @POST("/api/user/login")
    suspend fun loginUser(
        @Body request: LoginRequest
    ): Response<LoginResponse>

    @POST("/api/user/logout")
    suspend fun logoutUser(
        @Body request: LogoutRequest
    ): Response<LogoutResponse>

    @POST("/api/transactions/sync")
    suspend fun syncTransactions(
        @Body request: SyncTransactionsRequest
    ): Response<SyncTransactionsResponse>

    @GET("/api/transactions")
    suspend fun getAllTransactions(): Response<List<BankRemoteTransaction>>
}
