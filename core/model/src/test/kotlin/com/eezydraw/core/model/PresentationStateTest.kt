package com.eezydraw.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PresentationStateTest {
    private val project=ProjectId("presentation")
    private val room=Element(ElementId("room"),project,LayerId("base"),ElementBody.Room(
        Polygon2(listOf(Point2(0.0,0.0),Point2(4.0,0.0),Point2(4.0,3.0),Point2(0.0,3.0)))))

    @Test fun oldPreferencesAndProjectsKeepManualPanelsAndNormalUnlockedObjects() {
        val app=Json.decodeFromString<AppSettings>("{}")
        assertFalse(app.autoHidePanelsWhileDrawing);assertFalse(app.lockWallsByDefault)
        val settings=Json.decodeFromString<ProjectSettings>("{}")
        assertEquals(CanvasLayout(),settings.canvasLayout)
        assertEquals(PanelVisibility.OPEN,settings.canvasLayout.panel(CanvasPanelId.VIEW).state)
        assertFalse(settings.showHiddenObjects);assertFalse(settings.autoXrayFrontWalls)
        assertEquals(LockMode.NONE,room.lockMode);assertEquals(ObjectDisplay.NORMAL,room.objectDisplay)
        val legacy=room.copy(properties=mapOf(ELEMENT_LOCKED_KEY to ParameterValue.Flag(true)))
        assertEquals(LockMode.ALL,legacy.lockMode);assertTrue(legacy.objectLocked)
    }

    @Test fun lockModesRoundTripAndUnlockClearsLegacyProtectionWithoutChangingGeometry() {
        for(mode in LockMode.entries) {
            val changed=room.withLock(mode)
            assertEquals(mode,changed.lockMode)
            assertEquals(mode!=LockMode.NONE,changed.objectLocked)
            assertEquals(room.body,changed.body);assertEquals(room.transform,changed.transform)
            assertEquals(changed,Json.decodeFromString<Element>(Json.encodeToString(changed)))
            assertEquals(LockMode.NONE,changed.withLock(LockMode.NONE).lockMode)
            assertFalse(ELEMENT_LOCKED_KEY in changed.withLock(LockMode.NONE).properties)
        }
    }

    @Test fun roomSidePresentationIsIndependentAndNeverChangesBoundaryOrOtherSides() {
        val first=RoomSideRef(room.id,0)
        val other=RoomSideRef(room.id,1)
        val displayed=room.withDisplay(ObjectDisplay.WIREFRAME)
            .withRoomSideDisplay(first,ObjectDisplay.HIDDEN).withRoomSideDisplay(other,ObjectDisplay.XRAY)
        assertEquals(ObjectDisplay.WIREFRAME,displayed.objectDisplay)
        assertEquals(ObjectDisplay.HIDDEN,displayed.roomSideDisplay(first))
        assertEquals(ObjectDisplay.XRAY,displayed.roomSideDisplay(other))
        assertEquals(ObjectDisplay.NORMAL,displayed.roomSideDisplay(RoomSideRef(room.id,2)))
        assertFalse(displayed.hasRoomSideDisplay(RoomSideRef(room.id,2)))
        assertTrue(displayed.withRoomSideDisplay(first,ObjectDisplay.NORMAL).hasRoomSideDisplay(first))
        assertEquals(room.body,displayed.body);assertEquals(room.transform,displayed.transform)
        assertEquals(displayed,Json.decodeFromString<Element>(Json.encodeToString(displayed)))
        val restored=displayed.withAllRoomSidesNormal()
        assertEquals(ObjectDisplay.NORMAL,restored.roomSideDisplay(first))
        assertEquals(ObjectDisplay.NORMAL,restored.roomSideDisplay(other))
        assertEquals(ObjectDisplay.WIREFRAME,restored.objectDisplay)
        assertThrows(IllegalArgumentException::class.java) {room.withRoomSideDisplay(RoomSideRef(room.id,4),ObjectDisplay.HIDDEN)}
        assertThrows(IllegalArgumentException::class.java) {room.withRoomSideDisplay(RoomSideRef(ElementId("other"),0),ObjectDisplay.HIDDEN)}
    }

    @Test fun layoutAndVisibilityRoundTripWithProjectAndRejectInvalidPanelCoordinates() {
        val layout=CanvasLayout(hideAll=true).withPanel(CanvasPanelId.PROPERTIES,
            PanelPlacement(PanelVisibility.OPEN,.72,.25)).withPanel(CanvasPanelId.DRAW,PanelPlacement(PanelVisibility.COLLAPSED,.1,.8))
        val settings=ProjectSettings(canvasLayout=layout,hiddenLevelIds=setOf(LevelId("upper")),
            showHiddenObjects=true,autoXrayFrontWalls=true)
        assertEquals(settings,Json.decodeFromString<ProjectSettings>(Json.encodeToString(settings)))
        assertEquals(PanelVisibility.HIDDEN,CanvasLayout(panels=emptyMap()).panel(CanvasPanelId.VISIBILITY).state)
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,-.01,1.01)) {
            assertThrows(IllegalArgumentException::class.java) {PanelPlacement(x=value)}
            assertThrows(IllegalArgumentException::class.java) {PanelPlacement(y=value)}
        }
    }
}
