package com.eezydraw.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class TakeoffTest {
    @Test fun defaultsAndDefinitionsSerializeAndRejectInvalidQuantitiesAndRates() {
        val settings=TakeoffSettings(10.0,"MYR",providedSchedules(),mapOf("route" to mapOf("MYR" to 3.25)))
        assertEquals(settings,Json.decodeFromString<TakeoffSettings>(Json.encodeToString(settings)))
        assertEquals(RouteKind.entries.toSet(),providedSchedules().mapNotNull {it.routeKind}.toSet())
        assertEquals(TakeoffSettings(),Json.decodeFromString<ProjectSettings>("{}").takeoff)
        for(value in listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY))try {TakeoffSettings(value);fail()}catch(_:IllegalArgumentException){}
        assertFalse(validUnitRates(mapOf("usd" to 2.0)));assertFalse(validUnitRates(mapOf("USD" to -1.0)))
    }
}
