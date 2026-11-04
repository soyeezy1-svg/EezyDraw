package com.eezydraw.core.editing

import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.ViewPreset
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.*
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResizeTest {
    private val projectId = ProjectId("project")
    private val layerId = LayerId("drawing")
    private val plan = Viewport2D(pixelsPerMetre = 100.0)
    private val camera = OrbitCamera(distance = 20.0, orthographicHeight = 12.0)
    private val width = 1200.0
    private val height = 800.0

    @Test
    fun `every drawing shape resizes only its matching width depth and corner dimensions`() {
        for ((name, element) in allShapes()) {
            val document = document(element)
            val frame = resizeFrame(document, element)
            for (handle in ResizeHandle.entries.filter { it != ResizeHandle.HEIGHT }) {
                val supported = frame.supports(handle)
                val start = project(frame.handlePoint(handle), false)
                val signs = signs(handle)
                val amountX = if (affectsX(handle)) frame.size.x * 0.5 * signs.first else 0.0
                val amountY = if (affectsY(handle)) frame.size.y * 0.25 * signs.second else 0.0
                val end = project(plus(frame.handlePoint(handle), Point3(amountX, amountY)), false)
                val delta = resizeDelta(frame, handle, start, end, plan, camera, false, width, height)
                val resized = element.copy(transform = EditTransforms.apply(element.transform, delta))
                val expected = Point3(
                    frame.size.x * if (supported && affectsX(handle)) 1.5 else 1.0,
                    frame.size.y * if (supported && affectsY(handle)) 1.25 else 1.0,
                    frame.size.z,
                )
                assertPoint("$name $handle", expected, readProperties(document, resized).size)
                assertPoint("Fixed opposite point $name $handle", frame.pivotPoint(handle), delta.apply(frame.pivotPoint(handle)))
            }
        }
    }

    @Test
    fun `height handle changes height only for every solid and leaves every flat shape flat`() {
        for ((name, element) in allShapes()) for (projection in Projection.entries) {
            val document = document(element)
            val frame = resizeFrame(document, element)
            val orbit = camera.copy(projection = projection)
            val startWorld = frame.handlePoint(ResizeHandle.HEIGHT)
            val endWorld = plus(startWorld, times(frame.axis(2), frame.size.z * 0.5))
            val delta = resizeDelta(frame, ResizeHandle.HEIGHT, project(startWorld, true, orbit), project(endWorld, true, orbit),
                plan, orbit, true, width, height)
            val resized = element.copy(transform = EditTransforms.apply(element.transform, delta))
            assertPoint("$name $projection", Point3(frame.size.x, frame.size.y, frame.size.z * 1.5),
                readProperties(document, resized).size)
            assertPoint("Base $name $projection", frame.pivotPoint(ResizeHandle.HEIGHT),
                resizeFrame(document, resized).pivotPoint(ResizeHandle.HEIGHT))
            if (frame.size.z == 0.0) assertTrue(delta.isIdentity)
        }
    }

    @Test
    fun `perspective and orthographic corners resolve both local dimensions rather than screen height`() {
        val item = element(Shape.Box(Size3(2.0, 3.0, 4.0)))
        val frame = resizeFrame(document(item), item)
        for (projection in Projection.entries) for (handle in corners) {
            val orbit = camera.copy(projection = projection)
            val signs = signs(handle)
            val start = frame.handlePoint(handle)
            val end = plus(start, Point3(signs.first, signs.second * 0.75))
            val delta = resizeDelta(frame, handle, project(start, true, orbit), project(end, true, orbit),
                plan, orbit, true, width, height)
            assertPoint("$projection $handle", Point3(1.5, 1.25, 1.0), delta.axisScale)
        }
    }

    @Test
    fun `corner proportion lock scales width and depth together without touching height`() {
        val item = element(Shape.Box(Size3(2.0, 3.0, 4.0)))
        val frame = resizeFrame(document(item), item)
        for (handle in corners) {
            val signs = signs(handle)
            val start = frame.handlePoint(handle)
            val end = plus(start, Point3(signs.first, signs.second * 0.3))
            val delta = resizeDelta(frame, handle, project(start, false), project(end, false), plan, camera, false,
                width, height, keepProportions = true)
            assertPoint("$handle", Point3(1.5, 1.5, 1.0), delta.axisScale)
            val resized = item.copy(transform = EditTransforms.apply(item.transform, delta))
            assertPoint("$handle", Point3(3.0, 4.5, 4.0), readProperties(document(item), resized).size)
        }
    }

    @Test
    fun `rotated mirrored objects resize local axes and preserve fixed physical bases at survey coordinates`() {
        val item = element(Shape.Box(Size3(2.0, 3.0, 4.0)), Transform3(
            Point3(1e9, -1e9, 30.0), Rotation3(0.31, 0.4, 0.8), Scale3(-2.0, 3.0, -0.5),
        ))
        val document = document(item)
        val frame = resizeFrame(document, item)
        val orbit = camera.copy(target = item.transform.translation)
        for (handle in ResizeHandle.entries) {
            val signs = signs(handle)
            val amount = when (handle) {
                ResizeHandle.HEIGHT -> times(frame.axis(2), frame.size.z * 0.5)
                else -> plus(times(frame.axis(0), if (affectsX(handle)) frame.size.x * 0.5 * signs.first else 0.0),
                    times(frame.axis(1), if (affectsY(handle)) frame.size.y * 0.25 * signs.second else 0.0))
            }
            val start = frame.handlePoint(handle)
            val end = plus(start, amount)
            val delta = resizeDelta(frame, handle, project(start, true, orbit), project(end, true, orbit),
                plan, orbit, true, width, height)
            val changed = item.copy(transform = EditTransforms.apply(item.transform, delta))
            val expected = Point3(frame.size.x * if (affectsX(handle)) 1.5 else 1.0,
                frame.size.y * if (affectsY(handle)) 1.25 else 1.0,
                frame.size.z * if (handle == ResizeHandle.HEIGHT) 1.5 else 1.0)
            assertPoint("$handle", expected, readProperties(document, changed).size, 2e-6)
            assertTrue(changed.transform.scale.x < 0.0 && changed.transform.scale.z < 0.0)
            val changedFrame = resizeFrame(document, changed)
            assertPoint("Pivot $handle", frame.pivotPoint(handle), changedFrame.pivotPoint(handle), 1e-6)
            for (local in listOf(Point3(), Point3(1.0, 2.0, 3.0))) {
                assertPoint("Preview equals commit $handle", delta.apply(frame.worldPoint(local)), changedFrame.worldPoint(local), 2e-6)
            }
        }
    }

    @Test
    fun `display units and zoom do not change physical resize or Properties dimensions`() {
        for (units in LengthUnit.entries) for (pixelsPerMetre in listOf(10.0, 100.0, 1000.0)) {
            val item = element(Shape.Box(Size3(1.0, 1.0, 1.0)))
            val doc = document(item).let { it.copy(project = it.project.copy(settings = it.project.settings.copy(lengthUnit = units))) }
            val frame = resizeFrame(doc, item)
            val viewport = Viewport2D(pixelsPerMetre = pixelsPerMetre)
            val point = frame.handlePoint(ResizeHandle.WIDTH_POSITIVE)
            fun screen(p: Point3) = viewport.worldToScreen(Point2(p.x, p.y), width, height)
            val delta = resizeDelta(frame, ResizeHandle.WIDTH_POSITIVE, screen(point), screen(plus(point, Point3(0.25))),
                viewport, camera, false, width, height)
            val resized = item.copy(transform = EditTransforms.apply(item.transform, delta))
            assertPoint("$units $pixelsPerMetre", Point3(1.25, 1.0, 1.0), readProperties(doc, resized).size)
            val numeric = applyProperties(doc, resized, readProperties(doc, resized).copy(size = Point3(1.5, 1.0, 1.0)))
            assertPoint("Properties $units", Point3(1.5, 1.0, 1.0), resizeFrame(doc, numeric).size)
        }
    }

    @Test
    fun `rotated plan side drag inverts projected scale instead of treating metres as pixels`() {
        val item = element(Shape.Box(Size3(1.0, 2.0, 3.0)), Transform3(rotation = Rotation3(y = PI / 3)))
        val frame = resizeFrame(document(item), item)
        val start = frame.handlePoint(ResizeHandle.WIDTH_POSITIVE)
        val end = plus(start, times(frame.axis(0), 0.5))
        val delta = resizeDelta(frame, ResizeHandle.WIDTH_POSITIVE, project(start, false), project(end, false),
            plan, camera, false, width, height)
        assertPoint("Tilted plan side", Point3(1.5, 1.0, 1.0), delta.axisScale)
    }

    @Test
    fun `flat axes and edge on base planes have no unstable resize`() {
        val item = element(Shape.Circle(0.5))
        val frame = resizeFrame(document(item), item)
        assertFalse(frame.supports(ResizeHandle.HEIGHT))
        for (preset in listOf(ViewPreset.FRONT, ViewPreset.SIDE)) {
            val orbit = camera.preset(preset)
            assertFalse(resizePlaneVisible(frame, orbit, true))
            assertTrue(resizeDelta(frame, ResizeHandle.CORNER_MAX_MAX, Point2(400.0, 400.0), Point2(500.0, 500.0),
                plan, orbit, true, width, height).isIdentity)
        }
    }

    @Test
    fun `crossing the opposite edge clamps positive and a stationary pointer remains exact identity`() {
        val item = element(Shape.Box(Size3(1.0, 1.0, 1.0)))
        val frame = resizeFrame(document(item), item)
        val start = project(frame.handlePoint(ResizeHandle.WIDTH_POSITIVE), false)
        val delta = resizeDelta(frame, ResizeHandle.WIDTH_POSITIVE, start, Point2(start.x - 1000.0, start.y),
            plan, camera, false, width, height)
        assertTrue(delta.axisScale.x > 0.0)
        assertEquals(1.0, delta.axisScale.y, 0.0)
        assertEquals(1.0, delta.axisScale.z, 0.0)
        assertTrue(resizeDelta(frame, ResizeHandle.WIDTH_POSITIVE, start, start, plan, camera, false, width, height).isIdentity)
    }

    @Test
    fun `anisotropic GPU matrix equals committed local resize and mismatched frames reject shear`() {
        val rotation = Rotation3(0.3, 0.4, -0.8)
        val transform = Transform3(Point3(2.0, 3.0, 4.0), rotation, Scale3(-2.0, 3.0, 4.0))
        val delta = EditDelta(Point3(1.0, -2.0, 3.0), rotationZ = 0.5,
            axisScale = Point3(2.0, 0.5, 3.0), axes = rotation)
        val matrix = delta.linearMatrixDouble()
        val floats = delta.linearMatrix()
        val vector = Point3(3.0, -4.0, 5.0)
        val product = Point3(matrix[0] * vector.x + matrix[4] * vector.y + matrix[8] * vector.z,
            matrix[1] * vector.x + matrix[5] * vector.y + matrix[9] * vector.z,
            matrix[2] * vector.x + matrix[6] * vector.y + matrix[10] * vector.z)
        val world = plus(delta.pivot, vector)
        assertPoint("GPU", plus(delta.pivot, product), delta.apply(world))
        for (i in floats.indices) assertEquals(matrix[i], floats[i].toDouble(), 2e-7)
        val original = ResizeFrame(Bounds3(Point3(), Point3(1.0, 1.0, 1.0)), transform)
        val resized = original.copy(transform = EditTransforms.apply(transform, delta))
        assertPoint("Commit", delta.apply(original.worldPoint(vector)), resized.worldPoint(vector))
        assertThrows(IllegalArgumentException::class.java) {
            EditTransforms.apply(transform, delta.copy(axes = Rotation3()))
        }
        for (invalid in listOf(Point3(0.0, 1.0, 1.0), Point3(1.0, -1.0, 1.0))) {
            assertThrows(IllegalArgumentException::class.java) { EditDelta(axisScale = invalid) }
        }
    }

    private fun allShapes(): Map<String, Element> {
        val a = Point3(); val b = Point3(1.0); val c = Point3(1.0, 1.0); val d = Point3(0.0, 1.0)
        val inputs = mapOf(
            DrawingTool.LINE to listOf(a, b), DrawingTool.POLYLINE to listOf(a, b, c),
            DrawingTool.ARC to listOf(a, b, c), DrawingTool.CIRCLE to listOf(a, b),
            DrawingTool.ELLIPSE to listOf(a, c), DrawingTool.RECTANGLE to listOf(a, c),
            DrawingTool.POLYGON to listOf(a, b, c, d), DrawingTool.TRIANGLE to listOf(a, b, d),
            DrawingTool.SPLINE to listOf(a, b, c), DrawingTool.TEXT to listOf(a),
            DrawingTool.LEADER to listOf(a, b, c), DrawingTool.DIM_ALIGNED to listOf(a, b, d),
            DrawingTool.DIM_ANGULAR to listOf(a, b, d, c), DrawingTool.DIM_RADIAL to listOf(a, b, c),
            DrawingTool.BOX to listOf(a, c), DrawingTool.SPHERE to listOf(a, b),
            DrawingTool.CYLINDER to listOf(a, b), DrawingTool.CONE to listOf(a, b), DrawingTool.PYRAMID to listOf(a, c),
        )
        return inputs.mapKeys { it.key.name }.mapValues { (name, points) ->
            val sketch = createSketch(DrawingTool.valueOf(name), points)
            element(sketch.shape, sketch.transform)
        }
    }

    private fun element(shape: Shape, transform: Transform3 = Transform3()) = Element(
        ElementId("object"), projectId, layerId, ElementBody.DrawingShape(shape), transform = transform,
    )
    private fun document(item: Element) = ProjectDocument(Project(projectId, "Resize", 0L, 0L),
        layers = listOf(Layer(layerId, projectId, "Drawing")), elements = listOf(item))
    private fun project(p: Point3, is3D: Boolean, orbit: OrbitCamera = camera): Point2 =
        if (is3D) orbit.project(p, width, height)!!.let { Point2(it.x, it.y) }
        else plan.worldToScreen(Point2(p.x, p.y), width, height)
    private fun signs(handle: ResizeHandle) =
        (if (handle in listOf(ResizeHandle.WIDTH_NEGATIVE, ResizeHandle.CORNER_MIN_MIN, ResizeHandle.CORNER_MIN_MAX)) -1.0 else 1.0) to
            (if (handle in listOf(ResizeHandle.DEPTH_NEGATIVE, ResizeHandle.CORNER_MIN_MIN, ResizeHandle.CORNER_MAX_MIN)) -1.0 else 1.0)
    private fun affectsX(handle: ResizeHandle) = handle == ResizeHandle.WIDTH_NEGATIVE || handle == ResizeHandle.WIDTH_POSITIVE || handle in corners
    private fun affectsY(handle: ResizeHandle) = handle == ResizeHandle.DEPTH_NEGATIVE || handle == ResizeHandle.DEPTH_POSITIVE || handle in corners
    private fun plus(a: Point3, b: Point3) = Point3(a.x + b.x, a.y + b.y, a.z + b.z)
    private fun times(a: Point3, b: Double) = Point3(a.x * b, a.y * b, a.z * b)
    private fun assertPoint(message: String, expected: Point3, actual: Point3, tolerance: Double = 1e-8) {
        assertEquals("$message X", expected.x, actual.x, tolerance)
        assertEquals("$message Y", expected.y, actual.y, tolerance)
        assertEquals("$message Z", expected.z, actual.z, tolerance)
    }
    private companion object {
        val corners = setOf(ResizeHandle.CORNER_MIN_MIN, ResizeHandle.CORNER_MAX_MIN,
            ResizeHandle.CORNER_MAX_MAX, ResizeHandle.CORNER_MIN_MAX)
    }
}
