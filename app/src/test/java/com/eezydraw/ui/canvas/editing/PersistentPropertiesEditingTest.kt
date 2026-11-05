package com.eezydraw.ui.canvas.editing

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
@Config(sdk=[28],qualifiers="w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class PersistentPropertiesEditingTest {
    @get:Rule val compose=createComposeRule()

    @Test fun anotherNumericEditCanBeAppliedAfterAnUnchangedApplyLeavesThePanelOpen() {
        val source=ElementProperties(Point3(1.0,2.0,3.0),Rotation3(),Point3(1.0,1.0,1.0))
        val changes=mutableListOf<ElementProperties>()
        // The document is unchanged by the first Apply, so its supplied value remains equal.
        compose.setContent {EezyDrawTheme {PropertiesDialog(source,LengthUnit.MILLIMETRES,2,changes::add,{})}}
        compose.onNodeWithTag("properties_apply").performClick().assertIsNotEnabled()
        assertEquals(listOf(source),changes)
        compose.onNodeWithTag("property_x").performScrollTo().performTextReplacement("1250")
        compose.onNodeWithTag("properties_apply").assertIsEnabled().performClick()
        assertEquals(2,changes.size)
        assertEquals(1.25,changes.last().position.x,1e-9)
        assertEquals(source.position.y,changes.last().position.y,0.0)
    }
}
