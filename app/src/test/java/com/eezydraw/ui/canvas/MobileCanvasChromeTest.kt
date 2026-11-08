package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28],qualifiers="w1024dp-h1024dp") @LooperMode(LooperMode.Mode.PAUSED)
class MobileCanvasChromeTest {
    @get:Rule val compose=createComposeRule()
    private var layout by mutableStateOf(CanvasLayout())
    private var phone by mutableStateOf(true)
    private fun show(){compose.setContent {MaterialTheme {Box(Modifier.width(if(phone)360.dp else 840.dp).height(if(phone)800.dp else 420.dp)) {
        MobileCanvasChrome(layout,{layout=it},emptyList(),{},{},{},{},{},true,true,false,cube={Box(Modifier.size(112.dp))})
    }}}}
    private fun group(name:String)=compose.onNodeWithTag("cad_group_$name").performScrollTo()
    @Test fun groupTogglesOnlyOneSubstripAndNeverChangesWithoutTap() {
        show();group("draw").performClick();compose.onNodeWithTag("cad_substrip_draw").assertIsDisplayed()
        group("edit").performClick();compose.onNodeWithTag("cad_substrip_draw").assertDoesNotExist();compose.onNodeWithTag("cad_substrip_edit").assertIsDisplayed()
        group("edit").performClick();assertNull(layout.openGroup)
    }
    @Test fun fullscreenHidesBarsAndRestoresTheSameOpenGroup() {
        show();group("route").performClick();compose.onNodeWithTag("canvas_hide_panels").performClick()
        compose.onNodeWithTag("cad_bottom_bar").assertDoesNotExist();assertEquals(CanvasGroup.ROUTE,layout.openGroup)
        compose.onNodeWithTag("canvas_show_panels").assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("cad_substrip_route").assertIsDisplayed()
    }
    @Test fun scrollingAndPortraitLandscapeKeepTheSelectionAndTouchSizes() {
        show();group("settings").assertIsDisplayed().performClick();assertEquals(CanvasGroup.SETTINGS,layout.openGroup)
        compose.runOnIdle {phone=false};compose.onNodeWithTag("cad_bottom_bar").assertIsDisplayed();group("settings").assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.runOnIdle {phone=true};group("settings").assertIsSelected()
    }
    @Test fun projectLayoutsAndBottomVisibilityStayIndependent() {
        show();group("navigate").performClick();val first=layout
        compose.runOnIdle {layout=CanvasLayout(openGroup=CanvasGroup.LIBRARY,bottomBarVisible=false)}
        compose.onNodeWithTag("cad_bottom_bar").assertDoesNotExist();compose.onNodeWithTag("cad_show_bottom").performClick();compose.onNodeWithTag("cad_substrip_library").assertIsDisplayed()
        compose.runOnIdle {layout=first};compose.onNodeWithTag("cad_substrip_navigate").assertIsDisplayed()
    }
}
