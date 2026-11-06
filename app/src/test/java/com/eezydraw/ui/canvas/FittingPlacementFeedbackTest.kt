package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.eezydraw.core.editing.LibraryPlacement
import com.eezydraw.core.editing.PrecisionPick
import com.eezydraw.core.editing.projectPrecisionPoint
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class FittingPlacementFeedbackTest {
    @get:Rule val compose = createComposeRule()
    private val projectId = ProjectId("fitting-feedback")
    private val layerId = LayerId("services")
    private val section = RouteSection.Rectangular(.45,.25)
    private val itemId = LibraryItemId("test.tray-coupler")
    private val item = LibraryItem(itemId,"Tray coupler",Discipline.ELECTRICAL,"Tray fittings",Size3(.45,.2,.25),
        fitting=FittingDefinition(itemId,RouteKind.CABLE_TRAY,FittingKind.COUPLER,section,
            ports=listOf(FittingPort("input",Point3(.225,0.0,.125),Point3(0.0,-1.0,0.0),section)),
            centreline=listOf(Point3(.225,0.0,.125),Point3(.225,.2,.125))))
    private val route = Element(ElementId("tray-route"),projectId,layerId,
        ElementBody.Route(RouteKind.CABLE_TRAY,listOf(Point3(-1.0,-.1,1.2),Point3(.4,-.1,1.2)),section,
            ElevationSetting(1.2,ElevationReference.ABOVE_FINISHED_FLOOR)),name="West corridor tray")
    private val document = ProjectDocument(Project(projectId,"Fittings",0,0,
        settings=ProjectSettings(decimalPlaces=1)),layers=listOf(Layer(layerId,projectId,"Services")),
        libraryItems=listOf(item),elements=listOf(route))
    private val connection = Point3(.4,-.1,1.2)
    private val element = Element(ElementId("preview"),projectId,layerId,ElementBody.Component(item.id,item.defaultSize),
        transform=Transform3(translation=Point3(.175,-.1,1.075)))
    private val placement = LibraryPlacement(element,item,1.2,false,
        connectionPoint=connection,connectedRouteId=route.id,connectionSection=section)
    private val viewport = Viewport2D(pixelsPerMetre=120.0)
    private val orbit = OrbitCamera(target=Point3(0.0,0.0,.7),orthographicHeight=4.0)

    @Test fun connectionMarkerUsesThePortAndFollowsPlanAndOrbitProjection() {
        var is3D by mutableStateOf(false)
        compose.setContent { MaterialTheme { Box(Modifier.size(400.dp,320.dp)) {
            LibraryGhostPreview(document,item.id,Point3(),viewport,orbit,is3D,placement=placement)
        } } }
        fun checkProjection(threeDimensional: Boolean): Point2 {
            val bounds = compose.onNodeWithTag("library_ghost").fetchSemanticsNode().boundsInRoot
            val expected = checkNotNull(projectPrecisionPoint(connection,viewport,orbit,threeDimensional,
                bounds.width.toDouble(),bounds.height.toDouble()))
            val marker = compose.onNodeWithTag("fitting_connection_point").assertIsDisplayed()
                .assertContentDescriptionEquals("Connection point").fetchSemanticsNode()
            assertEquals(connection.x,marker.config[PrecisionWorldX],0.0)
            assertEquals(connection.y,marker.config[PrecisionWorldY],0.0)
            assertEquals(connection.z,marker.config[PrecisionWorldZ],0.0)
            assertEquals(expected.x,marker.config[PrecisionScreenX],1e-8)
            assertEquals(expected.y,marker.config[PrecisionScreenY],1e-8)
            compose.onNodeWithTag("fitting_connection_label").assertTextEquals("Connection point")
            return expected
        }
        val planPoint = checkProjection(false)
        compose.runOnIdle { is3D=true }
        assertNotEquals(planPoint,checkProjection(true))
        assertEquals(listOf(route),document.elements)
    }

    @Test fun markerDisappearsWhenTheFittingDraftIsDiscarded() {
        var preview by mutableStateOf<LibraryPlacement?>(placement)
        compose.setContent { MaterialTheme { Box(Modifier.size(400.dp,320.dp)) {
            LibraryGhostPreview(document,item.id,null,viewport,orbit,false,placement=preview)
        } } }
        compose.onNodeWithTag("fitting_connection_point").assertExists()
        compose.runOnIdle { preview=null }
        compose.onNodeWithTag("fitting_connection_point").assertDoesNotExist()
        compose.onNodeWithTag("fitting_connection_label").assertDoesNotExist()
    }

    @Test fun routeFeedbackShowsAdaptedSectionAndActualConnectionHeight() {
        compose.setContent { MaterialTheme { LibraryPlacementFeedback(document,item,placement,null) } }
        compose.onNodeWithText("Mounting height: 1200.0 mm AFFL").assertExists()
        compose.onNodeWithTag("fitting_connection_status").assertTextEquals("Connected to route: West corridor tray")
        compose.onNodeWithTag("fitting_connection_section").assertTextEquals("Section: 450.0 × 250.0 mm")
    }

    @Test fun freeFittingFeedbackExplainsConnectionAndUsesDiameterInProjectUnits() {
        val source = document.copy(project=document.project.copy(settings=ProjectSettings(lengthUnit=LengthUnit.METRES,decimalPlaces=2)))
        val free = placement.copy(connectedRouteId=null,connectionSection=RouteSection.Circular(.15))
        compose.setContent { MaterialTheme { LibraryPlacementFeedback(source,item,free,null) } }
        compose.onNodeWithTag("fitting_connection_status").assertTextEquals("Move near a matching route end to connect.")
        compose.onNodeWithTag("fitting_connection_section").assertTextEquals("Diameter: 0.15 m")
    }

    @Test fun invalidCandidateKeepsThePlacementErrorVisible() {
        compose.setContent { MaterialTheme { LibraryPlacementFeedback(document,item,null,
            "The Power system is hidden. Show it before placing this item.") } }
        compose.onNodeWithTag("library_placement_error").assertTextEquals(
            "The Power system is hidden. Show it before placing this item.")
        compose.onNodeWithTag("fitting_connection_status").assertDoesNotExist()
        compose.onNodeWithTag("fitting_connection_section").assertDoesNotExist()
    }

    @Test fun resolvedPrecisionPickDisplaysThePortAndPreservesTheRawAimForConfirmation() {
        val raw = PrecisionPick(Point3(.2,.1,0.0),Point2(200.0,180.0),SnapMode.GRID)
        val resolved = checkNotNull(resolvedLibraryPrecisionPick(raw,placement,viewport,orbit,true,800.0,600.0))
        assertEquals(connection,resolved.point)
        assertNotEquals(element.transform.translation,resolved.point)
        assertEquals(projectPrecisionPoint(connection,viewport,orbit,true,800.0,600.0),resolved.screen)
        assertEquals(route.id,resolved.elementId)
        assertEquals(SnapMode.ENDPOINT,resolved.mode)
        assertEquals(Point3(.2,.1,0.0),raw.point)
        assertNull(resolvedLibraryPrecisionPick(null,placement,viewport,orbit,true,800.0,600.0))
    }
}
