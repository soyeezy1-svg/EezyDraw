package com.eezydraw.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import com.eezydraw.ui.canvas.CanvasFrame
import java.io.ByteArrayOutputStream
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
class DrawingExportTest {
    private val p=ProjectId("sheet")
    private val layer=LayerId("base")
    private val level=Level(LevelId("g"),p,"Ground",0.0,.2,3.0)
    private val line=Element(ElementId("line"),p,layer,ElementBody.DrawingShape(Shape.Line(Point3(0.0,0.0),Point3(10.0,0.0))),level.id)
    private val doc=ProjectDocument(Project(p,"Office plan",0,0,projectNumber="P001",author="Designer"),levels=listOf(level),layers=listOf(Layer(layer,p,"Base")),elements=listOf(line))
    private val request=ViewExportRequest(doc,CanvasFrame(Viewport2D(Point2(5.0,0.0),30.0),OrbitCamera(),levelId=level.id.value,showGrid=false),400,240)
    private val folder=File(System.getProperty("java.io.tmpdir"),"eezy-2d-export").apply {mkdirs()}
    @Test fun paperAndScaleProduceExactPhysicalDimensionsAndPDFReaderFixtures() {
        for(paper in listOf(DrawingPaper.A3,DrawingPaper.A1))for(scale in listOf(50.0,100.0)) {
            val options=DrawingExportOptions(scope=DrawingScope.SHEET,paper=paper,scale=scale)
            val layout=DrawingExport.sheet(options,Bounds3(Point3(),Point3(10.0,0.0)))
            assertEquals(paper.width*72/25.4,layout.width.toDouble(),.51)
            assertEquals(10*1000/scale*72/25.4,layout.project(Point3(10.0,0.0)).x-layout.project(Point3()).x,1e-8)
            val bytes=ByteArrayOutputStream().also {DrawingExport.write(request,options,folder,it)}.toByteArray()
            assertTrue(bytes.size>100);assertTrue(bytes.toString(Charsets.ISO_8859_1).startsWith("%PDF-"))
            File(folder,"${paper.name}-${scale.toInt()}.pdf").writeBytes(bytes)
        }
    }
    @Test fun allFormatsCurrentLevelPortraitAndCurrentViewAreWritable() {
        for(format in DrawingFormat.entries){val options=DrawingExportOptions(format=format,scope=if(format==DrawingFormat.PNG)DrawingScope.VIEW else DrawingScope.LEVEL,landscape=false)
            val bytes=ByteArrayOutputStream().also {DrawingExport.write(request,options,folder,it)}.toByteArray()
            assertTrue(bytes.size>40);File(folder,"level.${format.extension}").writeBytes(bytes)}
        val bytes=ByteArrayOutputStream().also {DrawingExport.write(request,DrawingExportOptions(),folder,it)}.toByteArray()
        File(folder,"view.pdf").writeBytes(bytes)
    }
    @Test fun underlayOpacityTransformLevelAndToggleAreRespectedBehindGeometry() {
        val file=File(folder,"underlay.png");val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED);file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val asset=ProjectAsset(AssetId("image"),p,file.name,"image/png")
        val underlay=Underlay(ElementId("underlay"),p,asset.id,layer,UnderlayFormat.IMAGE,.25,level.id,Transform3(Point3(4.0,-1.0)),opacity=.5)
        val document=doc.copy(assets=listOf(asset),underlays=listOf(underlay))
        fun image(source:ProjectDocument,include:Boolean)=ByteArrayOutputStream().also {DrawingExport.write(request.copy(document=source),DrawingExportOptions(format=DrawingFormat.PNG,includeUnderlay=include),folder,it)}.toByteArray().let {BitmapFactory.decodeByteArray(it,0,it.size)}
        val shown=image(document,true);val excluded=image(document,false)
        shown.outputStreamFixture("underlay-on.png");excluded.outputStreamFixture("underlay-off.png")
        try {assertNotEquals(shown.getPixel(200,100),excluded.getPixel(200,100));assertTrue(Color.red(shown.getPixel(200,100))>Color.green(shown.getPixel(200,100)))}finally {shown.recycle();excluded.recycle()}
        val hidden=image(document.copy(layers=listOf(doc.layers.single().copy(visible=false))),true)
        try {assertEquals(Color.rgb(247,250,252),hidden.getPixel(200,100))}finally {hidden.recycle()}
        for(format in listOf(DrawingFormat.PDF,DrawingFormat.DXF)){val bytes=ByteArrayOutputStream().also {DrawingExport.write(request.copy(document=document),DrawingExportOptions(format=format,scope=DrawingScope.LEVEL),folder,it)}.toByteArray();File(folder,"with-underlay.${format.extension}").writeBytes(bytes)}
        File(folder,"no-underlay.pdf").writeBytes(ByteArrayOutputStream().also {DrawingExport.write(request.copy(document=document),DrawingExportOptions(scope=DrawingScope.LEVEL,includeUnderlay=false),folder,it)}.toByteArray())
    }
    @Test fun missingAssetsFailClearlyAndExcludeUnderlayDoesNotReadTheFile() {
        val asset=ProjectAsset(AssetId("missing"),p,"missing.png","image/png")
        val underlay=Underlay(ElementId("u"),p,asset.id,layer,UnderlayFormat.IMAGE,.01,level.id)
        val source=request.copy(document=doc.copy(assets=listOf(asset),underlays=listOf(underlay)))
        try {DrawingExport.write(source,DrawingExportOptions(format=DrawingFormat.PNG),folder,ByteArrayOutputStream());fail("Missing underlay ignored")}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("unavailable"))}
        DrawingExport.write(source,DrawingExportOptions(format=DrawingFormat.PNG,includeUnderlay=false),folder,ByteArrayOutputStream())
    }
    @Test fun dimensionsLabelsAndStrictHiddenVisibilitySurvivePDFExport() {
        val label=line.copy(id=ElementId("label"),body=ElementBody.DrawingShape(Shape.Text("Room A1",.5)),transform=Transform3(Point3(2.0,2.0)))
        val dim=line.copy(id=ElementId("dim"),body=ElementBody.DrawingShape(Shape.Dimension(DimensionKind.ALIGNED,listOf(Point3(),Point3(10.0,0.0)),Point3(0.0,1.0))))
        val hidden=line.copy(id=ElementId("hidden"),body=ElementBody.DrawingShape(Shape.Line(Point3(20.0,20.0),Point3(21.0,20.0)))).withDisplay(ObjectDisplay.HIDDEN)
        val source=doc.copy(elements=listOf(line,label,dim,hidden),project=doc.project.copy(settings=doc.project.settings.copy(showHiddenObjects=true)))
        val segments=exportPlanSegments(source,level.id)
        assertTrue(segments.none {it.elementId==hidden.id.value});assertTrue(segments.any {it.elementId==dim.id.value});assertTrue(segments.any {it.elementId==label.id.value})
        val bytes=ByteArrayOutputStream().also {DrawingExport.write(request.copy(document=source),DrawingExportOptions(scope=DrawingScope.SHEET,scale=50.0),folder,it)}.toByteArray()
        File(folder,"annotations.pdf").writeBytes(bytes)
    }
    private fun Bitmap.outputStreamFixture(name:String) {File(folder,name).outputStream().use {assertTrue(compress(Bitmap.CompressFormat.PNG,100,it))}}

    @Test fun everyPaperOrientationAndScaleValidation() {
        for(paper in DrawingPaper.entries) for(landscape in listOf(false,true)) {
            val options=DrawingExportOptions(scope=DrawingScope.SHEET,paper=paper,landscape=landscape,scale=50.0)
            val layout=DrawingExport.sheet(options,Bounds3(Point3(1e9,1e9),Point3(1e9+10,1e9+5)))
            assertEquals((if(landscape)paper.width else paper.height)*DrawingExport.POINTS_PER_MM,layout.width.toDouble(),.51)
            assertEquals((if(landscape)paper.height else paper.width)*DrawingExport.POINTS_PER_MM,layout.height.toDouble(),.51)
            assertEquals(10000/50.0*DrawingExport.POINTS_PER_MM,layout.project(Point3(1e9+10,1e9)).x-layout.project(Point3(1e9,1e9)).x,1e-6)
        }
        for(scale in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY,100001.0)) {
            try {DrawingExportOptions(scale=scale);fail("Invalid scale accepted")}catch(_:IllegalArgumentException){}
        }
        try {DrawingExport.write(request.copy(frame=request.frame.copy(levelId=null)),DrawingExportOptions(scope=DrawingScope.LEVEL),folder,ByteArrayOutputStream());fail("Missing level ignored")}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("level"))}
    }

    @Test fun pngExcludesHiddenObjectsOtherLevelsAndTransientSelection() {
        val hidden=line.copy(id=ElementId("hidden"),transform=Transform3(Point3(0.0,1.0))).withDisplay(ObjectDisplay.HIDDEN)
        val upper=line.copy(id=ElementId("upper"),levelId=LevelId("upper"),transform=Transform3(Point3(0.0,2.0)))
        val source=doc.copy(elements=listOf(line,hidden,upper),levels=doc.levels+level.copy(id=LevelId("upper")),project=doc.project.copy(settings=doc.project.settings.copy(showHiddenObjects=true)))
        fun render(document:ProjectDocument)=ByteArrayOutputStream().also {DrawingExport.write(request.copy(document=document),DrawingExportOptions(format=DrawingFormat.PNG),folder,it)}.toByteArray()
        assertArrayEquals(render(doc),render(source))
        val bytes=render(doc.copy(layers=listOf(doc.layers.single().copy(visible=false))))
        val image=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        try {assertEquals(Color.rgb(247,250,252),image.getPixel(200,120))}finally {image.recycle()}
    }

    @Test fun pdfDecorationsCanBeExcludedAndWritingFailuresPropagate() {
        val output=ByteArrayOutputStream()
        DrawingExport.write(request,DrawingExportOptions(scope=DrawingScope.SHEET,titleBlock=false,northArrow=false,scaleBar=false),folder,output)
        val text=output.toString("ISO-8859-1")
        assertFalse(text.contains("(Office plan) Tj"));assertFalse(text.contains("(N) Tj"))
        val failed=object:java.io.OutputStream(){override fun write(value:Int){throw java.io.IOException("destination full")}}
        try {DrawingExport.write(request,DrawingExportOptions(scope=DrawingScope.LEVEL),folder,failed);fail("Write failure ignored")}catch(e:java.io.IOException){assertEquals("destination full",e.message)}
    }

    @Test fun rotatedUnderlayKeepsItsOrientationAndHiddenLevelsAreExcluded() {
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        for(y in 0..7)for(x in 0..7)bitmap.setPixel(x,y,if(y<4)Color.RED else Color.BLUE)
        bitmap.outputStreamFixture("two-tone.png");bitmap.recycle()
        val asset=ProjectAsset(AssetId("colour"),p,"two-tone.png","image/png")
        val underlay=Underlay(ElementId("rotated"),p,asset.id,layer,UnderlayFormat.IMAGE,.25,level.id,Transform3(Point3(6.0,-1.0),Rotation3(z=Math.PI/2)),opacity=1.0)
        val source=doc.copy(assets=listOf(asset),underlays=listOf(underlay))
        val output=ByteArrayOutputStream()
        DrawingExport.write(request.copy(document=source),DrawingExportOptions(format=DrawingFormat.PNG),folder,output)
        val image=BitmapFactory.decodeByteArray(output.toByteArray(),0,output.size())
        try {assertEquals(Color.RED,image.getPixel(185,105));assertEquals(Color.BLUE,image.getPixel(215,105))}finally {image.recycle()}
        val pdf=ByteArrayOutputStream()
        DrawingExport.write(request.copy(document=source),DrawingExportOptions(scope=DrawingScope.LEVEL),folder,pdf)
        File(folder,"rotated-underlay.pdf").writeBytes(pdf.toByteArray())
        val hidden=source.copy(project=source.project.copy(settings=source.project.settings.copy(hiddenLevelIds=setOf(level.id))))
        val images=ExportUnderlays.load(hidden,level.id.value,folder,2048)
        try {assertTrue(images.isEmpty())}finally {images.forEach {it.close()}}
    }
}
