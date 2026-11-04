package com.eezydraw.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class InteractionPreferencesTest {
    @Test fun olderPreferencesUseSinglePlacementAndDistanceOnlyMeasurements() {
        val app = Json.decodeFromString<AppSettings>("{}")
        assertFalse(app.showDetailedMeasurement)
        assertFalse(app.keepToolActiveAfterPlacing)
        val project = Json.decodeFromString<ProjectSettings>("{}")
        assertFalse(project.showDetailedMeasurements)
        assertEquals(15, project.rotationSnapDegrees)
    }

    @Test fun preferenceOptInsAndRotationSnapRoundTripWithoutChangingPhysicalDefaults() {
        val settings = AppSettings(showDetailedMeasurement = true, keepToolActiveAfterPlacing = true,
            projectDefaults = ProjectSettings(rotationSnapDegrees = 45, showDetailedMeasurements = true))
        assertEquals(settings, Json.decodeFromString<AppSettings>(Json.encodeToString(settings)))
        assertEquals(.1, settings.projectDefaults.snap.gridSpacing, 0.0)
    }

    @Test fun rotationSnapAcceptsOnlyTheOfferedSteps() {
        for (step in listOf(1, 5, 15, 45, 90)) {
            assertEquals(step, ProjectSettings(rotationSnapDegrees = step).rotationSnapDegrees)
        }
        for (step in listOf(-15, 0, 2, 10, 30, 360)) {
            assertThrows(IllegalArgumentException::class.java) { ProjectSettings(rotationSnapDegrees = step) }
        }
    }
}
