package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.LocationRepository
import com.example.service.TrackingService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class TrackingViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val sharedPrefs = context.getSharedPreferences("find_my_friend_prefs", Context.MODE_PRIVATE)

    private val database = AppDatabase.getDatabase(context)
    private val repository = LocationRepository(database.locationCacheDao())

    private val _employeeName = MutableStateFlow(sharedPrefs.getString("employee_name", "") ?: "")
    val employeeName = _employeeName.asStateFlow()

    private val _jobDetails = MutableStateFlow(sharedPrefs.getString("job_details", "") ?: "")
    val jobDetails = _jobDetails.asStateFlow()

    private val _dbUrl = MutableStateFlow(sharedPrefs.getString("firebase_db_url", "https://find-my-friend-default-rtdb.firebaseio.com") ?: "https://find-my-friend-default-rtdb.firebaseio.com")
    val dbUrl = _dbUrl.asStateFlow()

    private val _durationHours = MutableStateFlow(1) 
    val durationHours = _durationHours.asStateFlow()

    val isTrackingServiceActive: StateFlow<Boolean> = TrackingService.isTracking
    val serviceLastSyncStatus: StateFlow<String> = TrackingService.lastSyncStatus
    val serviceRunningSessionId: StateFlow<String> = TrackingService.runningSessionId
    val serviceRunningEmployeeName: StateFlow<String> = TrackingService.runningEmployeeName
    val serviceRunningJobDetails: StateFlow<String> = TrackingService.runningJobDetails

    val cachedLocationsCount: StateFlow<Int> = repository.allCachedLocations
        .map { points ->
            val activeSession = TrackingService.runningSessionId.value
            if (activeSession.isNotEmpty()) {
                points.count { it.sessionId == activeSession }
            } else {
                points.size
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setEmployeeName(name: String) {
        _employeeName.value = name
        sharedPrefs.edit().putString("employee_name", name).apply()
    }

    fun setJobDetails(details: String) {
        _jobDetails.value = details
        sharedPrefs.edit().putString("job_details", details).apply()
    }

    fun setDbUrl(url: String) {
        _dbUrl.value = url
        sharedPrefs.edit().putString("firebase_db_url", url).apply()
    }

    fun setDuration(hours: Int) {
        _durationHours.value = hours
    }

    fun startTracking() {
        if (isTrackingServiceActive.value) return

        val newSessionId = "session_" + UUID.randomUUID().toString().substring(0, 8)

        val intent = Intent(context, TrackingService::class.java).apply {
            putExtra("session_id", newSessionId)
            putExtra("employee_name", _employeeName.value.ifBlank { "Field Employee" })
            putExtra("job_details", _jobDetails.value.ifBlank { "Field Maintenance" })
            putExtra("base_url", _dbUrl.value)
            putExtra("duration_hours", _durationHours.value)
        }
        
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopTracking() {
        val intent = Intent(context, TrackingService::class.java).apply {
            action = "STOP"
        }
        context.startService(intent)
    }
}
