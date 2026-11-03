package com.eezydraw.core.data

import com.eezydraw.core.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoomSettingsRepository(database: ProjectDatabase) : SettingsRepository {
    private val dao = database.settingsDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val settings: Flow<AppSettings> = dao.observe().map { row ->
        if (row == null) AppSettings() else try {
            json.decodeFromString<AppSettings>(row.json)
        } catch (failure: SerializationException) {
            throw ProjectStorageException("Saved settings could not be read.", failure)
        } catch (failure: IllegalArgumentException) {
            throw ProjectStorageException("Saved settings contain invalid values.", failure)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun save(settings: AppSettings) = withContext(Dispatchers.IO) {
        dao.upsert(SettingsEntity(json = json.encodeToString(settings)))
    }
}
