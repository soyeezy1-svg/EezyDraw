package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ServiceRoutingTest {
    private val id=ProjectId("routing")
    private val level=Level(LevelId("ground"),id,"Ground",0.0,.2,4.0)
    private val doc=ProjectDocument(Project(id,"Routes",0,0),levels=listOf(level,level.copy(id=LevelId("upper"),name="Upper",elevation=4.0)),layers=listOf(Layer(LayerId("services"),id,"Services")))
    private val settings=defaultRoutingSettings(RouteKind.CABLE_TRAY)
    private fun pick(x:Double,y:Double,z:Double=2.5,s:RoutingSettings=settings)=RoutePick(Point3(x,y,z),s,level.id)
    private fun create(vararg p:RoutePick,document:ProjectDocument=doc,kind:RouteKind=RouteKind.CABLE_TRAY)=ServiceRouting.create(document,kind,p.toList()).preview(document)
    private fun routes(d:ProjectDocument)=d.elements.filter {it.body is ElementBody.Route}
    private fun fittings(d:ProjectDocument)=d.elements.mapNotNull {e->(e.body as? ElementBody.Component)?.let {b->d.libraryItems.find {it.id==b.libraryItemId}?.fitting}}
    @Test fun `all seven service profiles and elevations produce native sections`() {
        RouteKind.entries.forEach {kind->val d=create(pick(0.0,0.0),pick(5.0,0.0),kind=kind)
            assertEquals(kind,(routes(d).single().body as ElementBody.Route).kind);assertEquals(5.0,routeLength(routes(d).single()),1e-8)
        }
        assertEquals(6.5,routingElevation(settings.elevation,doc.levels[1],doc.levels),1e-8)
        assertEquals(3.5,routingElevation(ElevationSetting(.3,ElevationReference.BELOW_SLAB),level,doc.levels),1e-8)
        assertEquals(3.5,routingElevation(ElevationSetting(.3,ElevationReference.BELOW_CEILING),level,doc.levels),1e-8)
    }
    @Test fun `ortho and height change append vertical riser before next leg`() {
        val a=appendRoutePoint(emptyList(),Point3(),settings,level,doc.levels)
        val b=appendRoutePoint(a,Point3(5.0,.1),settings,level,doc.levels)
        assertEquals(0.0,b.last().point.y,0.0)
        val raised=settings.copy(elevation=ElevationSetting(3.0,ElevationReference.ABOVE_FINISHED_FLOOR))
        val c=appendRoutePoint(b,Point3(5.0,4.0),raised,level,doc.levels)
        assertEquals(Point3(5.0,0.0,3.0),c[2].point)
        val d=ServiceRouting.create(doc,RouteKind.CABLE_TRAY,c).preview(doc)
        assertEquals(3,routes(d).size)
        assertEquals(.5,routeLength(routes(d)[1]),1e-8)
        assertTrue(fittings(d).any {it.kind==FittingKind.RISER_UP || it.kind==FittingKind.RISER_DOWN || it.kind==FittingKind.BEND_90_VERTICAL_INSIDE})
    }
    @Test fun `bends match sections and forty five mode bevels an orthogonal corner`() {
        val d=create(pick(0.0,0.0),pick(4.0,0.0),pick(4.0,4.0))
        assertEquals(1,fittings(d).count {it.kind==FittingKind.ELBOW_90})
        assertTrue(fittings(d).all {it.section==settings.section(RouteKind.CABLE_TRAY)})
        val s=settings.copy(bendDegrees=45)
        val beveled=create(pick(0.0,0.0,s=s),pick(4.0,0.0,s=s),pick(4.0,4.0,s=s))
        assertEquals(3,routes(beveled).size);assertEquals(2,fittings(beveled).count {it.kind==FittingKind.ELBOW_45})
        val off=settings.copy(autoFittings=false)
        assertTrue(fittings(create(pick(0.0,0.0,s=off),pick(4.0,0.0,s=off),pick(4.0,4.0,s=off))).isEmpty())
    }
    @Test fun `offset assembly and turning reducer keep physical ports on incident sections`() {
        val s=settings.copy(bendDegrees=45)
        val offset=create(pick(0.0,0.0,s=s),pick(4.0,0.0,s=s),pick(5.0,1.0,s=s),pick(9.0,1.0,s=s))
        assertEquals(1,fittings(offset).count {it.kind==FittingKind.OFFSET})
        val wider=settings.copy(width=.6)
        val turn=create(pick(0.0,0.0),pick(4.0,0.0,s=wider),pick(4.0,4.0,s=wider))
        assertEquals(1,fittings(turn).count {it.kind==FittingKind.ELBOW_90})
        assertEquals(1,fittings(turn).count {it.kind==FittingKind.REDUCER})
        val d=create(pick(0.0,0.0),pick(4.0,0.0),pick(4.0,4.0))
        val elbow=d.elements.first {e->(e.body as? ElementBody.Component)?.let {b->d.libraryItems.find {it.id==b.libraryItemId}?.fitting?.kind==FittingKind.ELBOW_90}==true}
        val definition=d.libraryItems.first {it.id==(elbow.body as ElementBody.Component).libraryItemId}.fitting!!
        val ports=definition.ports.map {measurementToWorld(elbow.transform,it.position)}
        val endpoints=routes(d).flatMap {e->(e.body as ElementBody.Route).fabricationPath!!.map {measurementToWorld(e.transform,it)}}
        for(port in ports)assertTrue(endpoints.any {p->kotlin.math.abs(p.x-port.x)+kotlin.math.abs(p.y-port.y)+kotlin.math.abs(p.z-port.z)<1e-6})
    }

    @Test fun `branch splits section into tee and a fourth leg makes cross`() {
        val main=create(pick(0.0,0.0),pick(8.0,0.0))
        val branch=create(pick(4.0,0.0),pick(4.0,4.0),document=main)
        assertEquals(3,routes(branch).size);assertEquals(1,fittings(branch).count {it.kind==FittingKind.TEE})
        val cross=create(pick(4.0,0.0),pick(4.0,-4.0),document=branch)
        assertEquals(1,fittings(cross).count {it.kind==FittingKind.CROSS})
    }
    @Test fun `size change creates reducer and editing updates connected fitting atomically`() {
        val wider=settings.copy(width=.6)
        val d=create(pick(0.0,0.0),pick(4.0,0.0,s=wider),pick(8.0,0.0,s=wider))
        val reducer=fittings(d).single {it.kind==FittingKind.REDUCER}
        assertEquals(wider.section(RouteKind.CABLE_TRAY),reducer.outletSection)
        val section=routes(d).first()
        val changed=ServiceRouting.edit(d,section.id,settings.copy(width=.6)).preview(d)
        assertEquals(0,fittings(changed).count {it.kind==FittingKind.REDUCER})
        val history=EditHistory()
        val cmd=ServiceRouting.edit(d,section.id,settings.copy(elevation=settings.elevation.copy(value=3.0)))
        val raised=history.execute(d,cmd)
        val body=routes(raised)[1].body as ElementBody.Route
        assertEquals(3.0,measurementToWorld(routes(raised)[1].transform,body.path.first()).z,1e-8)
        assertEquals(d,history.undo(raised));assertEquals(raised,history.redo(d))
    }
    @Test fun `dragging a shared end follows topology and rejects locked neighbors`() {
        val d=create(pick(0.0,0.0),pick(4.0,0.0),pick(4.0,4.0));val e=routes(d).first()
        val changed=ServiceRouting.edit(d,e.id,settings,end=Point3(5.0,1.0,2.5)).preview(d)
        val other=routes(changed)[1]
        assertEquals(Point3(5.0,1.0,2.5),measurementToWorld(other.transform,(other.body as ElementBody.Route).path.first()))
        val locked=d.copy(elements=d.elements.map {if(it.id==routes(d)[1].id)it.withLock(LockMode.ALL)else it})
        try {ServiceRouting.edit(locked,e.id,settings,end=Point3(5.0,1.0,2.5));fail("Locked neighbor changed")}catch(_:IllegalArgumentException){}
    }
    @Test fun `supports spacing replacement suppression and serialization retain graph`() {
        val d=create(pick(0.0,0.0),pick(5.0,0.0));val section=routes(d).single()
        val supported=ServiceRouting.supports(d,section.id,1.0,FittingKind.HANGER).preview(d)
        assertEquals(4,fittings(supported).count {it.kind==FittingKind.HANGER})
        val first=supported.elements.first {it.name==FittingKind.END_CAP.name.replace('_',' ')}
        val removed=ServiceRouting.fitting(supported,first.id,null).preview(supported)
        assertFalse(removed.elements.any {it.id==first.id})
        assertEquals(removed,ServiceRouting.reconcile(removed,removed))
    }
    @Test fun `equipment height capture adds a terminal riser and room ceiling references differ from slab`() {
        val item=BuiltInLibrary.items.first {it.symbol==LibrarySymbol.RACK}
        val rack=Element(ElementId("rack"),id,doc.layers.single().id,ElementBody.Component(item.id,item.defaultSize),level.id,transform=Transform3(Point3(5.0,0.0)))
        val base=doc.copy(elements=listOf(rack),libraryItems=listOf(item))
        val end=appendRoutingPick(base,RouteKind.CABLE_TRAY,listOf(pick(0.0,0.0)),Point3(5.0,0.0),settings,level)
        assertEquals(3,end.size)
        assertEquals(item.defaultSize.height,end.last().point.z,1e-8)
        val room=Element(ElementId("room"),id,doc.layers.single().id,ElementBody.Room(Polygon2(listOf(Point2(-1.0,-1.0),Point2(9.0,-1.0),Point2(9.0,9.0),Point2(-1.0,9.0))),ceilingHeight=3.0),levelId=level.id)
        val enclosed=doc.copy(elements=listOf(room))
        assertEquals(2.7,routingElevationAt(enclosed,ElevationSetting(.3,ElevationReference.BELOW_CEILING),level.id,Point3()),1e-8)
        assertEquals(3.5,routingElevationAt(enclosed,ElevationSetting(.3,ElevationReference.BELOW_SLAB),level.id,Point3()),1e-8)
        val raised=appendRoutePoint(listOf(pick(0.0,0.0)),Point3(5.0,0.0),settings,doc.levels[1],doc.levels)
        assertEquals(6.5,raised.last().point.z,1e-8)
    }
    @Test fun `copied network is independent and deleted sections leave valid caps`() {
        val d=create(pick(0.0,0.0),pick(4.0,0.0),pick(4.0,4.0))
        val cloned=EditCommands.paste(d,routes(d),Point3(20.0,0.0)).preview(d)
        val copy=ServiceRouting.reconcile(d,cloned)
        val networkIds=routes(copy).map {(it.body as ElementBody.Route).routing!!.networkId}.toSet()
        assertEquals(2,networkIds.size)
        val originalNode=(routes(d).first().body as ElementBody.Route).routing!!.startNode
        assertFalse(routes(copy).drop(2).any {(it.body as ElementBody.Route).routing!!.startNode==originalNode})
        val deleted=EditCommands.delete(d,setOf(routes(d).first().id)).preview(d)
        val repaired=ServiceRouting.reconcile(d,deleted)
        assertEquals(1,routes(repaired).size)
        assertEquals(2,fittings(repaired).count {it.kind==FittingKind.END_CAP})
        val changed=ServiceRouting.fitting(repaired,repaired.elements.first {ROUTING_OWNER in it.properties}.id,FittingKind.COUPLER).preview(repaired)
        assertTrue(fittings(changed).any {it.kind==FittingKind.COUPLER})
    }

    @Test fun `library face anchor follows equipment movement and hidden hosts do not capture`() {
        val item=BuiltInLibrary.items.first {it.symbol==LibrarySymbol.RACK}
        val equipment=Element(ElementId("rack"),id,doc.layers.single().id,ElementBody.Component(item.id,item.defaultSize))
        val base=doc.copy(elements=listOf(equipment),libraryItems=listOf(item))
        val at=Point3(z=item.defaultSize.height)
        assertEquals(equipment.id,snapRoute(base,RouteKind.CABLE_TRAY,at).anchor?.elementId)
        val d=create(RoutePick(at,settings,level.id),pick(5.0,0.0,z=at.z),document=base)
        val moved=base.elements.single().copy(transform=Transform3(Point3(1.0,0.0,0.0)))
        val changed=ServiceRouting.reconcile(d,d.copy(elements=d.elements.map {if(it.id==equipment.id)moved else it}))
        val e=routes(changed).first();assertEquals(1.0,measurementToWorld(e.transform,(e.body as ElementBody.Route).path.first()).x,1e-8)
        val hidden=base.copy(elements=listOf(equipment.withDisplay(ObjectDisplay.HIDDEN)))
        assertNull(snapRoute(hidden,RouteKind.CABLE_TRAY,at).anchor)
    }
}
