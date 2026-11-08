package com.eezydraw.core.editing

import com.eezydraw.core.geometry.ScheduleEngine
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TakeoffCommandsTest {
    private val p=ProjectId("takeoff");private val layer=LayerId("base")
    private val source=ProjectDocument(Project(p,"Takeoff",0,0),layers=listOf(Layer(layer,p,"Base")))
    @Test fun settingsAndRatesUndoRedoAndDoNotModifyLockedGeometry() {
        val item=LibraryItem(LibraryItemId("custom"),"Device",Discipline.ELECTRICAL,"Devices",Size3(1.0,1.0,1.0))
        val element=Element(ElementId("a"),p,layer,ElementBody.Component(item.id,item.defaultSize)).withLock(LockMode.ALL)
        val doc=source.copy(elements=listOf(element),libraryItems=listOf(item))
        val history=EditHistory();val configured=history.execute(doc,TakeoffCommands.settings(doc,TakeoffSettings(10.0,"GBP",listOf(providedSchedules().first()))))
        val table=ScheduleEngine.build(configured,providedSchedules().last());val key=table.rows.single().rateKey!!
        val priced=history.execute(configured,TakeoffCommands.rate(configured,key,17.25,setOf(item.id)))
        assertEquals(mapOf("GBP" to 17.25),priced.libraryItems.single().unitRates)
        assertEquals("17.25",ScheduleEngine.build(priced,providedSchedules().last()).pricedTotal.toPlainString())
        assertEquals(configured,history.undo(priced));assertEquals(priced,history.redo(configured));assertEquals(element,priced.elements.single())
        val cleared=TakeoffCommands.rate(priced,key,null,setOf(item.id)).preview(priced)
        assertTrue(cleared.libraryItems.single().unitRates.isEmpty());assertTrue(cleared.project.settings.takeoff.rates.isEmpty())
    }
    @Test fun realRoutingFittingsAreCountedOnceAndQuantitiesFollowEditAndUndo() {
        val settings=defaultRoutingSettings(RouteKind.CABLE_TRAY)
        fun pick(x:Double,y:Double,s:RoutingSettings=settings)=RoutePick(Point3(x,y,2.5),s,null)
        val routed=ServiceRouting.create(source,RouteKind.CABLE_TRAY,listOf(pick(0.0,0.0),pick(4.0,0.0,settings.copy(width=.5)),pick(4.0,4.0,settings.copy(width=.5)))).preview(source)
        val routeSchedule=providedSchedules().first {it.routeKind==RouteKind.CABLE_TRAY}
        val fittings=routed.elements.count {e->(e.body as? ElementBody.Component)?.let {b->routed.libraryItems.find {it.id==b.libraryItemId}?.fitting!=null}==true}
        val table=ScheduleEngine.build(routed,routeSchedule)
        assertEquals(fittings,table.fittings);assertEquals(8.0,table.measuredLength,1e-9)
        val fittingTable=ScheduleEngine.build(routed,providedSchedules().first {it.category==ScheduleCategory.FITTINGS})
        assertEquals(fittings,fittingTable.rows.sumOf {it.quantity}.toInt())
        assertTrue(fittingTable.rows.any {it.cells[ScheduleColumn.TYPE]?.endsWith(FittingKind.REDUCER.name)==true})
        val end=routed.elements.last {it.body is ElementBody.Route};val b=end.body as ElementBody.Route
        val history=EditHistory();val edited=history.execute(routed,ServiceRouting.edit(routed,end.id,b.routing!!.settings,null,Point3(4.0,6.0,2.5)))
        assertEquals(10.0,ScheduleEngine.build(edited,routeSchedule).measuredLength,1e-9)
        assertEquals(routed,history.undo(edited))
    }
}
