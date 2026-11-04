package com.eezydraw.ui.canvas.panels

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.core.editing.createMeasurement
import com.eezydraw.core.model.*
import com.eezydraw.ui.theme.EezyDrawTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Saved reference measurements use the same coordinates and metres as their editing callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class MeasurementsPanelTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val projectId = ProjectId("measure-project")
    private val layerId = LayerId("measure-layer")
    private val levelId = LevelId("level-2")
    private val measurementId = ElementId("socket-offset")

    @Test
    fun listSeparatesPlanDistanceAndHeightAboveFloorAndVisibilityDeleteUseTheMeasurementId() {
        val initial = fixture()
        var document by mutableStateOf(initial)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        val visibility = mutableListOf<Boolean>()
        val deleted = mutableListOf<Set<ElementId>>()
        compose.setContent { EezyDrawTheme(theme) { Box(Modifier.size(700.dp, 700.dp)) {
            MeasurementsPanel(document,
                onVisibility = { visible ->
                    visibility += visible
                    document = document.copy(project = document.project.copy(settings = document.project.settings.copy(showMeasurements = visible)))
                }, onEdit = { _, _, _, _, _, _, _ -> error("Listing must not edit a measurement") },
                onDelete = deleted::add, onDismiss = {})
        } } }

        compose.onNodeWithText("Socket to wall: DIST 1236.93 MM").performScrollTo().assertExists()
        compose.onNodeWithText("PLAN 300.00 MM  AZ 0.00 DEG  EL -75.96 DEG").performScrollTo().assertExists()
        compose.onNodeWithText("AFFL 1200.00 MM").performScrollTo().assertExists()
        compose.onNodeWithText("A X 0.00 MM Y 0.00 MM Z 11200.00 MM").performScrollTo().assertExists()
        compose.onNodeWithText("B X 300.00 MM Y 0.00 MM Z 10000.00 MM").performScrollTo().assertExists()
        compose.onNodeWithTag("measurements_visible").assertIsOn().performClick().assertIsOff()
        assertEquals(listOf(false), visibility)
        assertEquals("Visibility is presentation state, not deletion", initial.elements, document.elements)
        compose.runOnIdle { theme = ThemeMode.DARK }
        compose.onNodeWithTag("measurement_delete_socket-offset").performScrollTo().performClick()
        assertEquals(listOf(setOf(measurementId)), deleted)
        assertEquals(initial.elements, document.elements)
    }

    @Test
    fun editingNameEndAndLabelCoordinatesAppliesOneAtomicMetreValuedCallback() {
        val document = fixture()
        val edits = mutableListOf<Edit>()
        compose.setContent { EezyDrawTheme { Box(Modifier.size(700.dp, 700.dp)) {
            MeasurementsPanel(document, {},
                onEdit = { id, start, end, label, name, coordinates, affl -> edits += Edit(id, start, end, label, name, coordinates, affl) },
                onDelete = {}, onDismiss = {})
        } } }
        compose.onNodeWithTag("measurement_edit_socket-offset").performScrollTo().performClick()
        compose.onNodeWithTag("measurement_name").performScrollTo().performTextReplacement("  Socket position  ")
        compose.onNodeWithTag("measurement_1_X").performScrollTo().assertTextContains("300.00").performTextReplacement("900")
        compose.onNodeWithTag("measurement_1_Y").performScrollTo().performTextReplacement("250")
        compose.onNodeWithTag("measurement_1_Z").performScrollTo().performTextReplacement("10500")
        compose.onNodeWithTag("measurement_2_Y").performScrollTo().performTextReplacement("-750")
        assertTrue("Typing a draft must not mutate the document or dispatch partial edits", edits.isEmpty())
        compose.onNodeWithTag("measurement_apply").assertIsEnabled().performClick()

        assertEquals(1, edits.size)
        val edit = edits.single()
        assertEquals(measurementId, edit.id)
        assertEquals(Point3(0.0, 0.0, 11.2), edit.start)
        assertEquals(Point3(.9, .25, 10.5), edit.end)
        assertEquals(Point3(.7, -.75, 10.9), edit.label)
        assertEquals("Socket position", edit.name)
        assertTrue(edit.coordinates)
        assertTrue(edit.affl)
        compose.onNodeWithTag("measurement_name").assertDoesNotExist()
        assertEquals("The host decides how to commit the callback", fixture(), document)
    }

    @Test
    fun invalidOrZeroLengthDraftCannotApplyAndCancelLeavesTheSavedMeasurementAlone() {
        val edits = mutableListOf<ElementId>()
        val document = fixture()
        compose.setContent { EezyDrawTheme { Box(Modifier.size(700.dp, 700.dp)) {
            MeasurementsPanel(document, {}, onEdit = { id, _, _, _, _, _, _ -> edits += id }, onDelete = {}, onDismiss = {})
        } } }
        compose.onNodeWithTag("measurement_edit_socket-offset").performScrollTo().performClick()
        for (invalid in listOf("-", "NaN", "Infinity")) {
            compose.onNodeWithTag("measurement_1_X").performScrollTo().performTextReplacement(invalid)
            compose.onNodeWithTag("measurement_apply").assertIsNotEnabled()
        }
        compose.onNodeWithTag("measurement_1_X").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("measurement_1_Z").performScrollTo().performTextReplacement("11200")
        compose.onNodeWithTag("measurement_apply").assertIsNotEnabled()
        compose.onNodeWithTag("measurement_1_Z").performScrollTo().performTextReplacement("10000")
        compose.onNodeWithTag("measurement_apply").assertIsEnabled()
        compose.onNodeWithTag("measurement_name").performScrollTo().performTextReplacement("")
        compose.onNodeWithTag("measurement_apply").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.editing_cancel)).performClick()
        compose.onNodeWithTag("measurement_name").assertDoesNotExist()
        assertTrue(edits.isEmpty())
        compose.onNodeWithText("AFFL 1200.00 MM").performScrollTo().assertExists()
    }

    private fun fixture(): ProjectDocument {
        val socket = Element(ElementId("socket"), projectId, layerId,
            ElementBody.Component(LibraryItemId("socket-definition"), Size3(.08, .04, .08)),
            levelId = levelId, transform = Transform3(translation = Point3(0.0, 0.0, 11.2)))
        val wall = Element(ElementId("wall"), projectId, layerId,
            ElementBody.Wall(Point2(0.0, 0.0), Point2(2.0, 0.0), .2, 3.0),
            levelId = levelId, transform = Transform3(translation = Point3(.3, 0.0, 10.0)))
        val document = ProjectDocument(Project(projectId, "Reference positions", 0, 0),
            levels = listOf(Level(levelId, projectId, "Level 2", 10.0, .2, 3.0)),
            layers = listOf(Layer(layerId, projectId, "Electrical")), elements = listOf(socket, wall),
            libraryItems = listOf(LibraryItem(LibraryItemId("socket-definition"), "Socket", Discipline.ELECTRICAL,
                "Electrical", Size3(.08, .04, .08))))
        val sketch = createMeasurement(document, socket.transform.translation, wall.transform.translation,
            Point3(.7, -.4, 10.9), socket.id, wall.id, "Socket to wall", floorLevelId = levelId)
        val measurement = Element(measurementId, projectId, layerId, ElementBody.DrawingShape(sketch.shape),
            levelId = levelId, transform = sketch.transform)
        return document.copy(elements = document.elements + measurement)
    }

    private data class Edit(val id: ElementId, val start: Point3, val end: Point3, val label: Point3,
        val name: String, val coordinates: Boolean, val affl: Boolean)
}
