package com.eezydraw.core.editing

import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ClashCommandsTest {
    private val p=ProjectId("p");private val l=LayerId("l")
    private fun box(id:String,x:Double)=Element(ElementId(id),p,l,ElementBody.DrawingShape(Shape.Box(Size3(1.0,1.0,1.0))),name=id,transform=Transform3(Point3(x,0.0)))
    private val source=ProjectDocument(Project(p,"Clashes",0,0),layers=listOf(Layer(l,p,"Base")),elements=listOf(box("a",0.0),box("b",.5)))
    private val options=ClashCheckOptions(clearance=0.0,panelClearance=0.0)
    @Test fun rerunsPreserveStatusNotesAndIdsResolveMissingAndReopenReturningClashes() {
        val history=EditHistory()
        var doc=history.execute(source,ClashCommands.reconcile(source,ClashDetection.run(source,options),1))
        val id=doc.issues.single().id
        doc=history.execute(doc,ClashCommands.status(doc,id,ClashStatus.APPROVED,"Accepted penetration"))
        doc=history.execute(doc,ClashCommands.reconcile(doc,ClashDetection.run(doc,options),2))
        assertEquals(id,doc.issues.single().id);assertEquals(ClashStatus.APPROVED,doc.issues.single().clash!!.status)
        assertEquals(IssueStatus.CLOSED,doc.issues.single().status)
        assertEquals("Accepted penetration",doc.issues.single().clash!!.note)
        val moved=doc.copy(elements=listOf(doc.elements.first(),box("b",5.0)))
        val resolved=ClashCommands.reconcile(moved,ClashDetection.run(moved,options),3).preview(moved)
        assertEquals(ClashStatus.RESOLVED,resolved.issues.single().clash!!.status)
        val returned=resolved.copy(elements=source.elements)
        val reopened=ClashCommands.reconcile(returned,ClashDetection.run(returned,options),4).preview(returned)
        assertEquals(ClashStatus.NEW,reopened.issues.single().clash!!.status)
        assertEquals("Accepted penetration",reopened.issues.single().clash!!.note)
    }
    @Test fun statusChangesAreUndoableOnLockedObjectsAndRunOptionsPersist() {
        val locked=source.copy(elements=source.elements.map {it.withLock(LockMode.ALL)})
        val history=EditHistory();val checked=history.execute(locked,ClashCommands.reconcile(locked,ClashDetection.run(locked,options),1))
        val next=history.execute(checked,ClashCommands.status(checked,checked.issues.single().id,ClashStatus.IGNORED,"Known"))
        assertEquals(checked,history.undo(next));assertEquals(next,history.redo(checked))
        assertEquals(options,next.project.settings.clashCheck)
    }
    @Test fun differentScopesDoNotResolveUnrelatedResultsAndDeleteKeepsAuditHistory() {
        val checked=ClashCommands.reconcile(source,ClashDetection.run(source,options),1).preview(source)
        val other=options.copy(first=ClashGroup(category=Discipline.HVAC))
        val unchanged=ClashCommands.reconcile(checked,ClashDetection.run(checked,other),2).preview(checked)
        assertEquals(ClashStatus.NEW,unchanged.issues.single().clash!!.status)
        val deleted=EditCommands.delete(checked,setOf(checked.elements.last().id)).preview(checked)
        assertEquals(ClashStatus.RESOLVED,deleted.issues.single().clash!!.status)
        assertEquals(1,deleted.issues.single().elementIds.size)
    }
}
