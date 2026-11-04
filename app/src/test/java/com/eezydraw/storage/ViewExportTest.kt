package com.eezydraw.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.ViewPreset
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.model.*
import com.eezydraw.ui.canvas.CanvasFrame
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real bitmap/PDF output, camera projection and depth; no substitute export implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewExportTest {
    private val projectId = ProjectId("export")
    private val layerId = LayerId("drawing")
    private val red = 0xFFE32222L
    private val blue = 0xFF2255E3L
    private val background = Color.rgb(247,250,252)

    @Test fun pngUsesTheCurrentCameraAndVisibleCommittedGeometryWithoutSelectionControls() {
        val visible = shape("line",Shape.Line(Point3(1.4,3.0),Point3(2.6,3.0)))
        val hidden = visible.copy(id=ElementId("hidden"),layerId=LayerId("hidden-layer"))
        val document = document(visible,hidden).copy(layers=listOf(
            Layer(layerId,projectId,"Drawing",colourArgb=red),
            Layer(LayerId("hidden-layer"),projectId,"Hidden",colourArgb=blue,visible=false),
        ))
        val frame = frame(ViewMode.WIREFRAME).copy(viewport=Viewport2D(Point2(2.0,3.0),100.0),
            selectionScene=buildDrawingScene(document),selectionDelta=EditDelta(translation=Point3(100.0,100.0)))
        val stream = TrackingOutput()
        ViewExport.write(ViewExportRequest(document,frame,320,200),ViewExportFormat.PNG,stream)
        assertFalse("The caller owns the SAF output stream",stream.closed)
        val bytes=stream.toByteArray()
        assertArrayEquals(byteArrayOf(-119,80,78,71,13,10,26,10),bytes.take(8).toByteArray())
        val image=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        try {
            assertEquals(320,image.width); assertEquals(200,image.height)
            assertEquals(red.toInt(),image.getPixel(160,100))
            assertEquals(background,image.getPixel(40,100))
            assertEquals(background,image.getPixel(280,100))
            assertEquals(0,countPixels(image) { Color.blue(it)>200 && Color.red(it)<100 })
            // Display-unit preferences cannot change the scene's metre-to-pixel projection.
            val imperial=document.copy(project=document.project.copy(settings=document.project.settings.copy(lengthUnit=LengthUnit.FEET)))
            val other=ViewExport.render(ViewExportRequest(imperial,frame,320,200))
            try { assertTrue(image.sameAs(other)) } finally { other.recycle() }
        } finally { image.recycle() }
    }

    @Test fun sixModesProduceFilledDepthTestedViewsAndUseSystemColour() {
        val item=shape("box",Shape.Box(Size3(1.0,1.0,1.0))).copy(systemId=SystemId("system"),materialId=MaterialId("material"))
        val document=document(item).copy(
            materials=listOf(Material(MaterialId("material"),"Red material",red)),
            systems=listOf(BuildingSystem(SystemId("system"),projectId,"Blue system",Discipline.ELECTRICAL,blue)),
        )
        val modes=listOf(ViewMode.WIREFRAME,ViewMode.HIDDEN_LINE,ViewMode.SHADED,ViewMode.SHADED_WITH_EDGES,ViewMode.XRAY,ViewMode.REALISTIC)
        val centers=mutableMapOf<ViewMode,Int>()
        for(mode in modes) {
            val image=ViewExport.render(ViewExportRequest(document,frame(mode),200,200))
            try {
                centers[mode]=image.getPixel(100,100)
                assertTrue("$mode renders geometry",countPixels(image) { it!=background }>50)
            } finally { image.recycle() }
        }
        assertEquals(background,centers[ViewMode.WIREFRAME])
        assertEquals(background,centers[ViewMode.HIDDEN_LINE])
        val shaded=checkNotNull(centers[ViewMode.SHADED])
        assertTrue(Color.blue(shaded)>Color.red(shaded))
        assertNotEquals(background,shaded)
        val xray=checkNotNull(centers[ViewMode.XRAY])
        assertTrue("Transparent fill blends with the background",Color.red(xray)>Color.red(shaded))
        assertNotEquals(shaded,centers[ViewMode.REALISTIC])
    }

    @Test fun hiddenLineOmitsAnOpenEdgeBehindASolidButWireframeKeepsIt() {
        val box=shape("box",Shape.Box(Size3(1.0,1.0,1.0)),Transform3(translation=Point3(0.0,-.5,0.0)))
        val edge=shape("behind",Shape.Line(Point3(.1,1.0,.5),Point3(.9,1.0,.5))).copy(layerId=LayerId("red-edge"))
        val document=document(box,edge).copy(layers=listOf(
            Layer(layerId,projectId,"Blue box",colourArgb=blue),
            Layer(LayerId("red-edge"),projectId,"Red edge",colourArgb=red),
        ))
        val camera=OrbitCamera(target=Point3(.5,0.0,.5),distance=10.0,orthographicHeight=2.0).preset(ViewPreset.FRONT)
        val wire=ViewExport.render(ViewExportRequest(document,frame(ViewMode.WIREFRAME).copy(is3D=true,orbit=camera),200,200))
        val hidden=ViewExport.render(ViewExportRequest(document,frame(ViewMode.HIDDEN_LINE).copy(is3D=true,orbit=camera),200,200))
        try {
            assertEquals(red.toInt(),wire.getPixel(100,100))
            assertEquals("The face depth prepass must reject the rear edge",background,hidden.getPixel(100,100))
        } finally { wire.recycle(); hidden.recycle() }
    }

    @Test fun measurementStrokesRemainVisibleInFilledExportsAndPdfContainsTheSameSnapshot() {
        val box=shape("box",Shape.Box(Size3(1.0,1.0,1.0)))
        val dimension=shape("dimension",Shape.Dimension(DimensionKind.ALIGNED,
            listOf(Point3(),Point3(1.0)),Point3(y=-.35)))
        val document=document(box,dimension)
        val frame=frame(ViewMode.SHADED).copy(viewport=Viewport2D(Point2(.5,.1),100.0))
        val withDimension=ViewExport.render(ViewExportRequest(document,frame,200,200))
        val withoutDimension=ViewExport.render(ViewExportRequest(document.copy(elements=listOf(box)),frame,200,200))
        try {
            var annotations=0
            for(y in 125 until 190) for(x in 20 until 180) {
                if(withDimension.getPixel(x,y)!=withoutDimension.getPixel(x,y)) annotations++
            }
            assertTrue("Dimension line and measured text must be included",annotations>40)
        } finally { withDimension.recycle(); withoutDimension.recycle() }
        val output=TrackingOutput()
        ViewExport.write(ViewExportRequest(document,frame,200,200),ViewExportFormat.PDF,output)
        assertFalse(output.closed)
        val pdf=output.toByteArray()
        assertTrue(pdf.size>500)
        assertTrue(String(pdf.take(8).toByteArray(),Charsets.ISO_8859_1).startsWith("%PDF-"))
        val raw=String(pdf,Charsets.ISO_8859_1)
        assertTrue("A real PDF page is required",raw.contains("/Type /Page"))
        assertTrue(raw.trimEnd().endsWith("%%EOF"))
    }

    @Test fun outputIsBoundedWithoutChangingTheFramingAndDarkExportsRemainReadable() {
        val line=shape("line",Shape.Line(Point3(-.5,0.0),Point3(.5,0.0)))
        val document=document(line).copy(layers=listOf(Layer(layerId,projectId,"Dark ink",colourArgb=0xFF111111)))
        val frame=frame(ViewMode.WIREFRAME).copy(dark=true,viewport=Viewport2D(Point2(0.0,0.0),1200.0))
        val image=ViewExport.render(ViewExportRequest(document,frame,12000,6000))
        try {
            assertEquals(2048,image.width); assertEquals(1024,image.height)
            assertTrue(image.width*image.height<=ViewExport.MAX_PIXELS)
            assertTrue("Dark ink must gain contrast on the dark background",Color.red(image.getPixel(1024,512))>150)
            assertEquals(Color.rgb(14,18,26),image.getPixel(20,20))
        } finally { image.recycle() }
        assertThrows(IllegalArgumentException::class.java) { ViewExportRequest(document,frame,0,100) }
    }

    @Test fun projectionClipsNearPlaneTrianglesAndWriteErrorsReachTheCaller() {
        val item=shape("box",Shape.Box(Size3(1.0,1.0,1.0)))
        val camera=OrbitCamera(target=Point3(.5,.5,.5),distance=.2,orthographicHeight=1.0,projection=Projection.PERSPECTIVE)
            .preset(ViewPreset.FRONT)
        val request=ViewExportRequest(document(item),frame(ViewMode.REALISTIC).copy(is3D=true,orbit=camera),160,100)
        val image=ViewExport.render(request)
        try { assertTrue(countPixels(image) { it!=background }>100) } finally { image.recycle() }
        assertThrows(IOException::class.java) {
            ViewExport.write(request,ViewExportFormat.PDF,object:OutputStream(){
                override fun write(b:Int) { throw IOException("Storage disconnected") }
            })
        }
    }

    private fun frame(mode:ViewMode)=CanvasFrame(Viewport2D(Point2(.5,.5),100.0),OrbitCamera(),showGrid=false,viewMode=mode)
    private fun shape(id:String,shape:Shape,transform:Transform3=Transform3()) = Element(ElementId(id),projectId,layerId,
        ElementBody.DrawingShape(shape),transform=transform)
    private fun document(vararg elements:Element)=ProjectDocument(Project(projectId,"Shared positions",0,0),
        layers=listOf(Layer(layerId,projectId,"Drawing",colourArgb=red)),elements=elements.toList())
    private fun countPixels(image:Bitmap,predicate:(Int)->Boolean):Int {
        val values=IntArray(image.width*image.height)
        image.getPixels(values,0,image.width,0,0,image.width,image.height)
        return values.count(predicate)
    }
    private class TrackingOutput:ByteArrayOutputStream() {
        var closed=false
        override fun close() { closed=true; super.close() }
    }
}
