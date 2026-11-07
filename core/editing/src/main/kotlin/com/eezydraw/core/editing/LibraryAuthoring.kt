package com.eezydraw.core.editing

import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.geometry.buildSurfaceScene
import com.eezydraw.core.model.*

/** Stable project snapshots let a new default coexist with previously placed instances. */
fun libraryPlacementDefinition(document: ProjectDocument, item: LibraryItem): LibraryItem {
    // Photos belong to the reusable library; project instances only need physical geometry.
    val definition = item.copy(thumbnailBase64 = null)
    val existing = document.libraryItems.find { it.id == definition.id }
    if (existing == null || existing == definition) return definition
    val stem = "${definition.id.value}-snapshot-${definition.toString().hashCode().toUInt().toString(16)}"
    var index = 0
    while (true) {
        val id = LibraryItemId(if (index == 0) stem else "$stem-$index")
        val candidate = definition.copy(id = id, catalogSourceId = definition.catalogSourceId ?: definition.id)
        val saved = document.libraryItems.find { it.id == id }
        if (saved == null || saved == candidate) return candidate
        index++
    }
}

enum class LibraryBaseShape { BOX, CYLINDER, SPHERE, CONE }
enum class LibraryPlanSymbol { OUTLINE, CROSS, CIRCLE }

fun librarySymbolGeometry(symbol: LibraryPlanSymbol): List<Shape> = when (symbol) {
    LibraryPlanSymbol.OUTLINE -> listOf(Shape.Polyline(listOf(Point3(), Point3(1.0), Point3(1.0,1.0), Point3(y=1.0)), true))
    LibraryPlanSymbol.CROSS -> librarySymbolGeometry(LibraryPlanSymbol.OUTLINE) + listOf(
        Shape.Line(Point3(), Point3(1.0,1.0)), Shape.Line(Point3(1.0), Point3(y=1.0)))
    LibraryPlanSymbol.CIRCLE -> listOf(Shape.Polyline(List(64) { i ->
        val angle = i * Math.PI / 32
        Point3(.5 + kotlin.math.cos(angle) * .5, .5 + kotlin.math.sin(angle) * .5)
    }, true))
}

fun createLibraryItem(id: LibraryItemId, shape: LibraryBaseShape): LibraryItem {
    val primitive = when (shape) {
        LibraryBaseShape.BOX -> Shape.Box(Size3(1.0,1.0,1.0))
        LibraryBaseShape.CYLINDER -> Shape.Cylinder(.5,1.0)
        LibraryBaseShape.SPHERE -> Shape.Sphere(.5)
        LibraryBaseShape.CONE -> Shape.Cone(.5, height=1.0)
    }
    val project = ProjectId("custom-shape")
    val layer = LayerId("shape")
    val element = Element(ElementId("shape"),project,layer,ElementBody.DrawingShape(primitive))
    val document = ProjectDocument(Project(project,"Shape",0,0),layers=listOf(Layer(layer,project,"Shape")),elements=listOf(element))
    return libraryItemFromDrawing(document,setOf(element.id),id).copy(name="Custom item",category="Custom",
        symbolGeometry=librarySymbolGeometry(LibraryPlanSymbol.OUTLINE))
}

/** Bake the selected drawing's transforms and surfaces into a portable local mesh and plan strokes. */
fun libraryItemFromDrawing(document: ProjectDocument, ids: Set<ElementId>, id: LibraryItemId): LibraryItem {
    require(ids.isNotEmpty()) { "Draw and select the geometry to save first." }
    val selected = document.elements.filter { it.id in ids }
    require(selected.size == ids.size && selected.all { it.body is ElementBody.DrawingShape }) {
        "Select drawing shapes to create a custom item."
    }
    val scene = buildDrawingScene(document, ids)
    require(scene.segments.isNotEmpty() && scene.segments.size <= 10_000) { "Select between 1 and 10000 drawing edges." }
    val bounds = scene.bounds
    val size = Size3((bounds.max.x-bounds.min.x).coerceAtLeast(.001),
        (bounds.max.y-bounds.min.y).coerceAtLeast(.001),(bounds.max.z-bounds.min.z).coerceAtLeast(.001))
    fun local(p: Point3) = Point3(p.x-bounds.min.x,p.y-bounds.min.y,p.z-bounds.min.z)
    val outline = selected.singleOrNull()?.takeIf { bounds.max.z - bounds.min.z < 1e-8 }?.let { element ->
        val shape = (element.body as ElementBody.DrawingShape).shape
        val polygon = when (shape) {
            is Shape.Polygon -> shape.boundary
            is Shape.Polyline -> if(shape.closed) Polygon2(shape.points.map {Point2(it.x,it.y)}) else null
            is Shape.Circle -> Polygon2(List(64) { i -> val angle=i*Math.PI/32; Point2(kotlin.math.cos(angle)*shape.radius,kotlin.math.sin(angle)*shape.radius) })
            is Shape.Ellipse -> Polygon2(List(64) { i -> val angle=i*Math.PI/32; Point2(kotlin.math.cos(angle)*shape.radiusX,kotlin.math.sin(angle)*shape.radiusY) })
            else -> null
        }
        fun ring(points:List<Point2>)=points.map { p -> local(measurementToWorld(element.transform,Point3(p.x,p.y))).let {Point2(it.x,it.y)} }
        polygon?.let {Polygon2(ring(it.outer),it.holes.map(::ring))}
    }
    if(outline!=null) {
        return LibraryItem(id,"Custom drawing",Discipline.ARCHITECTURE,"Custom",size.copy(height=.1),
            geometry=listOf(Shape.Extrusion(outline,.1)),symbolGeometry=listOf(Shape.Polygon(outline)),
            defaultColourArgb=scene.segments.first().colorArgb)
    }
    val surface = buildSurfaceScene(document,ids)
    require(surface.triangleCount <= 20_000) { "Select a smaller drawing to save to the library." }
    val geometry = mutableListOf<Shape>()
    if (surface.triangleCount > 0) {
        val vertices = surface.chunks.flatMap { chunk -> chunk.vertices.indices.step(10).map { i ->
            local(Point3(chunk.origin.x+chunk.vertices[i],chunk.origin.y+chunk.vertices[i+1],chunk.origin.z+chunk.vertices[i+2]))
        } }
        geometry += Shape.Mesh(vertices,vertices.indices.toList())
    }
    // Keep analytic outlines too, including drawings with no surface (lines, arcs, annotations).
    geometry += scene.segments.map { Shape.Line(local(it.a),local(it.b)) }
    val symbol = scene.segments.map { Shape.Line(local(it.a).copy(z=0.0),local(it.b).copy(z=0.0)) }.distinct()
    return LibraryItem(id,"Custom drawing",Discipline.ARCHITECTURE,"Custom",size,
        geometry=geometry,symbolGeometry=symbol,defaultColourArgb=scene.segments.first().colorArgb)
}
