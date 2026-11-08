package com.eezydraw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.eezydraw.core.editing.*
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import kotlin.math.roundToInt

internal fun routingReferenceLabel(reference:ElevationReference)=when(reference) {
    ElevationReference.ABOVE_FINISHED_FLOOR->"Above finished floor"
    ElevationReference.BELOW_CEILING->"Below ceiling"
    ElevationReference.BELOW_SLAB->"Below slab"
    ElevationReference.ABOVE_CEILING->"Above ceiling"
    ElevationReference.PROJECT_DATUM->"Project datum"
}
@Composable
internal fun <T> RouteChoice(label:String,value:T,values:List<T>,onChange:(T)->Unit,text:(T)->String={it.toString()}) {
    var open by remember {mutableStateOf(false)}
    Box {TextButton({open=true},Modifier.testTag("route_choice_$label")) {Text("$label: ${text(value)}")}
        DropdownMenu(open,{open=false}) {values.forEach {v->DropdownMenuItem(text={Text(text(v))},onClick={onChange(v);open=false})}}}
}
@Composable
internal fun RouteNumber(label:String,value:Double,onChange:(Double)->Unit,positive:Boolean=true) {
    var input by remember(value) {mutableStateOf("%.3f".format(java.util.Locale.ROOT,value*1000).trimEnd('0').trimEnd('.'))}
    val number=input.toDoubleOrNull()?.div(1000)
    OutlinedTextField(input,{input=it;it.toDoubleOrNull()?.div(1000)?.takeIf {n->n.isFinite() && (!positive || n>0 && n<=100) && kotlin.math.abs(n)<=10000}?.let(onChange)},
        label={Text("$label (mm)")},singleLine=true,isError=number==null || positive && number<=0,
        modifier=Modifier.width(132.dp).testTag("route_input_$label"))
}
@Composable
internal fun RoutingSettingsBar(kind:RouteKind,settings:RoutingSettings,onChange:(RoutingSettings)->Unit,showElevation:Boolean=true,floorDefaults:FloorServiceSettings=FloorServiceSettings()) {
    Surface(tonalElevation=2.dp) {Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp).testTag("routing_settings"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(kind.name.replace('_',' '),style=MaterialTheme.typography.labelLarge)
        if(kind in listOf(RouteKind.CONDUIT,RouteKind.TRUNKING,RouteKind.CABLE_TRAY,RouteKind.PIPE)) {
            val installation=if(settings.wallOptions!=null)"On wall"else settings.floorInstallation?.let(::floorInstallationLabel) ?: "Above ceiling / free height"
            RouteChoice("Installation",installation,listOf("Above ceiling / free height")+if(kind in listOf(RouteKind.CONDUIT,RouteKind.TRUNKING))listOf("On wall")+FloorInstallation.entries.map(::floorInstallationLabel)else FloorInstallation.entries.map(::floorInstallationLabel),{label->
                val floor=FloorInstallation.entries.find {floorInstallationLabel(it)==label}
                val depth=floor?.let {floorDefaults.defaultDepths[it]} ?: settings.floorDepth
                onChange(settings.copy(floorInstallation=floor,floorDepth=depth,wallOptions=if(label=="On wall")WallServiceOptions()else null,
                    elevation=if(floor!=null)ElevationSetting(-depth,ElevationReference.ABOVE_FINISHED_FLOOR)else if(settings.floorInstallation!=null)ElevationSetting(2.5,ElevationReference.ABOVE_FINISHED_FLOOR)else settings.elevation))
            })
            if(settings.floorInstallation!=null)RouteNumber("Depth below finished floor",settings.floorDepth,{onChange(settings.copy(floorDepth=it,elevation=ElevationSetting(-it,ElevationReference.ABOVE_FINISHED_FLOOR)))})
        }
        if(settings.floorInstallation==null && (kind==RouteKind.CONDUIT || kind==RouteKind.TRUNKING)) {
            RouteChoice("Wall route",settings.wallOptions?.mode,listOf(null,WallServiceMode.SURFACE,WallServiceMode.CHASE),{mode->onChange(settings.copy(floorInstallation=null,wallOptions=mode?.let {(settings.wallOptions ?: WallServiceOptions()).copy(mode=it)}))}){it?.let(::wallModeLabel) ?: "Free route"}
            settings.wallOptions?.let {options->RouteNumber("Clearance",options.clearance,{onChange(settings.copy(wallOptions=options.copy(clearance=it.coerceIn(0.0,1.0))))},false)}
        }
        val circular=kind==RouteKind.PIPE || kind==RouteKind.CONDUIT || settings.round
        if(kind==RouteKind.DUCT)RouteChoice("Profile",settings.round,listOf(false,true),{onChange(settings.copy(round=it))}){if(it)"Round"else "Rectangular"}
        RouteChoice("Standard size",if(circular)settings.diameter else settings.width,listOf(.02,.025,.032,.04,.05,.075,.1,.15,.2,.3,.45,.5,.6,.9),{onChange(if(circular)settings.copy(diameter=it)else settings.copy(width=it))}){"${(it*1000).roundToInt()} mm"}
        if(circular)RouteNumber("Diameter",settings.diameter,{onChange(settings.copy(diameter=it))}) else {
            RouteNumber("Width",settings.width,{onChange(settings.copy(width=it))});RouteNumber("Height",settings.height,{onChange(settings.copy(height=it))})
        }
        if(showElevation && settings.floorInstallation==null) {
            RouteNumber("Elevation",settings.elevation.value,{onChange(settings.copy(elevation=settings.elevation.copy(value=it)))},false)
            RouteChoice("Reference",settings.elevation.reference,listOf(ElevationReference.ABOVE_FINISHED_FLOOR,ElevationReference.BELOW_CEILING,ElevationReference.BELOW_SLAB),{onChange(settings.copy(elevation=settings.elevation.copy(reference=it)))},::routingReferenceLabel)
        }
        RouteChoice("System",settings.system,listOf("Power","ICT","HVAC","Plumbing","Fire"),{onChange(settings.copy(system=it,colourArgb=when(it){"ICT"->0xFF9C67C4;"HVAC"->0xFF459E95;"Plumbing"->0xFF377FBD;"Fire"->0xFFCF4545;else->0xFFCA8735}))})
        RouteChoice("Colour",settings.colourArgb,listOf(0xFFCA8735,0xFF9C67C4,0xFF459E95,0xFF377FBD,0xFFCF4545,0xFF777777),{onChange(settings.copy(colourArgb=it))}){"#${it.toString(16).takeLast(6)}"}
        if(settings.wallOptions!=null)RouteChoice("Snap angles",settings.effectiveWallSnapAngles(),WallSnapAngles.entries,{onChange(settings.copy(wallSnapAngles=it))}) {
            when(it){WallSnapAngles.HORIZONTAL->"0°";WallSnapAngles.FORTY_FIVE->"45°";WallSnapAngles.NINETY->"90°";WallSnapAngles.FREE->"Free"}
        } else FilterChip(settings.ortho,{onChange(settings.copy(ortho=!settings.ortho))},{Text("Ortho")},Modifier.testTag("route_ortho"))
        FilterChip(settings.autoFittings,{onChange(settings.copy(autoFittings=!settings.autoFittings))},{Text("Auto fittings")},Modifier.testTag("route_auto_fittings"))
        if(settings.wallOptions==null)RouteChoice("Bend",settings.bendDegrees,listOf(45,90),{onChange(settings.copy(bendDegrees=it))}){"$it°"}
    }}
}

