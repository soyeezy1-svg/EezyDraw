package com.eezydraw

import android.app.Application
import com.eezydraw.core.data.ProjectDatabase
import com.eezydraw.core.data.RoomProjectRepository
import com.eezydraw.core.data.RoomSettingsRepository

/** One database and repository set per application process; no Activity references are retained. */
class EezyDrawApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    private val database = ProjectDatabase.open(application)
    val projects = RoomProjectRepository(application, database)
    val settings = RoomSettingsRepository(database)
}
