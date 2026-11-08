package com.eezydraw.ui.canvas
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import com.eezydraw.storage.ViewExportRequest
import java.util.UUID
fun captureIssueView(request:ViewExportRequest):IssueView {val f=request.frame;val d=request.document;return IssueView(UUID.randomUUID().toString(),f.viewport.center,f.viewport.pixelsPerMetre,f.orbit.target,f.orbit.yaw,f.orbit.pitch,f.orbit.distance,f.orbit.orthographicHeight,f.orbit.projection,f.is3D,f.levelId?.let(::LevelId),d.project.settings.copy(viewMode=f.viewMode),d.layers.associate {it.id to it.visible},d.systems.associate {it.id to it.visible},d.elements.associate {it.id to it.properties.filterKeys {key->key==ELEMENT_DISPLAY_KEY || key==ELEMENT_COLOUR_KEY || key.startsWith(ROOM_SIDE_DISPLAY_PREFIX)}},request.width,request.height,f.showGrid,planRotation=f.viewport.rotation,sectionBox=f.sectionBox)}
fun restoreIssuePresentation(doc:ProjectDocument,view:IssueView?):ProjectDocument {if(view==null)return doc
 return doc.copy(project=doc.project.copy(settings=view.settings.copy(canvasLayout=doc.project.settings.canvasLayout)),layers=doc.layers.map {it.copy(visible=view.layerVisibility[it.id] ?: it.visible)},systems=doc.systems.map {it.copy(visible=view.systemVisibility[it.id] ?: it.visible)},elements=doc.elements.map {e->val saved=view.displays[e.id];if(saved==null)e else e.copy(properties=e.properties.filterKeys {it!=ELEMENT_DISPLAY_KEY && it!=ELEMENT_COLOUR_KEY && !it.startsWith(ROOM_SIDE_DISPLAY_PREFIX)}+saved)})
}
fun issuePins(doc:ProjectDocument,frame:CanvasFrame,width:Int,height:Int):Map<IssueId,Point2> = doc.issues.filter {i->doc.layers.none {it.id==LayerId("issue-pins") && !it.visible} && i.clash==null && !i.hidden && i.levelId !in doc.project.settings.hiddenLevelIds && (frame.levelId==null||i.levelId==null||i.levelId?.value==frame.levelId)}.mapNotNull {i->i.position?.let {p->com.eezydraw.core.editing.projectPrecisionPoint(p,frame.viewport,frame.orbit,frame.is3D,width.toDouble(),height.toDouble())}?.takeIf {it.x in 0.0..width.toDouble() && it.y in 0.0..height.toDouble()}?.let {i.id to Point2(it.x,it.y)}}.toMap()
fun issueMarkups(doc:ProjectDocument,frame:CanvasFrame,view:IssueView?):List<IssueMarkup> {
 if(view==null || doc.layers.any {it.id==LayerId("issue-markups") && !it.visible})return emptyList()
 val still=view.levelId?.value==frame.levelId && view.is3D==frame.is3D && (if(frame.is3D)view.target==frame.orbit.target && view.yaw==frame.orbit.yaw && view.pitch==frame.orbit.pitch && view.distance==frame.orbit.distance && view.projection==frame.orbit.projection && view.orthographicHeight==frame.orbit.orthographicHeight else view.centre==frame.viewport.center && view.pixelsPerMetre==frame.viewport.pixelsPerMetre && view.planRotation==frame.viewport.rotation)
 if(!still)return emptyList()
 return doc.issues.filter {it.clash==null && !it.hidden && it.markupVisible && (it.capture?.id==view.id || it.savedViews.any {saved->saved.id==view.id})}.flatMap {it.annotations.filter {mark->mark.viewId==view.id || mark.viewId.isEmpty()}}
}
