package com.eezydraw.ui.settings

import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.ElevationReference
import com.eezydraw.core.model.ElevationSetting
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.ProjectSettings
import com.eezydraw.core.model.RouteKind
import com.eezydraw.core.model.SnapSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDraftTest {
    @Test
    fun changingDisplayUnitsPreservesPhysicalGridToleranceAndRouteHeight() {
        val original = AppSettings(projectDefaults = ProjectSettings(
            lengthUnit = LengthUnit.FEET,
            snap = SnapSettings(gridSpacing = 0.3048, tolerance = 0.0254),
            defaultRouteElevations = mapOf(RouteKind.PIPE to ElevationSetting(
                value = 3.048,
                reference = ElevationReference.ABOVE_CEILING,
            )),
        ))
        val draft = SettingsDraft.from(original).changeUnits(LengthUnit.INCHES)

        assertEquals(12.0, draft.inputs.getValue("grid").toDouble(), 0.000001)
        assertEquals(1.0, draft.inputs.getValue("tolerance").toDouble(), 0.000001)
        assertEquals(120.0, draft.inputs.getValue("height_PIPE").toDouble(), 0.000001)
        val saved = draft.toSettings() ?: throw AssertionError("Converted valid inputs must remain valid")
        assertEquals(LengthUnit.INCHES, saved.projectDefaults.lengthUnit)
        assertEquals(0.3048, saved.projectDefaults.snap.gridSpacing, 0.000001)
        assertEquals(0.0254, saved.projectDefaults.snap.tolerance, 0.000001)
        assertEquals(3.048, saved.projectDefaults.defaultRouteElevations.getValue(RouteKind.PIPE).value, 0.000001)
        assertEquals(ElevationReference.ABOVE_CEILING, saved.projectDefaults.defaultRouteElevations.getValue(RouteKind.PIPE).reference)
    }

    @Test
    fun partialNumericInputIsRetainedWithoutSavingAnInvalidModel() {
        val original = AppSettings()
        val draft = SettingsDraft.from(original).input("grid", "-").input("autosave", "0")

        assertEquals("-", draft.inputs["grid"])
        assertTrue("grid" in draft.errors())
        assertFalse("autosave" in draft.errors())
        assertNull(draft.toSettings())
        assertEquals(0.1, original.projectDefaults.snap.gridSpacing, 0.0)

        val corrected = draft.input("grid", "250").toSettings()
        assertNotNull(corrected)
        assertEquals(0, corrected!!.autosaveIntervalSeconds)
        assertEquals(0.25, corrected.projectDefaults.snap.gridSpacing, 0.000001)
    }

    @Test
    fun extremeConversionsAndSubMetreUnderflowRemainInvalidDraftsInsteadOfCrashing() {
        val metreDefaults = AppSettings(projectDefaults = ProjectSettings(lengthUnit = LengthUnit.METRES))
        val overflow = SettingsDraft.from(metreDefaults)
            .input("grid", "1e308")
            .changeUnits(LengthUnit.MILLIMETRES)

        assertTrue(overflow.inputs.getValue("grid").toDouble().isInfinite())
        assertTrue("grid" in overflow.errors())
        assertNull(overflow.toSettings())

        val underflow = SettingsDraft.from(AppSettings())
            .input("grid", "1e-323")
            .input("tolerance", "4.9e-324")

        assertTrue(underflow.inputs.getValue("grid").toDouble() > 0.0)
        assertTrue("grid" in underflow.errors())
        assertTrue("tolerance" in underflow.errors())
        assertNull(underflow.toSettings())
    }

    @Test
    fun unsafeFilenameComponentsCannotBeSaved() {
        val draft = SettingsDraft.from(AppSettings())
        for (pattern in listOf("../project", "folder\\project", "{unknown}", "..")) {
            val invalid = draft.input("file_naming", pattern)
            assertTrue("file_naming" in invalid.errors())
            assertNull(invalid.toSettings())
        }
        assertNotNull(draft.input("file_naming", "{name}-{date}-{id}").toSettings())
    }
}
