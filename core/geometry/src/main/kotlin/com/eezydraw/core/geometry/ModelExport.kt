package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ModelFormat(val extension:String) { IFC("ifc"), OBJ("obj"), GLTF("gltf"), GLB("glb"), STL("stl") }
enum class ModelScope { PROJECT, LEVEL, SELECTED, VISIBLE }
enum class ModelUnits(val factor:Double) { MM(1000.0), CM(100.0), M(1.0) }
enum class ModelCuts { CUTS, SEPARATE, NONE }
data class ModelExportOptions(val format:ModelFormat=ModelFormat.IFC,val scope:ModelScope=ModelScope.PROJECT,
    val units:ModelUnits=ModelUnits.M,val levelId:LevelId?=null,val selected:Set<ElementId> = emptySet(),
    val systems:Set<String> = emptySet(),val walls:Boolean=true,val slabs:Boolean=true,val library:Boolean=true,
    val routes:Boolean=true,val fittings:Boolean=true,val underfloor:Boolean=true,val cuts:ModelCuts=ModelCuts.CUTS,val planView:Boolean=true,val upAxis:ModelUpAxis?=null,val moveToOrigin:Boolean=false,val appVersion:String="unknown",
    val colourBy:ModelColourBy=ModelColourBy.MATERIAL,val exportDisplayMode:Boolean=false) {
    val fileExtension:String get()=if(format==ModelFormat.OBJ)"zip" else format.extension
}
data class ExportMesh(val element:Element,val name:String,val vertices:List<Point3>,val host:ElementId?=null,val material:ExportMaterial=ExportMaterial("Opening / chase",MattePalette.OTHER)) {
    val colourArgb:Long get()=material.colourArgb
}

