package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class PortableMaterialExportTest {
    private val p=ProjectId("coloured")
    private val layer=LayerId("base")
    private val level=Level(LevelId("ground"),p,"Ground",0.0,.2,3.0)
    private val system=BuildingSystem(SystemId("power"),p,"Power red",Discipline.ELECTRICAL,0xFFCC4020L)
    private val paint=Material(MaterialId("paint"),"Paint / warm white",0xFFE8DCC0L)
    private val glass=Material(MaterialId("glass"),"Glass blue 40%",0x664488CCL)
    private val wall=Element(ElementId("wall"),p,layer,ElementBody.Wall(Point2(0.0,0.0),Point2(4.0,0.0),.2,3.0),level.id,name="Wall",materialId=paint.id)
    private val door=LibraryItem(LibraryItemId("door"),"Door",Discipline.ARCHITECTURE,"Openings",Size3(.9,.15,2.1),geometry=listOf(Shape.Box(Size3(.9,.15,2.1))))
    private val window=door.copy(id=LibraryItemId("window"),name="Custom glazing",materialId=glass.id,opening=OpeningKind.WINDOW)
    private val equipment=door.copy(id=LibraryItemId("equipment"),name="Equipment",discipline=Discipline.ELECTRICAL)
    private val fitting=BuiltInLibrary.items.first {it.fitting?.routeKind==RouteKind.PIPE && it.fitting?.kind==FittingKind.ELBOW_90}
    private fun component(name:String,item:LibraryItem,x:Double)=wall.copy(id=ElementId(name.lowercase()),name=name,materialId=null,body=ElementBody.Component(item.id,item.defaultSize),transform=Transform3(Point3(x,2.0,0.0)))
    private val doc:ProjectDocument get() {
        val boundary=Polygon2(listOf(Point2(0.0,0.0),Point2(4.0,0.0),Point2(4.0,4.0),Point2(0.0,4.0)))
        val route=wall.copy(id=ElementId("route"),name="Route",materialId=null,systemId=system.id,body=ElementBody.Route(RouteKind.PIPE,listOf(Point3(0.0,5.0,1.0),Point3(4.0,5.0,1.0)),RouteSection.Circular(.1),ElevationSetting(1.0,ElevationReference.PROJECT_DATUM)))
        return ProjectDocument(Project(p,"Coloured model",0,0),levels=listOf(level),layers=listOf(Layer(layer,p,"Base")),systems=listOf(system),materials=listOf(paint,glass),libraryItems=listOf(door,window,equipment,fitting),elements=listOf(
            wall,wall.copy(id=ElementId("floor"),name="Floor",materialId=null,body=ElementBody.Room(boundary)),
            wall.copy(id=ElementId("slab"),name="Slab",materialId=null,body=ElementBody.Slab(boundary,.2),transform=Transform3(Point3(6.0,0.0,0.0))),
            component("Door",door,0.0),component("Window",window,2.0),component("Equipment",equipment,4.0),component("Fitting",fitting,6.0),route,
            component("Xray",equipment,8.0).withDisplay(ObjectDisplay.XRAY),component("Hidden",equipment,10.0).withDisplay(ObjectDisplay.HIDDEN)))
    }
    private fun bytes(format:ModelFormat,document:ProjectDocument=doc,options:ModelExportOptions=ModelExportOptions(format=format,exportDisplayMode=true))=
        ByteArrayOutputStream().also {ModelExport.writePackage(document,options,it)}.toByteArray()
    private fun entries(data:ByteArray):Map<String,ByteArray> = buildMap {
        ZipInputStream(data.inputStream()).use {zip->while(true){val entry=zip.nextEntry ?: break;put(entry.name,zip.readBytes())}}
    }
    private fun gltf(data:ByteArray,format:ModelFormat):JsonObject {
        if(format==ModelFormat.GLTF)return Json.parseToJsonElement(data.toString(Charsets.UTF_8)).jsonObject
        val buffer=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546c67,buffer.int);assertEquals(2,buffer.int);assertEquals(data.size,buffer.int)
        val length=buffer.int;assertEquals(0x4e4f534a,buffer.int)
        return Json.parseToJsonElement(ByteArray(length).also {buffer.get(it)}.toString(Charsets.UTF_8).trim()).jsonObject
    }
    @Test fun colouredModelEveryFormatHasPortableMaterialBindingsAndReadbackFixtures() {
        val folder=File(System.getProperty("java.io.tmpdir"),"eezy-coloured-export").apply {mkdirs()}
        val expected=ModelExport.meshes(doc,ModelExportOptions(exportDisplayMode=true))
        assertEquals(9,expected.size)
        assertEquals(0x664488CCL,expected.first {it.name=="Window"}.colourArgb)
        assertEquals(71L,expected.first {it.name=="Xray"}.colourArgb ushr 24)
        for(format in ModelFormat.entries) {
            val data=bytes(format)
            File(folder,"coloured.${if(format==ModelFormat.OBJ)"zip"else format.extension}").writeBytes(data)
            when(format) {
                ModelFormat.OBJ->{
                    val files=entries(data);assertEquals(setOf("model.obj","model.mtl"),files.keys)
                    files.forEach {(name,content)->File(folder,name).writeBytes(content)}
                    val obj=files.getValue("model.obj").toString(Charsets.UTF_8)
                    val mtl=files.getValue("model.mtl").toString(Charsets.UTF_8)
                    assertTrue("mtllib model.mtl" in obj);assertFalse("o Hidden" in obj)
                    val declared=mtl.lines().filter {it.startsWith("newmtl ")}.map {it.removePrefix("newmtl ")}.toSet()
                    assertTrue(declared.all {it.startsWith("material_")});assertTrue(declared.any {"Glass_blue_40" in it})
                    assertEquals(expected.size,obj.lines().count {it.startsWith("usemtl ")})
                    obj.lines().filter {it.startsWith("usemtl ")}.forEach {assertTrue(it.removePrefix("usemtl ") in declared)}
                    assertTrue("Kd ${68/255.0} ${136/255.0} ${204/255.0}" in mtl)
                    assertTrue("d 0.4" in mtl);assertTrue("d ${71/255.0}" in mtl)
                    assertTrue(obj.lines().filter {it.startsWith("v ")}.all {it.split(' ').size==4})
                }
                ModelFormat.GLTF,ModelFormat.GLB->{
                    val json=gltf(data,format)
                    assertEquals(expected.size,json.getValue("meshes").jsonArray.size)
                    val materials=json.getValue("materials").jsonArray
                    for((i,mesh) in expected.withIndex()) {
                        val primitive=json.getValue("meshes").jsonArray[i].jsonObject.getValue("primitives").jsonArray.single().jsonObject
                        val material=materials[primitive.getValue("material").jsonPrimitive.int].jsonObject
                        assertEquals(mesh.material.name,material.getValue("name").jsonPrimitive.content)
                        val pbr=material.getValue("pbrMetallicRoughness").jsonObject
                        val colour=pbr.getValue("baseColorFactor").jsonArray.map {it.jsonPrimitive.double}
                        assertEquals(alpha(mesh.colourArgb),colour[3],1e-8)
                        assertEquals(if(colour[3]<1)"BLEND"else "OPAQUE",material.getValue("alphaMode").jsonPrimitive.content)
                        assertEquals(.9,pbr.getValue("roughnessFactor").jsonPrimitive.double,0.0)
                        assertEquals(0.0,pbr.getValue("metallicFactor").jsonPrimitive.double,0.0)
                        if(mesh.name=="Window") {
                            assertEquals(.05780543,colour[0],1e-7);assertEquals(.24620133,colour[1],1e-7);assertEquals(.60382734,colour[2],1e-7)
                        }
                    }
                }
                ModelFormat.IFC->{
                    val text=data.toString(Charsets.UTF_8)
                    assertEquals(expected.size,text.lines().count {"=IFCSTYLEDITEM(" in it})
                    assertEquals(expected.size,text.lines().count {"=IFCSURFACESTYLE(" in it})
                    assertTrue("'Glass blue 40%'" in text);assertTrue(",0.6,$,$,$,$,$,$,.MATT." in text)
                    assertFalse("'Hidden'" in text)
                    // Every style references an actual tessellated face set, rather than an unattached material.
                    val faces=Regex("#(\\d+)=IFCTRIANGULATEDFACESET").findAll(text).map {it.groupValues[1]}.toSet()
                    Regex("IFCSTYLEDITEM\\(#(\\d+),").findAll(text).forEach {assertTrue(it.groupValues[1] in faces)}
                }
                ModelFormat.STL->{val text=data.toString(Charsets.UTF_8);assertTrue(text.startsWith("solid "));assertTrue("facet normal" in text);assertFalse("Kd " in text)}
            }
        }
    }
    @Test fun colourChoicesPreserveIntrinsicAlphaAndDisplayOptionIsExplicit() {
        val element=component("Custom",window,0.0).copy(systemId=system.id,properties=mapOf(ELEMENT_COLOUR_KEY to ParameterValue.Colour(0x8020A040L))).withDisplay(ObjectDisplay.XRAY)
        val document=doc.copy(elements=listOf(element))
        val base=ModelExportOptions()
        val material=ModelExport.meshes(document,base).single()
        assertEquals(0x8020A040L,material.colourArgb)
        val body=element.body as ElementBody.Component
        val parameterColour=element.copy(properties=emptyMap(),body=body.copy(parameters=mapOf(LibraryParameterKeys.COLOUR to ParameterValue.Colour(0x40224488L))))
        assertEquals(0x40224488L,ModelExport.meshes(document.copy(elements=listOf(parameterColour)),base).single().colourArgb)
        val systemMesh=ModelExport.meshes(document,base.copy(colourBy=ModelColourBy.SYSTEM)).single()
        assertEquals(0x80CC4020L,systemMesh.colourArgb);assertEquals(system.name,systemMesh.material.name)
        val transparentSystem=document.copy(systems=listOf(system.copy(colourArgb=0x40CC4020L)))
        assertEquals(64L,ModelExport.meshes(transparentSystem,base.copy(colourBy=ModelColourBy.SYSTEM)).single().colourArgb ushr 24)
        val byLevel=ModelExport.meshes(document,base.copy(colourBy=ModelColourBy.LEVEL)).single()
        assertEquals(128L,byLevel.colourArgb ushr 24);assertEquals("Level: Ground",byLevel.material.name)
        assertEquals(byLevel.material,ModelExport.meshes(document.copy(levels=document.levels.reversed()),base.copy(colourBy=ModelColourBy.LEVEL)).single().material)
        val xray=ModelExport.meshes(document,base.copy(exportDisplayMode=true)).single()
        assertEquals(36L,xray.colourArgb ushr 24)
        val global=document.copy(project=document.project.copy(settings=document.project.settings.copy(viewMode=ViewMode.XRAY)),elements=listOf(element.withDisplay(ObjectDisplay.NORMAL)))
        assertEquals(xray.material,ModelExport.meshes(global,base.copy(exportDisplayMode=true)).single().material)
        val assigned=element.copy(properties=emptyMap(),materialId=paint.id).withDisplay(ObjectDisplay.NORMAL)
        assertEquals(paint.colourArgb,ModelExport.meshes(document.copy(elements=listOf(assigned)),base).single().colourArgb)
    }
    @Test fun hiddenObjectsAndHierarchyNeverLeakIntoAnyScopeEvenWhenShownForInspection() {
        for(hide in listOf("object","layer","system","level")) {
            val hidden=wall.copy(id=ElementId("hidden"),systemId=system.id).let {if(hide=="object")it.withDisplay(ObjectDisplay.HIDDEN)else it}
            val visible=wall.copy(id=ElementId("visible"),layerId=LayerId("visible"),levelId=null)
            val document=doc.copy(elements=listOf(visible,hidden),layers=doc.layers.map {it.copy(visible=hide!="layer")}+Layer(visible.layerId,p,"Visible"),systems=listOf(system.copy(visible=hide!="system")),project=doc.project.copy(settings=doc.project.settings.copy(showHiddenObjects=true,hiddenLevelIds=if(hide=="level")setOf(level.id)else emptySet())))
            for(scope in ModelScope.entries) {
                val options=ModelExportOptions(scope=scope,levelId=level.id,selected=setOf(hidden.id,visible.id))
                if(scope==ModelScope.LEVEL) {
                    try {ModelExport.meshes(document,options);fail("Hidden level object exported")}catch(_:IllegalArgumentException){}
                }else assertEquals(listOf(visible.id),ModelExport.meshes(document,options).map {it.element.id})
            }
        }
    }
    @Test fun sharedWallRemainsExportedWhenOneOwningRoomIsHidden() {
        val boundary=Polygon2(listOf(Point2(0.0,0.0),Point2(4.0,0.0),Point2(4.0,4.0),Point2(0.0,4.0)))
        val room=wall.copy(id=ElementId("room"),name="Room",body=ElementBody.Room(boundary,wallFaces=listOf(RoomWallFace(edgeIndex=0,wallId=wall.id,side=WallSide.LEFT))))
        val hidden=room.copy(id=ElementId("hidden-room")).withDisplay(ObjectDisplay.HIDDEN)
        val document=doc.copy(elements=listOf(hidden,room,wall))
        for(scope in listOf(ModelScope.PROJECT,ModelScope.VISIBLE)) {
            val meshes=ModelExport.meshes(document,ModelExportOptions(scope=scope))
            assertTrue(meshes.any {it.element.id==wall.id})
            assertFalse(meshes.any {it.element.id==hidden.id})
        }
    }
    @Test fun ceilingAndServiceVisibilityTogglesAreHonouredByEveryScope() {
        val ceiling=component("Ceiling",equipment,0.0).copy(properties=mapOf(CEILING_OBJECT to ParameterValue.Flag(true)))
        val settings=defaultRoutingSettings(RouteKind.PIPE).copy(floorInstallation=FloorInstallation.IN_SLAB)
        val route=wall.copy(id=ElementId("buried"),materialId=null,body=ElementBody.Route(RouteKind.PIPE,listOf(Point3(),Point3(2.0,0.0,0.0)),RouteSection.Circular(.1),settings.elevation,routing=RouteTopology("network","a","b",settings)))
        val document=doc.copy(elements=listOf(wall,ceiling,route))
        assertEquals(3,ModelExport.meshes(document,ModelExportOptions()).size)
        val hidden=document.copy(project=document.project.copy(settings=document.project.settings.copy(showCeilings=false,floorServices=document.project.settings.floorServices.copy(showServices=false))))
        for(scope in ModelScope.entries)assertEquals(listOf(wall.id),ModelExport.meshes(hidden,ModelExportOptions(scope=scope,levelId=level.id,selected=document.elements.map {it.id}.toSet())).map {it.element.id})
    }
    @Test fun defaultCategoriesAreDistinctAndSharedWithPlanAndSurfaceColours() {
        val bare=doc.copy(elements=doc.elements.filter {it.name !in listOf("Xray","Hidden")}.map {it.copy(materialId=null,systemId=null)},libraryItems=doc.libraryItems.map {it.copy(materialId=null)})
        val meshes=ModelExport.meshes(bare,ModelExportOptions())
        assertEquals(8,meshes.map {it.colourArgb}.toSet().size)
        val lines=buildDrawingScene(bare)
        val surfaces=buildSurfaceScene(bare)
        for(mesh in meshes) {
            assertTrue(lines.segments.filter {it.elementId==mesh.element.id.value}.all {it.colorArgb==mesh.colourArgb})
            val chunks=surfaces.chunks.filter {it.elementId==mesh.element.id.value}
            assertTrue(chunks.isNotEmpty())
            assertEquals((mesh.colourArgb shr 16 and 255)/255.0,chunks.first().vertices[6].toDouble(),1e-7)
        }
    }
}
