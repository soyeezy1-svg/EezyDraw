package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class NavigationCommandsTest {
    @Test fun namedViewsSaveDeleteAndUndoWithoutEditingGeometry() {
        val p=ProjectId("views");val document=ProjectDocument(Project(p,"Views",0,0))
        val view=SavedView(ViewId("view"),p,"North",Camera(Point3(z=10.0),Point3()),sectionBox=Bounds3(Point3(),Point3(1.0,1.0,1.0)))
        val history=EditHistory();val saved=history.execute(document,NavigationCommands.save(document,view))
        assertEquals(listOf(view),saved.views);assertEquals(document,history.undo(saved));assertEquals(saved,history.redo(document))
        val deleted=history.execute(saved,NavigationCommands.delete(saved,view.id));assertTrue(deleted.views.isEmpty());assertEquals(saved,history.undo(deleted))
    }
}
