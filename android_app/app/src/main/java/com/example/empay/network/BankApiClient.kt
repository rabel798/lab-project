package com.example.empay.network

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Singleton managing Retrofit client with smart automatic failover between
 * the live cloud bank server (20.219.56.82:5000) and local emulator (10.0.2.2:5000).
 */
object BankApiClient {

    private const val PREFS_NAME = "empay_network_prefs"
    private const val KEY_BASE_URL = "laptop_bank_base_url"

    // Primary: Cloud server specified by project; Secondary: Local host emulator alias
    const val CLOUD_BASE_URL = "http://20.219.56.82:5000/"
    const val LOCAL_BASE_URL = "http://10.0.2.2:5000/"
    const val DEFAULT_BASE_URL = CLOUD_BASE_URL

    @Volatile
    private var currentBaseUrl: String = DEFAULT_BASE_URL

    @Volatile
    private var apiService: BankApiService? = null

    fun getBaseUrl(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_BASE_URL, null)
        if (saved == null || saved.contains("10.0.2.2")) {
            prefs.edit().putString(KEY_BASE_URL, CLOUD_BASE_URL).apply()
            currentBaseUrl = CLOUD_BASE_URL
        } else {
            currentBaseUrl = saved
        }
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

        // Automatic Failover Interceptor: If cloud fails, fallback to local (or vice-versa)
        val failoverInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            try {
                val response = chain.proceed(originalRequest)
                if (response.isSuccessful || response.code < 500) {
                    return@Interceptor response
                }
                response.close()
            } catch (_: IOException) {
                // Connection failed or timed out -> attempt alternate host
            }

            val currentHost = originalRequest.url.host
            val alternateHost = if (currentHost == "20.219.56.82") "10.0.2.2" else "20.219.56.82"
            val fallbackUrl = originalRequest.url.newBuilder()
                .host(alternateHost)
                .port(5000)
                .build()

            val fallbackRequest = originalRequest.newBuilder().url(fallbackUrl).build()
            try {
                chain.proceed(fallbackRequest)
            } catch (fallbackErr: IOException) {
                throw IOException("Bank Server offline (${originalRequest.url.host})")
            }
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(failoverInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(BankApiService::class.java)
    }
}

