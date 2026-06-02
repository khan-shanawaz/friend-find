package com.example.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class LocationRepository(private val dao: LocationCacheDao) {

    val allCachedLocations: Flow<List<LocationCache>> = dao.getAllCachedLocations()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://placeholder.firebaseio.com/") // Dynamically overridden in FirebaseApi
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val api = retrofit.create(FirebaseApi::class.java)

    suspend fun insertCache(location: LocationCache) {
        dao.insertLocation(location)
    }

    suspend fun getCachedList(): List<LocationCache> {
        return dao.getCachedLocationsList()
    }

    suspend fun deleteCaches(locations: List<LocationCache>) {
        dao.deleteLocations(locations)
    }

    suspend fun uploadMetadata(
        baseUrl: String,
        sessionId: String,
        employeeName: String,
        jobDetails: String,
        startedAt: Long,
        expiresAt: Long
    ): Boolean {
        return try {
            val dbUrl = cleanBaseUrl(baseUrl)
            val fullUrl = "$dbUrl/tracking_sessions/$sessionId/metadata.json"
            val metadata = SessionMetadata(
                employee_name = employeeName,
                job_details = jobDetails,
                started_at = startedAt,
                expires_at = expiresAt
            )
            val response = api.updateMetadata(fullUrl, metadata)
            response.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun uploadLocation(
        baseUrl: String,
        sessionId: String,
        latitude: Double,
        longitude: Double,
        lastUpdated: Long
    ): Boolean {
        return try {
            val dbUrl = cleanBaseUrl(baseUrl)
            val fullUrl = "$dbUrl/tracking_sessions/$sessionId/live_location.json"
            val liveLoc = LiveLocation(
                latitude = latitude,
                longitude = longitude,
                last_updated = lastUpdated
            )
            val response = api.updateLocation(fullUrl, liveLoc)
            response.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun syncOfflineCache(baseUrl: String, sessionId: String): Int {
        val cachedPoints = getCachedList()
        if (cachedPoints.isEmpty()) return 0

        var syncedCount = 0
        val pointsToSync = cachedPoints.filter { it.sessionId == sessionId }
        val pointsToDelete = mutableListOf<LocationCache>()

        for (point in pointsToSync) {
            val success = uploadLocation(
                baseUrl = baseUrl,
                sessionId = point.sessionId,
                latitude = point.latitude,
                longitude = point.longitude,
                lastUpdated = point.timestamp / 1000
            )
            if (success) {
                pointsToDelete.add(point)
                syncedCount++
            } else {
                break
            }
        }

        if (pointsToDelete.isNotEmpty()) {
            deleteCaches(pointsToDelete)
        }
        return syncedCount
    }

    suspend fun clearSessionCache(sessionId: String) {
        dao.clearSessionCache(sessionId)
    }

    private fun cleanBaseUrl(url: String): String {
        var clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://$clean"
        }
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length - 1)
        }
        return clean
    }
}
