package com.eezydraw.core.editing

import com.eezydraw.core.geometry.ClashDetection
import com.eezydraw.core.geometry.ScheduleEngine
import com.eezydraw.core.model.*
import java.util.UUID
import kotlin.math.*

/** Editable stock-model fixture. No files, UI state, device library writes or global document mutation. */
object TestBuilding {
    const val NAME="EEZY Test Building"
    const val STOREY=4.2
    const val CLASH_TARGET="sample.clashTarget"
    val stressLevels=listOf(5,10,20)
    fun build(base:ProjectDocument,levelCount:Int=3,progress:(Float)->Unit={},cancel:()->Unit={}):ProjectDocument {
        require(levelCount in listOf(3,5,10,20)) {"Choose 3, 5, 10 or 20 levels"}
        val p=base.project.id
        fun id(key:String)=UUID.nameUUIDFromBytes("${p.value}:sample:$key".toByteArray()).toString()
        val levels=List(levelCount){i->Level(LevelId(id("level-$i")),p,if(i==0)"Ground"else "Level $i",i*STOREY,.25,STOREY)}
        val disciplines=listOf(Discipline.ARCHITECTURE,Discipline.STRUCTURE,Discipline.ELECTRICAL,Discipline.ICT,Discipline.FIRE_PROTECTION,Discipline.HVAC,Discipline.PLUMBING)
        val names=listOf("Architecture","Structure","Power","ICT","Fire","HVAC","Plumbing")
        val colours=listOf(0xFF727B85,0xFF89939D,0xFFCF8024,0xFF2878BC,0xFFD34B42,0xFF459E95,0xFF377FBD)
        val layers=disciplines.mapIndexed {i,d->Layer(LayerId(id("layer-$d")),p,names[i],colours[i])}+Layer(LayerId(id("floor-services")),p,"Underfloor services",0xFFBB8B30)+Layer(LayerId(id("reference")),p,"QA reference",visible=false)
        val systems=disciplines.mapIndexed {i,d->BuildingSystem(SystemId(id("system-$d")),p,names[i],d,colours[i])}
        val materials=(BuiltInLibrary.items.map {it.defaultMaterial}+"Brick").distinct().filter {it.isNotBlank()}.map {Material(MaterialId(id("material-$it")),it,0xFFAAAAAA)}
        val settings=base.project.settings.copy(lengthUnit=LengthUnit.MILLIMETRES,viewMode=ViewMode.WIREFRAME,
            defaultWallHeight=STOREY-.25,defaultWallThickness=.18,hiddenLevelIds=emptySet(),canvasLayout=CanvasLayout(),
            floorServices=FloorServiceSettings(),wallServices=WallServiceSettings(),
            takeoff=TakeoffSettings(10.0,"USD",providedSchedules()))
        val shell=ProjectDocument(base.project.copy(name=if(levelCount==3)NAME else "$NAME — $levelCount levels",description="Editable coordination and QA sample with illustrative equipment and unit rates.",projectNumber="EEZY-QA",settings=settings),levels=levels,layers=layers,systems=systems,materials=materials)
        val floors=levels.mapIndexed {index,level->cancel();val result=Floor(shell,index,id("floor-$index"),cancel).build();progress(.8f*(index+1)/levelCount);result}
        var result=shell.copy(elements=floors.flatMap {it.elements},libraryItems=floors.flatMap {it.libraryItems}.distinctBy {it.id},materials=(materials+floors.flatMap {it.materials}).distinctBy {it.id})
        // Join actual coincident endpoint nodes between independently built floors, then refresh fittings once.
        val nodes=hashMapOf<String,String>()
        result=result.copy(elements=result.elements.map {e->val r=e.body as? ElementBody.Route ?: return@map e;val t=r.routing ?: return@map e
            fun node(at:Point3):String {val world=measurementToWorld(e.transform,at);val key="${r.kind}:${t.settings.system}:"+listOf(world.x,world.y,world.z).joinToString(","){round(it*1e6).toLong().toString()};return nodes.getOrPut(key){id(key)}}
            e.copy(body=r.copy(routing=t.copy(networkId=id("network:${r.kind}:${t.settings.system}"),startNode=node(r.path.first()),endNode=node(r.path.last()))))
        })
        cancel();result=ServiceRouting.reconcile(result.copy(elements=emptyList()),result)
        // Seed only the deliberately marked solids through the real clash engine. Full checks remain user-run.
        val options=ClashCheckOptions(visibleOnly=false,clearance=0.0,panelClearance=0.0,planView=false)
        val targets=result.copy(elements=result.elements.filter {CLASH_TARGET in it.properties})
        val run=ClashDetection.run(targets,options,cancel=cancel)
        result=ClashCommands.reconcile(result,run,base.project.createdAtEpochMillis).preview(result)
        result.issues.filter {it.clash?.levelIds?.contains(levels.first().id)==true}.forEach {issue->
            val beam=issue.clash!!.let {it.firstName.contains("beam",true) || it.secondName.contains("beam",true)}
            result=ClashCommands.status(result,issue.id,if(beam)ClashStatus.ACTIVE else ClashStatus.APPROVED,"Deliberate sample intersection; demonstration status for QA.").preview(result)
        }
        result=result.copy(elements=result.elements.map {e->when {
            e.name.startsWith("Column") -> e.withLock(LockMode.ALL)
            e.name.startsWith("Hidden spare chair") -> e.withDisplay(ObjectDisplay.HIDDEN)
            else -> e
        }})
        val boq=ScheduleEngine.build(result,providedSchedules().first {it.category==ScheduleCategory.BOQ}.copy(visibleOnly=false))
        val rates=boq.rows.mapNotNull {row->row.rateKey?.let {it to mapOf("USD" to if(row.unit==TakeoffUnit.EACH)25.0 else 10.0)}}.toMap()
        result=result.copy(project=result.project.copy(settings=result.project.settings.copy(takeoff=result.project.settings.takeoff.copy(rates=rates))))
        result=addViewsAndIssues(result,::id)
        cancel();progress(1f);return result
    }

