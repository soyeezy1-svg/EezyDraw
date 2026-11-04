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
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Rotation3
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
class PropertiesDialogTest {
    @get:Rule val compose = createComposeRule()
    private val source = ElementProperties(Point3(1.0, 2.0, 3.0), Rotation3(z = Math.PI / 2), Point3(2.0, 3.0, 0.0))

    @Test
    fun applyConvertsPhysicalUnitsAndDegreesAndRunsOnlyOnce() {
        val edits = mutableListOf<ElementProperties>()
        compose.setContent { EezyDrawTheme { PropertiesDialog(source, LengthUnit.MILLIMETRES, 2, edits::add, {}) } }
        compose.onNodeWithTag("property_x").assertTextContains("1000.00").performTextReplacement("1250")
        compose.onNodeWithTag("property_rotation_z").performScrollTo().assertTextContains("90.00").performTextReplacement("180")
        compose.onNodeWithTag("property_width").performScrollTo().performTextReplacement("2500")
        compose.onNodeWithTag("property_height").performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList<ElementProperties>(), edits)
        compose.onNodeWithTag("properties_apply").performClick().assertIsNotEnabled()
        assertEquals(1, edits.size)
        assertEquals(1.25, edits.single().position.x, 1e-12)
        assertEquals(Math.PI, edits.single().rotation.z, 1e-12)
        assertEquals(Point3(2.5, 3.0, 0.0), edits.single().size)
    }

    @Test
    fun partialInvalidDraftSurvivesRestorationAndCancelNeverApplies() {
        var applies = 0
        var dismisses = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EezyDrawTheme {
            PropertiesDialog(source, LengthUnit.INCHES, 2, { applies++ }, { dismisses++ })
        } }
        compose.onNodeWithTag("property_x").performTextReplacement("-")
        compose.onNodeWithTag("properties_apply").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("property_x").assertTextContains("-")
        compose.onNodeWithTag("properties_apply").assertIsNotEnabled()
        compose.onNodeWithTag("properties_cancel").performClick()
        assertEquals(0, applies)
        assertEquals(1, dismisses)
    }

    @Test
    fun positiveSizeAndFiniteRotationAreRequiredAndUntouchedRoundedValuesRemainExact() {
        val exact = source.copy(position = Point3(.00000003123, 2.0, 3.0), size = Point3(.0000000002, 3.0, 0.0))
        val edits = mutableListOf<ElementProperties>()
        compose.setContent { EezyDrawTheme { PropertiesDialog(exact, LengthUnit.METRES, 2, edits::add, {}) } }
        compose.onNodeWithTag("properties_apply").assertIsEnabled()
        compose.onNodeWithTag("property_depth").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("properties_apply").assertIsNotEnabled()
        compose.onNodeWithTag("property_depth").performTextReplacement("3")
        compose.onNodeWithTag("property_rotation_z").performScrollTo().performTextReplacement("NaN")
        compose.onNodeWithTag("properties_apply").assertIsNotEnabled()
        compose.onNodeWithTag("property_rotation_z").performTextReplacement("90.00")
        compose.onNodeWithTag("properties_apply").performClick()
        assertEquals(exact, edits.single())
    }
}
