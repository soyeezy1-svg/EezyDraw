package com.eezydraw.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** The catalogue indexes metadata; document payloads and previews live in versioned files. */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val template: String,
    val units: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long,
    val currentFile: String,
    val currentChecksum: String,
    val thumbnailFile: String,
    val backupFile: String? = null,
    val backupChecksum: String? = null,
)

@Entity(tableName = "settings")
data class SettingsEntity(@PrimaryKey val id: Int = 1, val json: String)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY lastOpenedAt DESC, updatedAt DESC, id ASC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun find(id: String): ProjectEntity?

    @Query("SELECT COALESCE(MAX(lastOpenedAt), 0) FROM projects")
    suspend fun latestAccess(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("UPDATE projects SET lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun touch(id: String, openedAt: Long)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: SettingsEntity)
}

@Database(entities = [ProjectEntity::class, SettingsEntity::class], version = 1, exportSchema = true)
abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        fun open(context: Context): ProjectDatabase = Room.databaseBuilder(
            context.applicationContext, ProjectDatabase::class.java, "eezy-draw.db",
        ).build()
    }
}