/** Snapshot-only conversion. All geometry is in world metres, including the project origin. */
object ModelExport {
    fun meshes(document:ProjectDocument,options:ModelExportOptions):List<ExportMesh> {
        require(options.scope!=ModelScope.LEVEL || options.levelId!=null){"Choose a level to export"}
        require(options.scope!=ModelScope.SELECTED || options.selected.isNotEmpty()){"Select objects before exporting"}
        val presentation=ScenePresentation(document,options.planView)
        // Whole/level/selected can include buried services, but never explicitly hidden geometry.
        val exportVisibility=ScenePresentation(document,true)
        val definitions=document.libraryItems.associateBy {it.id}
        val byId=document.elements.associateBy {it.id}
        val selectedNodes=document.elements.filter {it.id in options.selected}.flatMap {e->(e.body as? ElementBody.Route)?.routing?.let {listOf(it.startNode,it.endNode)} ?: emptyList()}.toSet()
        fun included(e:Element):Boolean {
            val owner=(e.properties["routingOwner"] as? ParameterValue.Text)?.value?.let {byId[ElementId(it)]}
            if(!options.underfloor && (owner ?: e).floorRouting()!=null)return false
            if(options.scope==ModelScope.LEVEL && e.levelId!=options.levelId)return false
            if(options.scope==ModelScope.SELECTED && e.id !in options.selected && owner?.id !in options.selected && (e.properties["routingNode"] as? ParameterValue.Text)?.value !in selectedNodes)return false
            if(exportVisibility.display(e)==ObjectDisplay.HIDDEN || options.scope==ModelScope.VISIBLE && presentation.display(e)==ObjectDisplay.HIDDEN)return false
            val system=e.systemId?.let {id->document.systems.find {it.id==id}?.name} ?: when(e.body){is ElementBody.Wall,is ElementBody.Room,is ElementBody.Slab->"Architecture";else->"Structure"}
            val discipline=e.systemId?.let {id->document.systems.find {it.id==id}?.discipline} ?: (e.body as? ElementBody.Component)?.let {definitions[it.libraryItemId]?.discipline}
            val category=when(discipline){Discipline.ELECTRICAL->"Power";Discipline.ICT->"ICT";Discipline.HVAC->"HVAC";Discipline.PLUMBING->"Plumbing";Discipline.FIRE_PROTECTION->"Fire";Discipline.ARCHITECTURE->"Architecture";Discipline.STRUCTURE,Discipline.CIVIL->"Structure";null->system}
            if(options.systems.isNotEmpty() && options.systems.none {it.equals(system,true) || it.equals(category,true)})return false
            return when(val b=e.body){is ElementBody.Wall,is ElementBody.Room->options.walls;is ElementBody.Slab->options.slabs;is ElementBody.Route->options.routes;is ElementBody.Component->when(ifcClass(document,ExportMesh(e,"",emptyList()))){"IFCWALL"->options.walls;"IFCSLAB"->options.slabs;else->if(definitions[b.libraryItemId]?.fitting!=null)options.fittings else options.library};else->options.library}
        }
        val ids=document.elements.filter(::included).mapTo(hashSetOf()){it.id}
        val source=unfilteredGeometryDocument(document).let {it.copy(project=it.project.copy(settings=it.project.settings.copy(
            floorServices=it.project.settings.floorServices.copy(showChannels=options.cuts==ModelCuts.CUTS),wallServices=it.project.settings.wallServices.copy(showCuts=options.cuts==ModelCuts.CUTS))))}
        val scene=buildSurfaceScene(source,ids,architecturalCuts=options.cuts==ModelCuts.CUTS)
        val meshes=scene.chunks.groupBy {it.elementId}.map { (id,chunks)->
            val element=byId.getValue(ElementId(id))
            val vertices=chunks.filter {c->element.body !is ElementBody.Room || c.roomSide?.let {side->byId[side.roomId]?.let {exportVisibility.sideDisplay(it,side)!=ObjectDisplay.HIDDEN && (options.scope!=ModelScope.VISIBLE || presentation.sideDisplay(it,side)!=ObjectDisplay.HIDDEN)}}!=false}.flatMap {c->
                (c.vertices.indices step 10).map {i->Point3(c.origin.x+c.vertices[i]+document.project.origin.x,c.origin.y+c.vertices[i+1]+document.project.origin.y,c.origin.z+c.vertices[i+2]+document.project.origin.z)}
            }
            ExportMesh(element,element.name.ifBlank {(element.body as? ElementBody.Component)?.let {definitions[it.libraryItemId]?.name} ?: element.body::class.simpleName.orEmpty()},vertices,
                material=exportMaterial(document,element,options,exportVisibility.display(element)))
        }.filter {it.vertices.isNotEmpty()}.toMutableList()
        if(options.cuts==ModelCuts.SEPARATE) {
            val frames=wallFrames(document).associateBy {it.host.id to it.roomSide}
            val cuts=wallServiceCuts(document).filter {it.wallId in ids || it.sourceId in ids}
            fun add(host:Element,points:List<Point2>,depthA:Double,depthB:Double,world:(Point2,Double)->Point3) {
                val factory=org.locationtech.jts.geom.GeometryFactory()
                val poly=factory.createPolygon((points+points.first()).map {org.locationtech.jts.geom.Coordinate(it.x,it.y)}.toTypedArray())
                val triangles=org.locationtech.jts.triangulate.polygon.PolygonTriangulator.triangulate(poly)
                val vertices=mutableListOf<Point3>()
                for(i in 0 until triangles.numGeometries){val c=triangles.getGeometryN(i).coordinates
                    vertices+=listOf(world(Point2(c[0].x,c[0].y),depthA),world(Point2(c[2].x,c[2].y),depthA),world(Point2(c[1].x,c[1].y),depthA),world(Point2(c[0].x,c[0].y),depthB),world(Point2(c[1].x,c[1].y),depthB),world(Point2(c[2].x,c[2].y),depthB))}
                for(i in points.indices){val a=points[i];val b=points[(i+1)%points.size];vertices+=listOf(world(a,depthA),world(b,depthA),world(b,depthB),world(a,depthA),world(b,depthB),world(a,depthB))}
                meshes+=ExportMesh(host.copy(id=ElementId("void-${host.id.value}-${meshes.size}")),"Opening / chase in ${host.name}",vertices.map {Point3(it.x+document.project.origin.x,it.y+document.project.origin.y,it.z+document.project.origin.z)},host.id)
            }
            for(c in cuts){val f=frames[c.wallId to c.roomSide] ?: continue;add(f.host,c.outline,c.fromDepth,c.toDepth){p,d->f.world(p.x,d,p.y)}}
            for(slab in document.elements.filter {it.id in ids && it.body is ElementBody.Slab})for(c in floorServiceCuts(document,slab))add(slab,c.outline,0.0,c.depth.coerceAtMost((slab.body as ElementBody.Slab).thickness*kotlin.math.abs(slab.transform.scale.z))){p,d->measurementToWorld(slab.transform,Point3(p.x/kotlin.math.abs(slab.transform.scale.x),p.y/kotlin.math.abs(slab.transform.scale.y),-d/kotlin.math.abs(slab.transform.scale.z)))}
        }
        require(meshes.isNotEmpty()){"The export filters contain no 3D solids"}
        require(meshes.sumOf {it.vertices.size.toLong()}<=600_000){"Export exceeds 200,000 triangles; export individual levels"}
        return meshes
    }
    /** Android single-document save/share: OBJ and its required sidecar travel together. */
    fun writePackage(document:ProjectDocument,options:ModelExportOptions,output:OutputStream) {
        if(options.format!=ModelFormat.OBJ){write(document,options,output);return}
        options.resolvedUpAxis()
        val (meshes,shift)=prepareModelCoordinates(meshes(document,options),options)
        ZipOutputStream(object:java.io.FilterOutputStream(output) {override fun close(){flush()}}).use {zip->
            zip.putNextEntry(ZipEntry("model.obj"));writeTextMeshes(meshes,options,zip,shift);zip.closeEntry()
            zip.putNextEntry(ZipEntry("model.mtl"));writeMtl(meshes,zip);zip.closeEntry()
        }
    }
    /** Companion to raw OBJ write for callers saving a folder rather than a ZIP. */
    fun writeObjMaterials(document:ProjectDocument,options:ModelExportOptions,output:OutputStream) {
        require(options.format==ModelFormat.OBJ)
        writeMtl(meshes(document,options),output)
    }
    private fun materialId(index:Int,material:ExportMaterial):String = "material_${index}_"+material.name.replace(Regex("[^A-Za-z0-9_-]"),"_").take(80)
    private fun writeMtl(meshes:List<ExportMesh>,output:OutputStream) {
        val w=output.bufferedWriter()
        w.appendLine("# EEZY DRAW materials (sRGB diffuse colour; d is opacity)")
        for((i,material) in meshes.map {it.material}.distinct().withIndex()) {
            w.appendLine("# ${material.name.replace(Regex("[\\r\\n]")," ")}")
            w.appendLine("newmtl ${materialId(i,material)}")
            w.appendLine("Kd ${rgb(material.colourArgb).joinToString(" ")}")
            w.appendLine("d ${alpha(material.colourArgb)}")
            w.appendLine("Ka 0 0 0\nKs 0 0 0\nNs 10\nillum 2\n")
        }
        w.flush()
    }
    fun write(document:ProjectDocument,options:ModelExportOptions,output:OutputStream) {
        options.resolvedUpAxis()
        val (meshes,shift)=prepareModelCoordinates(meshes(document,options),options)
        when(options.format){ModelFormat.IFC->writeIfc(document,meshes,options,output,shift);ModelFormat.GLTF,ModelFormat.GLB->gltf(meshes,options,output,shift);else->writeTextMeshes(meshes,options,output,shift)}
    }
    private fun writeTextMeshes(meshes:List<ExportMesh>,options:ModelExportOptions,output:OutputStream,shift:Point3) {
            val w=output.bufferedWriter();var index=1;var normalIndex=1
            val solidName="EEZY_DRAW_upAxis_${options.resolvedUpAxis().name}_units_${options.units.name}_app_${options.appVersion.replace(Regex("[^A-Za-z0-9._-]"),"_")}_origin_${if(options.moveToOrigin)"MOVE"else "PROJECT"}_offset_${shift.x}_${shift.y}_${shift.z}"
            if(options.format==ModelFormat.OBJ){w.appendLine("# EEZY DRAW ${exportCoordinateNote(options,shift)}");w.appendLine("mtllib model.mtl");w.appendLine("# Forward axis: ${if(options.resolvedUpAxis()==ModelUpAxis.Y_UP)"-Z"else "+Y"}")}else w.appendLine("solid $solidName")
            val materials=meshes.map {it.material}.distinct()
            for(m in meshes){if(options.format==ModelFormat.OBJ){
                w.appendLine("o "+m.name.replace(Regex("[\\r\\n#]"),"_"))
                w.appendLine("usemtl ${materialId(materials.indexOf(m.material),m.material)}")
            }
                for(t in m.vertices.chunked(3))if(options.format==ModelFormat.OBJ){for(p in t)w.appendLine("v ${p.x*options.units.factor} ${p.y*options.units.factor} ${p.z*options.units.factor}");val n=triangleNormal(t[0],t[1],t[2]);w.appendLine("vn ${n.x} ${n.y} ${n.z}");w.appendLine("f $index//$normalIndex ${index+1}//$normalIndex ${index+2}//$normalIndex");index+=3;normalIndex++}else {
                    val n=triangleNormal(t[0],t[1],t[2])
                    w.appendLine("facet normal ${n.x} ${n.y} ${n.z}\nouter loop");for(p in t)w.appendLine("vertex ${p.x*options.units.factor} ${p.y*options.units.factor} ${p.z*options.units.factor}");w.appendLine("endloop\nendfacet")}}
            if(options.format==ModelFormat.STL)w.appendLine("endsolid $solidName");w.flush()
    }
    private fun gltf(meshes:List<ExportMesh>,options:ModelExportOptions,output:OutputStream,shift:Point3) {
        val size=meshes.sumOf {it.vertices.size}*24;val buffer=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        val views=mutableListOf<String>();val accessors=mutableListOf<String>();val nodes=mutableListOf<String>();val meshJson=mutableListOf<String>()
        for((i,m) in meshes.withIndex()) {
            val origin=m.vertices.first();val offset=buffer.position()
            val points=m.vertices.map {Point3((it.x-origin.x)*options.units.factor,(it.y-origin.y)*options.units.factor,(it.z-origin.z)*options.units.factor)}
            for(t in points.chunked(3)){val normal=triangleNormal(t[0],t[1],t[2]);for(p in t)buffer.putFloat(p.x.toFloat()).putFloat(p.y.toFloat()).putFloat(p.z.toFloat()).putFloat(normal.x.toFloat()).putFloat(normal.y.toFloat()).putFloat(normal.z.toFloat())}
            views+="{\"buffer\":0,\"byteOffset\":$offset,\"byteLength\":${points.size*24},\"byteStride\":24,\"target\":34962}"
            accessors+="{\"bufferView\":$i,\"componentType\":5126,\"count\":${points.size},\"type\":\"VEC3\",\"min\":[${points.minOf {it.x}},${points.minOf {it.y}},${points.minOf {it.z}}],\"max\":[${points.maxOf {it.x}},${points.maxOf {it.y}},${points.maxOf {it.z}}]}"
            accessors+="{\"bufferView\":$i,\"byteOffset\":12,\"componentType\":5126,\"count\":${points.size},\"type\":\"VEC3\"}"
            meshJson+="{\"name\":${json(m.name)},\"primitives\":[{\"attributes\":{\"POSITION\":${i*2},\"NORMAL\":${i*2+1}},\"material\":$i,\"mode\":4}]}"
            val scale=1/options.units.factor
            nodes+="{\"mesh\":$i,\"name\":${json(m.name)},\"translation\":[${origin.x},${origin.y},${origin.z}],\"scale\":[$scale,$scale,$scale]}"
        }
        val materials=meshes.map {m->"{\"name\":${json(m.material.name)},\"doubleSided\":true,\"alphaMode\":${json(if((m.colourArgb shr 24 and 255)<255)"BLEND"else "OPAQUE")},\"pbrMetallicRoughness\":{\"baseColorFactor\":[${linearRgb(m.colourArgb).joinToString()},${(m.colourArgb shr 24 and 255)/255.0}],\"metallicFactor\":${m.material.metallic},\"roughnessFactor\":${m.material.roughness}}}"}
        val uri=if(options.format==ModelFormat.GLTF)",\"uri\":\"data:application/octet-stream;base64,${Base64.getEncoder().encodeToString(buffer.array())}\""else ""
        val text="{\"asset\":{\"version\":\"2.0\",\"generator\":${json("EEZY DRAW "+options.appVersion)},\"extras\":{\"sourceUnits\":${json(options.units.name)},\"upAxis\":${json(options.resolvedUpAxis().name)},\"coordinateUnits\":\"M\",\"appVersion\":${json(options.appVersion)},\"originPolicy\":${json(if(options.moveToOrigin)"MOVE_TO_ORIGIN"else "PROJECT")},\"sourceOffsetMetres\":[${shift.x},${shift.y},${shift.z}]}},\"scene\":0,\"scenes\":[{\"nodes\":[${meshes.indices.joinToString()}]}],\"nodes\":[${nodes.joinToString()}],\"meshes\":[${meshJson.joinToString()}],\"materials\":[${materials.joinToString()}],\"buffers\":[{\"byteLength\":$size$uri}],\"bufferViews\":[${views.joinToString()}],\"accessors\":[${accessors.joinToString()}]}"
        if(options.format==ModelFormat.GLTF){output.write(text.toByteArray());return}
        val data=text.toByteArray();val padded=(data.size+3)/4*4
        val header=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546c67).putInt(2).putInt(12+8+padded+8+size).putInt(padded).putInt(0x4e4f534a).array()
        output.write(header);output.write(data);repeat(padded-data.size){output.write(32)};output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(size).putInt(0x004e4942).array());output.write(buffer.array())
    }
}
internal fun json(s:String)="\""+s.flatMap {c->when(c){'"'->"\\\"";'\\'->"\\\\";'\n'->"\\n";'\r'->"\\r";else->if(c.code<32)" "else c.toString()}.toList()}.joinToString("")+"\""

internal fun rgb(colour:Long)=listOf((colour shr 16 and 255)/255.0,(colour shr 8 and 255)/255.0,(colour and 255)/255.0)
