package com.eezydraw.ui.canvas

import com.eezydraw.core.editing.DrawingTool
import com.eezydraw.core.editing.ToolOptions
import com.eezydraw.core.editing.supportsDefaultPlacement
import com.eezydraw.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Construction previews are discarded geometry; no command or project mutation is involved. */
class ConstructionPreviewTest {
    private val projectId = ProjectId("preview-project")
    private val layerId = LayerId("drawing")
    private val existing = Element(ElementId("existing"), projectId, layerId,
        ElementBody.DrawingShape(Shape.Line(Point3(), Point3(2.0, 0.0))))
    private val document = ProjectDocument(Project(projectId, "Preview", 0, 0),
        layers = listOf(Layer(layerId, projectId, "Drawing")), elements = listOf(existing))

    @Test fun everyToolHasAGhostBeforeTheFirstPickWithoutPlacingAnObject() {
        val at = Point3(10.0, -3.0, 2.0)
        val before = document.copy()
        for (tool in DrawingTool.entries.filterNot { it == DrawingTool.SELECT }) {
            val scene = buildConstructionPreview(document, tool, ToolOptions(), emptyList(), at)
            assertNotNull("$tool must show a ghost before a tap", scene)
            assertTrue("$tool must show preview geometry", requireNotNull(scene).segments.isNotEmpty())
            assertEquals(before, document)
            assertEquals(listOf(existing), document.elements)
        }
        assertNull(buildConstructionPreview(document, DrawingTool.SELECT, ToolOptions(), emptyList(), at))
        assertNull(buildConstructionPreview(document, DrawingTool.BOX, ToolOptions(), emptyList(), null))
    }

    @Test fun SizedGhostFollowsTheCursorInCanonicalMetresWithoutChangingItsDimensions() {
        val first = Point3(1e9, -1e9, 12.0)
        val next = Point3(first.x + 4.0, first.y - 3.0, first.z)
        for (tool in DrawingTool.entries.filter(::supportsDefaultPlacement)) {
            val a = requireNotNull(buildConstructionPreview(document, tool, ToolOptions(), emptyList(), first))
            val b = requireNotNull(buildConstructionPreview(document, tool, ToolOptions(), emptyList(), next))
            assertEquals("$tool moves with the finger", 4.0, b.bounds.min.x - a.bounds.min.x, 1e-7)
            assertEquals(-3.0, b.bounds.min.y - a.bounds.min.y, 1e-7)
            assertEquals(1.0, b.bounds.max.x - b.bounds.min.x, 1e-6)
            assertEquals(1.0, b.bounds.max.y - b.bounds.min.y, 1e-6)
            assertEquals(a.bounds.max.z - a.bounds.min.z, b.bounds.max.z - b.bounds.min.z, 1e-7)
            assertEquals(listOf(existing), document.elements)
        }
    }

    @Test fun partialLineKeepsItsPickedStartAndTracksTheUncommittedEnd() {
        val picked = Point3(2.0, 3.0, 4.0)
        val cursor = Point3(7.0, 6.0, 4.0)
        val scene = requireNotNull(buildConstructionPreview(document, DrawingTool.LINE,
            ToolOptions(), listOf(picked), cursor))
        assertEquals(picked, scene.segments.single().a)
        assertEquals(cursor, scene.segments.single().b)
        assertEquals(listOf(existing), document.elements)
        val closed = requireNotNull(buildConstructionPreview(document, DrawingTool.POLYLINE,
            ToolOptions(closed = true), emptyList(), cursor))
        assertFalse(closed.segments.isEmpty())
    }

    @Test fun invalidArcPicksRemainAnOpenGhostRatherThanChangingTheDrawing() {
        val picks = listOf(Point3(), Point3(1.0, 0.0))
        val scene = requireNotNull(buildConstructionPreview(document, DrawingTool.ARC,
            ToolOptions(), picks, Point3(2.0, 0.0)))
        assertEquals(2, scene.segments.size)
        assertEquals(picks.first(), scene.segments.first().a)
        assertEquals(Point3(2.0, 0.0), scene.segments.last().b)
        assertEquals(listOf(existing), document.elements)
    }
}