@Composable
internal fun RoutingRouteList(document:ProjectDocument,onSelect:(Set<ElementId>)->Unit) {
    val routes=document.elements.filter {it.body is ElementBody.Route}
    if(routes.isEmpty())return
    var expanded by remember(document.project.id) {mutableStateOf(false)}
    Column {TextButton({expanded=!expanded},Modifier.testTag("route_list_toggle")){Text("Routes (${routes.size}) ${if(expanded)"▴"else "▾"}")}
        if(expanded)Column(Modifier.heightIn(max=160.dp).verticalScroll(rememberScrollState()).testTag("route_list")) {
            routes.forEach {e->val b=e.body as ElementBody.Route
                TextButton({onSelect(setOf(e.id))},Modifier.testTag("route_list_${e.id.value}")) {
                    Text("${e.name} · %.2f m · %s · %.3f m · %s".format(java.util.Locale.ROOT,routeLength(e),when(val s=b.section){is RouteSection.Circular->"Ø${(s.diameter*1000).roundToInt()}";is RouteSection.Rectangular->"${(s.width*1000).roundToInt()}×${(s.height*1000).roundToInt()}"},measurementToWorld(e.transform,b.path.first()).z,document.systems.find {it.id==e.systemId}?.name.orEmpty()))
                }
            }
        }
    }
}