    private class Floor(val shell:ProjectDocument,val index:Int,val prefix:String,val cancel:()->Unit) {
        val level=shell.levels[index];val z=level.elevation
        var doc=shell.copy(elements=emptyList(),views=emptyList(),issues=emptyList())
        var sequence=0
        fun eid(label:String)=ElementId("$prefix:${sequence++}:$label")
        fun layer(d:Discipline)=shell.layers[shell.systems.indexOfFirst {it.discipline==d}].id
        fun system(d:Discipline)=shell.systems.first {it.discipline==d}
        fun point(x:Double,y:Double,h:Double=0.0)=Point3(x,y,z+h)
        fun polygon(x:Double,y:Double,w:Double,h:Double)=Polygon2(listOf(Point2(x,y),Point2(x+w,y),Point2(x+w,y+h),Point2(x,y+h)))
        fun room(name:String,b:Polygon2) {
            doc=doc.copy(elements=doc.elements+Element(eid(name),doc.project.id,layer(Discipline.ARCHITECTURE),ElementBody.Room(b,"${index+1}-${sequence}",3.6,wallOptions=RoomWallOptions(.18,STOREY-.25)),level.id,name=name,transform=Transform3(Point3(z=z))))
            val at=b.outer.first();doc=doc.copy(elements=doc.elements+Element(eid("label"),doc.project.id,layer(Discipline.ARCHITECTURE),ElementBody.DrawingShape(Shape.Text(name,.22)),level.id,name="$name label",transform=Transform3(point(at.x+.4,at.y+.5,.02))))
        }
        fun component(slug:String,x:Double,y:Double,h:Double=0.0,size:Size3?=null,wall:WallServiceOptions?=null,name:String?=null,discipline:Discipline?=null):ElementId {
            cancel();val stock=requireNotNull(BuiltInLibrary.find(LibraryItemId(slug))){"Sample definition unavailable: $slug"}
            val d=discipline ?: stock.discipline;val sys=system(d)
            val item=libraryPlacementDefinition(doc,stock.copy(defaultSystem=sys.name,manufacturer="EEZY sample",model="DEMO",unitRates=mapOf("USD" to 25.0)))
            val elementId=eid(stock.name)
            var placement=if(stock.mounting==MountingType.WALL && wall!=null)previewLibraryPlacement(doc,item,point(x,y,h),level.id,layer(d),elementId,wall)else {
                val params=item.defaultParameters()+(LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(h,Quantity.LENGTH))
                var e=Element(elementId,doc.project.id,layer(d),ElementBody.Component(item.id,size ?: item.defaultSize,params),level.id,sys.id,name ?: item.name,
                    Transform3(point(x,y,h),scale=Scale3(z=if(stock.mounting==MountingType.CEILING)-1.0 else 1.0)))
                if(floorDevice(item)!=null)e=FloorServices.place(doc,item,e)
                LibraryPlacement(e,item,h,false)
            }
            if(name!=null)placement=placement.copy(element=placement.element.copy(name=name))
            doc=LibraryCommands.place(doc,placement).preview(doc)
            val placed=doc.elements.first {it.id==elementId}
            if(placed.wallService()?.options?.drop!=null && placed.wallService()?.options?.drop!=WallDrop.NONE)doc=WallServices.connectDevice(doc,placed.id)
            return elementId
        }
        fun route(kind:RouteKind,name:String,points:List<Point3>,config:RoutingSettings=defaultRoutingSettings(kind),configs:List<RoutingSettings>?=null,topLevel:LevelId?=null):List<ElementId> {
            cancel();val d=when(config.system){"ICT"->Discipline.ICT;"HVAC"->Discipline.HVAC;"Plumbing"->Discipline.PLUMBING;"Fire"->Discipline.FIRE_PROTECTION;else->Discipline.ELECTRICAL}
            doc=doc.copy(layers=doc.layers.sortedBy {if(it.id==layer(d))0 else 1})
            val command=ServiceRouting.create(doc,kind,points.mapIndexed {i,p->RoutePick(p,(configs?.get(i) ?: config).let {if(it.floorInstallation==null && it.wallOptions==null)it.copy(elevation=ElevationSetting(p.z-z,ElevationReference.ABOVE_FINISHED_FLOOR))else it},if(i==points.lastIndex && topLevel!=null)topLevel else level.id)})
            doc=command.preview(doc);val ids=command.addedElementIds
            doc=doc.copy(layers=shell.layers,elements=doc.elements.map {if(it.id in ids && it.body is ElementBody.Route)it.copy(name=name)else it})
            return doc.elements.filter {it.id in ids && it.body is ElementBody.Route}.map {it.id}
        }
        fun build():ProjectDocument {
            room("Lobby",polygon(0.0,0.0,8.0,6.0));room("Office A",polygon(8.0,0.0,8.0,6.0));room("Office B",polygon(16.0,0.0,8.0,6.0))
            room("Corridor",polygon(0.0,6.0,24.0,2.0));room("Server room",polygon(0.0,8.0,8.0,6.0));room("Plant room",polygon(8.0,8.0,8.0,6.0))
            room("Toilets",polygon(16.0,8.0,4.0,6.0));room("Stair core",polygon(20.0,8.0,4.0,6.0))
            room("L-shaped office",Polygon2(listOf(Point2(0.0,14.0),Point2(24.0,14.0),Point2(24.0,18.0),Point2(8.0,18.0),Point2(8.0,16.0),Point2(0.0,16.0))))
            doc=RoomWalls.reconcile(shell.copy(elements=emptyList()),doc)
            val slab=Element(eid("slab"),doc.project.id,layer(Discipline.STRUCTURE),ElementBody.Slab(polygon(0.0,0.0,24.0,18.0).copy(holes=listOf(polygon(20.8,9.5,2.0,3.5).outer)),.25),level.id,system(Discipline.STRUCTURE).id,"Floor slab",Transform3(Point3(z=z)))
            doc=doc.copy(elements=doc.elements+slab)
            for(x in listOf(0.2,23.4))for(y in listOf(.2,17.4))component("eezy.structure.square-column",x,y,size=Size3(.4,.4,STOREY-.25),name="Column ${index+1} $x $y")
            component("eezy.structure.rectangular-beam",14.0,6.5,2.85,Size3(.4,2.0,.5),name="Intentional beam / duct clash")
            if(index<shell.levels.lastIndex)component("eezy.architecture.stairs",21.0,9.5,size=Size3(1.0,3.0,STOREY),name="Stair flight")
            for(x in listOf(3.0,11.0,19.0)) {
                component("eezy.architecture.single-door",x,6.0,wall=WallServiceOptions(WallServiceMode.OPENING,depth=.18))
                component("eezy.architecture.single-window",x,0.0,wall=WallServiceOptions(WallServiceMode.OPENING,depth=.18))
            }
            for(x in listOf(4.0,12.0,18.0,22.0))component("eezy.architecture.single-door",x,8.0,wall=WallServiceOptions(WallServiceMode.OPENING,depth=.18))
            for(x in listOf(2.0,10.0,18.0)) {
                component("eezy.electrical.socket",x,0.0,wall=WallServiceOptions(WallServiceMode.RECESS,drop=WallDrop.CEILING))
                component("eezy.electrical.single-switch",x+2,6.0,wall=WallServiceOptions(WallServiceMode.RECESS,drop=WallDrop.CEILING))
                component("eezy.ict.data-outlet",x+3,0.0,wall=WallServiceOptions(WallServiceMode.RECESS,drop=WallDrop.FLOOR))
                component("eezy.electrical.linear-light",x,3.0,3.4)
                component("eezy.ict.wifi-access-point",x+1,4.0,3.35)
                component("eezy.fire_protection.smoke-detector",x+2,4.0,3.35)
                component("eezy.fire_protection.sprinkler",x+3,4.0,3.3)
                component("eezy.hvac.fan-coil-unit",x,4.8,3.35)
                component("eezy.hvac.supply-diffuser",x+2,3.0,3.3)
            }
            component("eezy.electrical.distribution-board",1.0,8.0,wall=WallServiceOptions(WallServiceMode.SURFACE))
            component("eezy.fire_protection.manual-call-point",7.0,6.0,wall=WallServiceOptions(WallServiceMode.RECESS))
            component("eezy.ict.rack-42u",2.0,10.0);component("eezy.ict.rack-42u",4.0,10.0)
            component("eezy.ict.patch-panel",2.05,10.1,1.6)
            component("eezy.ict.cctv-camera",7.0,7.0,3.4)
            component("eezy.hvac.air-handling-unit",10.0,11.0)
            component("eezy.hvac.volume-damper",12.0,7.4,3.2)
            component("eezy.plumbing.water-closet",17.0,10.5);component("eezy.plumbing.water-closet",18.5,10.5)
            component("eezy.plumbing.basin",17.0,8.0,wall=WallServiceOptions(WallServiceMode.SURFACE))
            component("eezy.plumbing.floor-drain",18.0,12.0);component("eezy.plumbing.gate-valve",14.4,11.0,.5)
            component("eezy.architecture.chair",10.0,16.0,name="Hidden spare chair ${index+1}")
            component("floor-service-box",11.0,3.0)
            component("floor-service-sleeve",14.425,11.925,discipline=Discipline.STRUCTURE)
            val tray=defaultRoutingSettings(RouteKind.CABLE_TRAY).copy(elevation=ElevationSetting(2.7,ElevationReference.ABOVE_FINISHED_FLOOR))
            route(RouteKind.CABLE_TRAY,"Main tray",listOf(point(1.0,7.0,2.7),point(12.0,7.0,2.7),point(23.0,7.0,2.7)),tray,listOf(tray,tray.copy(width=.2),tray.copy(width=.2)))
            route(RouteKind.CABLE_TRAY,"Tray tee to server",listOf(point(4.0,7.0,2.7),point(4.0,9.0,2.7)),tray)
            val trunks=route(RouteKind.TRUNKING,"Office trunking",listOf(point(1.0,3.0,2.9),point(9.0,3.0,2.9),point(9.0,4.5,2.9),point(21.0,4.5,2.9)))
            trunks.firstOrNull()?.let {doc=ServiceRouting.supports(doc,it,1.5).preview(doc)}
            route(RouteKind.CABLE_BASKET,"ICT basket",listOf(point(2.0,10.0,2.0),point(2.0,9.0,2.0),point(2.0,9.0,2.8),point(6.0,9.0,2.8),point(6.0,7.8,2.8)),defaultRoutingSettings(RouteKind.CABLE_BASKET))
            route(RouteKind.CABLE_LADDER,"Plant ladder",listOf(point(10.0,13.0,2.7),point(14.0,13.0,2.7)),defaultRoutingSettings(RouteKind.CABLE_LADDER))
            route(RouteKind.DUCT,"Supply duct",listOf(point(10.0,11.0,1.5),point(10.0,11.0,3.1),point(10.0,7.5,3.1),point(23.0,7.5,3.1)))
            route(RouteKind.DUCT,"Duct branch",listOf(point(19.0,7.5,3.1),point(19.0,3.5,3.1)))
            route(RouteKind.PIPE,"Intentional pipe / tray clash",listOf(point(7.0,5.0,2.7),point(7.0,10.0,2.7)))
            route(RouteKind.PIPE,"Water supply",listOf(point(14.5,12.0,.5),point(18.0,12.0,.5),point(18.0,10.0,.5)))
            route(RouteKind.PIPE,"Sprinkler pipe",listOf(point(2.0,4.0,3.2),point(22.0,4.0,3.2)),defaultRoutingSettings(RouteKind.PIPE).copy(system="Fire",diameter=.04))
            for((kind,install) in listOf(RouteKind.CONDUIT to FloorInstallation.IN_SCREED,RouteKind.TRUNKING to FloorInstallation.IN_SLAB,RouteKind.CABLE_TRAY to FloorInstallation.RAISED_FLOOR,RouteKind.PIPE to FloorInstallation.IN_SLAB)) {
                val config=defaultRoutingSettings(kind).copy(floorInstallation=install,floorDepth=if(install==FloorInstallation.IN_SCREED).02 else if(install==FloorInstallation.IN_SLAB).1 else .35)
                val y=1.0+kind.ordinal*.25
                val routes=route(kind,"${floorInstallationLabel(install)} ${kind.name}",listOf(point(2.0,y,-config.floorDepth),point(11.1,y,-config.floorDepth),point(11.1,3.1,-config.floorDepth)),config)
                if(kind==RouteKind.CONDUIT)routes.lastOrNull()?.let {doc=FloorServices.rise(doc,it,doc.elements.first {e->(e.body as? ElementBody.Component)?.libraryItemId==LibraryItemId("floor-service-box")}.id).preview(doc)}
            }
            if(index<shell.levels.lastIndex)route(RouteKind.PIPE,"Inter-level water riser",listOf(point(14.5,12.0,.5),point(14.5,12.0,STOREY+.5)),topLevel=shell.levels[index+1].id)
            val face=requireNotNull(WallServices.nearest(doc,point(4.0,0.0,1.0),level.id))
            val depth=if(face.side==WallFaceSide.LEFT).02 else face.frame.thickness-.02
            val cfg=defaultRoutingSettings(RouteKind.CONDUIT).copy(wallOptions=WallServiceOptions(WallServiceMode.CHASE,depth=.11),wallSnapAngles=WallSnapAngles.FORTY_FIVE)
            route(RouteKind.CONDUIT,"Deep diagonal wall chase",listOf(face.frame.world(6.0,depth,2.7),face.frame.world(6.5,depth,2.7),face.frame.world(7.5,depth,1.7),face.frame.world(7.5,depth,.4)),cfg)
            val measurement=createMeasurement(doc,point(0.0,0.0),point(24.0,0.0),point(12.0,-1.2),name="Building width",floorLevelId=level.id)
            doc=doc.copy(elements=doc.elements+Element(eid("dimension"),doc.project.id,layer(Discipline.ARCHITECTURE),ElementBody.DrawingShape(measurement.shape),level.id,name="Building width",transform=measurement.transform))
            fun through(e:Element,x:Double,y:Double,h:Double):Boolean {val r=e.body as? ElementBody.Route ?: return false;val pts=r.path.map {measurementToWorld(e.transform,it)};return pts.zipWithNext().any {(a,b)->val v=Point3(b.x-a.x,b.y-a.y,b.z-a.z);val t=((x-a.x)*v.x+(y-a.y)*v.y+(z+h-a.z)*v.z)/(v.x*v.x+v.y*v.y+v.z*v.z);t in .01.. .99 && hypot(hypot(a.x+t*v.x-x,a.y+t*v.y-y),a.z+t*v.z-z-h)<1e-5}}
            val duct=doc.elements.first {e->(e.body as? ElementBody.Route)?.kind==RouteKind.DUCT && through(e,14.2,7.5,3.1)}.id
            val trayId=doc.elements.first {e->(e.body as? ElementBody.Route)?.kind==RouteKind.CABLE_TRAY && through(e,7.0,7.0,2.7)}.id
            doc=doc.copy(elements=doc.elements.map {e->if(e.id==duct || e.id==trayId || e.name.startsWith("Intentional"))e.copy(properties=e.properties+(CLASH_TARGET to ParameterValue.Flag(true)))else e})
            return doc
        }
    }

