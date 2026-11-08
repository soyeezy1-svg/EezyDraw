package com.eezydraw.ui.canvas

import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import com.eezydraw.storage.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import kotlin.math.PI

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class RotatedViewExportTest {
    @Test fun rotatedPlanMatrixAndSoftwareExportAgreeWithPickingAndSection() {
        val p=ProjectId("rotated");val l=LayerId("base")
        val e=Element(ElementId("line"),p,l,ElementBody.DrawingShape(Shape.Line(Point3(1.0),Point3(3.0))))
        val doc=ProjectDocument(Project(p,"Rotated",0,0),layers=listOf(Layer(l,p,"Base")),elements=listOf(e))
        val viewport=Viewport2D(Point2(0.0,0.0),40.0,PI/2)
        val matrix=filledPlanMatrix(viewport,400,400,-2.0,2.0)
        val screen=viewport.worldToScreen(Point2(2.0,0.0),400.0,400.0)
        assertEquals(screen.x,(matrix[0]*2+1)*200.0,1e-5);assertEquals(screen.y,(1-matrix[1]*2)*200.0,1e-5)
        val frame=CanvasFrame(viewport,OrbitCamera(),showGrid=false,sectionBox=Bounds3(Point3(1.5,-1.0,-1.0),Point3(2.5,1.0,1.0)))
        val bitmap=ViewExport.render(ViewExportRequest(doc,frame,400,400))
        try {assertNotEquals(bitmap.getPixel(20,20),bitmap.getPixel(screen.x.toInt(),screen.y.toInt()));assertEquals(bitmap.getPixel(20,20),bitmap.getPixel(200,160))}finally {bitmap.recycle()}
    }
}
