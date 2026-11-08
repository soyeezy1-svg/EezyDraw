package com.eezydraw.core.editing
import com.eezydraw.core.model.*
import org.junit.Test
import org.junit.Assert.*
class IssueCommandsTest {
 private val p=ProjectId("p");private val source=ProjectDocument(Project(p,"Issues",0,0))
 private val issue=Issue(IssueId("i"),p,"Leak",1,priority=IssuePriority.CRITICAL,dueDate="2026-12-01",position=Point3(),assignedTo="Pat")
 @Test fun creationEditFilteringAndHistory(){val h=EditHistory();val doc=h.execute(source,IssueCommands.put(source,issue));assertEquals(listOf(issue),filterIssues(doc,"pat",priority=IssuePriority.CRITICAL));assertTrue(filterIssues(doc,status=IssueStatus.CLOSED).isEmpty());val edited=h.execute(doc,IssueCommands.put(doc,issue.copy(status=IssueStatus.RESOLVED)));assertEquals(doc,h.undo(edited));assertEquals(edited,h.redo(doc))}
 @Test fun lockHideAndAttachmentValidation(){val locked=IssueCommands.put(source,issue.copy(locked=true)).preview(source);try {IssueCommands.put(locked,issue.copy(locked=true,title="Changed"));fail()}catch(_:IllegalArgumentException){};val hidden=IssueCommands.put(source,issue.copy(hidden=true)).preview(source);assertTrue(filterIssues(hidden).isEmpty());try {IssueCommands.put(source,issue.copy(attachments=listOf(AssetId("missing"))));fail()}catch(_:IllegalArgumentException){}}
 @Test fun clashLinksAndAssetPatchesUndoTogether(){val a=ProjectAsset(AssetId("a"),p,"photo.png","image/png");val linked=issue.copy(sourceClashId=IssueId("clash-source"),attachments=listOf(a.id),annotations=listOf(IssueMarkup(MarkupTool.ARROW,listOf(Point2(.1,.2),Point2(.8,.7)))));val h=EditHistory();val doc=h.execute(source,IssueCommands.put(source,linked,listOf(a)));assertEquals(source,h.undo(doc));assertEquals(doc,h.redo(source));assertEquals(linked,doc.issues.single())}
}
