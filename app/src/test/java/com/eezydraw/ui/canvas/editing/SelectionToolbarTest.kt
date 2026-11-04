package com.eezydraw.ui.canvas.editing

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.eezydraw.core.model.Layer
import com.eezydraw.core.model.LayerId
import com.eezydraw.core.model.ProjectId
import com.eezydraw.core.model.ThemeMode
import com.eezydraw.ui.theme.EezyDrawTheme
import org.junit.Assert.assertEquals
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
class SelectionToolbarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectionHasExactlyFiveFingerSizedActionsAndPropertiesNeedsAnExplicitTap() {
        val fixture = Fixture()
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { EezyDrawTheme(theme) { Surface { fixture.Toolbar() } } }
        compose.onAllNodes(hasClickAction()).assertCountEquals(5)
        listOf("mode", "copy", "delete", "properties", "more").forEach { action ->
            compose.onNodeWithTag("selection_$action").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("selection_scale").assertDoesNotExist()
        assertTrue(fixture.actions.isEmpty())
        compose.onNodeWithTag("selection_mode").performClick()
        assertEquals(GizmoMode.ROTATE, fixture.mode)
        compose.onNodeWithTag("selection_mode").performClick()
        assertEquals(GizmoMode.MOVE, fixture.mode)
        compose.runOnIdle { theme = ThemeMode.DARK }
        compose.onNodeWithTag("selection_copy").performClick()
        compose.onNodeWithTag("selection_delete").performClick()
        assertEquals(listOf("copy", "delete"), fixture.actions)
        compose.onNodeWithTag("selection_properties").performClick()
        assertEquals(listOf("copy", "delete", "properties"), fixture.actions)
    }

    @Test
    fun scaleAndAdvancedEditOperationsAreAvailableOnlyInsideMore() {
        val fixture = Fixture()
        compose.setContent { EezyDrawTheme { Surface { fixture.Toolbar() } } }
        chooseMore("scale")
        assertEquals(GizmoMode.SCALE, fixture.mode)
        compose.onNodeWithTag("selection_scale").assertDoesNotExist()
        compose.onNodeWithTag("selection_mode").performClick()
        assertEquals("The primary switch leaves Scale for Move", GizmoMode.MOVE, fixture.mode)
        chooseMore("keep_proportions")
        assertTrue(fixture.keepProportions)
        listOf("mirror_x", "mirror_y", "array", "rotate_90", "paste", "undo", "redo").forEach(::chooseMore)
        assertEquals(listOf("mirror_x", "mirror_y", "array", "rotate_90", "paste", "undo", "redo"), fixture.actions)
        compose.onNodeWithTag("selection_more").performClick()
        compose.onNodeWithTag("selection_layer").performScrollTo().performClick()
        compose.onNodeWithTag("selection_layer_second").performClick()
        assertEquals(LayerId("second"), fixture.layer)
        compose.onNodeWithTag("selection_more").performClick()
        compose.onNodeWithTag("selection_colour").performScrollTo().performClick()
        compose.onNodeWithTag("selection_colour_ffd64747").performClick()
        assertEquals(0xFFD64747L, fixture.colour)
        chooseMore("lock")
        assertTrue(fixture.locked)
        chooseMore("lock")
        assertEquals(false, fixture.locked)
    }

    @Test
    fun eachRotationSnapChoiceIsExplicitAndBackNavigationDoesNotChangeIt() {
        val fixture = Fixture()
        compose.setContent { EezyDrawTheme { Surface { fixture.Toolbar() } } }
        assertEquals(15, fixture.snap)
        listOf(1, 5, 15, 45, 90).forEach { degrees ->
            compose.onNodeWithTag("selection_more").performClick()
            compose.onNodeWithTag("selection_rotation_snap").performScrollTo().performClick()
            compose.onNodeWithTag("selection_snap_$degrees").performClick()
            assertEquals(degrees, fixture.snap)
        }
        compose.onNodeWithTag("selection_more").performClick()
        compose.onNodeWithTag("selection_rotation_snap").performScrollTo().performClick()
        compose.onNodeWithTag("selection_more_back").performClick()
        assertEquals(90, fixture.snap)
        compose.onNodeWithTag("selection_scale").assertIsEnabled()
    }

    @Test
    fun lockedSelectionKeepsCopyPropertiesAndUnlockWithoutEnablingMutation() {
        val fixture = Fixture().apply { editable = false; locked = true; undo = false; redo = false; paste = false }
        compose.setContent { EezyDrawTheme { Surface { fixture.Toolbar() } } }
        compose.onNodeWithTag("selection_delete").assertIsNotEnabled()
        compose.onNodeWithTag("selection_copy").assertIsEnabled().performClick()
        compose.onNodeWithTag("selection_properties").assertIsEnabled().performClick()
        compose.onNodeWithTag("selection_more").performClick()
        listOf("scale", "keep_proportions", "mirror_x", "mirror_y", "array", "layer", "colour", "rotate_90", "paste", "undo", "redo")
            .forEach { action -> compose.onNodeWithTag("selection_$action").performScrollTo().assertIsNotEnabled() }
        compose.onNodeWithTag("selection_lock").performScrollTo().assertIsEnabled().performClick()
        assertEquals(false, fixture.locked)
        assertEquals(listOf("copy", "properties"), fixture.actions)
    }

    private fun chooseMore(action: String) {
        compose.onNodeWithTag("selection_more").performClick()
        compose.onNodeWithTag("selection_$action").performScrollTo().assertIsEnabled().performClick()
    }

    private class Fixture {
        var mode by mutableStateOf(GizmoMode.MOVE)
        var editable by mutableStateOf(true)
        var locked by mutableStateOf(false)
        var undo by mutableStateOf(true)
        var redo by mutableStateOf(true)
        var paste by mutableStateOf(true)
        var layer by mutableStateOf<LayerId?>(LayerId("first"))
        var colour: Long? = null
        var snap by mutableStateOf(15)
        var keepProportions by mutableStateOf(false)
        val actions = mutableListOf<String>()
        private val project = ProjectId("toolbar")
        private val layers = listOf(Layer(LayerId("first"), project, "First"), Layer(LayerId("second"), project, "Second"))

        @Composable fun Toolbar() {
            SelectionToolbar(mode, editable, undo, redo, paste, layers, layer, locked, snap,
                onMode = { mode = it }, onCopy = { actions += "copy" }, onDelete = { actions += "delete" },
                onProperties = { actions += "properties" }, onMirrorX = { actions += "mirror_x" },
                onMirrorY = { actions += "mirror_y" }, onArray = { actions += "array" },
                onRotate90 = { actions += "rotate_90" }, onUndo = { actions += "undo" }, onRedo = { actions += "redo" },
                onPaste = { actions += "paste" }, onLayer = { layer = it }, onColour = { colour = it },
                onLock = { locked = it }, onRotationSnap = { snap = it },
                keepProportions = keepProportions,onKeepProportions = {keepProportions=it})
        }
    }
}
