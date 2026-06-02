package com.example.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.AppDatabase
import com.example.data.LocationCache
import com.example.data.LocationRepository
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var repository: LocationRepository
    private var locationCallback: LocationCallback? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var sessionId: String = ""
    private var employeeName: String = ""
    private var jobDetails: String = ""
    private var baseUrl: String = ""
    private var expiresAt: Long = 0L

    companion object {
        private const val CHANNEL_ID = "tracking_service_channel"
        private const val NOTIFICATION_ID = 2026

        private val _isTracking = MutableStateFlow(false)
        val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

        private val _currentLocation = MutableStateFlow<Location?>(null)
        val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

        private val _lastSyncStatus = MutableStateFlow("Offline")
        val lastSyncStatus: StateFlow<String> = _lastSyncStatus.asStateFlow()

        private val _runningSessionId = MutableStateFlow("")
        val runningSessionId: StateFlow<String> = _runningSessionId.asStateFlow()

        private val _runningEmployeeName = MutableStateFlow("")
        val runningEmployeeName: StateFlow<String> = _runningEmployeeName.asStateFlow()

        private val _runningJobDetails = MutableStateFlow("")
        val runningJobDetails: StateFlow<String> = _runningJobDetails.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getDatabase(this)
        repository = LocationRepository(db.locationCacheDao())
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent.action
        if (action == "STOP") {
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }

        sessionId = intent.getStringExtra("session_id") ?: ""
        employeeName = intent.getStringExtra("employee_name") ?: "Field Employee"
        jobDetails = intent.getStringExtra("job_details") ?: ""
        baseUrl = intent.getStringExtra("base_url") ?: ""
        val durationHours = intent.getIntExtra("duration_hours", 1)
        
        val startedAt = System.currentTimeMillis() / 1000
        expiresAt = if (durationHours == -1) {
            // End of Day (today at 23:59:59)
            val calendar = java.util.Calendar.getInstance()
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 23)
            calendar.set(java.util.Calendar.MINUTE, 59)
            calendar.set(java.util.Calendar.SECOND, 59)
            calendar.set(java.util.Calendar.MILLISECOND, 999)
            calendar.timeInMillis / 1000
        } else {
            startedAt + (durationHours * 3600L)
        }

        _runningSessionId.value = sessionId
        _runningEmployeeName.value = employeeName
        _runningJobDetails.value = jobDetails

        // Start foreground with proper Android version checks
        val notification = buildNotification("Starting share session...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        _isTracking.value = true
        _lastSyncStatus.value = "Registering shift..."

        serviceScope.launch {
            // 1. Upload shift metadata
            val success = repository.uploadMetadata(
                baseUrl = baseUrl,
                sessionId = sessionId,
                employeeName = employeeName,
                jobDetails = jobDetails,
                startedAt = startedAt,
                expiresAt = expiresAt
            )
            if (success) {
                _lastSyncStatus.value = "Shift active, waiting for Location"
                updateNotification("Live location sharing active for $employeeName")
            } else {
                _lastSyncStatus.value = "Metadata offline (Local Cache active)"
                updateNotification("Sharing active offline (connection waiting)")
            }

            // 2. Start retrieving locations
            startLocationUpdates()
        }

        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (locationCallback != null) return

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10000L // 10 seconds interval
        ).apply {
            setMinUpdateIntervalMillis(5000L) // Fastest interval
            setMinUpdateDistanceMeters(5.0f) // 5 meters displacement
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                _currentLocation.value = location
                handleNewLocation(location)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (unlikely: SecurityException) {
            Log.e("TrackingService", "Lost location permission. Could not request updates. $unlikely")
            _lastSyncStatus.value = "Error: Permission denied"
            stopTracking()
        }
    }

    private fun handleNewLocation(location: Location) {
        val currentTimeSec = System.currentTimeMillis() / 1000
        if (expiresAt > 0 && currentTimeSec > expiresAt) {
            Log.i("TrackingService", "Session expired automatically. Stopping service.")
            _lastSyncStatus.value = "Session Expired"
            stopTracking()
            stopSelf()
            return
        }

        serviceScope.launch {
            val success = repository.uploadLocation(
                baseUrl = baseUrl,
                sessionId = sessionId,
                latitude = location.latitude,
                longitude = location.longitude,
                lastUpdated = currentTimeSec
            )

            if (success) {
                _lastSyncStatus.value = "Live Tracking Active"
                // Sync any backlog offline cache points
                val syncedOfflineCount = repository.syncOfflineCache(baseUrl, sessionId)
                if (syncedOfflineCount > 0) {
                    _lastSyncStatus.value = "Live Tracking Active (Synced $syncedOfflineCount historical caches)"
                }
            } else {
                // If failed, insert point into SQLite local database for offline safety
                val cachePoint = LocationCache(
                    sessionId = sessionId,
                    employeeName = employeeName,
                    jobDetails = jobDetails,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    timestamp = System.currentTimeMillis()
                )
                repository.insertCache(cachePoint)
                _lastSyncStatus.value = "Offline (Cached location locally)"
            }

            updateNotification("Last updated: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        }
    }

    private fun stopTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
        _isTracking.value = false
        _currentLocation.value = null
        _lastSyncStatus.value = "Offline"
        _runningSessionId.value = ""
        _runningEmployeeName.value = ""
        _runningJobDetails.value = ""
        serviceJob.cancel()
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, TrackingService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Find My Friend - Location Sharing")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", stopPendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Field Employee Live GPS Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when background location sharing with management is operational"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
