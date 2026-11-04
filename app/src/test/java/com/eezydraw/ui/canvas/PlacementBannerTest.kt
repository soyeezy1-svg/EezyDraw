package com.eezydraw.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.eezydraw.core.editing.DrawingTool
import com.eezydraw.core.editing.ToolOptions
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.ViewPreset
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlacementBannerTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var previewView: View

    @Test fun choosingAToolShowsItsHintAndRequiresAnExplicitCancelOrDoneAction() {
        val actions = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            PlacementBanner("Box", "Tap to place box", false, { actions += "cancel" }, { actions += "done" })
        } }
        compose.onNodeWithTag("placement_active_tool").assertTextEquals("Box")
        compose.onNodeWithTag("placement_hint").assertTextEquals("Tap to place box")
        compose.onNodeWithTag("placement_cancel").assertHeightIsAtLeast(48.dp).assertIsEnabled()
        compose.onNodeWithTag("placement_done").assertHeightIsAtLeast(48.dp).assertIsNotEnabled()
        assertTrue("Showing a tool must never place an object", actions.isEmpty())
        compose.onNodeWithTag("placement_cancel").performClick()
        assertEquals(listOf("cancel"), actions)
    }

    @Test fun doneCommitsOnlyThroughTheExplicitAction() {
        var commits = 0
        compose.setContent { MaterialTheme { PlacementBanner("Polyline", "Tap next point", true, {}, { commits++ }) } }
        assertEquals(0, commits)
        compose.onNodeWithTag("placement_done").assertIsEnabled().performClick()
        assertEquals(1, commits)
    }

    @Test fun rectangleGhostIsActuallyTranslucentAndLeavesTheProjectUnchanged() {
        val projectId = ProjectId("ghost")
        val document = ProjectDocument(Project(projectId, "Ghost", 0, 0))
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color.Blue)) {
                previewView = LocalView.current
                Box(Modifier.size(200.dp).background(Color.White)) {
                    ConstructionPreview(document, DrawingTool.RECTANGLE, ToolOptions(), emptyList(),
                        Point2(0.0, 0.0), 0.0, Viewport2D(Point2(.5, .5), 100.0))
                }
            }
        }
        val image = captureGhost()
        val pixels = image.toPixelMap()
        val edgeX = image.width / 2 - 50
        val painted = (0 until image.height).map { pixels[edgeX, it] }
            .filter { it.red < .95f && it.blue > .95f }
        assertTrue("A ghost line must be visible", painted.isNotEmpty())
        assertTrue("Ghost pixels must blend with the background instead of opaque blue", painted.all { it.red > .3f && it.green > .3f })
        assertTrue(document.elements.isEmpty())
    }

    @Test fun orbitGhostUsesTheCurrentThreeDimensionalCamera() {
        val projectId = ProjectId("orbit-ghost")
        val document = ProjectDocument(Project(projectId, "Ghost", 0, 0))
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color.Blue)) {
            previewView = LocalView.current
            Box(Modifier.size(240.dp).background(Color.White)) {
                ConstructionPreview(document, DrawingTool.BOX, ToolOptions(), emptyList(), null, 0.0, Viewport2D(),
                    orbit = OrbitCamera(target = Point3(.5, .5, .5), orthographicHeight = 3.0).preset(ViewPreset.ISOMETRIC),
                    is3D = true, cursorWorld = Point3())
            }
        } }
        val image = captureGhost()
        val pixels = image.toPixelMap()
        val painted = (0 until image.height).sumOf { y -> (0 until image.width).count { x -> pixels[x,y].red < .95f } }
        assertTrue("The three-dimensional cube ghost must be drawn", painted > 40)
        assertTrue(document.elements.isEmpty())
    }
    /** Draw the real Compose view without Window/PixelCopy callbacks, absent in paused Robolectric. */
    private fun captureGhost(): androidx.compose.ui.graphics.ImageBitmap {
        val bounds = compose.onNodeWithTag("construction_ghost").fetchSemanticsNode().boundsInRoot
        return compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            previewView.draw(canvas)
            bitmap.asImageBitmap()
        }
    }
}
