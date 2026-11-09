package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelExportTest {
    @Before fun prepareReaderFixtureDirectory() {
        val directory=File(System.getProperty("java.io.tmpdir"),"eezy-model-export")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create model reader fixtures" }
    }
    private val p=ProjectId("export")
    private val layer=LayerId("base")
    private val level=Level(LevelId("ground"),p,"Ground",3.0,.2,3.0)
    private val wall=Element(ElementId("wall"),p,layer,ElementBody.Wall(Point2(0.0,0.0),Point2(4.0,0.0),.2,3.0),level.id,name="Wall '01'",transform=Transform3(Point3(10.0,20.0,3.0),Rotation3(z=.5)))
    private val doc=ProjectDocument(Project(p,"Export",0,0,origin=Point3(1000.0,2000.0,0.0)),levels=listOf(level),layers=listOf(Layer(layer,p,"Architecture")),elements=listOf(wall))
    private fun bytes(options:ModelExportOptions,document:ProjectDocument=doc)=ByteArrayOutputStream().also {ModelExport.write(document,options,it)}.toByteArray()
    @Test fun allFormatsProduceReadableStructuresAndReferenceFixtures() {
        val folder=File(System.getProperty("java.io.tmpdir"),"eezy-model-export").apply {mkdirs()}
        for(format in ModelFormat.entries) {
            val data=bytes(ModelExportOptions(format=format))
            File(folder,"fixture.${format.extension}").writeBytes(data)
            assertTrue(data.size>100)
            when(format) {
                ModelFormat.IFC->{val text=data.toString(Charsets.UTF_8);assertTrue("FILE_SCHEMA(('IFC4'))" in text);assertTrue("IFCWALL(" in text);assertTrue("IFCBUILDINGSTOREY(" in text);assertTrue("IFCTRIANGULATEDFACESET(" in text);assertTrue("Pset_EezyDraw" in text)}
                ModelFormat.OBJ->{val text=data.toString(Charsets.UTF_8);val lines=text.lines();val vertices=lines.count {it.startsWith("v ")};val faces=lines.filter {it.startsWith("f ")};assertEquals(vertices/3,faces.size);faces.flatMap {it.split(" ").drop(1)}.forEach {assertTrue(it.substringBefore('/').toInt() in 1..vertices)}}
                ModelFormat.STL->{val text=data.toString(Charsets.UTF_8);assertEquals(text.lines().count {it.startsWith("facet normal") }*3,text.lines().count {it.startsWith("vertex ")})}
                ModelFormat.GLB->{val buffer=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);assertEquals(0x46546c67,buffer.int);assertEquals(2,buffer.int);assertEquals(data.size,buffer.int);val length=buffer.int;assertEquals(0x4e4f534a,buffer.int);assertEquals(0,length%4)}
                ModelFormat.GLTF->{val text=data.toString(Charsets.UTF_8);assertTrue("data:application/octet-stream;base64," in text);assertTrue("\"version\":\"2.0\"" in text)}
            }
        }
    }
    @Test fun unitsWorldOrientationAndOriginAreRetained() {
        val metre=bytes(ModelExportOptions(format=ModelFormat.OBJ)).toString(Charsets.UTF_8).lines().first {it.startsWith("v ")}.split(" ").drop(1).take(3).map(String::toDouble)
        val mm=bytes(ModelExportOptions(format=ModelFormat.OBJ,units=ModelUnits.MM)).toString(Charsets.UTF_8).lines().first {it.startsWith("v ")}.split(" ").drop(1).take(3).map(String::toDouble)
        metre.zip(mm).forEach {(m,n)->assertEquals(m*1000,n,1e-8)}
        assertTrue(metre[0]>1000);assertTrue(-metre[2]>2000)
        for(unit in ModelUnits.entries)for(format in ModelFormat.entries)File(System.getProperty("java.io.tmpdir"),"eezy-model-export/${unit.name}.${format.extension}").writeBytes(bytes(ModelExportOptions(format=format,units=unit)))
    }
    @Test fun scopesVisibilitySystemsAndCategoryFiltersAreIndependent() {
        val other=wall.copy(id=ElementId("other"),levelId=LevelId("upper"))
        val hidden=wall.copy(id=ElementId("hidden")).withDisplay(ObjectDisplay.HIDDEN)
        val document=doc.copy(levels=doc.levels+level.copy(id=LevelId("upper"),elevation=6.0),elements=listOf(wall,other,hidden))
        assertEquals(3,ModelExport.meshes(document,ModelExportOptions()).size)
        assertEquals(2,ModelExport.meshes(document,ModelExportOptions(scope=ModelScope.VISIBLE)).size)
        assertEquals(2,ModelExport.meshes(document,ModelExportOptions(scope=ModelScope.LEVEL,levelId=level.id)).size)
        val ifc=bytes(ModelExportOptions(scope=ModelScope.LEVEL,levelId=level.id),document).toString(Charsets.UTF_8)
        assertEquals(1,ifc.lines().count {"=IFCBUILDINGSTOREY(" in it})
        assertEquals(1,ModelExport.meshes(document,ModelExportOptions(scope=ModelScope.SELECTED,selected=setOf(other.id))).size)
        try {ModelExport.meshes(document,ModelExportOptions(systems=setOf("Power")));fail("System filter ignored")}catch(_:IllegalArgumentException){}
        try {ModelExport.meshes(document,ModelExportOptions(walls=false));fail("Wall filter ignored")}catch(_:IllegalArgumentException){}
    }
    @Test fun cutsCanBeBakedOrExportedAsSeparateObjectsAndFloorServicesCanBeExcluded() {
        val config=defaultRoutingSettings(RouteKind.CONDUIT).copy(floorInstallation=FloorInstallation.IN_SLAB)
        val route=wall.copy(id=ElementId("route"),body=ElementBody.Route(RouteKind.CONDUIT,listOf(Point3(1.0,.08,1.0),Point3(3.0,.08,1.0)),RouteSection.Circular(.025),config.elevation,routing=RouteTopology("n","a","b",config),wallService=WallServiceAttachment(wall.id,WallFaceSide.LEFT,WallServiceOptions(WallServiceMode.CHASE))),transform=Transform3())
        val host=wall.copy(transform=Transform3())
        val document=doc.copy(elements=listOf(host,route))
        assertEquals(1,ModelExport.meshes(document,ModelExportOptions(underfloor=false)).size)
        assertTrue(ModelExport.meshes(document,ModelExportOptions(cuts=ModelCuts.SEPARATE)).any {it.host==host.id})
        File(System.getProperty("java.io.tmpdir"),"eezy-model-export/opening.ifc").writeBytes(bytes(ModelExportOptions(cuts=ModelCuts.SEPARATE),document))
        assertNotEquals(ModelExport.meshes(document,ModelExportOptions(cuts=ModelCuts.NONE)).first().vertices,ModelExport.meshes(document,ModelExportOptions()).first().vertices)
    }
    @Test fun ifcMapsLibraryAndRouteClassesAndCarriesMetadata() {
        for((name,type) in listOf("Wall" to "IFCWALL","Floor slab" to "IFCSLAB","Door" to "IFCDOOR","Window" to "IFCWINDOW","Column" to "IFCCOLUMN","Beam" to "IFCBEAM","Pump" to "IFCPUMP")) {
            val item=LibraryItem(LibraryItemId(name),name,Discipline.ARCHITECTURE,"Model",Size3(1.0,1.0,2.0),geometry=listOf(Shape.Box(Size3(1.0,1.0,2.0))),manufacturer="Maker",model="M1")
            val e=wall.copy(body=ElementBody.Component(item.id,item.defaultSize))
            val data=bytes(ModelExportOptions(),doc.copy(elements=listOf(e),libraryItems=listOf(item))).toString(Charsets.UTF_8)
            assertTrue(type in data);assertTrue("Maker" in data);assertTrue("MountingHeight" in data)
            if(type=="IFCWALL" || type=="IFCSLAB")try {ModelExport.meshes(doc.copy(elements=listOf(e),libraryItems=listOf(item)),ModelExportOptions(walls=false,slabs=false));fail("Library building category ignored")}catch(_:IllegalArgumentException){}
            File(System.getProperty("java.io.tmpdir"),"eezy-model-export/$name.ifc").apply {parentFile.mkdirs();writeText(data)}
        }
    }
    @Test fun ifcAllServiceClassesSpacesSlabsAndFittingsHaveReadbackFixtures() {
        val folder=File(System.getProperty("java.io.tmpdir"),"eezy-model-export").apply {mkdirs()}
        val fixtures=mutableListOf<Pair<ProjectDocument,String>>()
        fixtures+=doc.copy(elements=listOf(wall.copy(body=ElementBody.Slab(Polygon2(listOf(Point2(0.0,0.0),Point2(4.0,0.0),Point2(4.0,4.0),Point2(0.0,4.0))),.2)))) to "IFCSLAB"
        fixtures+=doc.copy(elements=listOf(wall.copy(body=ElementBody.Room(Polygon2(listOf(Point2(0.0,0.0),Point2(4.0,0.0),Point2(4.0,4.0),Point2(0.0,4.0))))))) to "IFCSPACE"
        for(kind in RouteKind.entries) {
            val config=defaultRoutingSettings(kind)
            val e=wall.copy(body=ElementBody.Route(kind,listOf(Point3(),Point3(2.0,0.0)),config.section(kind),config.elevation))
            fixtures+=doc.copy(elements=listOf(e)) to when(kind){RouteKind.PIPE->"IFCPIPESEGMENT";RouteKind.DUCT->"IFCDUCTSEGMENT";else->"IFCCABLECARRIERSEGMENT"}
            val fitting=BuiltInLibrary.items.first {it.fitting?.routeKind==kind && it.fitting?.kind==FittingKind.ELBOW_90}
            fixtures+=doc.copy(elements=listOf(wall.copy(body=ElementBody.Component(fitting.id,fitting.defaultSize))),libraryItems=listOf(fitting)) to when(kind){RouteKind.PIPE->"IFCPIPEFITTING";RouteKind.DUCT->"IFCDUCTFITTING";else->"IFCCABLECARRIERFITTING"}
        }
        fixtures.forEachIndexed {i,(document,type)->val data=bytes(ModelExportOptions(),document);assertTrue(type in data.toString(Charsets.UTF_8));if("FITTING" in type)assertTrue("Inlet" in data.toString(Charsets.UTF_8));File(folder,"class-$i.ifc").writeBytes(data)}
    }
    @Test fun largeProjectsRetainLastElementWithoutTruncation() {
        val document=doc.copy(elements=(0 until 1500).map {wall.copy(id=ElementId("wall-$it"),name="Wall $it")})
        val text=bytes(ModelExportOptions(format=ModelFormat.OBJ),document).toString(Charsets.UTF_8)
        assertTrue("o Wall 1499" in text)
    }
}
