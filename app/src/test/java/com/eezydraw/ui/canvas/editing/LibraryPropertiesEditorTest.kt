package com.eezydraw.ui.canvas.editing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.eezydraw.core.editing.ElementProperties
import com.eezydraw.core.editing.LibraryInstanceProperties
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.LockMode
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Rotation3
import com.eezydraw.core.model.Size3
import com.eezydraw.ui.theme.EezyDrawTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryPropertiesEditorTest {
    @get:Rule val compose = createComposeRule()
    private val transform = ElementProperties(Point3(1.0, 2.0, 1.2), Rotation3(), Point3(.086, .025, .086))
    private val library = LibraryInstanceProperties(Size3(.086, .025, .086), 1.2,
        "Plastic", 0xFFF0F0F0, "Power", "Generic", "SW-1", "Switch")

    @Test fun applyChangesDimensionsAndAllInstanceParametersTogetherInCanonicalUnits() {
        val changes = mutableListOf<Pair<ElementProperties, LibraryInstanceProperties>>()
        var plainApplies = 0
        compose.setContent { EezyDrawTheme {
            PropertiesDialog(transform, LengthUnit.MILLIMETRES, 1, { plainApplies++ }, {},
                libraryProperties = library, onApplyLibrary = { geometry, item -> changes += geometry to item })
        } }
        compose.onNodeWithTag("property_width").performScrollTo().performTextReplacement("120")
        compose.onNodeWithTag("property_depth").performScrollTo().performTextReplacement("35")
        compose.onNodeWithTag("property_height").performScrollTo().performTextReplacement("100")
        compose.onNodeWithTag("property_mounting_height").performScrollTo()
            .assertTextContains("1200.0").performTextReplacement("1500")
        compose.onNodeWithTag("property_material").performScrollTo().performTextReplacement("Steel")
        compose.onNodeWithTag("property_colour").performScrollTo().performTextReplacement("#336699")
        compose.onNodeWithTag("property_system").performScrollTo().performTextReplacement("ICT")
        compose.onNodeWithTag("property_manufacturer").performScrollTo().performTextReplacement("Acme")
        compose.onNodeWithTag("property_model").performScrollTo().performTextReplacement("SW-2")
        compose.onNodeWithTag("property_name_tag").performScrollTo().performTextReplacement("Desk switch")
        assertEquals(0, changes.size)
        compose.onNodeWithTag("properties_apply").performClick().assertIsNotEnabled()
        assertEquals(0, plainApplies)
        assertEquals(transform.copy(size = Point3(.12, .035, .1)), changes.single().first)
        assertEquals(library.copy(size = Size3(.12, .035, .1), mountingHeight = 1.5,
            material = "Steel", colourArgb = 0xFF336699, system = "ICT", manufacturer = "Acme",
            model = "SW-2", nameTag = "Desk switch"), changes.single().second)
    }

    @Test fun typeUpdateUsesDraftWhileResetRemainsAvailableForInvalidInput() {
        val updates = mutableListOf<LibraryInstanceProperties>()
        var resets = 0
        var applies = 0
        compose.setContent { EezyDrawTheme {
            PropertiesDialog(transform, LengthUnit.CENTIMETRES, 2, { applies++ }, {},
                libraryProperties = library, onApplyLibrary = { _, _ -> applies++ },
                onUpdateAllOfType = { _, item -> updates += item }, onResetToLibraryDefault = { resets++ })
        } }
        compose.onNodeWithTag("property_mounting_height").performScrollTo().performTextReplacement("140")
        compose.onNodeWithTag("properties_update_all_type").performScrollTo().performClick()
        assertEquals(1.4, updates.single().mountingHeight, 1e-12)
        assertEquals(library.size, updates.single().size)
        assertEquals(0, applies)
        compose.onNodeWithTag("property_colour").performScrollTo().performTextReplacement("invalid")
        compose.onNodeWithTag("properties_apply").assertIsNotEnabled()
        compose.onNodeWithTag("properties_update_all_type").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("properties_reset_library").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, resets)
    }

    @Test fun incompleteMountingDraftSurvivesRestorationAndNeverAppliesOnCancel() {
        var applies = 0
        var dismisses = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EezyDrawTheme {
            PropertiesDialog(transform, LengthUnit.INCHES, 2, {}, { dismisses++ },
                libraryProperties = library, onApplyLibrary = { _, _ -> applies++ })
        } }
        compose.onNodeWithTag("property_mounting_height").performScrollTo().performTextReplacement("-")
        compose.onNodeWithTag("properties_apply").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("property_mounting_height").performScrollTo().assertTextContains("-")
        compose.onNodeWithTag("properties_apply").assertIsNotEnabled()
        compose.onNodeWithTag("properties_cancel").performClick()
        assertEquals(0, applies)
        assertEquals(1, dismisses)
    }

    @Test fun positionLockProtectsMountingAndFullLockProtectsAllParameters() {
        var mode by mutableStateOf(LockMode.POSITION_ONLY)
        compose.setContent { EezyDrawTheme {
            PropertiesDialog(transform, LengthUnit.MILLIMETRES, 2, {}, {}, lockMode = mode,
                libraryProperties = library, onApplyLibrary = { _, _ -> },
                onUpdateAllOfType = { _, _ -> }, onResetToLibraryDefault = {})
        } }
        compose.onNodeWithTag("property_mounting_height").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("property_material").performScrollTo().assertIsEnabled().performTextReplacement("Steel")
        compose.runOnIdle { mode = LockMode.ALL }
        compose.onNodeWithTag("property_material").performScrollTo().assertIsNotEnabled().assertTextContains("Plastic")
        compose.onNodeWithTag("properties_apply").assertIsNotEnabled()
        compose.onNodeWithTag("properties_update_all_type").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("properties_reset_library").performScrollTo().assertIsNotEnabled()
    }

    @Test fun legacyFlatSymbolKeepsItsPositiveCatalogAxisWhenMetadataIsApplied() {
        val flat = transform.copy(size = transform.size.copy(z = 0.0))
        val changes = mutableListOf<Pair<ElementProperties, LibraryInstanceProperties>>()
        compose.setContent { EezyDrawTheme {
            PropertiesDialog(flat, LengthUnit.MILLIMETRES, 2, {}, {}, libraryProperties = library,
                onApplyLibrary = { geometry, item -> changes += geometry to item })
        } }
        compose.onNodeWithTag("property_height").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("properties_apply").performClick()
        assertEquals(flat, changes.single().first)
        assertEquals(library.size, changes.single().second.size)
    }

    @Test fun colourParserRejectsMalformedValuesAndRetainsAlpha() {
        assertEquals(0xFF112233, parseLibraryColour("#112233"))
        assertEquals(0x80112233, parseLibraryColour("0x80112233"))
        assertNull(parseLibraryColour("red"))
        assertNull(parseLibraryColour("#12345"))
        assertNull(parseLibraryColour("#GG112233"))
    }
}