@Composable
internal fun RoutingSelectionActions(document:ProjectDocument,element:Element,actions:CanvasEditorActions) {
    val route=element.body as? ElementBody.Route
    val topology=route?.routing
    val managed=ROUTING_OWNER in element.properties
    if(topology==null && !managed)return
    var rise by remember(element.id) {mutableStateOf<String?>(null)}
    var edit by remember(element.id) {mutableStateOf(false)}
    var more by remember(element.id) {mutableStateOf(false)}
    var spacing by remember(element.id) {mutableDoubleStateOf(1.0)}
    var accessories by remember(element.id) {mutableStateOf(false)}
    var accessory by remember(element.id) {mutableStateOf(FittingKind.SUPPORT)}
    Row {
        if(topology!=null)TextButton({edit=true},Modifier.testTag("route_properties")){Text("Route properties")}
        Box {TextButton({more=true},Modifier.testTag("route_more")){Text("More")}
            DropdownMenu(more,{more=false}) {
                if(managed) {
                    val component=element.body as? ElementBody.Component
                    val family=document.libraryItems.find {it.id==component?.libraryItemId}?.fitting
                    BuiltInLibrary.items.mapNotNull {it.fitting}.filter {it.routeKind==family?.routeKind && (it.section is RouteSection.Circular)==(family.section is RouteSection.Circular)}.map {it.kind}.distinct().forEach {kind->DropdownMenuItem(text={Text("Replace: ${kind.name.replace('_',' ')}")},onClick={actions.onRouteFitting(element.id,kind);more=false})}
                    DropdownMenuItem(text={Text("Delete fitting")},onClick={actions.onRouteFitting(element.id,null);more=false})
                }
                if(topology!=null || managed)DropdownMenuItem(text={Text("Add supports / accessories")},onClick={accessories=true;more=false})
            }
        }
    }
    if(element.floorRouting()!=null)Row {
        TextButton({rise="wall"},Modifier.testTag("rise_to_wall")){Text("Rise to wall")}
        TextButton({rise="box"},Modifier.testTag("rise_to_floor_box")){Text("Rise to floor box")}
    }
    if(rise!=null)AlertDialog(onDismissRequest={rise=null},title={Text(if(rise=="wall")"Rise to wall"else "Rise to floor box")},text={Column(Modifier.heightIn(max=280.dp).verticalScroll(rememberScrollState())) {
        val targets=document.elements.filter {e->(e.body as? ElementBody.Component)?.let {b->document.libraryItems.find {it.id==b.libraryItemId}?.let {if(rise=="wall")it.mounting==MountingType.WALL else floorDevice(it) in listOf(FloorDeviceKind.FLOOR_BOX,FloorDeviceKind.FLOOR_SOCKET)}}==true}
        if(targets.isEmpty())Text("Place a ${if(rise=="wall")"wall device"else "floor box or floor socket"} from the library first.")
        targets.forEach {target->TextButton({actions.onFloorRise(element.id,target.id);rise=null}){Text(target.name)}}
    }},confirmButton={TextButton({rise=null}){Text("Cancel")}})
    if(edit && topology!=null) {
        var value by remember(element) {mutableStateOf(topology.settings)}
        AlertDialog(onDismissRequest={edit=false},title={Text("Route section")},text={Column {RoutingSettingsBar(route.kind,value,{value=it},floorDefaults=document.project.settings.floorServices);Text("Drag either end handle to reshape the section. Connected sections and fittings follow.")}},
            confirmButton={TextButton({actions.onEditRoute(element.id,value,null,null);edit=false},Modifier.testTag("route_apply")){Text("Apply")}},dismissButton={TextButton({edit=false}){Text("Cancel")}})
    }
    if(accessories)AlertDialog(onDismissRequest={accessories=false},title={Text("Add support every X mm")},text={Column {
        RouteNumber("Spacing",spacing,{spacing=it})
        RouteChoice("Accessory",accessory,listOf(FittingKind.SUPPORT,FittingKind.HANGER,FittingKind.BRACKET,FittingKind.COUPLER,FittingKind.EXPANSION_JOINT),{accessory=it})
    }},confirmButton={TextButton({val owner=(element.properties[ROUTING_OWNER] as? ParameterValue.Text)?.value?.let(::ElementId) ?: element.id
        actions.onRouteSupports(owner,spacing,accessory);accessories=false},Modifier.testTag("route_add_supports")){Text("Add")}},dismissButton={TextButton({accessories=false}){Text("Cancel")}})
}

