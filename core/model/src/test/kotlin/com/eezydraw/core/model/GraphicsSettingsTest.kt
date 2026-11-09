package com.eezydraw.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class GraphicsSettingsTest {
    @Test fun graphicsChoicesRoundTripAndOldPreferencesKeepTheirQualityTier() {
        val settings=AppSettings(renderQuality=RenderQuality.BALANCED,graphics=GraphicsOptions(false,LightingStrength.STRONG,TextureDetail.HIGH,false,false,true))
        assertEquals(settings,Json.decodeFromString<AppSettings>(Json.encodeToString(settings)))
        val old=Json.decodeFromString<AppSettings>("{\"renderQuality\":\"BALANCED\"}")
        assertEquals(RenderQuality.BALANCED,old.renderQuality);assertEquals(GraphicsOptions(),old.graphics)
        assertEquals(ViewMode.SHADED_WITH_EDGES,ProjectSettings().viewMode)
        assertEquals(RenderQuality.QUALITY,AppSettings().renderQuality)
    }
    private fun luminance(colour:Long):Double {
        fun linear(n:Long):Double {val c=n/255.0;return if(c<=.04045)c/12.92 else ((c+.055)/1.055).pow(2.4)}
        return .2126*linear(colour shr 16 and 255)+.7152*linear(colour shr 8 and 255)+.0722*linear(colour and 255)
    }
    @Test fun adjacentMatteDefaultsHaveMeasurableContrast() {
        fun ratio(a:Long,b:Long):Double {val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05)}
        assertTrue(ratio(MattePalette.WALL,MattePalette.FLOOR)>2)
        assertTrue(ratio(MattePalette.CEILING,MattePalette.WALL)>1.25)
        assertTrue(ratio(MattePalette.EQUIPMENT,MattePalette.FLOOR)>1.5)
    }
}