    private fun addViewsAndIssues(document:ProjectDocument,id:(String)->String):ProjectDocument {
        val p=document.project.id;val first=document.levels.first()
        val capture=IssueView(id("capture"),Point2(12.0,9.0),25.0,Point3(12.0,9.0,first.elevation),-.8,.55,40.0,26.0,Projection.ORTHOGRAPHIC,false,first.id,document.project.settings,
            document.layers.associate {it.id to it.visible},document.systems.associate {it.id to it.visible},document.elements.associate {it.id to it.properties.filterKeys {key->key==ELEMENT_DISPLAY_KEY || key==ELEMENT_COLOUR_KEY || key.startsWith(ROOM_SIDE_DISPLAY_PREFIX)}},1000,750,true)
        val views=listOf(SavedView(ViewId(id("plan")),p,"Ground plan",Camera(Point3(12.0,9.0,100.0),Point3(12.0,9.0),up=Point3(y=1.0)),levelId=first.id,capture=capture),
            SavedView(ViewId(id("building")),p,"Building isometric",Camera(Point3(40.0,-30.0,document.levels.last().elevation+28.0),Point3(12.0,9.0,document.levels.last().elevation/2),orthographicHeight=max(32.0,document.levels.size*STOREY*1.4))),
            SavedView(ViewId(id("xray")),p,"Ground underfloor X-ray",Camera(Point3(30.0,-20.0,15.0),Point3(12.0,9.0),orthographicHeight=32.0),ViewMode.XRAY,first.id),
            SavedView(ViewId(id("section")),p,"Plant section box",Camera(Point3(30.0,-10.0,15.0),Point3(12.0,10.0,2.0),orthographicHeight=12.0),ViewMode.SHADED_WITH_EDGES,first.id,sectionBox=Bounds3(Point3(8.0,7.0,-.3),Point3(16.0,14.0,4.0))))
        var doc=document.copy(views=views)
        val clash=document.issues.firstOrNull {it.clash?.let {c->first.id in c.levelIds && (c.firstName.contains("beam",true) || c.secondName.contains("beam",true))}==true}
        val marks=listOf(IssueMarkup(MarkupTool.CLOUD,listOf(Point2(.35,.35),Point2(.65,.55)),viewId=capture.id,levelId=first.id),IssueMarkup(MarkupTool.ARROW,listOf(Point2(.7,.3),Point2(.55,.45)),viewId=capture.id,levelId=first.id),IssueMarkup(MarkupTool.TEXT,listOf(Point2(.3,.3)),text="Review coordination",viewId=capture.id,levelId=first.id))
        val issue=Issue(IssueId(id("issue")),p,"Coordinate beam and duct before installation",document.project.createdAtEpochMillis,description="Open Clashes to inspect deliberately intersecting services. This sample is a QA fixture.",elementIds=clash?.elementIds.orEmpty(),viewId=views.first().id,position=clash?.position ?: Point3(14.2,7.5,3.1),priority=IssuePriority.HIGH,assignedTo="MEP coordinator",levelId=first.id,sourceClashId=clash?.id,capture=capture,annotations=marks)
        doc=IssueCommands.put(doc,issue).preview(doc)
        val chase=doc.elements.first {it.name=="Deep diagonal wall chase"}
        val note=Issue(IssueId(id("chase-issue")),p,"Review chase depth warning",document.project.createdAtEpochMillis,description="The 110 mm demonstration chase exceeds 50% of the 180 mm wall. Select it in the wall schedule.",elementIds=setOf(chase.id),position=measurementToWorld(chase.transform,(chase.body as ElementBody.Route).path.first()),levelId=first.id,priority=IssuePriority.MEDIUM)
        doc=IssueCommands.put(doc,note).preview(doc)
        val reference=Element(ElementId(id("reference-box")),p,doc.layers.first {it.name=="QA reference"}.id,ElementBody.DrawingShape(Shape.Box(Size3(1.0,1.0,1.0))),first.id,name="Hidden layer reference",transform=Transform3(Point3(25.0,9.0)))
        return doc.copy(elements=doc.elements+reference)
    }
}