/** Preview uses the same transaction builder as commit, including snapped tees and size adaptation. */
@Composable
internal fun RouteGhost(document:ProjectDocument,kind:RouteKind,picks:List<RoutePick>,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,preview:ProjectDocument?=null) {
    val scene=remember(document,kind,picks,is3D,preview) {runCatching {
        val after=preview ?: ServiceRouting.create(document,kind,picks).preview(document)
        val old=document.elements.associateBy {it.id}
        buildDrawingScene(after.copy(elements=after.elements.filter {old[it.id]!=it}),planSymbols=!is3D)
    }.getOrNull()}
    val colour=MaterialTheme.colorScheme.primary.copy(alpha=.45f)
    Canvas(Modifier.fillMaxSize().testTag("route_ghost")) {
        scene?.segments?.take(10000)?.forEach {e->
            val a=projectPrecisionPoint(e.a,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble())
            val b=projectPrecisionPoint(e.b,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble())
            if(a!=null && b!=null)drawLine(colour,Offset(a.x.toFloat(),a.y.toFloat()),Offset(b.x.toFloat(),b.y.toFloat()),2.dp.toPx())
        }
    }
}

@Composable
internal fun BoxScope.RouteEndHandles(element:Element,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,width:Int,height:Int,onEnd:(Point3?,Point3?)->Unit) {
    val b=element.body as? ElementBody.Route ?: return
    if(b.routing==null || element.lockMode!=LockMode.NONE)return
    val density=LocalDensity.current.density
    listOf(b.path.first(),b.path.last()).forEachIndexed {index,local->
        val world=measurementToWorld(element.transform,local)
        val screen=projectPrecisionPoint(world,viewport,orbit,is3D,width.toDouble(),height.toDouble()) ?: return@forEachIndexed
        var drag by remember(element,index) {mutableStateOf(Offset.Zero)}
        Surface(Modifier.offset {IntOffset((screen.x-16*density+drag.x).roundToInt(),(screen.y-16*density+drag.y).roundToInt())}.size(32.dp).testTag("route_end_$index")
            .pointerInput(element,index,viewport,orbit) {detectDragGestures(onDragStart={drag=Offset.Zero},onDragCancel={drag=Offset.Zero},onDragEnd={
                val x=screen.x+drag.x;val y=screen.y+drag.y
                val p=if(is3D)orbit.rayToPlaneZ(x,y,width.toDouble(),height.toDouble(),world.z)else viewport.screenToWorld(x,y,width.toDouble(),height.toDouble()).let {Point3(it.x,it.y,world.z)}
                if(p!=null)onEnd(if(index==0)p else null,if(index==1)p else null);drag=Offset.Zero
            }) {change,amount->change.consume();drag+=amount}},color=MaterialTheme.colorScheme.primary,shape=MaterialTheme.shapes.small) {
            Box(contentAlignment=Alignment.Center){Text(if(index==0)"A"else "B",color=Color.White)}
        }
    }
}
