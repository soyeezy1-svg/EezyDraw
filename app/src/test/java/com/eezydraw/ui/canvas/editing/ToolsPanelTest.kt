package com.eezydraw.ui.canvas.editing

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import com.eezydraw.core.editing.DrawingTool
import com.eezydraw.core.editing.ToolOptions
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.ui.theme.EezyDrawTheme
import com.eezydraw.ui.canvas.panels.CanvasToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ToolsPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun drawAndEditGroupsCollapseIndependentlyWithoutChangingTheActiveTool() {
        var selected by mutableStateOf(DrawingTool.SELECT)
        compose.setContent { EezyDrawTheme { Panel(selected, onTool = { selected = it }) } }
        compose.onNodeWithTag("tools_expand").performClick()
        compose.onNodeWithTag("tools_draw_group").performScrollTo().performClick()
        compose.onNodeWithTag("tool_line").assertDoesNotExist()
        compose.onNodeWithTag("tools_copy").assertExists()
        compose.onNodeWithTag("tools_edit_group").performScrollTo().performClick()
        compose.onNodeWithTag("tools_copy").assertDoesNotExist()
        assertEquals(DrawingTool.SELECT, selected)
        compose.onNodeWithTag("tools_draw_group").performScrollTo().performClick()
        compose.onNodeWithTag("tool_circle").performScrollTo().performClick()
        assertEquals(DrawingTool.CIRCLE, selected)
    }

    @Test
    fun panelStartsCollapsedSelectsAToolAndRetainsExpansionAcrossRestoration() {
        var selected by mutableStateOf(DrawingTool.SELECT)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EezyDrawTheme { Panel(tool = selected, onTool = { selected = it }) } }
        compose.onNodeWithTag("tools_panel").assertDoesNotExist()
        compose.onNodeWithTag("tools_expand").performClick()
        compose.onNodeWithTag("tool_line").performScrollTo().performClick()
        assertEquals(DrawingTool.LINE, selected)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tools_panel").assertExists()
        compose.onNodeWithTag("tools_collapse").performClick()
        compose.onNodeWithTag("tools_panel").assertDoesNotExist()
        assertEquals(DrawingTool.LINE, selected)
    }

    @Test
    fun finishAndSelectionActionsHonorCapabilityFlags() {
        var finishable by mutableStateOf(false)
        var selected by mutableStateOf(0)
        val actions = mutableListOf<String>()
        compose.setContent { EezyDrawTheme {
            Panel(DrawingTool.POLYLINE, selectedCount = selected, canFinish = finishable,
                onFinish = { actions += "finish" }, onCancel = { actions += "cancel" },
                onCopy = { actions += "copy" }, onDelete = { actions += "delete" })
        } }
        compose.onNodeWithTag("tools_expand").performClick()
        compose.onNodeWithTag("tools_finish").assertIsNotEnabled()
        compose.onNodeWithTag("tools_copy").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("tools_paste").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { finishable = true; selected = 1 }
        compose.onNodeWithTag("tools_finish").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("tools_cancel").performClick()
        compose.onNodeWithTag("tools_copy").performScrollTo().performClick()
        compose.onNodeWithTag("tools_delete").performScrollTo().performClick()
        assertEquals(listOf("finish", "cancel", "copy", "delete"), actions)
    }

    @Test
    fun solidHeightConvertsDisplayUnitsAndRejectsInvalidOptions() {
        var options by mutableStateOf(ToolOptions())
        var updates = 0
        compose.setContent { EezyDrawTheme {
            Panel(DrawingTool.BOX, options, onOptions = { options = it; updates++ })
        } }
        compose.onNodeWithTag("tools_expand").performClick()
        compose.onNodeWithTag("tool_solid_height").performScrollTo().performTextReplacement("-")
        assertEquals(0, updates)
        compose.onNodeWithTag("tool_solid_height").performTextReplacement("2500")
        assertEquals(1, updates)
        assertEquals(2.5, options.height, 1e-12)
    }

    @Test
    fun blankPanelTouchesDoNotReachCanvasAndChildControlsRemainUsable() {
        var canvasGestures = 0
        var selected by mutableStateOf(DrawingTool.SELECT)
        compose.setContent { EezyDrawTheme {
            Box(Modifier.size(700.dp, 600.dp).testTag("panel_canvas").pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = true)
                    canvasGestures++
                    waitForUpOrCancellation()
                }
            }) { Panel(tool = selected, onTool = { selected = it }) }
        } }
        compose.onNodeWithTag("tools_expand").performTouchInput { click() }
        // The header space between the title and collapse button has no child action.
        compose.onNodeWithTag("tools_panel").performTouchInput {
            click(Offset(width * .55f, 20f))
            swipe(Offset(width * .55f, 20f), Offset(width * .55f, 100f))
        }
        compose.onNodeWithTag("tool_line").performScrollTo().performTouchInput { click() }
        assertEquals(DrawingTool.LINE, selected)
        assertEquals(0, canvasGestures)
        compose.onNodeWithTag("tools_collapse").performTouchInput { click() }
        compose.onNodeWithTag("tools_panel").assertDoesNotExist()
        compose.onNodeWithTag("panel_canvas").performTouchInput {
            swipe(Offset(width * .8f, height * .6f), Offset(width * .9f, height * .6f))
        }
        assertEquals(1, canvasGestures)
    }

    @Test
    fun defaultDimensionsStayPhysicalAcrossEveryDisplayUnitAndPlacementToggle() {
        var options by mutableStateOf(ToolOptions())
        var units by mutableStateOf(LengthUnit.MILLIMETRES)
        compose.setContent { EezyDrawTheme {
            Panel(DrawingTool.BOX, options, onOptions = { options = it }, units = units)
        } }
        compose.onNodeWithTag("tools_expand").performClick()
        LengthUnit.entries.forEach { next ->
            compose.runOnIdle { units = next }
            compose.waitForIdle()
            listOf("tool_default_width", "tool_default_depth", "tool_solid_height").forEach { tag ->
                compose.onNodeWithTag(tag).performScrollTo()
                assertEquals(1.0 / editingMetresPerUnit(next), inputNumber(tag), 1e-9)
            }
            assertEquals(1.0, options.width, 0.0)
            assertEquals(1.0, options.depth, 0.0)
            assertEquals(1.0, options.height, 0.0)
        }
        compose.runOnIdle { units = LengthUnit.MILLIMETRES }
        compose.onNodeWithTag("tool_default_width").performScrollTo().performTextReplacement("2400")
        compose.onNodeWithTag("tool_default_depth").performScrollTo().performTextReplacement("1800")
        compose.onNodeWithTag("tool_solid_height").performScrollTo().performTextReplacement("3200")
        compose.onNodeWithTag("tool_draw_by_points").performScrollTo().assertIsOff().performClick().assertIsOn()
        assertFalse(options.useDefaultDimensions)
        compose.onNodeWithTag("tool_default_width").assertDoesNotExist()
        compose.onNodeWithTag("tool_draw_by_points").performClick().assertIsOff()
        compose.onNodeWithTag("tool_default_width").assertTextContains("2400")
        compose.onNodeWithTag("tool_default_depth").performScrollTo().assertTextContains("1800")
        compose.onNodeWithTag("tool_solid_height").performScrollTo().assertTextContains("3200")
        assertEquals(2.4, options.width, 1e-12)
        assertEquals(1.8, options.depth, 1e-12)
        assertEquals(3.2, options.height, 1e-12)
    }

    @Test
    fun dimensionDraftRetainsDecimalInputAndInvalidTextAcrossRestoration() {
        var options by mutableStateOf(ToolOptions())
        var units by mutableStateOf(LengthUnit.METRES)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EezyDrawTheme {
            Panel(DrawingTool.RECTANGLE, options, onOptions = { options = it }, units = units)
        } }
        compose.onNodeWithTag("tools_expand").performClick()
        compose.onNodeWithTag("tool_default_width").performScrollTo().performTextReplacement("2.")
        compose.onNodeWithTag("tool_default_width").assertTextContains("2.")
        assertEquals(2.0, options.width, 0.0)
        compose.onNodeWithTag("tool_default_width").performTextReplacement("2.75")
        assertEquals(2.75, options.width, 0.0)
        compose.onNodeWithTag("tool_default_depth").performScrollTo().performTextReplacement("-")
        assertEquals(1.0, options.depth, 0.0)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tool_default_depth").performScrollTo().assertTextContains("-")
        compose.runOnIdle { units = LengthUnit.INCHES }
        compose.onNodeWithTag("tool_default_depth").assertTextContains("-")
        compose.onNodeWithTag("tool_default_width").performScrollTo()
        assertEquals(2.75 / .0254, inputNumber("tool_default_width"), 1e-9)
        assertEquals(2.75, options.width, 0.0)
    }

    @Test
    fun roundSolidsExposeDiameterWithoutIndependentDepthAndSelectionExposesProportionLock() {
        var tool by mutableStateOf(DrawingTool.SPHERE)
        var options by mutableStateOf(ToolOptions())
        compose.setContent { EezyDrawTheme {
            Panel(tool, options, onOptions = { options = it })
        } }
        compose.onNodeWithTag("tools_expand").performClick()
        compose.onNodeWithTag("tool_default_width").assertTextContains("Diameter", substring = true)
        compose.onNodeWithTag("tool_default_depth").assertDoesNotExist()
        compose.onNodeWithTag("tool_solid_height").assertDoesNotExist()
        compose.onNodeWithTag("tool_default_width").performScrollTo().performTextReplacement("2000")
        assertEquals(2.0, options.width, 0.0)
        compose.runOnIdle { tool = DrawingTool.CYLINDER }
        compose.onNodeWithTag("tool_default_width").assertTextContains("2000")
        compose.onNodeWithTag("tool_default_depth").assertDoesNotExist()
        compose.onNodeWithTag("tool_solid_height").assertExists()
        compose.runOnIdle { tool = DrawingTool.SELECT }
        compose.onNodeWithTag("tool_keep_proportions").assertIsOff().performClick().assertIsOn()
        assertTrue(options.keepProportions)
    }

    @Test
    fun embeddedDrawAndEditContentHaveNoAutomaticExpansionOrDuplicateChrome() {
        var focus by mutableStateOf(CanvasToolGroup.DRAW)
        var tool by mutableStateOf(DrawingTool.SELECT)
        compose.setContent {EezyDrawTheme {Panel(tool=tool,onTool={tool=it},embedded=true,focusGroup=focus)}}
        compose.onNodeWithTag("tool_line").performScrollTo().performClick()
        assertEquals(DrawingTool.LINE,tool)
        compose.onNodeWithTag("tools_expand").assertDoesNotExist()
        compose.onNodeWithTag("tools_collapse").assertDoesNotExist()
        compose.onNodeWithTag("tools_undo").assertDoesNotExist()
        compose.onNodeWithTag("tools_panel").assertExists()
        compose.runOnIdle {focus=CanvasToolGroup.EDIT}
        compose.onNodeWithTag("edit_tools_panel").assertExists()
        compose.onNodeWithTag("tools_copy").assertExists()
        compose.onNodeWithTag("tool_line").assertDoesNotExist()
        compose.onNodeWithTag("tools_finish").assertDoesNotExist()
    }

    private fun inputNumber(tag: String): Double = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.EditableText].text.toDouble()

    @Composable
    private fun Panel(
        tool: DrawingTool = DrawingTool.SELECT,
        options: ToolOptions = ToolOptions(),
        selectedCount: Int = 0,
        canFinish: Boolean = false,
        onTool: (DrawingTool) -> Unit = {},
        onOptions: (ToolOptions) -> Unit = {},
        onFinish: () -> Unit = {},
        onCancel: () -> Unit = {},
        onCopy: () -> Unit = {},
        onDelete: () -> Unit = {},
        units: LengthUnit = LengthUnit.MILLIMETRES,
        embedded: Boolean = false,
        focusGroup: CanvasToolGroup? = null,
    ) = ToolsPanel(tool, options, units, selectedCount,
        canUndo = false, canRedo = false, canPaste = false, canFinish = canFinish,
        onTool, onOptions, onFinish, onCancel, {}, {}, onCopy, {}, {}, {}, {}, onDelete, {},
        embedded=embedded,focusGroup=focusGroup)
}
