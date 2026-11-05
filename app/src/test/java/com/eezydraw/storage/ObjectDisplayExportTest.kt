package com.eezydraw.storage

import android.graphics.Bitmap
import android.graphics.Color
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.ViewPreset
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.*
import com.eezydraw.ui.canvas.CanvasFrame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ObjectDisplayExportTest {
    private val project=ProjectId("object-display-export")
    private val layer=LayerId("base")
    private val red=0xFFE32222L
    private val blue=0xFF2255E3L
    private val background=Color.rgb(247,250,252)
    private val box=Element(ElementId("box"),project,layer,ElementBody.DrawingShape(Shape.Box(Size3(1.0,1.0,1.0))))
    private fun document(vararg elements:Element)=ProjectDocument(Project(project,"Display",0,0),
        layers=listOf(Layer(layer,project,"Base",colourArgb=red)),elements=elements.toList())
    private fun frame(mode:ViewMode)=CanvasFrame(Viewport2D(Point2(.5,.5),100.0),OrbitCamera(),viewMode=mode,showGrid=false)
    private fun image(document:ProjectDocument,frame:CanvasFrame)=ViewExport.render(ViewExportRequest(document,frame,200,200))
    private fun Bitmap.countInk():Int=(0 until width).sumOf {x->(0 until height).count {y->getPixel(x,y)!=background}}

    @Test fun objectWireframeHasNoFillAndObjectXrayStillHasFacesInAWireframeProject() {
        val filled=image(document(box),frame(ViewMode.SHADED))
        val wire=image(document(box.withDisplay(ObjectDisplay.WIREFRAME)),frame(ViewMode.SHADED))
        val xray=image(document(box.withDisplay(ObjectDisplay.XRAY)),frame(ViewMode.WIREFRAME))
        try {
            assertNotEquals(background,filled.getPixel(100,100))
            assertEquals("Only this object's faces are suppressed",background,wire.getPixel(100,100))
            assertTrue(wire.countInk()>100)
            assertTrue("An object X-ray overrides the global wireframe mode",Color.green(xray.getPixel(100,100))<230)
            assertTrue("Transparent fill exposes the background",Color.green(xray.getPixel(100,100))>80)
        } finally {filled.recycle();wire.recycle();xray.recycle()}
    }

    @Test fun hiddenObjectsAreAbsentUntilFaintOutlinesAreRequested() {
        val hidden=document(box.withDisplay(ObjectDisplay.HIDDEN))
        val absent=image(hidden,frame(ViewMode.SHADED))
        val shown=image(hidden.copy(project=hidden.project.copy(settings=hidden.project.settings.copy(showHiddenObjects=true))),frame(ViewMode.SHADED))
        try {
            assertEquals(0,absent.countInk())
            assertTrue(shown.countInk()>100)
            assertEquals("Hidden outlines have no filled faces",background,shown.getPixel(100,100))
            val visible=shown.getPixel(50,100)
            assertTrue("Ghost alpha keeps the outline unobtrusive",Color.green(visible)>180)
        } finally {absent.recycle();shown.recycle()}
    }

    @Test fun oneRoomWallCanBeTransparentOrHiddenAndCameraAutoXrayExposesTheInterior() {
        val room=Element(ElementId("room"),project,layer,ElementBody.Room(Polygon2(listOf(
            Point2(-1.0,-1.0),Point2(1.0,-1.0),Point2(1.0,1.0),Point2(-1.0,1.0))),ceilingHeight=2.0))
        val inside=box.copy(id=ElementId("inside"),body=ElementBody.DrawingShape(Shape.Box(Size3(.5,.5,.5))),
            transform=Transform3(translation=Point3(-.25,-.25,.75)),properties=mapOf(ELEMENT_COLOUR_KEY to ParameterValue.Colour(blue)))
        val camera=OrbitCamera(target=Point3(0.0,0.0,1.0),orthographicHeight=3.0).preset(ViewPreset.FRONT)
        val view=frame(ViewMode.SHADED).copy(is3D=true,orbit=camera)
        val normal=image(document(room,inside),view)
        val hidden=image(document(room.withRoomSideDisplay(RoomSideRef(room.id,0),ObjectDisplay.HIDDEN),inside),view)
        val xray=image(document(room.withRoomSideDisplay(RoomSideRef(room.id,0),ObjectDisplay.XRAY),inside),view)
        val automatic=image(document(room,inside),view.copy(autoXrayFrontWalls=true))
        try {
            val solid=normal.getPixel(100,100)
            assertTrue("The front wall normally occludes the interior",Color.red(solid)>Color.blue(solid))
            for ((name,image) in listOf("hidden" to hidden,"X-ray" to xray,"automatic" to automatic)) {
                val pixel=image.getPixel(100,100)
                assertTrue("$name front wall reveals the blue interior: $pixel",Color.blue(pixel)>Color.red(pixel))
            }
            assertNotEquals("Transparent wall remains visible over the interior",hidden.getPixel(100,100),xray.getPixel(100,100))
        } finally {normal.recycle();hidden.recycle();xray.recycle();automatic.recycle()}
    }
}
