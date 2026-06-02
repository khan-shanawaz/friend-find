package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "location_cache")
data class LocationCache(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: String,
    val employeeName: String,
    val jobDetails: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

@Dao
interface LocationCacheDao {
    @Query("SELECT * FROM location_cache ORDER BY timestamp ASC")
    fun getAllCachedLocations(): Flow<List<LocationCache>>

    @Query("SELECT * FROM location_cache ORDER BY timestamp ASC")
    suspend fun getCachedLocationsList(): List<LocationCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: LocationCache)

    @Delete
    suspend fun deleteLocations(locations: List<LocationCache>)

    @Query("DELETE FROM location_cache WHERE sessionId = :sessionId")
    suspend fun clearSessionCache(sessionId: String)
}

@Database(entities = [LocationCache::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationCacheDao(): LocationCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "location_cache_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
