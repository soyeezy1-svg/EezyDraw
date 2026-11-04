package com.eezydraw.core.editing

import com.eezydraw.core.model.ElementId
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.ProjectId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    @Test
    fun `one element edit on a 100000 object drawing retains one sparse patch`() {
        val original = EditFixtures.document().copy(elements = List(100_000) { EditFixtures.line("element-$it") })
        val id = ElementId("element-50000")
        val command = EditCommands.transform(original, setOf(id), EditDelta(translation = Point3(1.0, 2.0, 3.0)))
        assertEquals(1, command.changedElementCount)
        assertEquals(setOf(id), command.affectedElementIds)
        val history = EditHistory()
        val edited = history.execute(original, command)
        assertEquals(1L, history.retainedChangeCount)
        assertSame(original.elements[123], edited.elements[123])
        assertEquals(Point3(1.0, 2.0, 3.0), edited.elements[50_000].transform.translation)
        assertEquals(original, history.undo(edited))
        assertEquals(edited, history.redo(original))
    }

    @Test
    fun `a new edit clears redo but a no-op does not`() {
        val history = EditHistory()
        val original = EditFixtures.document()
        val first = history.execute(original, EditCommands.transform(original, setOf(ElementId("line")), EditDelta(translation = Point3(1.0))))
        val undone = history.undo(first)
        assertTrue(history.canRedo)
        val unchanged = history.execute(undone, EditCommands.transform(undone, setOf(ElementId("line")), EditDelta()))
        assertSame(undone, unchanged)
        assertTrue(history.canRedo)
        val branch = history.execute(unchanged, EditCommands.transform(unchanged, setOf(ElementId("line")), EditDelta(translation = Point3(y = 2.0))))
        assertFalse(history.canRedo)
        assertEquals(1, history.undoCount)
        assertSame(branch, history.redo(branch))
    }

    @Test
    fun `undo count limit trims oldest history and supports disabling and clearing`() {
        val history = EditHistory(2)
        var current = EditFixtures.document()
        val snapshots = mutableListOf(current)
        repeat(3) {
            current = history.execute(current, EditCommands.transform(current, setOf(ElementId("line")), EditDelta(translation = Point3(1.0))))
            snapshots.add(current)
        }
        assertEquals(2, history.undoCount)
        current = history.undo(current)
        current = history.undo(current)
        assertEquals(snapshots[1], current)
        assertFalse(history.canUndo)
        history.setLimit(1)
        assertEquals(1, history.redoCount)
        assertEquals(snapshots[2], history.redo(current))
        history.clear()
        assertEquals(0L, history.retainedChangeCount)
        assertFalse(history.canRedo)
        assertFalse(history.canUndo)
        history.setLimit(0)
        current = history.execute(current, EditCommands.transform(current, setOf(ElementId("line")), EditDelta(translation = Point3(1.0))))
        assertFalse(history.canUndo)
        assertEquals(0L, history.retainedChangeCount)
        assertEquals(Point3(2.0), current.elements.single().transform.translation)
    }

    @Test
    fun `stale commands are rejected without destroying valid redo`() {
        val history = EditHistory()
        val original = EditFixtures.document()
        val command = EditCommands.transform(original, setOf(ElementId("line")), EditDelta(translation = Point3(1.0)))
        val edited = history.execute(original, command)
        assertThrows(IllegalArgumentException::class.java) { history.execute(edited, command) }
        val undone = history.undo(edited)
        val changed = undone.copy(elements = undone.elements.map { it.copy(name = "Externally changed") })
        assertThrows(IllegalArgumentException::class.java) { history.execute(changed, command) }
        assertTrue(history.canRedo)
        assertThrows(IllegalArgumentException::class.java) { history.redo(changed) }
        assertTrue(history.canRedo)
        assertEquals(edited, history.redo(undone))
    }

    @Test
    fun `project mismatch and changed order are guarded but current settings survive undo`() {
        val original = EditFixtures.document().copy(elements = listOf(EditFixtures.line("a"), EditFixtures.line("b")))
        val command = EditCommands.delete(original, setOf(ElementId("a")))
        val history = EditHistory()
        assertThrows(IllegalArgumentException::class.java) {
            history.execute(original.copy(project = original.project.copy(id = ProjectId("other"))), command)
        }
        assertThrows(IllegalArgumentException::class.java) { history.execute(original.copy(elements = original.elements.reversed()), command) }
        val edited = history.execute(original, command)
        val renamed = edited.copy(project = edited.project.copy(name = "Current metadata"))
        val undone = history.undo(renamed)
        assertEquals("Current metadata", undone.project.name)
        assertEquals(original.elements, undone.elements)
    }

    @Test
    fun `delete undo restores element order and only stores removed or cleaned elements`() {
        val original = EditFixtures.document().copy(elements = (0..7).map { EditFixtures.line("$it") })
        val command = EditCommands.delete(original, setOf(ElementId("0"), ElementId("3"), ElementId("7")))
        val history = EditHistory()
        val edited = history.execute(original, command)
        assertEquals(3, command.changedElementCount)
        assertEquals(listOf("1", "2", "4", "5", "6"), edited.elements.map { it.id.value })
        assertEquals(original, history.undo(edited))
        assertEquals(edited, history.redo(original))
    }

    @Test
    fun `unsupported limits are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { EditHistory(-1) }
        assertThrows(IllegalArgumentException::class.java) { EditHistory(10_001) }
        assertThrows(IllegalArgumentException::class.java) { EditHistory().setLimit(-1) }
    }
}
