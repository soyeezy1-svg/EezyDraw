package com.eezydraw.core.model

import kotlinx.serialization.Serializable

@Serializable enum class IssuePriority { LOW, MEDIUM, HIGH, CRITICAL }
@Serializable enum class MarkupTool { CLOUD, ARROW, RECTANGLE, CIRCLE, PEN, TEXT, HIGHLIGHTER }
@Serializable data class IssueMarkup(val tool:MarkupTool,val points:List<Point2>,val colour:Long=0xFFFF3030,val thickness:Double=3.0,val text:String="",val viewId:String="",val levelId:LevelId?=null) {
    init {require(points.size in 1..10000 && points.all {it.x in 0.0..1.0 && it.y in 0.0..1.0});require(thickness.isFinite() && thickness in 1.0..30.0 && colour in 0..0xFFFFFFFFL && text.length<=2000)}
}
@Serializable data class IssueView(val id:String,val centre:Point2,val pixelsPerMetre:Double,val target:Point3,val yaw:Double,val pitch:Double,val distance:Double,val orthographicHeight:Double,val projection:Projection,val is3D:Boolean,val levelId:LevelId?,val settings:ProjectSettings,val layerVisibility:Map<LayerId,Boolean>,val systemVisibility:Map<SystemId,Boolean>,val displays:Map<ElementId,Map<String,ParameterValue>>,val width:Int,val height:Int,val grid:Boolean,val screenshot:AssetId?=null,val planRotation:Double=0.0,val sectionBox:Bounds3?=null) {
    init {require(planRotation.isFinite());require(width>0 && height>0 && pixelsPerMetre.isFinite() && pixelsPerMetre>0 && distance.isFinite() && distance>0 && orthographicHeight.isFinite() && orthographicHeight>0 && yaw.isFinite() && pitch.isFinite())}
}
enum class IssueSort { CREATED, PRIORITY, DUE_DATE, TITLE, STATUS }
fun filterIssues(document:ProjectDocument,query:String="",status:IssueStatus?=null,priority:IssuePriority?=null,level:LevelId?=null,sort:IssueSort=IssueSort.CREATED):List<Issue> = document.issues.filter {it.clash==null && !it.hidden && (status==null||it.status==status) && (priority==null||it.priority==priority) && (level==null||it.levelId==level) && "${it.title} ${it.description} ${it.assignedTo.orEmpty()}".contains(query,true)}.sortedWith(when(sort){IssueSort.CREATED->compareByDescending {it.createdAtEpochMillis};IssueSort.PRIORITY->compareByDescending {it.priority.ordinal};IssueSort.DUE_DATE->compareBy {it.dueDate ?: "9999"};IssueSort.TITLE->compareBy {it.title.lowercase()};IssueSort.STATUS->compareBy {it.status.ordinal}})
