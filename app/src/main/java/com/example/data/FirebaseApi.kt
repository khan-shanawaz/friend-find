package com.example.data

import retrofit2.Response
import retrofit2.http.*

interface FirebaseApi {
    @PUT
    suspend fun updateMetadata(
        @Url url: String,
        @Body metadata: SessionMetadata
    ): Response<Unit>

    @PUT
    suspend fun updateLocation(
        @Url url: String,
        @Body location: LiveLocation
    ): Response<Unit>
}

data class SessionMetadata(
    val employee_name: String,
    val job_details: String,
    val started_at: Long, // unix seconds timestamp
    val expires_at: Long // unix seconds timestamp
)

data class LiveLocation(
    val latitude: Double,
    val longitude: Double,
    val last_updated: Long // unix seconds timestamp
)
