package com.eezydraw.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FloorServiceSettingsTest {
    @Test fun `old serialized settings retain free routing and floor defaults are positive AFFL depths`() {
        val route=Json.decodeFromString<RoutingSettings>("{}")
        assertNull(route.floorInstallation)
        val settings=Json.decodeFromString<ProjectSettings>("{}")
        assertEquals(.5,settings.floorServices.maxDepthFraction,0.0)
        assertTrue(FloorInstallation.entries.all {(settings.floorServices.defaultDepths[it] ?: 0.0)>0})
        val updated=route.copy(floorInstallation=FloorInstallation.IN_SLAB,floorDepth=.12)
        assertEquals(updated,Json.decodeFromString<RoutingSettings>(Json.encodeToString(RoutingSettings.serializer(),updated)))
    }
    @Test fun `invalid floor depth and clearance are rejected and catalogue provides recessed floor items`() {
        try {RoutingSettings(floorDepth=-.1);fail("Negative depth accepted")}catch(_:IllegalArgumentException){}
        try {FloorServiceSettings(clearance=-.1);fail("Negative clearance accepted")}catch(_:IllegalArgumentException){}
        assertEquals(FloorDeviceKind.entries.toSet(),floorServiceLibraryItems().mapNotNull {it.floorService}.toSet())
    }
}
