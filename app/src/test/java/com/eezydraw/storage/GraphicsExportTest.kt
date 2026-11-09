package com.eezydraw.storage

import android.graphics.Bitmap
import android.graphics.Color
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import com.eezydraw.ui.canvas.CanvasFrame
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphicsExportTest {
    private val p=ProjectId("graphics");private val l=LayerId("l")
    private val polygon=Polygon2(listOf(Point2(0.0,0.0),Point2(6.0,0.0),Point2(6.0,4.0),Point2(0.0,4.0)))
    private val equipment=LibraryItem(LibraryItemId("equipment"),"Equipment",Discipline.HVAC,"Devices",Size3(1.0,.7,1.0),geometry=listOf(Shape.Box(Size3(1.0,.7,1.0))))
    private val document=ProjectDocument(Project(p,"Rendering",0,0),layers=listOf(Layer(l,p,"Base")),libraryItems=listOf(equipment),elements=listOf(
        Element(ElementId("floor"),p,l,ElementBody.Slab(polygon,.2)),
        Element(ElementId("wall-a"),p,l,ElementBody.Wall(Point2(0.0,0.0),Point2(6.0,0.0),.2,3.0)),
        Element(ElementId("wall-b"),p,l,ElementBody.Wall(Point2(0.0,0.0),Point2(0.0,4.0),.2,3.0)),
        Element(ElementId("equipment"),p,l,ElementBody.Component(equipment.id,equipment.defaultSize),transform=Transform3(Point3(2.0,1.5,0.0))),
    ))
    private fun render(options:GraphicsOptions=GraphicsOptions(),camera:OrbitCamera=OrbitCamera(target=Point3(2.5,1.6,1.1),distance=11.0,orthographicHeight=7.0,projection=Projection.PERSPECTIVE))=
        ViewExport.render(ViewExportRequest(document,CanvasFrame(Viewport2D(),camera,is3D=true,showGrid=false,viewMode=ViewMode.SHADED_WITH_EDGES,graphics=options),480,360))
    private fun pixels(b:Bitmap)=IntArray(b.width*b.height).also {b.getPixels(it,0,b.width,0,0,b.width,b.height)}
    @Test fun lightingAndContactControlsChangeRealRenderedPixelsAndProvideReviewImages() {
        val normal=render();val soft=render(GraphicsOptions(lighting=LightingStrength.SOFT));val noContact=render(GraphicsOptions(contactShadows=false))
        try {
            val a=pixels(normal);val b=pixels(soft);val c=pixels(noContact)
            assertTrue(a.indices.count {a[it]!=b[it]}>500)
            assertTrue("Wall/floor contacts must darken actual pixels",a.indices.count {Color.red(a[it])<Color.red(c[it])}>50)
            assertTrue(a.any {Color.blue(it)>Color.red(it)+5})
            val output=File("build/reports/render-quality").apply {mkdirs()}
            for((name,image)in listOf("normal" to normal,"soft" to soft,"no-contact" to noContact))File(output,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        } finally {normal.recycle();soft.recycle();noContact.recycle()}
    }
    @Test fun antialiasingAndMatteDetailHaveVisibleBoundedEffects() {
        val smooth=render(GraphicsOptions(textureDetail=TextureDetail.HIGH))
        val sharp=render(GraphicsOptions(antiAliasing=false,textureDetail=TextureDetail.HIGH))
        val camera=OrbitCamera(target=Point3(2.0,.2,1.6),yaw=-kotlin.math.PI/2,pitch=0.0,distance=.3,orthographicHeight=2.0,projection=Projection.PERSPECTIVE)
        val detailed=render(GraphicsOptions(textureDetail=TextureDetail.HIGH),camera)
        val flat=render(GraphicsOptions(textureDetail=TextureDetail.LOW),camera)
        try {
            val a=pixels(smooth);val b=pixels(sharp);val c=pixels(flat);val d=pixels(detailed)
            assertTrue(a.indices.count {a[it]!=b[it]}>100)
            assertTrue(d.indices.count {d[it]!=c[it]}>100)
            assertEquals(480,smooth.width);assertEquals(360,smooth.height)
        }finally {smooth.recycle();sharp.recycle();flat.recycle();detailed.recycle()}
    }
    @Test fun closeRoamAndWalkViewsKeepGeometryVisible() {
        val camera=OrbitCamera(target=Point3(2.0,1.0,1.6),yaw=-kotlin.math.PI/2,pitch=-.1,distance=.3,orthographicHeight=2.0,projection=Projection.PERSPECTIVE)
        for(c in listOf(camera,camera.walk(.1,.15),camera.lookAround(.2,.05))) {
            val image=render(camera=c)
            try {assertTrue(pixels(image).toSet().size>20)}finally {image.recycle()}
        }
    }
}
