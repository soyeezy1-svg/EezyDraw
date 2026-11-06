package com.eezydraw.ui.canvas.editing

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
import com.eezydraw.core.model.*
import com.eezydraw.ui.theme.EezyDrawTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class FittingPropertiesEditorTest {
    @get:Rule val compose = createComposeRule()
    private val transform = ElementProperties(Point3(1.0, 2.0, 1.2), Rotation3(), Point3(.4, .4, .2))
    private val library = LibraryInstanceProperties(Size3(.4, .4, .2), 1.2,
        "Steel", 0xFF999999, "HVAC", "Generic", "", "Duct reducer",
        section = RouteSection.Rectangular(.3, .15), outletSection = RouteSection.Rectangular(.2, .1))

    @Test fun connectionFieldsApplyInProjectUnitsWhileEnvelopeRemainsReadOnly() {
        val changes = mutableListOf<LibraryInstanceProperties>()
        compose.setContent { EezyDrawTheme {
            PropertiesDialog(transform, LengthUnit.MILLIMETRES, 1, {}, {}, libraryProperties = library,
                onApplyLibrary = { _, item -> changes += item })
        } }
        compose.onNodeWithTag("property_width").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("property_depth").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("property_height").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("property_fitting_section_width").performScrollTo().performTextReplacement("500")
        compose.onNodeWithTag("property_fitting_section_height").performScrollTo().performTextReplacement("250")
        compose.onNodeWithTag("property_fitting_outlet_width").performScrollTo().performTextReplacement("300")
        compose.onNodeWithTag("property_fitting_outlet_height").performScrollTo().performTextReplacement("150")
        compose.onNodeWithTag("property_mounting_height").performScrollTo().performTextReplacement("-400")
        compose.onNodeWithTag("properties_apply").performClick()
        assertEquals(library.copy(section = RouteSection.Rectangular(.5, .25),
            outletSection = RouteSection.Rectangular(.3, .15), mountingHeight = -.4), changes.single())
    }

    @Test fun invalidCircularOutletDisablesApplyAndRestoresDraft() {
        val circular = library.copy(section = RouteSection.Circular(.1), outletSection = RouteSection.Circular(.05))
        val changes = mutableListOf<LibraryInstanceProperties>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EezyDrawTheme {
            PropertiesDialog(transform, LengthUnit.CENTIMETRES, 2, {}, {}, libraryProperties = circular,
                onApplyLibrary = { _, item -> changes += item }, onResetToLibraryDefault = {})
        } }
        compose.onNodeWithTag("property_fitting_diameter").performScrollTo().assertTextContains("10.00")
        compose.onNodeWithTag("property_fitting_outlet_diameter").performScrollTo().performTextReplacement("-")
        compose.onNodeWithTag("properties_apply").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("property_fitting_outlet_diameter").performScrollTo().assertTextContains("-")
        compose.onNodeWithTag("properties_reset_library").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("property_fitting_outlet_diameter").performScrollTo().performTextReplacement("8")
        compose.onNodeWithTag("properties_apply").performClick()
        assertEquals(RouteSection.Circular(.08), changes.single().outletSection)
    }

    @Test fun positionLockProtectsConnectionSizesAndCanonicalReset() {
        compose.setContent { EezyDrawTheme {
            PropertiesDialog(transform, LengthUnit.MILLIMETRES, 1, {}, {}, lockMode = LockMode.POSITION_ONLY,
                libraryProperties = library, onApplyLibrary = { _, _ -> }, onResetToLibraryDefault = {})
        } }
        compose.onNodeWithTag("property_fitting_section_width").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("property_fitting_outlet_height").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("property_mounting_height").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("property_material").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("properties_reset_library").performScrollTo().assertIsNotEnabled()
    }
}
