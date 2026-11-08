package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class DxfExportTest {
    private val p=ProjectId("dxf")
    private val layer=LayerId("base")
    private val level=Level(LevelId("g"),p,"Ground",0.0,.2,3.0)
    private val system=BuildingSystem(SystemId("power"),p,"Power",Discipline.ELECTRICAL,0xFFFF0000)
    private val line=Element(ElementId("line"),p,layer,ElementBody.DrawingShape(Shape.Line(Point3(1.0,1.0),Point3(3.0,1.0))),level.id,system.id)
    private val doc=ProjectDocument(Project(p,"Plan",0,0),levels=listOf(level),layers=listOf(Layer(layer,p,"Base")),systems=listOf(system),elements=listOf(line))
    private fun write(document:ProjectDocument=doc,unit:ModelUnits=ModelUnits.MM,clip:PlanClip?=null,rasters:List<DxfRaster> = emptyList()):String=ByteArrayOutputStream().also {DxfExport.write(document,level.id,unit,it,clip,rasters)}.toString("UTF-8")
    private fun pairs(text:String)=text.trimEnd('\n').lines().chunked(2).map {it[0].toInt() to it[1]}
    @Test fun unitsAndSystemLayersKeepModelCoordinatesAndProduceReaderFixtures() {
        val folder=File(System.getProperty("java.io.tmpdir"),"eezy-2d-export").apply {mkdirs()}
        for(unit in ModelUnits.entries){val text=write(unit=unit);val values=pairs(text)
            assertTrue(values.contains(2 to "Power"));assertTrue(values.contains(10 to (unit.factor).toString()));assertTrue(values.contains(11 to (3*unit.factor).toString()))
            assertTrue(text.endsWith("0\nEOF\n"));File(folder,"plan-${unit.name}.dxf").writeText(text)
        }
    }
    @Test fun hiddenObjectsLayersSystemsAndOtherLevelsAreExcludedEvenWithFaintOutlinesEnabled() {
        val upper=level.copy(id=LevelId("upper"),elevation=3.0)
        val hidden=line.copy(id=ElementId("hidden")).withDisplay(ObjectDisplay.HIDDEN)
        val other=line.copy(id=ElementId("upper-line"),levelId=upper.id)
        val document=doc.copy(levels=listOf(level,upper),elements=listOf(line,hidden,other),project=doc.project.copy(settings=doc.project.settings.copy(showHiddenObjects=true)))
        assertEquals(1,pairs(write(document)).count {it==0 to "LINE"})
        assertEquals(0,pairs(write(doc.copy(systems=listOf(system.copy(visible=false))))).count {it==0 to "LINE"})
        assertEquals(0,pairs(write(doc.copy(layers=listOf(doc.layers.single().copy(visible=false))))).count {it==0 to "LINE"})
    }
    @Test fun floorRoutesUseDeclaredDashPatternWithoutDoubleSegmentingTheLine() {
        val config=defaultRoutingSettings(RouteKind.CONDUIT).copy(floorInstallation=FloorInstallation.IN_SLAB)
        val route=line.copy(body=ElementBody.Route(RouteKind.CONDUIT,listOf(Point3(1.0,1.0,-.1),Point3(3.0,1.0,-.1)),RouteSection.Circular(.025),config.elevation,routing=RouteTopology("n","a","b",config)))
        val text=write(doc.copy(elements=listOf(route)))
        assertEquals(1,pairs(text).count {it==0 to "LINE"});assertTrue(pairs(text).contains(6 to "DASHED"));assertTrue(pairs(text).contains(49 to "120.0"))
        File(System.getProperty("java.io.tmpdir"),"eezy-2d-export/dashed.dxf").apply {parentFile.mkdirs();writeText(text)}
    }
    @Test fun labelsDimensionsAndClippedViewsRemainInThePlan() {
        val label=line.copy(id=ElementId("label"),body=ElementBody.DrawingShape(Shape.Text("Room A1",.2)),transform=Transform3(Point3(2.0,1.0)))
        val dimension=line.copy(id=ElementId("dimension"),body=ElementBody.DrawingShape(Shape.Dimension(DimensionKind.ALIGNED,listOf(Point3(1.0,1.0),Point3(3.0,1.0)),Point3(2.0,2.0),MeasurementData(anchors=listOf(MeasurementAnchor(Point3(1.0,1.0)),MeasurementAnchor(Point3(3.0,1.0)))))))
        val document=doc.copy(elements=listOf(line,label,dimension))
        val text=write(document);assertTrue(pairs(text).contains(1 to "Room A1"));assertTrue(pairs(text).count {it==0 to "LINE"}>1)
        File(System.getProperty("java.io.tmpdir"),"eezy-2d-export/annotations.dxf").apply {parentFile.mkdirs();writeText(text)}
        val hidden=write(document.copy(project=document.project.copy(settings=document.project.settings.copy(showMeasurements=false))))
        assertTrue(pairs(hidden).count {it==0 to "LINE"}<pairs(text).count {it==0 to "LINE"})
        val clip=write(clip=PlanClip(1.5,.5,2.5,1.5));assertTrue(pairs(clip).contains(10 to "1500.0"));assertTrue(pairs(clip).contains(11 to "2500.0"))
    }
    @Test fun embeddedUnderlayIsPortableAndLayerNamesAreSanitizedWithoutCollisions() {
        val raster=DxfRaster(listOf(Point2(0.0,2.0),Point2(2.0,2.0),Point2(2.0,0.0),Point2(0.0,0.0)),2,2,intArrayOf(0xFFFF0000.toInt(),-1,-1,0xFF0000FF.toInt()))
        val text=write(rasters=listOf(raster));assertTrue(pairs(text).contains(0 to "SOLID"));assertTrue(pairs(text).contains(8 to "UNDERLAY"));assertTrue(pairs(text).contains(420 to "16711680"))
        File(System.getProperty("java.io.tmpdir"),"eezy-2d-export/underlay.dxf").apply {parentFile.mkdirs();writeText(text)}
        val second=system.copy(id=SystemId("s2"),name="Power/Test")
        val third=system.copy(id=SystemId("s3"),name="Power:Test")
        val names=write(doc.copy(systems=listOf(second,third),elements=listOf(line.copy(systemId=second.id),line.copy(id=ElementId("second"),systemId=third.id))))
        assertTrue(pairs(names).contains(2 to "Power_Test"));assertTrue(pairs(names).contains(2 to "Power_Test_2"))
    }
    @Test fun projectOriginAndHiddenLevelsRemainConsistentWithModelUnits() {
        val source=doc.copy(project=doc.project.copy(origin=Point3(100.0,200.0,300.0)))
        val text=write(source);val values=pairs(text)
        assertTrue(values.contains(10 to "101000.0"));assertTrue(values.contains(20 to "201000.0"))
        assertTrue(values.contains(30 to "0.0")) // 2D model space remains in the XY plane.
        val hidden=source.copy(project=source.project.copy(settings=source.project.settings.copy(hiddenLevelIds=setOf(level.id))))
        assertEquals(0,pairs(write(hidden)).count {it==0 to "LINE"})
    }
}
