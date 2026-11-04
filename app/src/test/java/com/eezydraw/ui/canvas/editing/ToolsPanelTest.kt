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
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.eezydraw.core.editing.DrawingTool
import com.eezydraw.core.editing.ToolOptions
import com.eezydraw.core.model.LengthUnit
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
class ToolsPanelTest {
    @get:Rule val compose = createComposeRule()

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
    ) = ToolsPanel(tool, options, LengthUnit.MILLIMETRES, selectedCount,
        canUndo = false, canRedo = false, canPaste = false, canFinish = canFinish,
        onTool, onOptions, onFinish, onCancel, {}, {}, onCopy, {}, {}, {}, {}, onDelete, {})
}
