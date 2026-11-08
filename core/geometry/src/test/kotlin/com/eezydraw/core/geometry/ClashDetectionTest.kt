package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureTimeMillis

class ClashDetectionTest {
    private val p=ProjectId("clashes");private val layer=LayerId("base");private val level=LevelId("g")
    private fun box(id:String,x:Double=0.0,y:Double=0.0,z:Double=0.0,size:Size3=Size3(1.0,1.0,1.0),rotation:Double=0.0)=Element(ElementId(id),p,layer,ElementBody.DrawingShape(Shape.Box(size)),level,name=id,transform=Transform3(Point3(x,y,z),Rotation3(z=rotation)))
    private fun doc(vararg elements:Element)=ProjectDocument(Project(p,"Clashes",0,0),layers=listOf(Layer(layer,p,"Base")),levels=listOf(Level(level,p,"Ground",0.0,.2,3.0)),elements=elements.toList())
    private val hardOnly=ClashCheckOptions(clearance=0.0,panelClearance=0.0)
    @Test fun hardOverlapTouchContainmentAndDuplicatesAreDistinct() {
        val hard=ClashDetection.run(doc(box("a"),box("b",.75)),hardOnly).clashes.single()
        assertEquals(ClashKind.HARD,hard.kind);assertEquals(.25,hard.overlap,1e-6)
        assertTrue(ClashDetection.run(doc(box("a"),box("b",1.0)),hardOnly).clashes.isEmpty())
        assertEquals(ClashKind.DUPLICATE,ClashDetection.run(doc(box("a"),box("b")),hardOnly).clashes.single().kind)
        assertEquals(ClashKind.HARD,ClashDetection.run(doc(box("a",size=Size3(4.0,4.0,4.0)),box("b",1.0,1.0,1.0)),hardOnly).clashes.single().kind)
    }
    @Test fun clearanceUsesEuclideanSurfaceDistanceAndToleranceIgnoresTinyOverlaps() {
        val c=ClashDetection.run(doc(box("a"),box("b",1.03,1.04)),ClashCheckOptions(clearance=.1,tolerance=0.0)).clashes.single()
        assertEquals(ClashKind.CLEARANCE,c.kind);assertEquals(.05,c.distance,1e-6)
        assertTrue(ClashDetection.run(doc(box("a"),box("b",.9995)),hardOnly).clashes.isEmpty())
        assertEquals(ClashKind.HARD,ClashDetection.run(doc(box("a"),box("b",.998)),hardOnly).clashes.single().kind)
        assertTrue(ClashDetection.run(doc(box("a"),box("b",1.05)),ClashCheckOptions(clearance=.05,tolerance=.001)).clashes.isEmpty())
    }
    @Test fun rotatedNarrowBodiesDoNotCollideJustBecauseBoundingBoxesOverlap() {
        val a=box("a",size=Size3(4.0,.1,.1),rotation=Math.PI/4)
        val b=box("b",y=.3,size=Size3(4.0,.1,.1),rotation=Math.PI/4)
        assertTrue(ClashDetection.run(doc(a,b),hardOnly).clashes.isEmpty())
        assertEquals(ClashKind.HARD,ClashDetection.run(doc(a,b.copy(transform=b.transform.copy(translation=Point3(y=.05)))),hardOnly).clashes.single().kind)
    }
    @Test fun holesAndPhysicalWallChasesRemainEmptySpace() {
        val outer=listOf(Point2(0.0,0.0),Point2(10.0,0.0),Point2(10.0,10.0),Point2(0.0,10.0))
        val hole=listOf(Point2(4.0,4.0),Point2(6.0,4.0),Point2(6.0,6.0),Point2(4.0,6.0))
        val slab=box("slab").copy(body=ElementBody.Slab(Polygon2(outer,listOf(hole)),.2),transform=Transform3())
        val item=box("item",4.7,4.7,-.15,Size3(.3,.3,.1))
        assertTrue(ClashDetection.run(doc(slab,item),hardOnly).clashes.isEmpty())
        val solid=item.copy(transform=Transform3(Point3(1.0,1.0,-.15)))
        assertEquals(ClashKind.HARD,ClashDetection.run(doc(slab,solid),hardOnly).clashes.single().kind)
    }
    @Test fun crossingRoutesUnderfloorServicesAndStructureUsePhysicalSolids() {
        val config=defaultRoutingSettings(RouteKind.CONDUIT).copy(floorInstallation=FloorInstallation.IN_SLAB)
        fun route(id:String,path:List<Point3>)=box(id).copy(body=ElementBody.Route(RouteKind.CONDUIT,path,RouteSection.Circular(.1),config.elevation,routing=RouteTopology("network-$id","$id-a","$id-b",config)),transform=Transform3())
        val a=route("a",listOf(Point3(-1.0,0.0,-.1),Point3(1.0,0.0,-.1)))
        val b=route("b",listOf(Point3(0.0,-1.0,-.1),Point3(0.0,1.0,-.1)))
        assertEquals(ClashKind.HARD,ClashDetection.run(doc(a,b),hardOnly).clashes.single().kind)
        assertEquals(ClashKind.DUPLICATE,ClashDetection.run(doc(a,a.copy(id=ElementId("copy"))),hardOnly).clashes.single().kind)
        assertTrue(ClashDetection.run(doc(a,b),hardOnly.copy(planView=false)).clashes.isEmpty())
        assertEquals(1,ClashDetection.run(doc(a,b),hardOnly.copy(planView=false,visibleOnly=false)).clashes.size)
        assertEquals(1,ClashDetection.run(doc(a,b),hardOnly.copy(first=ClashGroup(objects=ClashObjectCategory.ROUTES),second=ClashGroup(objects=ClashObjectCategory.UNDERFLOOR))).clashes.size)
        val fitting=BuiltInLibrary.items.first {it.fitting?.kind==FittingKind.ELBOW_90 && it.fitting?.routeKind==RouteKind.CONDUIT}
        val e=box("fitting").copy(body=ElementBody.Component(fitting.id,fitting.defaultSize))
        val structure=box("structure",-5.0,-5.0,-5.0,Size3(10.0,10.0,10.0))
        val fitted=doc(e,structure).copy(libraryItems=listOf(fitting))
        assertEquals(ClashKind.HARD,ClashDetection.run(fitted,hardOnly.copy(first=ClashGroup(objects=ClashObjectCategory.FITTINGS))).clashes.single().kind)
    }
    @Test fun systemsCategoriesLevelsVisibilityAndLocksFilterIndependently() {
        val power=BuildingSystem(SystemId("power"),p,"Power",Discipline.ELECTRICAL,0xFFFF0000)
        val hvac=power.copy(id=SystemId("hvac"),name="HVAC",discipline=Discipline.HVAC)
        val a=box("a").copy(systemId=power.id).withLock(LockMode.ALL)
        val b=box("b",.5).copy(systemId=hvac.id)
        val source=doc(a,b).copy(systems=listOf(power,hvac))
        val options=hardOnly.copy(first=ClashGroup(category=Discipline.ELECTRICAL),second=ClashGroup(system=hvac.id))
        assertEquals(1,ClashDetection.run(source,options).clashes.size)
        assertTrue(ClashDetection.run(source,options.copy(levelId=LevelId("upper"))).clashes.isEmpty())
        val hidden=source.copy(elements=listOf(a,b.withDisplay(ObjectDisplay.HIDDEN)))
        assertTrue(ClashDetection.run(hidden,options).clashes.isEmpty())
        assertEquals(1,ClashDetection.run(hidden,options.copy(visibleOnly=false)).clashes.size)
        assertTrue(ClashDetection.run(source.copy(layers=listOf(source.layers.single().copy(visible=false))),options).clashes.isEmpty())
        val near=source.copy(elements=listOf(a,b.copy(transform=Transform3(Point3(1.2,0.0)))))
        assertEquals(1,ClashDetection.run(near,options.copy(systemClearances=mapOf(power.id to .3))).clashes.size)
    }
    @Test fun largeSpatiallySeparatedProjectPrunesQuadraticPairsAndCancels() {
        val source=doc(*(0 until 2000).map {box("box-$it",it*3.0)}.toTypedArray())
        lateinit var run:ClashRun
        val time=measureTimeMillis {run=ClashDetection.run(source,hardOnly)}
        assertEquals(2000,run.checkedObjects);assertEquals(0L,run.candidatePairs);assertTrue(run.clashes.isEmpty())
        assertTrue("Spatial regression took $time ms",time<20000)
        var checkpoints=0
        try {ClashDetection.run(source,hardOnly,cancel={if(++checkpoints>10)throw InterruptedException("cancel")});fail("Cancellation ignored")}catch(_:InterruptedException){}
    }
    @Test fun inspectionColoursAndXrayRevealOnlyThePairWithoutChangingTheSource() {
        val source=doc(box("a").withDisplay(ObjectDisplay.HIDDEN),box("b",.5),box("c",10.0))
        val result=ClashDetection.run(source,hardOnly.copy(visibleOnly=false)).clashes.single()
        val styled=clashInspection(source,result,true)
        assertEquals(ObjectDisplay.HIDDEN,source.elements.first().objectDisplay)
        assertEquals(ObjectDisplay.XRAY,styled.elements.first().objectDisplay)
        assertEquals(0xFFFF3030L,styled.elements.first().colourOverride)
        assertEquals(0xFF00B5FFL,styled.elements[1].colourOverride)
        assertEquals(0x30808080L,styled.elements[2].colourOverride)
    }
    @Test fun hostedChasesAndFloorChannelsAreCutBeforeChecking() {
        val wall=box("wall").copy(body=ElementBody.Wall(Point2(0.0,0.0),Point2(4.0,0.0),.2,3.0),transform=Transform3())
        val config=defaultRoutingSettings(RouteKind.CONDUIT)
        val route=box("conduit").copy(body=ElementBody.Route(RouteKind.CONDUIT,listOf(Point3(1.0,.08,1.0),Point3(3.0,.08,1.0)),RouteSection.Circular(.025),config.elevation,
            wallService=WallServiceAttachment(wall.id,WallFaceSide.LEFT,WallServiceOptions(WallServiceMode.CHASE))),transform=Transform3())
        assertTrue(ClashDetection.run(doc(wall,route),hardOnly).clashes.isEmpty())
        val unhosted=route.copy(body=(route.body as ElementBody.Route).copy(wallService=null))
        assertEquals(ClashKind.HARD,ClashDetection.run(doc(wall,unhosted),hardOnly).clashes.single().kind)
        val slab=box("slab").copy(body=ElementBody.Slab(Polygon2(listOf(Point2(0.0,-2.0),Point2(4.0,-2.0),Point2(4.0,2.0),Point2(0.0,2.0))),.2),transform=Transform3())
        val floorConfig=config.copy(floorInstallation=FloorInstallation.IN_SLAB)
        val floor=route.copy(body=(route.body as ElementBody.Route).copy(path=listOf(Point3(1.0,0.0,-.1),Point3(3.0,0.0,-.1)),wallService=null,routing=RouteTopology("n","a","b",floorConfig)))
        assertTrue(ClashDetection.run(doc(slab,floor),hardOnly).clashes.isEmpty())
    }
    @Test fun boardClearanceAndLargeCoordinatesRemainInPhysicalMetres() {
        val item=BuiltInLibrary.items.first {it.symbol==LibrarySymbol.PANEL}
        val board=box("board").copy(body=ElementBody.Component(item.id,Size3(1.0,1.0,1.0)))
        val source=doc(board,box("near",1.2)).copy(libraryItems=listOf(item))
        assertEquals(ClashKind.CLEARANCE,ClashDetection.run(source,ClashCheckOptions()).clashes.single().kind)
        val translated=doc(box("a",1e9,1e9,1e9),box("b",1e9+.75,1e9,1e9))
        assertEquals(.25,ClashDetection.run(translated,hardOnly).clashes.single().overlap,1e-6)
    }
}
