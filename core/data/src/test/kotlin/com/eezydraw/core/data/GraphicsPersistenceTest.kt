package com.eezydraw.core.data

import androidx.room.Room
import com.eezydraw.core.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class GraphicsPersistenceTest {
    @Test fun legacyPreferencesUpgradeTheNewProjectDefaultWhileKeepingTheSavedQualityTier()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val name="legacy-graphics-${UUID.randomUUID()}.db"
        val db=Room.databaseBuilder(context,ProjectDatabase::class.java,name).build()
        try {
            db.settingsDao().upsert(SettingsEntity(json="""{"renderQuality":"BALANCED","projectDefaults":{"viewMode":"WIREFRAME"}}"""))
            val saved=RoomSettingsRepository(db).settings.first()
            assertEquals(ViewMode.SHADED_WITH_EDGES,saved.projectDefaults.viewMode)
            assertEquals(RenderQuality.BALANCED,saved.renderQuality)
        }finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun graphicsSettingsSurviveARealDatabaseCloseAndReopen()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val name="graphics-${UUID.randomUUID()}.db"
        val saved=AppSettings(renderQuality=RenderQuality.PERFORMANCE,graphics=GraphicsOptions(false,LightingStrength.SOFT,TextureDetail.HIGH,false,false,true))
        var db=Room.databaseBuilder(context,ProjectDatabase::class.java,name).build()
        try {
            RoomSettingsRepository(db).save(saved);db.close()
            db=Room.databaseBuilder(context,ProjectDatabase::class.java,name).build()
            assertEquals(saved,RoomSettingsRepository(db).settings.first())
        }finally {db.close();context.deleteDatabase(name)}
    }
}
