package com.eezydraw.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PrecisionPointerSettingsTest {
    @Test fun olderPreferencesEnableConfirmPlacementWithFingerOffsetAndProjectGridNudge() {
        val pointer = Json.decodeFromString<AppSettings>("{}").precisionPointer
        assertTrue(pointer.enabled)
        assertEquals(80.0, pointer.offsetDp, 0.0)
        assertEquals(PrecisionOffsetSide.ABOVE_LEFT, pointer.side)
        assertTrue(pointer.magnifier)
        assertEquals(3.0, pointer.magnifierZoom, 0.0)
        assertNull(pointer.nudgeStepMetres)
        assertTrue(pointer.hapticFeedback)
    }

    @Test fun directTapAndAllPrecisionChoicesRoundTripInDevicePreferences() {
        val settings = AppSettings(precisionPointer=PrecisionPointerSettings(enabled=false, offsetDp=120.0,
            side=PrecisionOffsetSide.ABOVE_RIGHT, magnifier=false, magnifierZoom=4.0,
            nudgeStepMetres=.0254, hapticFeedback=false))
        assertEquals(settings, Json.decodeFromString<AppSettings>(Json.encodeToString(settings)))
        assertEquals(.1, settings.projectDefaults.snap.gridSpacing, 0.0)
    }

    @Test fun invalidOffsetsZoomAndPhysicalStepsCannotBePersisted() {
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,0.0,23.0,241.0))
            assertThrows(IllegalArgumentException::class.java) {PrecisionPointerSettings(offsetDp=value)}
        for(value in listOf(Double.NaN,Double.NEGATIVE_INFINITY,0.0,1.99,4.01))
            assertThrows(IllegalArgumentException::class.java) {PrecisionPointerSettings(magnifierZoom=value)}
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,0.0,-.01))
            assertThrows(IllegalArgumentException::class.java) {PrecisionPointerSettings(nudgeStepMetres=value)}
        assertEquals(24.0,PrecisionPointerSettings(offsetDp=24.0,magnifierZoom=2.0).offsetDp,0.0)
        assertEquals(240.0,PrecisionPointerSettings(offsetDp=240.0,magnifierZoom=4.0).offsetDp,0.0)
    }
}
