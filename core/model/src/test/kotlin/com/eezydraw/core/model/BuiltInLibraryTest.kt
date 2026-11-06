package com.eezydraw.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class BuiltInLibraryTest {
    @Test fun `expanded catalogue retains original electrical and ICT types with stable unique ids and both representations`() {
        val items = BuiltInLibrary.items
        val electricalIds = listOf("single-switch", "double-switch", "dimmer", "socket", "floor-box", "isolator",
            "distribution-board", "downlight", "linear-light", "emergency-light", "exit-sign", "junction-box",
            "pull-box", "cable-tray", "cable-ladder", "trunking", "conduit", "earth-bar", "earth-rod")
            .map { LibraryItemId("eezy.electrical.$it") }
        val ictIds = listOf("rack-42u", "rack-24u", "rack-12u", "patch-panel", "cable-basket", "data-outlet",
            "fibre-box", "wifi-access-point", "cctv-camera", "access-control-reader", "intercom", "pa-speaker", "tv-outlet")
            .map { LibraryItemId("eezy.ict.$it") }
        val originalIds = (electricalIds + ictIds).toSet()
        val originalItems = items.filter { it.id in originalIds }
        assertEquals(32, originalItems.size)
        assertEquals(originalIds, originalItems.map { it.id }.toSet())
        assertTrue(items.size > originalItems.size)
        assertEquals(items.size, items.map { it.id }.distinct().size)
        assertEquals(setOf(Discipline.ELECTRICAL, Discipline.ICT), originalItems.map { it.discipline }.toSet())
        items.forEach { item ->
            assertTrue(item.builtIn)
            assertTrue("${item.name} needs a physical solid", item.geometry.any {
                it is Shape.Box || it is Shape.Extrusion || it is Shape.Cylinder || it is Shape.Cone ||
                    it is Shape.Sphere || it is Shape.Pyramid || it is Shape.Revolution || it is Shape.Mesh
            })
            assertTrue(item.symbolGeometry.isNotEmpty())
            assertEquals(item, BuiltInLibrary.find(item.id))
            assertTrue(item.defaultSize.width > 0 && item.defaultSize.depth > 0 && item.defaultSize.height > 0)
            assertEquals(item.defaultMountingHeight,
                (item.defaultParameters().getValue(LibraryParameterKeys.MOUNTING_HEIGHT) as ParameterValue.Number).value, 0.0)
        }
        val switch = originalItems.single { it.symbol == LibrarySymbol.SWITCH }
        assertEquals(MountingType.WALL, switch.mounting)
        assertEquals(1.2, switch.defaultMountingHeight, 0.0)
        assertEquals(Size3(.086, .035, .086), switch.defaultSize)
        assertEquals(MountingType.CEILING, originalItems.single { it.symbol == LibrarySymbol.WIFI }.mounting)
        assertTrue(originalItems.filter { it.symbol == LibrarySymbol.RACK }.all { it.mounting == MountingType.FLOOR })
        assertEquals(setOf("42U rack", "24U rack", "12U rack"),
            originalItems.filter { it.symbol == LibrarySymbol.RACK }.map { it.name }.toSet())
    }

    @Test fun `search is case insensitive multi word and finds punctuation aliases and metadata`() {
        val items = BuiltInLibrary.items
        assertEquals(listOf("Single switch", "Double switch"), librarySearch(items, "SWITCH electrical").map { it.name })
        assertEquals(listOf("Wi-Fi access point"), librarySearch(items, "wifi").map { it.name })
        assertEquals(listOf("Wi-Fi access point"), librarySearch(items, "wi-fi").map { it.name })
        assertEquals(listOf("42U rack"), librarySearch(items, "rack 42U").map { it.name })
        assertTrue(librarySearch(items, "rack electrical").isEmpty())
        assertEquals(items, librarySearch(items, "  "))
        val custom = items.first().copy(manufacturer = "Acme", model = "A-25")
        assertEquals(listOf(custom), librarySearch(listOf(custom), "acme A25"))
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf("Single switch"), librarySearch(items, "SINGLE SWITCH").map { it.name })
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test fun `favorites and recents use ids preserve order and bound recently used entries`() {
        val ids = BuiltInLibrary.items.map { it.id }
        var state = LibraryState().toggledFavorite(ids[0]).toggledFavorite(ids[1])
        assertEquals(setOf(ids[0], ids[1]), state.favoriteIds)
        state = state.toggledFavorite(ids[0])
        assertEquals(setOf(ids[1]), state.favoriteIds)
        ids.forEach { state = state.recordUsed(it) }
        assertEquals(ids.asReversed().take(24), state.recentIds)
        state = state.recordUsed(ids.last())
        assertEquals(24, state.recentIds.size)
        assertEquals(1, state.recentIds.count { it == ids.last() })
        assertEquals(state, Json.decodeFromString<LibraryState>(Json.encodeToString(state)))
    }

    @Test fun `project definition overrides built in and old records gain compatible defaults`() {
        val original = BuiltInLibrary.items.first()
        val changed = original.copy(name = "Project specific switch")
        val merged = BuiltInLibrary.including(listOf(changed))
        assertEquals(BuiltInLibrary.items.size, merged.size)
        assertEquals(changed, merged.single { it.id == original.id })
        val old = Json.decodeFromString<LibraryItem>("""{
            "id":"legacy","name":"Legacy box","discipline":"ELECTRICAL","category":"Custom",
            "defaultSize":{"width":1.0,"depth":2.0,"height":3.0}
        }""")
        assertEquals(MountingType.FLOOR, old.mounting)
        assertEquals(0.0, old.defaultMountingHeight, 0.0)
        assertEquals(LibrarySymbol.BOX, old.symbol)
        assertTrue(old.symbolGeometry.isEmpty())
        assertNull(old.fitting)
        val legacyDocument = Json.decodeFromString<ProjectDocument>("""{
            "project":{"id":"old","name":"Old project","createdAtEpochMillis":1,"updatedAtEpochMillis":1}
        }""")
        assertEquals(LibraryState(), legacyDocument.libraryState)
    }
}
