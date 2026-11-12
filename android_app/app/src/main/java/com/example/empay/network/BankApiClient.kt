package com.example.empay.network

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton managing Retrofit client and dynamic Laptop Bank base URL configuration.
 */
object BankApiClient {

    private const val PREFS_NAME = "empay_network_prefs"
    private const val KEY_BASE_URL = "laptop_bank_base_url"

    // Default: Android Emulator alias for host machine localhost:5000
    const val DEFAULT_BASE_URL = "http://10.0.2.2:5000/"

    @Volatile
    private var currentBaseUrl: String = DEFAULT_BASE_URL

    @Volatile
    private var apiService: BankApiService? = null

    fun getBaseUrl(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentBaseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        return currentBaseUrl
    }

    fun setBaseUrl(context: Context, newUrl: String) {
        var formatted = newUrl.trim()
        if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
            formatted = "http://$formatted"
        }
        if (!formatted.endsWith("/")) {
            formatted = "$formatted/"
        }
        currentBaseUrl = formatted

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BASE_URL, formatted).apply()

        synchronized(this) {
            apiService = null // Force recreation with new URL
        }
    }

    fun getApiService(context: Context): BankApiService {
        return apiService ?: synchronized(this) {
            apiService ?: createApiService(context).also { apiService = it }
        }
    }

    private fun createApiService(context: Context): BankApiService {
        val url = getBaseUrl(context)

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(BankApiService::class.java)
    }
}

