package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt

enum class ModelFormat(val extension:String) { IFC("ifc"), OBJ("obj"), GLTF("gltf"), GLB("glb"), STL("stl") }
enum class ModelScope { PROJECT, LEVEL, SELECTED, VISIBLE }
enum class ModelUnits(val factor:Double) { MM(1000.0), CM(100.0), M(1.0) }
enum class ModelCuts { CUTS, SEPARATE, NONE }
data class ModelExportOptions(val format:ModelFormat=ModelFormat.IFC,val scope:ModelScope=ModelScope.PROJECT,
    val units:ModelUnits=ModelUnits.M,val levelId:LevelId?=null,val selected:Set<ElementId> = emptySet(),
    val systems:Set<String> = emptySet(),val walls:Boolean=true,val slabs:Boolean=true,val library:Boolean=true,
    val routes:Boolean=true,val fittings:Boolean=true,val underfloor:Boolean=true,val cuts:ModelCuts=ModelCuts.CUTS,val planView:Boolean=true)
data class ExportMesh(val element:Element,val name:String,val vertices:List<Point3>,val host:ElementId?=null)

/** Snapshot-only conversion. All geometry is in world metres, including the project origin. */
object ModelExport {
    fun meshes(document:ProjectDocument,options:ModelExportOptions):List<ExportMesh> {
        require(options.scope!=ModelScope.LEVEL || options.levelId!=null){"Choose a level to export"}
        require(options.scope!=ModelScope.SELECTED || options.selected.isNotEmpty()){"Select objects before exporting"}
        val presentation=ScenePresentation(document,options.planView)
        val definitions=document.libraryItems.associateBy {it.id}
        val byId=document.elements.associateBy {it.id}
        val selectedNodes=document.elements.filter {it.id in options.selected}.flatMap {e->(e.body as? ElementBody.Route)?.routing?.let {listOf(it.startNode,it.endNode)} ?: emptyList()}.toSet()
        fun included(e:Element):Boolean {
            val owner=(e.properties["routingOwner"] as? ParameterValue.Text)?.value?.let {byId[ElementId(it)]}
            if(!options.underfloor && (owner ?: e).floorRouting()!=null)return false
            if(options.scope==ModelScope.LEVEL && e.levelId!=options.levelId)return false
            if(options.scope==ModelScope.SELECTED && e.id !in options.selected && owner?.id !in options.selected && (e.properties["routingNode"] as? ParameterValue.Text)?.value !in selectedNodes)return false
            if(options.scope==ModelScope.VISIBLE && presentation.display(e)==ObjectDisplay.HIDDEN)return false
            val system=e.systemId?.let {id->document.systems.find {it.id==id}?.name} ?: when(e.body){is ElementBody.Wall,is ElementBody.Room,is ElementBody.Slab->"Architecture";else->"Structure"}
            val discipline=e.systemId?.let {id->document.systems.find {it.id==id}?.discipline} ?: (e.body as? ElementBody.Component)?.let {definitions[it.libraryItemId]?.discipline}
            val category=when(discipline){Discipline.ELECTRICAL->"Power";Discipline.ICT->"ICT";Discipline.HVAC->"HVAC";Discipline.PLUMBING->"Plumbing";Discipline.FIRE_PROTECTION->"Fire";Discipline.ARCHITECTURE->"Architecture";Discipline.STRUCTURE,Discipline.CIVIL->"Structure";null->system}
            if(options.systems.isNotEmpty() && options.systems.none {it.equals(system,true) || it.equals(category,true)})return false
            return when(val b=e.body){is ElementBody.Wall,is ElementBody.Room->options.walls;is ElementBody.Slab->options.slabs;is ElementBody.Route->options.routes;is ElementBody.Component->when(ifcClass(document,ExportMesh(e,"",emptyList()))){"IFCWALL"->options.walls;"IFCSLAB"->options.slabs;else->if(definitions[b.libraryItemId]?.fitting!=null)options.fittings else options.library};else->options.library}
        }
        val ids=document.elements.filter(::included).mapTo(hashSetOf()){it.id}
        val source=unfilteredGeometryDocument(document).let {it.copy(project=it.project.copy(settings=it.project.settings.copy(
            floorServices=it.project.settings.floorServices.copy(showChannels=options.cuts==ModelCuts.CUTS),wallServices=it.project.settings.wallServices.copy(showCuts=options.cuts==ModelCuts.CUTS))))}
        val scene=buildSurfaceScene(source,ids)
        val meshes=scene.chunks.groupBy {it.elementId}.map { (id,chunks)->
            val element=byId.getValue(ElementId(id))
            val vertices=chunks.filter {c->options.scope!=ModelScope.VISIBLE || c.roomSide?.let {side->byId[side.roomId]?.let {presentation.sideDisplay(it,side)!=ObjectDisplay.HIDDEN}}!=false}.flatMap {c->
                (c.vertices.indices step 10).map {i->Point3(c.origin.x+c.vertices[i]+document.project.origin.x,c.origin.y+c.vertices[i+1]+document.project.origin.y,c.origin.z+c.vertices[i+2]+document.project.origin.z)}
            }
            ExportMesh(element,element.name.ifBlank {(element.body as? ElementBody.Component)?.let {definitions[it.libraryItemId]?.name} ?: element.body::class.simpleName.orEmpty()},vertices)
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
    fun write(document:ProjectDocument,options:ModelExportOptions,output:OutputStream) {
        val meshes=meshes(document,options)
        when(options.format){ModelFormat.IFC->writeIfc(document,meshes,options,output);ModelFormat.GLTF,ModelFormat.GLB->gltf(meshes,options,output);else->{
            val w=output.bufferedWriter();var index=1
            if(options.format==ModelFormat.OBJ)w.appendLine("# EEZY DRAW units ${options.units}")else w.appendLine("solid EEZY_DRAW_${options.units}")
            for(m in meshes){if(options.format==ModelFormat.OBJ)w.appendLine("o "+m.name.replace(Regex("[\\r\\n#]"),"_"))
                for(t in m.vertices.chunked(3))if(options.format==ModelFormat.OBJ){for(p in t)w.appendLine("v ${p.x*options.units.factor} ${p.y*options.units.factor} ${p.z*options.units.factor}");w.appendLine("f $index ${index+1} ${index+2}");index+=3}else {
                    val a=t[0];val b=t[1];val c=t[2];val x=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y);val y=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z);val z=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);val n=sqrt(x*x+y*y+z*z).coerceAtLeast(1e-30)
                    w.appendLine("facet normal ${x/n} ${y/n} ${z/n}\nouter loop");for(p in t)w.appendLine("vertex ${p.x*options.units.factor} ${p.y*options.units.factor} ${p.z*options.units.factor}");w.appendLine("endloop\nendfacet")}}
            if(options.format==ModelFormat.STL)w.appendLine("endsolid EEZY_DRAW_${options.units}");w.flush()
        }}
    }
    private fun gltf(meshes:List<ExportMesh>,options:ModelExportOptions,output:OutputStream) {
        val size=meshes.sumOf {it.vertices.size}*12;val buffer=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        val views=mutableListOf<String>();val accessors=mutableListOf<String>();val nodes=mutableListOf<String>();val meshJson=mutableListOf<String>()
        for((i,m) in meshes.withIndex()) {
            val origin=m.vertices.first();val offset=buffer.position()
            val points=m.vertices.map {Point3((it.x-origin.x)*options.units.factor,(it.y-origin.y)*options.units.factor,(it.z-origin.z)*options.units.factor)}
            for(p in points)buffer.putFloat(p.x.toFloat()).putFloat(p.z.toFloat()).putFloat((-p.y).toFloat()) // Z-up -> glTF Y-up.
            views+="{\"buffer\":0,\"byteOffset\":$offset,\"byteLength\":${points.size*12},\"target\":34962}"
            accessors+="{\"bufferView\":$i,\"componentType\":5126,\"count\":${points.size},\"type\":\"VEC3\",\"min\":[${points.minOf {it.x}},${points.minOf {it.z}},${-points.maxOf {it.y}}],\"max\":[${points.maxOf {it.x}},${points.maxOf {it.z}},${-points.minOf {it.y}}]}"
            meshJson+="{\"name\":${json(m.name)},\"primitives\":[{\"attributes\":{\"POSITION\":$i},\"mode\":4}]}"
            val scale=1/options.units.factor
            nodes+="{\"mesh\":$i,\"name\":${json(m.name)},\"translation\":[${origin.x},${origin.z},${-origin.y}],\"scale\":[$scale,$scale,$scale]}"
        }
        val uri=if(options.format==ModelFormat.GLTF)",\"uri\":\"data:application/octet-stream;base64,${Base64.getEncoder().encodeToString(buffer.array())}\""else ""
        val text="{\"asset\":{\"version\":\"2.0\",\"generator\":\"EEZY DRAW\",\"extras\":{\"sourceUnits\":${json(options.units.name)}}},\"scene\":0,\"scenes\":[{\"nodes\":[${meshes.indices.joinToString()}]}],\"nodes\":[${nodes.joinToString()}],\"meshes\":[${meshJson.joinToString()}],\"buffers\":[{\"byteLength\":$size$uri}],\"bufferViews\":[${views.joinToString()}],\"accessors\":[${accessors.joinToString()}]}"
        if(options.format==ModelFormat.GLTF){output.write(text.toByteArray());return}
        val data=text.toByteArray();val padded=(data.size+3)/4*4
        val header=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546c67).putInt(2).putInt(12+8+padded+8+size).putInt(padded).putInt(0x4e4f534a).array()
        output.write(header);output.write(data);repeat(padded-data.size){output.write(32)};output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(size).putInt(0x004e4942).array());output.write(buffer.array())
    }
}
internal fun json(s:String)="\""+s.flatMap {c->when(c){'"'->"\\\"";'\\'->"\\\\";'\n'->"\\n";'\r'->"\\r";else->if(c.code<32)" "else c.toString()}.toList()}.joinToString("")+"\""
