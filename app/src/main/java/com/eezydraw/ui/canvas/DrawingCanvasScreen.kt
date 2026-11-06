package com.eezydraw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.eezydraw.R
import com.eezydraw.core.geometry.*
import com.eezydraw.core.editing.*
import com.eezydraw.core.model.*
import com.eezydraw.ui.canvas.editing.*
import com.eezydraw.ui.canvas.panels.*
import com.eezydraw.storage.ViewExportFormat
import com.eezydraw.storage.ViewExportRequest
import com.eezydraw.ui.projects.lengthUnitLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.hypot

private sealed interface SceneLoad {
    data object Loading : SceneLoad
    data class Ready(
        val document: ProjectDocument,
        val selectedIds: Set<ElementId>,
        val scene: DrawingScene,
        val mainScene: DrawingScene,
        val selectionScene: DrawingScene?,
        val snaps: SnapEngine,
        val picker: EditorPicker,
        val resizeFrame: ResizeFrame?,
        val moves: MoveContext,
        val surfaces: SurfaceScene?,
        val mainSurfaces: SurfaceScene?,
        val selectedSurfaces: SurfaceScene?,
        val annotationScene: DrawingScene,
        val selectedAnnotationScene: DrawingScene?,
        val measurementScene: DrawingScene,
        val selectedMeasurementScene: DrawingScene?,
        val detailedMeasurements:Boolean,
        val pointPicks:PointPickContext,
        val componentReferences:Map<ElementId,Point3>,
        val planSymbols:Boolean,
    ) : SceneLoad
    data object Failed : SceneLoad
}

private fun sameCanvasGeometry(a: ProjectDocument, b: ProjectDocument): Boolean =
    a.elements == b.elements && a.layers == b.layers && a.systems == b.systems && a.libraryItems == b.libraryItems &&
        a.materials == b.materials && a.levels == b.levels && a.project.settings.showMeasurements == b.project.settings.showMeasurements &&
        a.project.settings.hiddenLevelIds==b.project.settings.hiddenLevelIds && a.project.settings.showHiddenObjects==b.project.settings.showHiddenObjects &&
        a.project.settings.lengthUnit == b.project.settings.lengthUnit &&
        a.project.settings.angleFormat == b.project.settings.angleFormat &&
        a.project.settings.decimalPlaces == b.project.settings.decimalPlaces

private enum class MeasurementMode { NONE, DISTANCE, REFERENCE }
private data class MeasurementPick(val point: Point3,val elementId: ElementId? = null)
private data class PointInputSnapshot(val points:List<Point3>,val measurements:List<MeasurementPick>,val anchor:Point2?,val candidate:PrecisionPick?)
private data class PrecisionSnapKey(val mode:SnapMode?,val element:ElementId?,val wallEdge:Boolean,val discretePoint:Point3?)
private fun SurfaceScene.partition(ids: Set<ElementId>,selected: Boolean): SurfaceScene {
    val matching=chunks.filter { (ElementId(it.elementId) in ids)==selected }
    val bounds=matching.map {it.bounds}.reduceOrNull {a,b->Bounds3(
        Point3(minOf(a.min.x,b.min.x),minOf(a.min.y,b.min.y),minOf(a.min.z,b.min.z)),
        Point3(maxOf(a.max.x,b.max.x),maxOf(a.max.y,b.max.y),maxOf(a.max.z,b.max.z))) } ?: Bounds3(Point3(),Point3())
    return SurfaceScene(matching,bounds)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawingCanvasScreen(
    document: ProjectDocument,
    settings: AppSettings,
    dirty: Boolean,
    busy: Boolean,
    onSnapSettingsChange: (SnapSettings) -> Unit,
    onUnitsChange: (LengthUnit) -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onDetails: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    surface: (@Composable (DrawingScene?, CanvasFrame, Modifier) -> Unit)? = null,
    editor: CanvasEditorActions? = null,
    onExportView: (ViewExportRequest,ViewExportFormat)->Unit = { _,_-> },
) {
    var viewport by rememberSaveable(document.project.id.value, stateSaver = PlanCameraSaver) { mutableStateOf(Viewport2D()) }
    var orbit by rememberSaveable(document.project.id.value, stateSaver = OrbitCameraSaver) { mutableStateOf(OrbitCamera()) }
    var is3D by rememberSaveable(document.project.id.value) { mutableStateOf(false) }
    var initialized by rememberSaveable(document.project.id.value) { mutableStateOf(false) }
    var orbitShown by rememberSaveable(document.project.id.value) { mutableStateOf(false) }
    var gizmoMode by rememberSaveable(document.project.id.value) { mutableStateOf(GizmoMode.MOVE) }
    var cameraOrbiting by remember {mutableStateOf(false)}
    var rotationAngle by remember {mutableStateOf<Double?>(null)}
    var level by rememberSaveable(document.project.id.value) { mutableStateOf(document.levels.firstOrNull()?.id?.value) }
    var orbitLevel by rememberSaveable(document.project.id.value) { mutableStateOf<String?>(null) }
    var anchorX by rememberSaveable(document.project.id.value) { mutableStateOf<Double?>(null) }
    var anchorY by rememberSaveable(document.project.id.value) { mutableStateOf<Double?>(null) }
    var zoomWindow by rememberSaveable { mutableStateOf(false) }
    var probe by rememberSaveable { mutableStateOf(false) }
    var orbitPan by rememberSaveable { mutableStateOf(false) }
    var snapsOpen by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var rendererFailed by remember(document.project.id.value) { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var loading: SceneLoad by remember(document.project.id.value) { mutableStateOf(SceneLoad.Loading) }
    var prepared: SceneLoad.Ready? by remember(document.project.id.value) { mutableStateOf(null) }
    var tool by rememberSaveable(document.project.id.value) { mutableStateOf(DrawingTool.SELECT) }
    var options by rememberSaveable(document.project.id.value, stateSaver = listSaver(
        save = { listOf(it.text, it.textHeight, it.height, it.closed,it.width,it.depth,it.useDefaultDimensions,it.keepProportions) },
        restore = { ToolOptions(it[0] as String, it[1] as Double, it[2] as Double, it[3] as Boolean,
            it.getOrNull(4) as? Double ?: 1.0,it.getOrNull(5) as? Double ?: 1.0,
            it.getOrNull(6) as? Boolean ?: true,it.getOrNull(7) as? Boolean ?: false) },
    )) { mutableStateOf(ToolOptions()) }
    var points by remember(document.project.id.value) { mutableStateOf(emptyList<Point3>()) }
    var pointerWorld by remember {mutableStateOf<Point3?>(null)}
    var precisionPick by remember(document.project.id) {mutableStateOf<PrecisionPick?>(null)}
    var precisionFinger by remember {mutableStateOf<Point2?>(null)}
    var precisionDragging by remember {mutableStateOf(false)}
    var precisionSnap by remember {mutableStateOf<PrecisionSnapKey?>(null)}
    var pointUndo by remember(document.project.id) {mutableStateOf(emptyList<PointInputSnapshot>())}
    var pointRedo by remember(document.project.id) {mutableStateOf(emptyList<PointInputSnapshot>())}
    var pendingLibrary by rememberSaveable(document.project.id.value) {mutableStateOf<String?>(null)}
    var placedToken by remember {mutableIntStateOf(0)}
    var placedFeedback by remember {mutableStateOf(false)}
    var addSelection by rememberSaveable { mutableStateOf(false) }
    var propertiesSession by rememberSaveable { mutableIntStateOf(0) }
    var canvasLayout by remember(document.project.id) {mutableStateOf(document.project.settings.canvasLayout)}
    var panelBounds by remember(document.project.id) {mutableStateOf(emptyMap<CanvasPanelId,Rect>())}
    fun recordPanelBounds(id:CanvasPanelId,bounds:Rect?) {
        val next=if(bounds==null)panelBounds-id else panelBounds+(id to bounds)
        if(next!=panelBounds)panelBounds=next
    }
    var selectedRoomSide by remember(document.project.id) {mutableStateOf<RoomSideRef?>(null)}
    LaunchedEffect(document.project.settings.canvasLayout) {canvasLayout=document.project.settings.canvasLayout}
    val panelsHidden=canvasLayout.hideAll
    val propertiesOpen=canvasLayout.panel(CanvasPanelId.PROPERTIES).state==PanelVisibility.OPEN
    fun updateLayout(next:CanvasLayout) {canvasLayout=next;editor?.onCanvasLayout?.invoke(next)}
    fun setPanel(id:CanvasPanelId,state:PanelVisibility) {updateLayout(canvasLayout.withPanel(id,canvasLayout.panel(id).copy(state=state)))}
    fun setPanelPlacement(id:CanvasPanelId,placement:PanelPlacement) {updateLayout(canvasLayout.withPanel(id,placement))}
    fun openProperties() {propertiesSession++;setPanel(CanvasPanelId.PROPERTIES,PanelVisibility.OPEN)}
    var arrayOpen by rememberSaveable { mutableStateOf(false) }
    val panelStates=rememberSaveableStateHolder()
    var measurementMode by rememberSaveable { mutableStateOf(MeasurementMode.NONE) }
    var measurementPicks by remember { mutableStateOf(emptyList<MeasurementPick>()) }
    var toolError by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<EditDelta?>(null) }
    var waitingForCommit by remember { mutableStateOf<ProjectDocument?>(null) }
    var dragPivot by remember { mutableStateOf(Point3()) }
    var dragResizeFrame by remember { mutableStateOf<ResizeFrame?>(null) }
    val focus = remember { FocusRequester() }
    var canvasFocused by remember { mutableStateOf(false) }
    val cursor = remember { mutableStateOf<SnapResult?>(null) }
    val fallbackSnaps = remember { SnapEngine(SpatialIndex(emptyList())) }
    val window = remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val ready = prepared
    val renderDocument=remember(document,settings.showDetailedMeasurement) {document.copy(project=document.project.copy(
        settings=document.project.settings.copy(showDetailedMeasurements=settings.showDetailedMeasurement)))}
    val selectedIds = editor?.selectedIds ?: emptySet()
    val density = LocalDensity.current.density
    val haptics=LocalHapticFeedback.current
    val selectedLevel = if (is3D) orbitLevel else level
    val floorZ = document.levels.find { it.id.value == selectedLevel }?.elevation ?: 0.0
    val snapSettings = document.project.settings.snap
    val anchor = if (anchorX != null && anchorY != null) Point2(anchorX!!, anchorY!!) else null
    val latestReady = rememberUpdatedState(ready)
    val latestSnaps = rememberUpdatedState(snapSettings)
    val latestLevel = rememberUpdatedState(selectedLevel)
    val latestSettings = rememberUpdatedState(settings)
    val latestAnchor = rememberUpdatedState(anchor)
    val latestEditor = rememberUpdatedState(editor)
    val latestDocument = rememberUpdatedState(document)
    val latestTool = rememberUpdatedState(tool)
    val latestFloorZ = rememberUpdatedState(floorZ)
    val latestAddSelection = rememberUpdatedState(addSelection)
    val latestMeasurementMode = rememberUpdatedState(measurementMode)
    val selectedBounds = remember(ready?.picker,selectedIds) { ready?.picker?.boundsFor(selectedIds) }
    val selectionHasMeasurements=remember(document.elements,selectedIds) {document.elements.any {it.id in selectedIds && isMeasurement(it)}}
    val selectedElement=remember(document.elements,selectedIds,propertiesOpen) {
        if(propertiesOpen)document.elements.singleOrNull {it.id in selectedIds} else null
    }
    val selectedProperties=remember(document,selectedElement) { selectedElement?.let {readProperties(document,it)} }
    val selectedLibraryProperties=remember(document,selectedElement) {
        selectedElement?.takeIf {it.body is ElementBody.Component}?.let {readLibraryProperties(document,it)}
    }
    val pendingLibraryItem=remember(document.libraryItems,pendingLibrary) {
        pendingLibrary?.let {id->document.libraryItems.find {it.id.value==id} ?: BuiltInLibrary.find(LibraryItemId(id))}
    }
    val libraryAim=precisionPick?.point ?: pointerWorld ?: Point3(viewport.center.x,viewport.center.y,floorZ)
    val libraryPlacementResult=remember(document,pendingLibraryItem,libraryAim,selectedLevel) {
        pendingLibraryItem?.let {item->runCatching {
            previewLibraryPlacement(libraryPreviewDocument(document),item,libraryAim,selectedLevel?.let(::LevelId))
        }}
    }
    val libraryPlacement=libraryPlacementResult?.getOrNull()
    // Keep the raw aim for recomputing the host attachment; display the resolved insertion point.
    val displayedPrecisionPick=if(pendingLibraryItem!=null && libraryPlacement!=null)precisionPick?.let {pick->
        val point=libraryPlacement.element.transform.translation
        projectPrecisionPoint(point,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble())?.let {screen->
            pick.copy(point=point,screen=screen,wallEdge=libraryPlacement.wallSnapped,
                mode=pick.mode.takeIf {pick.point==point},
                elementId=(libraryPlacement.element.body as ElementBody.Component).host?.hostElementId)
        }
    } else precisionPick
    val lockedSelection = remember(document.elements,document.layers,selectedIds) {
        val lockedLayers = document.layers.filter { it.locked }.mapTo(HashSet()) { it.id }
        document.elements.any { it.id in selectedIds && (it.layerId in lockedLayers || it.lockMode==LockMode.ALL) }
    }
    val positionLockedSelection=remember(document.elements,selectedIds) {document.elements.any {it.id in selectedIds && it.lockMode==LockMode.POSITION_ONLY}}
    val positionLockedPivot=remember(document.elements,selectedIds) {document.elements.singleOrNull {it.id in selectedIds && it.lockMode==LockMode.POSITION_ONLY}?.transform?.translation}
    // Plan symbols add strokes to the same physical geometry. Keep valid picks available
    // while the camera's line presentation is being rebuilt after a 2D/3D switch.
    val geometryMatches = remember(ready?.document,document,ready?.detailedMeasurements,settings.showDetailedMeasurement) {
        ready?.let { sameCanvasGeometry(it.document,document) && it.detailedMeasurements==settings.showDetailedMeasurement } == true }
    val sceneMatches = geometryMatches && ready?.selectedIds == selectedIds
    val selectionResizeFrame = if (sceneMatches) ready?.resizeFrame else null
    val latestGeometryMatches = rememberUpdatedState(geometryMatches)
    val latestSceneMatches = rememberUpdatedState(sceneMatches)
    val latestBounds = rememberUpdatedState(selectedBounds)
    val latestLocked = rememberUpdatedState(lockedSelection)
    val latestPositionLocked=rememberUpdatedState(positionLockedSelection)
    val latestBusy = rememberUpdatedState(busy)
    val needsSurfaces = document.project.settings.viewMode != ViewMode.WIREFRAME || measurementMode != MeasurementMode.NONE ||
        document.project.settings.autoXrayFrontWalls || document.elements.any {element->element.objectDisplay==ObjectDisplay.XRAY ||
            element.properties.any {(key,value)->key.startsWith(ROOM_SIDE_DISPLAY_PREFIX) && (value as? ParameterValue.Text)?.value==ObjectDisplay.XRAY.name}}
    val placementActive=tool!=DrawingTool.SELECT || measurementMode!=MeasurementMode.NONE || pendingLibrary!=null
    val floatingPanelsVisible=!panelsHidden && !(settings.autoHidePanelsWhileDrawing && (placementActive || selectedIds.isNotEmpty()))
    val latestPlacementActive=rememberUpdatedState(placementActive)
    val precisionActive=placementActive && settings.precisionPointer.enabled
    val latestPrecisionActive=rememberUpdatedState(precisionActive)
    val canPointUndo=pointUndo.isNotEmpty()
    val canPointRedo=pointRedo.isNotEmpty()

    LaunchedEffect(selectedIds) {
        gizmoMode=GizmoMode.MOVE;rotationAngle=null
        if(selectedRoomSide?.roomId !in selectedIds)selectedRoomSide=null
    }
    LaunchedEffect(placedToken) {
        if(placedToken>0) {placedFeedback=true;delay(1200);placedFeedback=false}
    }

    LaunchedEffect(document.levels) {
        if(level!=null && document.levels.none {it.id.value==level})level=document.levels.firstOrNull()?.id?.value
        if(orbitLevel!=null && document.levels.none {it.id.value==orbitLevel})orbitLevel=null
    }

    LaunchedEffect(document.project.id, document.elements, document.layers, document.systems, document.libraryItems,document.materials,document.levels,
        document.project.settings.lengthUnit, document.project.settings.angleFormat, document.project.settings.decimalPlaces,
        document.project.settings.showMeasurements,document.project.settings.hiddenLevelIds,document.project.settings.showHiddenObjects,
        settings.showDetailedMeasurement,selectedIds,needsSurfaces,is3D) {
        try {
            val previous = prepared
            val compiled = withContext(Dispatchers.Default) {
                val planSymbols=!is3D
                val sameGeometry = previous != null && sameCanvasGeometry(previous.document,document) && previous.detailedMeasurements==settings.showDetailedMeasurement && previous.planSymbols==planSymbols
                val scene = if (sameGeometry) previous!!.scene else buildDrawingScene(renderDocument,planSymbols=planSymbols)
                val index = SpatialIndex(scene.segments)
                val allIds=document.elements.mapTo(HashSet()) {it.id}
                val mainIds=allIds-selectedIds
                val main = if (selectedIds.isEmpty()) scene else buildDrawingScene(renderDocument,mainIds,planSymbols)
                val selected = if (selectedIds.isEmpty()) null else buildDrawingScene(renderDocument,selectedIds,planSymbols)
                val surfaces=if(sameGeometry && previous!!.surfaces!=null) previous.surfaces else if(needsSurfaces)buildSurfaceScene(document) else null
                val mainSurfaces=surfaces?.let {if(selectedIds.isEmpty())it else it.partition(selectedIds,false)}
                val selectedSurfaces=if(selectedIds.isEmpty())null else surfaces?.partition(selectedIds,true)
                val surfacedIds=surfaces?.chunks.orEmpty().mapTo(HashSet()) {ElementId(it.elementId)}
                val annotationIds=allIds-surfacedIds+if(planSymbols)document.elements.filter {it.body is ElementBody.Component}.map {it.id} else emptyList()
                val annotations=if(surfaces==null)main else buildDrawingScene(renderDocument,annotationIds-selectedIds,planSymbols)
                val selectedAnnotations=if(selectedIds.isEmpty())null else if(surfaces==null)selected else buildDrawingScene(renderDocument,annotationIds.intersect(selectedIds),planSymbols)
                val dimensionIds=document.elements.filter {((it.body as? ElementBody.DrawingShape)?.shape is Shape.Dimension)}.mapTo(HashSet()){it.id}
                val measurementScene=buildDrawingScene(renderDocument,dimensionIds-selectedIds)
                val selectedMeasurements=if(selectedIds.isEmpty())null else buildDrawingScene(renderDocument,dimensionIds.intersect(selectedIds))
                val resize = if (sameGeometry && previous!!.selectedIds == selectedIds) previous.resizeFrame else
                    if (selectedIds.size == 1) document.elements.find { it.id in selectedIds && !isMeasurement(it) }?.let { resizeFrame(document,it) } else null
                SceneLoad.Ready(document, selectedIds, scene, main, selected,
                    if (sameGeometry) previous!!.snaps else SnapEngine(index),
                    if (sameGeometry) previous!!.picker else EditorPicker(document, scene, index),resize,
                    MoveContext(document,selectedIds,scene),surfaces,mainSurfaces,selectedSurfaces,annotations,selectedAnnotations,measurementScene,selectedMeasurements,settings.showDetailedMeasurement,
                    if(sameGeometry)previous!!.pointPicks else PointPickContext(document,scene),
                    if(sameGeometry)previous!!.componentReferences else document.elements.filter {it.body is ElementBody.Component}.associate {it.id to it.transform.translation},planSymbols)
            }
            prepared = compiled
            loading = compiled
            if (waitingForCommit != null && document != waitingForCommit) {
                preview = null
                waitingForCommit = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            loading = SceneLoad.Failed
        }
    }
    LaunchedEffect(waitingForCommit) {
        val before = waitingForCommit ?: return@LaunchedEffect
        withFrameNanos { }
        // Commands are synchronous. A rejected or numerically unchanged drag has no new scene.
        if (latestDocument.value == before) { preview = null; waitingForCommit = null }
    }
    fun inputSnapshot()=PointInputSnapshot(points,measurementPicks,anchor,precisionPick)
    fun clearPrecisionCandidate() {precisionPick=null;precisionFinger=null;precisionDragging=false;precisionSnap=null}
    fun retainInput(before:PointInputSnapshot) {
        pointUndo=(pointUndo+before).takeLast(settings.undoLimit);pointRedo=emptyList()
    }
    fun applyPrecisionPick(pick:PrecisionPick,feedback:Boolean=true) {
        precisionPick=pick;pointerWorld=pick.point
        cursor.value=SnapResult(Point2(pick.point.x,pick.point.y),pick.mode,pick.elementId?.value)
        val key=if(pick.mode==null && !pick.wallEdge && pick.elementId==null)null else PrecisionSnapKey(pick.mode,pick.elementId,pick.wallEdge,
            pick.point.takeIf {pick.mode in setOf(SnapMode.GRID,SnapMode.ENDPOINT,SnapMode.MIDPOINT,SnapMode.INTERSECTION) || pick.elementId!=null && !pick.wallEdge && pick.mode==null})
        if(feedback && key!=null && key!=precisionSnap && settings.precisionPointer.hapticFeedback)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        precisionSnap=key
    }
    fun restoreInput(snapshot:PointInputSnapshot) {
        points=snapshot.points;measurementPicks=snapshot.measurements
        anchorX=snapshot.anchor?.x;anchorY=snapshot.anchor?.y
        clearPrecisionCandidate()
        snapshot.candidate?.let {candidate->
            projectPrecisionPoint(candidate.point,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble())?.let {screen->
                if(screen.x in 0.0..size.width.toDouble() && screen.y in 0.0..size.height.toDouble())applyPrecisionPick(candidate.copy(screen=screen),feedback=false)
            }
        }
        pointerWorld=precisionPick?.point ?: snapshot.points.lastOrNull() ?: snapshot.measurements.lastOrNull()?.point
    }
    fun cancelConstruction() {
        points = emptyList(); preview = null; waitingForCommit = null
        pointUndo=emptyList();pointRedo=emptyList();clearPrecisionCandidate()
        measurementPicks=emptyList();measurementMode=MeasurementMode.NONE
        pendingLibrary=null;tool=DrawingTool.SELECT;rotationAngle=null
        anchorX = null; anchorY = null
    }
    fun placed(library:Boolean=false) {
        pointUndo=emptyList();pointRedo=emptyList();clearPrecisionCandidate()
        points=emptyList();measurementPicks=emptyList();anchorX=null;anchorY=null
        gizmoMode=GizmoMode.MOVE
        placedToken++
        if(library || !settings.keepToolActiveAfterPlacing) {tool=DrawingTool.SELECT;measurementMode=MeasurementMode.NONE;pendingLibrary=null}
    }
    fun commitMeasurement(picks:List<MeasurementPick>,label:Point3):Boolean {
        if(picks.size<2 || busy)return false
        return try {
            val source=picks[0].elementId
            val sourceLevel=document.elements.find {it.id==source}?.levelId ?: selectedLevel?.let(::LevelId)
            fun objectName(id:ElementId?):String?=document.elements.find {it.id==id}?.let {element->
                element.name.takeIf(String::isNotBlank) ?: (element.body as? ElementBody.Component)?.let {body->document.libraryItems.find {it.id==body.libraryItemId}?.name}
            }
            val name=if(measurementMode==MeasurementMode.REFERENCE) {
                val sourceName=objectName(source) ?: "Object"
                val targetName=objectName(picks[1].elementId) ?: if(picks[1].elementId==null && grid)"grid reference" else "reference"
                "$sourceName to $targetName".filterNot(Char::isISOControl).take(120)
            } else "Measurement"
            val geometry=createMeasurement(document,picks[0].point,picks[1].point,label,source,picks[1].elementId,
                name=name,
                showCoordinates=settings.showDetailedMeasurement,
                showHeightAboveFloor=source!=null,floorLevelId=sourceLevel)
            val success=editor?.onAddSketch?.invoke(geometry,selectedLevel)==true
            if(success) placed()
            success
        } catch(error:IllegalArgumentException) {toolError=error.message;measurementPicks=picks.take(1);false}
    }
    fun finishConstruction(picks: List<Point3> = points) {
        if (busy) return
        try {
            val sketch = if (options.useDefaultDimensions && supportsDefaultPlacement(tool))
                createDefaultSketch(tool,picks.first(),options) else createSketch(tool, picks, options)
            if (editor?.onAddSketch?.invoke(sketch, level) == true) {
                placed()
            }
        } catch (error: IllegalArgumentException) {
            toolError = error.message
            if (tool.autoFinishPoints == picks.size) points = picks.dropLast(1)
        }
    }
    val onCanvasTap = rememberUpdatedState<(Offset) -> Unit>({ position ->
        val actions = latestEditor.value
        val confirmed=if(precisionActive)precisionPick else null
        if(actions!=null && !busy && pendingLibrary!=null) {
            val at=confirmed?.point ?: pointerWorld ?: Point3(viewport.center.x,viewport.center.y,floorZ)
            if((!precisionActive || libraryPlacement!=null) && actions.onInsertLibrary(LibraryItemId(pendingLibrary!!),at,selectedLevel))placed(library=true)
        } else if(actions!=null && !busy && latestMeasurementMode.value!=MeasurementMode.NONE && latestGeometryMatches.value && ready!=null) {
            val sceneHit=if(is3D && confirmed==null)pickSceneHit(ready.scene,ready.surfaces,CanvasFrame(viewport,orbit,true,selectedLevel,floorElevation=floorZ),
                position.x.toDouble(),position.y.toDouble(),size.width.toDouble(),size.height.toDouble(),14.0*density) else null
            val picked=confirmed?.elementId ?: if(is3D)ready.picker.pick3D(orbit,position.x.toDouble(),position.y.toDouble(),size.width.toDouble(),size.height.toDouble(),14.0*density,selectedLevel) ?: sceneHit?.elementId
                else ready.picker.pickPlan(viewport.screenToWorld(position.x.toDouble(),position.y.toDouble(),size.width.toDouble(),size.height.toDouble()),14.0*density/viewport.pixelsPerMetre,selectedLevel)
            val raw=confirmed?.point ?: if(is3D)sceneHit?.point ?: pointerWorld
                else cursor.value?.point?.let {Point3(it.x,it.y,floorZ)}
            raw?.let {point->
                if(measurementMode==MeasurementMode.REFERENCE && measurementPicks.isEmpty() && picked==null) toolError="Tap a visible object for the first reference."
                else if(measurementMode==MeasurementMode.REFERENCE && measurementPicks.size==2) commitMeasurement(measurementPicks,point)
                else {
                    var at=if(confirmed==null && measurementMode==MeasurementMode.REFERENCE && picked!=null)measurementPointForElement(document,picked,point) else point
                    if(confirmed==null && measurementMode==MeasurementMode.REFERENCE && measurementPicks.size==1 && picked==null && grid) {
                        val first=measurementPicks[0].point; val spacing=snapSettings.gridSpacing
                        val gx=kotlin.math.round(point.x/spacing)*spacing;val gy=kotlin.math.round(point.y/spacing)*spacing
                        at=if(abs(point.x-gx)<=abs(point.y-gy))Point3(gx,first.y,floorZ) else Point3(first.x,gy,floorZ)
                    }
                    val picks=measurementPicks+MeasurementPick(at,if(measurementMode==MeasurementMode.REFERENCE)picked else null)
                    measurementPicks=picks
                    if(measurementMode==MeasurementMode.DISTANCE && picks.size==2) {
                        val a=picks[0].point;val b=picks[1].point
                        val offset=if(is3D)max(.1,orbit.orthographicHeight*.06) else 40.0*density/viewport.pixelsPerMetre
                        commitMeasurement(picks,Point3(a.x+(b.x-a.x)/2,a.y+(b.y-a.y)/2+offset,a.z+(b.z-a.z)/2))
                    }
                }
            }
        } else if (actions != null && !busy && latestTool.value == DrawingTool.SELECT && !probe && latestGeometryMatches.value) {
            val picker = latestReady.value?.picker
            val picked = if (is3D) picker?.pick3D(orbit, position.x.toDouble(), position.y.toDouble(),
                size.width.toDouble(), size.height.toDouble(), 14.0 * density, selectedLevel) ?: ready?.surfaces?.let {surfaces->
                    pickSceneHit(ready.scene,surfaces,CanvasFrame(viewport,orbit,true,selectedLevel,floorElevation=floorZ),
                        position.x.toDouble(),position.y.toDouble(),size.width.toDouble(),size.height.toDouble(),14.0*density)?.elementId}
            else picker?.pickPlan(viewport.screenToWorld(position.x.toDouble(), position.y.toDouble(),
                size.width.toDouble(), size.height.toDouble()), 14.0 * density / viewport.pixelsPerMetre, selectedLevel)
            actions.onSelect(if (latestAddSelection.value) {
                if (picked == null) emptySet() else if (picked in actions.selectedIds) actions.selectedIds - picked else actions.selectedIds + picked
            } else setOfNotNull(picked))
        } else if (actions != null && !busy && latestTool.value != DrawingTool.SELECT) {
            (if(is3D)pointerWorld?.let {Point2(it.x,it.y)} else cursor.value?.point)?.let { point ->
                if (points.size >= 512) toolError = "Finish this shape before adding more than 512 points."
                else {
                    val picks = points + (confirmed?.point ?: Point3(point.x, point.y, latestFloorZ.value))
                    points = picks
                    anchorX = point.x; anchorY = point.y
                    if (options.useDefaultDimensions && supportsDefaultPlacement(latestTool.value) ||
                        latestTool.value.autoFinishPoints == picks.size) finishConstruction(picks)
                }
            }
        } else if (!is3D) cursor.value?.point?.let { anchorX = it.x; anchorY = it.y }
    })
    fun fit() {
        if (!geometryMatches) return
        val scene = ready?.scene ?: return
        if (size.width <= 0 || size.height <= 0) return
        val bounds = if(scene.segments.isEmpty())Bounds3(Point3(-2.0,-2.0,floorZ),Point3(2.0,2.0,floorZ+2.0)) else scene.boundsForLevel(selectedLevel)
        if (is3D) orbit = orbit.fit(bounds, size.width.toDouble() / size.height)
        else viewport = viewport.fit(bounds, size.width.toDouble(), size.height.toDouble(), 48.0 * density)
    }
    LaunchedEffect(ready, size, selectedLevel) {
        if (!initialized && ready != null && size.width > 0 && size.height > 0) {
            val emptyBounds = Bounds3(Point3(-2.0,-2.0,floorZ),Point3(2.0,2.0,floorZ+2.0))
            val planBounds = if (ready.scene.segments.isEmpty()) emptyBounds else ready.scene.boundsForLevel(level)
            viewport = viewport.fit(planBounds, size.width.toDouble(), size.height.toDouble(), 48.0 * density)
            orbit = orbit.fit(if (ready.scene.segments.isEmpty()) emptyBounds else ready.scene.bounds, size.width.toDouble() / size.height)
            initialized = true
        }
    }
    val updatePointer = rememberUpdatedState<(Offset) -> Unit>({ position ->
        if (!is3D && size.width > 0 && size.height > 0) {
            val point = viewport.screenToWorld(position.x.toDouble(), position.y.toDouble(), size.width.toDouble(), size.height.toDouble())
            val engine = if (latestGeometryMatches.value) latestReady.value?.snaps ?: fallbackSnaps else fallbackSnaps
            cursor.value = engine.snap(
                point, latestSnaps.value, max(latestSnaps.value.tolerance, 14.0 * density / viewport.pixelsPerMetre),
                latestAnchor.value, latestLevel.value,
            )
            pointerWorld=cursor.value?.point?.let {Point3(it.x,it.y,floorZ)}
        } else if(size.width>0 && size.height>0 && placementActive) {
            val point=orbit.rayToPlaneZ(position.x.toDouble(),position.y.toDouble(),size.width.toDouble(),size.height.toDouble(),floorZ)
                ?: viewPlanePoint(orbit,position.x.toDouble(),position.y.toDouble(),size.width.toDouble(),size.height.toDouble())
            pointerWorld=if(measurementMode==MeasurementMode.NONE)point.copy(z=floorZ) else point
        }
    })
    val updatePrecisionPointer=rememberUpdatedState<(Offset,Boolean)->Unit>({position,stylus->
        if(size.width>0 && size.height>0 && latestGeometryMatches.value) {
            val context=latestReady.value?.pointPicks
            if(context!=null) {
                val finger=Point2(position.x.toDouble(),position.y.toDouble())
                val target=offsetCrosshair(finger,size.width.toDouble(),size.height.toDouble(),density.toDouble(),latestSettings.value.precisionPointer,stylus)
                precisionFinger=finger
                val reference=measurementPicks.firstOrNull()?.point ?: points.lastOrNull()
                var picked=context.pick(target,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble(),floorZ,
                    selectedLevel,snapSettings,reference,14.0*density)
                if(measurementMode==MeasurementMode.REFERENCE && measurementPicks.size<2 && picked.elementId==null) {
                    val picker=latestReady.value?.picker
                    val id=if(is3D)picker?.pick3D(orbit,picked.screen.x,picked.screen.y,size.width.toDouble(),size.height.toDouble(),14.0*density,selectedLevel)
                        else picker?.pickPlan(Point2(picked.point.x,picked.point.y),14.0*density/viewport.pixelsPerMetre,selectedLevel)
                    latestReady.value?.componentReferences?.get(id)?.let {mountingPoint->
                        projectPrecisionPoint(mountingPoint,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble())?.let {projected->
                            if(projected.x in 0.0..size.width.toDouble() && projected.y in 0.0..size.height.toDouble())
                                picked=PrecisionPick(mountingPoint,projected,elementId=id)
                        }
                    }
                }
                applyPrecisionPick(picked)
            }
        }
    })
    fun confirmPrecisionPoint() {
        val pick=precisionPick ?: return
        if(busy || !geometryMatches)return
        val before=inputSnapshot();val token=placedToken
        onCanvasTap.value(Offset(pick.screen.x.toFloat(),pick.screen.y.toFloat()))
        if(token==placedToken && (before.points!=points || before.measurements!=measurementPicks))retainInput(before)
        clearPrecisionCandidate()
    }
    fun nudgePrecision(dx:Int,dy:Int) {
        val pick=precisionPick ?: return
        if(busy)return
        val step=settings.precisionPointer.nudgeStepMetres ?: snapSettings.gridSpacing
        try {
            val next=nudgePrecisionPoint(pick.point,dx,dy,step,viewport,orbit,is3D)
            val screen=projectPrecisionPoint(next,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble()) ?: return
            if(screen.x !in 0.0..size.width.toDouble() || screen.y !in 0.0..size.height.toDouble())return
            retainInput(inputSnapshot())
            // A nudge is an exact SI displacement, rather than another approximate finger pick.
            fun aligned(value:Double)=abs(value/snapSettings.gridSpacing-kotlin.math.round(value/snapSettings.gridSpacing))<1e-7
            val gridPoint=snapSettings.enabled && SnapMode.GRID in snapSettings.modes && aligned(next.x) && aligned(next.y) &&
                (!is3D || abs(next.z-floorZ)<1e-7 || aligned(next.z))
            applyPrecisionPick(PrecisionPick(next,screen,if(gridPoint)SnapMode.GRID else null))
        } catch(_:IllegalArgumentException) { /* Invalid or unprojectable nudges leave the retained point intact. */ }
    }
    fun undoInputOrDocument() {
        if(pointUndo.isNotEmpty()) {
            val previous=pointUndo.last();pointRedo=(pointRedo+inputSnapshot()).takeLast(settings.undoLimit)
            pointUndo=pointUndo.dropLast(1);restoreInput(previous)
        } else {cancelConstruction();editor?.onUndo?.invoke()}
    }
    fun redoInputOrDocument() {
        if(pointRedo.isNotEmpty()) {
            val next=pointRedo.last();pointUndo=(pointUndo+inputSnapshot()).takeLast(settings.undoLimit)
            pointRedo=pointRedo.dropLast(1);restoreInput(next)
        } else {cancelConstruction();editor?.onRedo?.invoke()}
    }
    LaunchedEffect(viewport,orbit,is3D,size) {
        precisionPick?.let {pick->
            val screen=projectPrecisionPoint(pick.point,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble())
            if(screen==null || screen.x !in 0.0..size.width.toDouble() || screen.y !in 0.0..size.height.toDouble())clearPrecisionCandidate()
            else precisionPick=pick.copy(screen=screen)
        }
    }
    LaunchedEffect(document.elements,document.layers,document.systems,document.libraryItems,selectedLevel,snapSettings,settings.precisionPointer.enabled) {
        // A pending snap must never confirm a host that was hidden/removed while the tool stayed active.
        clearPrecisionCandidate()
        pointUndo=pointUndo.map {it.copy(candidate=null)};pointRedo=pointRedo.map {it.copy(candidate=null)}
    }
    fun beginTool(next:DrawingTool) {
        cancelConstruction();tool=next;probe=false;zoomWindow=false;gizmoMode=GizmoMode.MOVE
        if(next!=DrawingTool.SELECT) {
            is3D=false;editor?.onSelect?.invoke(emptySet())
            pointerWorld=Point3(viewport.center.x,viewport.center.y,floorZ)
            cursor.value=SnapResult(viewport.center,null)
        }
    }
    fun beginMeasurement(mode:MeasurementMode) {
        cancelConstruction();measurementMode=mode;editor?.onSelect?.invoke(emptySet());probe=false
        pointerWorld=if(is3D)orbit.target else Point3(viewport.center.x,viewport.center.y,floorZ)
    }
    fun cubeDirection(normal:Point3) {
        is3D=true;zoomWindow=false;window.value=null
        val horizontal=kotlin.math.hypot(normal.x,normal.y)
        orbit=orbit.copy(yaw=if(horizontal<1e-12)-PI/2 else atan2(normal.y,normal.x),pitch=atan2(normal.z,horizontal),projection=Projection.ORTHOGRAPHIC)
        orbitLevel=level
        if(!orbitShown &&ready!=null&&size.height>0) {orbit=orbit.fit(if(ready.scene.segments.isEmpty())Bounds3(Point3(-2.0,-2.0,floorZ),Point3(2.0,2.0,floorZ+2.0)) else ready.scene.boundsForLevel(orbitLevel),size.width.toDouble()/size.height);orbitShown=true}
    }
    fun homeView() {
        is3D=true;orbitLevel=level;orbit=orbit.preset(ViewPreset.ISOMETRIC).withProjection(Projection.ORTHOGRAPHIC)
        if(ready!=null&&size.height>0)orbit=orbit.fit(if(ready.scene.segments.isEmpty())Bounds3(Point3(-2.0,-2.0,floorZ),Point3(2.0,2.0,floorZ+2.0)) else ready.scene.boundsForLevel(orbitLevel),size.width.toDouble()/size.height)
        orbitShown=true
    }
    fun finishTool() {
        if(tool!=DrawingTool.SELECT && points.size>=tool.minimumPoints && points.isNotEmpty())finishConstruction()
        else if(!precisionActive && measurementPicks.size>=2)commitMeasurement(measurementPicks,pointerWorld ?: measurementPicks.last().point)
        else cancelConstruction()
    }
    val activeLabel=when {
        pendingLibrary!=null->pendingLibraryItem?.name ?: "Library item"
        measurementMode==MeasurementMode.DISTANCE->stringResource(R.string.panel_measure_distance)
        measurementMode==MeasurementMode.REFERENCE->stringResource(R.string.panel_reference_dimension)
        tool!=DrawingTool.SELECT->stringResource(drawingToolLabel(tool))
        else->""
    }
    val accent = MaterialTheme.colorScheme.primary
    val frame = CanvasFrame(viewport, orbit, is3D, selectedLevel, snapSettings.gridSpacing, grid,
        MaterialTheme.colorScheme.background.luminance() < .5f, floorZ, ready?.selectionScene, preview,
        surfaceScene=ready?.mainSurfaces,selectionSurfaceScene=ready?.selectedSurfaces,viewMode=document.project.settings.viewMode,
        annotationScene=ready?.annotationScene,selectionAnnotationScene=ready?.selectedAnnotationScene,
        measurementScene=ready?.measurementScene,selectionMeasurementScene=ready?.selectedMeasurementScene,
        autoXrayFrontWalls=document.project.settings.autoXrayFrontWalls)
    val viewToolbarContent:@Composable ()->Unit={
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("canvas_toolbar").padding(horizontal=8.dp),
                    horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                    CanvasActionButton(Icons.Outlined.FitScreen,stringResource(R.string.canvas_extents),{fit()},Modifier.testTag("canvas_extents"),enabled=geometryMatches)
                    CanvasActionButton(Icons.Outlined.CropFree,stringResource(R.string.canvas_window),{zoomWindow=!zoomWindow;window.value=null},Modifier.testTag("canvas_zoom_window"),selected=zoomWindow)
                    editor?.let {actions->
                        CanvasActionButton(Icons.AutoMirrored.Outlined.Undo,stringResource(R.string.editing_undo),{undoInputOrDocument()},Modifier.testTag("view_undo"),enabled=actions.canUndo || canPointUndo)
                        CanvasActionButton(Icons.AutoMirrored.Outlined.Redo,stringResource(R.string.editing_redo),{redoInputOrDocument()},Modifier.testTag("view_redo"),enabled=actions.canRedo || canPointRedo)
                        ViewControls(renderDocument,document.project.settings.viewMode,actions.onViewMode,actions.onLayerVisibility,actions.onSystemVisibility,actions.onMeasurementsVisibility,onOpenVisibility={setPanel(CanvasPanelId.VISIBILITY,PanelVisibility.OPEN)})
                    }
                    CanvasActionButton(Icons.Outlined.Save,stringResource(R.string.canvas_save),onSave,Modifier.testTag("canvas_save"),enabled=dirty && !busy)
                    if(!is3D) {
                        CanvasActionButton(Icons.Outlined.MyLocation,stringResource(R.string.canvas_probe),{probe=!probe},Modifier.testTag("canvas_probe"),selected=probe)
                        CanvasActionButton(Icons.Outlined.Grid4x4,stringResource(R.string.canvas_snaps),{snapsOpen=true},Modifier.testTag("canvas_snaps"))
                        CanvasActionButton(Icons.Outlined.Clear,stringResource(R.string.canvas_clear_reference),{anchorX=null;anchorY=null},Modifier.testTag("canvas_clear_reference"),enabled=anchor!=null)
                    }
                    if(editor!=null) CanvasActionButton(Icons.Outlined.LibraryAdd,stringResource(R.string.canvas_add_selection),{addSelection=!addSelection},Modifier.testTag("canvas_add_selection"),selected=addSelection)
                    CanvasActionButton(Icons.Outlined.GridOn,stringResource(R.string.canvas_grid),{grid=!grid},selected=grid)
                    CanvasActionButton(Icons.Outlined.Image,stringResource(R.string.canvas_export_png),{onExportView(ViewExportRequest(renderDocument,frame,size.width,size.height),ViewExportFormat.PNG)},Modifier.testTag("canvas_export_png"),enabled=geometryMatches && size.width>0 && !placementActive)
                    CanvasActionButton(Icons.Outlined.PictureAsPdf,stringResource(R.string.canvas_export_pdf),{onExportView(ViewExportRequest(renderDocument,frame,size.width,size.height),ViewExportFormat.PDF)},Modifier.testTag("canvas_export_pdf"),enabled=geometryMatches && size.width>0 && !placementActive)
                }
    }
    Scaffold(
        modifier = modifier.testTag("drawing_canvas").focusRequester(focus).onPreviewKeyEvent { event ->
            val actions = editor
            if (actions == null || busy || !canvasFocused || event.type != KeyEventType.KeyDown) false
            else when {
                event.key == Key.Escape -> { cancelConstruction(); tool = DrawingTool.SELECT; true }
                event.isCtrlPressed && event.key == Key.C -> { actions.onCopy(); true }
                event.isCtrlPressed && event.key == Key.V -> { cancelConstruction(); actions.onPaste(); true }
                event.isCtrlPressed && event.key == Key.Z && event.isShiftPressed -> { redoInputOrDocument(); true }
                event.isCtrlPressed && event.key == Key.Z -> { undoInputOrDocument(); true }
                event.isCtrlPressed && event.key == Key.Y -> { redoInputOrDocument(); true }
                event.key == Key.Delete || event.key == Key.Backspace -> { if(selectedRoomSide==null){cancelConstruction();actions.onDelete()}; true }
                else -> false
            }
        }.onFocusChanged { canvasFocused = it.isFocused }.focusable(),
        topBar = {
            if(!panelsHidden) TopAppBar(
                title = { Text(document.project.name, maxLines = 1) },
                navigationIcon = { IconButton(onBack, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.canvas_back))
                } },
                actions = {
                    IconButton(onExport, enabled = !busy) { Icon(Icons.Outlined.Share, stringResource(R.string.canvas_export)) }
                    IconButton(onDetails, enabled = !busy, modifier = Modifier.testTag("canvas_details")) {
                        Icon(Icons.Outlined.Description, stringResource(R.string.canvas_project_details))
                    }
                },
            )
        },
        bottomBar = {
            CanvasStatus(document, is3D, orbit, cursor, selectedLevel, onUnitsChange,
                onLevel = { cancelConstruction(); editor?.onSelect?.invoke(emptySet()); if (is3D) orbitLevel = it else level = it }, onSnaps = { snapsOpen = true })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(!panelsHidden) {
                if(editor!=null) CanvasToolbars(
                    onDraw={},onEdit={},onView={},onMeasure={beginMeasurement(MeasurementMode.DISTANCE)},
                    onReferenceDimension={beginMeasurement(MeasurementMode.REFERENCE)},
                    onMeasurements={setPanel(CanvasPanelId.MEASUREMENTS,PanelVisibility.OPEN)},onLibrary={},
                    onTogglePanels={},panelsHidden=false,modifier=Modifier.fillMaxWidth(),
                    activeTool=if(placementActive)activeLabel else null,measureActive=measurementMode!=MeasurementMode.NONE,
                    libraryActive=pendingLibrary!=null,panelStates=canvasLayout.panels,
                    onPanelChange={id,state->setPanel(id,state)},showPanelToggle=false)
                if(placementActive) PlacementBanner(activeLabel,
                    when {
                        precisionActive->stringResource(R.string.precision_aim_hint,activeLabel)
                        pendingLibrary!=null || options.useDefaultDimensions && supportsDefaultPlacement(tool)->stringResource(R.string.placement_tap_to_place,activeLabel)
                        measurementMode==MeasurementMode.DISTANCE && measurementPicks.isEmpty()->stringResource(R.string.canvas_measure_pick_first)
                        measurementMode==MeasurementMode.DISTANCE->stringResource(R.string.canvas_measure_pick_second)
                        measurementMode==MeasurementMode.REFERENCE && measurementPicks.isEmpty()->stringResource(R.string.canvas_reference_pick_object)
                        measurementMode==MeasurementMode.REFERENCE && measurementPicks.size==1->stringResource(R.string.canvas_reference_pick_reference)
                        measurementMode==MeasurementMode.REFERENCE->stringResource(R.string.canvas_reference_place_label)
                        points.isEmpty()->stringResource(R.string.placement_tap_to_start,activeLabel)
                        else->stringResource(R.string.placement_add_points)
                    },canDone=true,onCancel={cancelConstruction()},onDone={finishTool()},showCancel=!precisionActive)
                if(placedFeedback) Text(stringResource(R.string.placement_placed),Modifier.padding(8.dp).testTag("placement_placed"),style=MaterialTheme.typography.labelMedium)
            }
            Box(
                Modifier.fillMaxWidth().weight(1f).onSizeChanged { size = it }
                    .testTag("canvas_surface").semantics {
                        set(PlanCenterX, viewport.center.x); set(PlanCenterY, viewport.center.y)
                        set(PlanScale, viewport.pixelsPerMetre); set(OrbitYaw, orbit.yaw); set(OrbitPitch, orbit.pitch)
                    }
                    .pointerInput(document.project.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            if (latestReady.value == null) return@awaitEachGesture
                            focus.requestFocus()
                            val first = down.position
                            var moved = 0f
                            var multi = false
                            val bodyReady=latestReady.value
                            val bodySelection=latestEditor.value?.selectedIds.orEmpty()
                            val bodyHit=if(latestSettings.value.bodyDragMovesSelection && latestTool.value==DrawingTool.SELECT &&
                                latestMeasurementMode.value==MeasurementMode.NONE && !latestPositionLocked.value && selectedRoomSide==null && !latestPlacementActive.value && gizmoMode==GizmoMode.MOVE && latestSceneMatches.value && !latestLocked.value && !latestBusy.value && !zoomWindow && !probe) {
                                if(is3D)bodyReady?.picker?.pick3D(orbit,first.x.toDouble(),first.y.toDouble(),size.width.toDouble(),size.height.toDouble(),14.0*density,latestLevel.value)
                                else bodyReady?.picker?.pickPlan(viewport.screenToWorld(first.x.toDouble(),first.y.toDouble(),size.width.toDouble(),size.height.toDouble()),14.0*density/viewport.pixelsPerMetre,latestLevel.value)
                            } else null
                            val bodyMove=bodyHit!=null && bodyHit in bodySelection
                            val bodyPivot=latestBounds.value?.center() ?: Point3()
                            var bodyCancelled=false
                            val probing = probe || (down.type == PointerType.Stylus && latestSettings.value.inputMode == InputMode.STYLUS)
                            val precise=latestPrecisionActive.value && !zoomWindow
                            val pen=down.type==PointerType.Stylus || down.type==PointerType.Eraser || down.type==PointerType.Mouse
                            if(precise) {precisionDragging=true;updatePrecisionPointer.value(first,pen)} else updatePointer.value(first)
                            if (zoomWindow) window.value = first to first
                            down.consume()
                            try { while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                val position = event.changes.first().position
                                if (pressed == 0) {
                                    if(bodyMove && moved>=viewConfiguration.touchSlop) {
                                        val delta=preview
                                        if(!bodyCancelled && delta!=null && !delta.isIdentity) {
                                            waitingForCommit=latestDocument.value
                                            if(latestEditor.value?.onTransform?.invoke(delta)!=true) {preview=null;waitingForCommit=null}
                                        } else {preview=null;waitingForCommit=null}
                                    } else if (zoomWindow) {
                                        if (is3D) {
                                            val dx = abs(position.x - first.x).toDouble()
                                            val dy = abs(position.y - first.y).toDouble()
                                            if (dx >= 4 && dy >= 4) orbit = orbit.pan(
                                                size.width / 2.0 - (first.x + position.x) / 2,
                                                size.height / 2.0 - (first.y + position.y) / 2,
                                                size.width.toDouble(), size.height.toDouble(),
                                            ).zoom(min(size.width / dx, size.height / dy))
                                        } else viewport = viewport.zoomWindow(first.x.toDouble(), first.y.toDouble(), position.x.toDouble(), position.y.toDouble(), size.width.toDouble(), size.height.toDouble())
                                        window.value = null
                                        zoomWindow = false
                                    } else if(precise && !multi) {
                                        updatePrecisionPointer.value(position,pen)
                                    } else if (!multi && moved < viewConfiguration.touchSlop) {
                                        updatePointer.value(position)
                                        onCanvasTap.value(position)
                                    }
                                    cameraOrbiting=false
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                                moved = max(moved, (position - first).getDistance())
                                val pan = event.calculatePan()
                                val sensitivity = latestSettings.value.gestureSensitivity
                                if (pressed >= 2) {
                                    multi = true
                                    precisionDragging=false;precisionFinger=null
                                    cameraOrbiting=is3D
                                    if(bodyMove) {bodyCancelled=true;preview=null}
                                    val zoom = event.calculateZoom().toDouble().pow(sensitivity)
                                    val centroid = event.calculateCentroid()
                                    if (is3D) orbit = orbit.pan(pan.x.toDouble(), pan.y.toDouble(), size.width.toDouble(), size.height.toDouble()).zoom(zoom)
                                    else viewport = viewport.pan(pan.x.toDouble(), pan.y.toDouble()).zoom(zoom,
                                        centroid.x.toDouble(), centroid.y.toDouble(), size.width.toDouble(), size.height.toDouble())
                                } else if(bodyMove) {
                                    if(!bodyCancelled && moved>=viewConfiguration.touchSlop) preview=bodyReady?.moves?.delta(bodyPivot,
                                        Point2(first.x.toDouble(),first.y.toDouble()),Point2(position.x.toDouble(),position.y.toDouble()),null,
                                        viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble(),latestSnaps.value,14.0*density,latestLevel.value)
                                } else if (zoomWindow) window.value = first to position
                                else if(latestPlacementActive.value) { /* Only the ghost follows a placement drag. */ }
                                else if (is3D) {
                                    if(moved>=viewConfiguration.touchSlop)cameraOrbiting=true
                                    orbit = if (orbitPan) orbit.pan(pan.x*sensitivity, pan.y*sensitivity, size.width.toDouble(), size.height.toDouble())
                                    else orbit.orbit(-pan.x*.006*sensitivity, pan.y*.006*sensitivity)
                                }
                                else if (!probing) viewport = viewport.pan(pan.x*sensitivity, pan.y*sensitivity)
                                if(precise && !multi)updatePrecisionPointer.value(position,pen)
                                else if(!precise)updatePointer.value(position)
                                event.changes.forEach { it.consume() }
                            } } finally {cameraOrbiting=false;precisionDragging=false;precisionFinger=null}
                        }
                    }
                    .pointerInput(document.project.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (!latestPrecisionActive.value && event.type == PointerEventType.Move && event.changes.none { it.pressed }) {
                                    event.changes.firstOrNull()?.let { updatePointer.value(it.position) }
                                }
                            }
                        }
                    },
            ) {
                if (surface != null) surface(ready?.mainScene, frame, Modifier.fillMaxSize())
                else NativeDrawingSurface(ready?.mainScene, frame, Modifier.fillMaxSize(), onError = { rendererFailed = true })
                if (ready != null) Box(Modifier.size(1.dp).testTag("canvas_ready"))
                if (!is3D) PlanOverlays(viewport, document.project.settings.lengthUnit, cursor, anchor, window)
                else {
                    Canvas(Modifier.fillMaxSize()) {
                        window.value?.let { (a,b) ->
                            drawRect(accent, Offset(min(a.x,b.x), min(a.y,b.y)), Size(abs(a.x-b.x),abs(a.y-b.y)), style = Stroke(2f*density))
                        }
                    }
                }
                if (editor != null && tool != DrawingTool.SELECT) {
                    ConstructionPreview(document,tool,options,points,cursor.value?.point,floorZ,viewport,
                        orbit=orbit,is3D=is3D,cursorWorld=pointerWorld)
                }
                if(measurementMode!=MeasurementMode.NONE) MeasurementGhostPreview(measurementPicks.map {it.point},pointerWorld,viewport,orbit=orbit,is3D=is3D)
                libraryPlacement?.let { LibraryGhostPreview(document,it.item.id,libraryAim,viewport,orbit,is3D,placement=it) }
                if (editor != null && selectedBounds != null && !placementActive && !zoomWindow && !cameraOrbiting && !panelsHidden && selectedRoomSide==null) {
                    SelectionGizmo(selectedBounds, viewport, orbit, is3D, preview,
                        onStart = { _, _ -> dragPivot = positionLockedPivot ?: selectedBounds.center(); dragResizeFrame = if(positionLockedSelection)null else selectionResizeFrame
                            waitingForCommit = null; preview = EditDelta(pivot = dragPivot) },
                        onDrag = { handle, start, current ->
                            try {
                                preview = when (handle) {
                                    GizmoHandle.MOVE_FREE, GizmoHandle.MOVE_X, GizmoHandle.MOVE_Y, GizmoHandle.MOVE_Z -> {
                                        val axis = when (handle) {
                                            GizmoHandle.MOVE_X -> Point3(x = 1.0)
                                            GizmoHandle.MOVE_Y -> Point3(y = 1.0)
                                            GizmoHandle.MOVE_Z -> Point3(z = 1.0)
                                            else -> null
                                        }
                                        ready!!.moves.delta(dragPivot,
                                            Point2(start.x.toDouble(),start.y.toDouble()), Point2(current.x.toDouble(),current.y.toDouble()),
                                            axis,viewport, orbit, is3D, size.width.toDouble(), size.height.toDouble(),snapSettings,14.0*density,selectedLevel)
                                    }
                                    GizmoHandle.ROTATE_X,GizmoHandle.ROTATE_Y,GizmoHandle.ROTATE_Z -> {
                                        val axis=when(handle) {GizmoHandle.ROTATE_X->RotationAxis.X;GizmoHandle.ROTATE_Y->RotationAxis.Y;else->RotationAxis.Z}
                                        val a=Point2(start.x.toDouble(),start.y.toDouble());val b=Point2(current.x.toDouble(),current.y.toDouble())
                                        rotationAngle=rotationGestureAngle(dragPivot,axis,a,b,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble(),document.project.settings.rotationSnapDegrees)*180/PI
                                        rotationGestureDelta(dragPivot,axis,a,b,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble(),document.project.settings.rotationSnapDegrees)
                                    }
                                    GizmoHandle.SCALE -> {
                                        val projected = if (is3D) orbit.project(dragPivot,size.width.toDouble(),size.height.toDouble())?.let { Point2(it.x,it.y) }
                                            else viewport.worldToScreen(Point2(dragPivot.x,dragPivot.y),size.width.toDouble(),size.height.toDouble())
                                        val factor = projected?.let { hypot(current.x-it.x,current.y-it.y) / max(1.0,hypot(start.x-it.x,start.y-it.y)) } ?: 1.0
                                        EditDelta(pivot = dragPivot, scale = factor.coerceIn(.001,1000.0))
                                    }
                                    else -> {
                                        val resize = requireNotNull(handle.resizeHandle())
                                        resizeDelta(requireNotNull(dragResizeFrame),resize,
                                            Point2(start.x.toDouble(),start.y.toDouble()),Point2(current.x.toDouble(),current.y.toDouble()),
                                            viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble(),options.keepProportions)
                                    }
                                }
                            } catch (_: IllegalArgumentException) { preview = null }
                        },
                        onEnd = { cancelled ->
                            val delta = preview
                            rotationAngle=null
                            if (!cancelled && delta != null) {
                                waitingForCommit = document
                                val applied = editor.onTransform(delta)
                                // A rejected/no-op command has no new scene to wait for.
                                if (!applied || delta.isIdentity) {
                                    waitingForCommit = null; preview = null
                                }
                            } else { preview = null; waitingForCommit = null }
                        }, enabled = !busy && !lockedSelection && !(positionLockedSelection && gizmoMode==GizmoMode.MOVE) && waitingForCommit == null && sceneMatches,
                        resizeFrame = if(positionLockedSelection)null else selectionResizeFrame,allowResize=!selectionHasMeasurements,
                        mode=gizmoMode,rotationAngleDegrees=rotationAngle,pivotOverride=positionLockedPivot)
                }
                if(!zoomWindow && !panelsHidden) Row(Modifier.align(Alignment.TopStart).padding(8.dp),verticalAlignment=Alignment.Top) {
                    ViewCube(orbit,onFace={cubeDirection(it)},modifier=Modifier.size(112.dp),
                        onCorner={cubeDirection(it)},onOrbit={dx,dy->
                            if(!is3D) {is3D=true;orbitLevel=level}
                            orbit=orbit.orbit(-dx*.006*settings.gestureSensitivity,dy*.006*settings.gestureSensitivity)
                        },onGestureActive={cameraOrbiting=it},
                        onPlan={is3D=false;zoomWindow=false;window.value=null},
                        onProjection={projection->
                            if(!is3D) {is3D=true;orbitLevel=level}
                            orbit=orbit.withProjection(projection);orbitShown=true
                        })
                    CanvasActionButton(Icons.Outlined.Home,stringResource(R.string.canvas_home),{homeView()},Modifier.testTag("canvas_home"))
                }
                // Library visibility changes only through explicit panel/full-screen controls.
                if(!panelsHidden) editor?.let {actions->
                    PersistentPanel(CanvasPanelId.LIBRARY,stringResource(R.string.panel_library),canvasLayout.panel(CanvasPanelId.LIBRARY),
                        {setPanelPlacement(CanvasPanelId.LIBRARY,it)},onBoundsChanged={recordPanelBounds(CanvasPanelId.LIBRARY,it)},icon=Icons.Outlined.Inventory2) {
                        panelStates.SaveableStateProvider("library") {
                        LibraryPanel(document.libraryItems,onInsert={id->
                            cancelConstruction();pendingLibrary=id.value;actions.onSelect(emptySet());probe=false;zoomWindow=false
                            pointerWorld=if(is3D)orbit.target.copy(z=floorZ) else Point3(viewport.center.x,viewport.center.y,floorZ)
                        },onDismiss={setPanel(CanvasPanelId.LIBRARY,PanelVisibility.HIDDEN)},embedded=true,
                            state=document.libraryState,onStateChange=actions.onLibraryState,
                            units=document.project.settings.lengthUnit,decimalPlaces=document.project.settings.decimalPlaces,
                            activeItemId=pendingLibraryItem?.id)
                        }
                    }
                }
                if(floatingPanelsVisible) {
                    PersistentPanel(CanvasPanelId.VIEW,stringResource(R.string.panel_view),canvasLayout.panel(CanvasPanelId.VIEW),
                        {setPanelPlacement(CanvasPanelId.VIEW,it)},onBoundsChanged={recordPanelBounds(CanvasPanelId.VIEW,it)},width=520.dp,icon=Icons.Outlined.Visibility,compact=true) {viewToolbarContent()}
                    editor?.let {actions->
                        for(group in listOf(CanvasToolGroup.DRAW,CanvasToolGroup.EDIT)) {
                            val id=if(group==CanvasToolGroup.DRAW)CanvasPanelId.DRAW else CanvasPanelId.EDIT
                            PersistentPanel(id,stringResource(if(group==CanvasToolGroup.DRAW)R.string.panel_draw else R.string.panel_edit),canvasLayout.panel(id),
                                {setPanelPlacement(id,it)},onBoundsChanged={recordPanelBounds(id,it)},icon=if(group==CanvasToolGroup.DRAW)Icons.Outlined.Draw else Icons.Outlined.Tune) {
                                panelStates.SaveableStateProvider("tools:$group") {
                                    ToolsPanel(tool,options,document.project.settings.lengthUnit,selectedIds.size,
                                        actions.canUndo || canPointUndo,actions.canRedo || canPointRedo,actions.canPaste,points.size>=tool.minimumPoints && points.isNotEmpty(),
                                        onTool={beginTool(it)},onOptions={options=it},onFinish={finishConstruction()},onCancel={cancelConstruction()},
                                        onUndo={undoInputOrDocument()},onRedo={redoInputOrDocument()},onCopy=actions.onCopy,
                                        onPaste={cancelConstruction();actions.onPaste()},onMirrorX={actions.onMirror(MirrorAxis.X,positionLockedPivot ?: elementWorldBounds(document,selectedIds).center())},
                                        onMirrorY={actions.onMirror(MirrorAxis.Y,positionLockedPivot ?: elementWorldBounds(document,selectedIds).center())},onArray={arrayOpen=true},
                                        onDelete=actions.onDelete,onProperties={openProperties()},expandedOverride=true,focusGroup=group,embedded=true)
                                }
                            }
                        }
                        PersistentPanel(CanvasPanelId.MEASURE,stringResource(R.string.panel_measure),canvasLayout.panel(CanvasPanelId.MEASURE),
                            {setPanelPlacement(CanvasPanelId.MEASURE,it)},onBoundsChanged={recordPanelBounds(CanvasPanelId.MEASURE,it)},icon=Icons.Outlined.Straighten) {
                            MeasureTools({beginMeasurement(MeasurementMode.DISTANCE)},{beginMeasurement(MeasurementMode.REFERENCE)},
                                {setPanel(CanvasPanelId.MEASUREMENTS,PanelVisibility.OPEN)})
                        }
                        PersistentPanel(CanvasPanelId.PROPERTIES,stringResource(R.string.editing_properties),canvasLayout.panel(CanvasPanelId.PROPERTIES),
                            {setPanelPlacement(CanvasPanelId.PROPERTIES,it)},onBoundsChanged={recordPanelBounds(CanvasPanelId.PROPERTIES,it)},icon=Icons.Outlined.Tune) {
                            val source=document.elements.singleOrNull {it.id in selectedIds}
                            when {
                                selectionHasMeasurements->MeasurementsPanel(renderDocument,actions.onMeasurementsVisibility,actions.onUpdateMeasurement,actions.onDeleteElements,
                                    onDismiss={setPanel(CanvasPanelId.PROPERTIES,PanelVisibility.HIDDEN)},embedded=true)
                                selectedProperties!=null->panelStates.SaveableStateProvider("properties:$propertiesSession:${source?.id?.value}") {
                                    PropertiesSidePanel(selectedProperties,document.project.settings.lengthUnit,document.project.settings.decimalPlaces,
                                        onApply=actions.onProperties,onDismiss={setPanel(CanvasPanelId.PROPERTIES,PanelVisibility.HIDDEN)},
                                        lockMode=source?.lockMode ?: LockMode.NONE,layerLocked=document.layers.any {it.id==source?.layerId && it.locked},embedded=true,
                                        libraryProperties=selectedLibraryProperties,
                                        onApplyLibrary={transform,parameters->actions.onLibraryProperties(transform,parameters,false)},
                                        onUpdateAllOfType={transform,parameters->actions.onLibraryProperties(transform,parameters,true)},
                                        onResetToLibraryDefault=actions.onResetLibraryProperties)
                                }
                                else->Text(stringResource(R.string.presentation_select_properties),Modifier.padding(12.dp))
                            }
                        }
                        PersistentPanel(CanvasPanelId.MEASUREMENTS,stringResource(R.string.panel_saved_measurements),canvasLayout.panel(CanvasPanelId.MEASUREMENTS),
                            {setPanelPlacement(CanvasPanelId.MEASUREMENTS,it)},onBoundsChanged={recordPanelBounds(CanvasPanelId.MEASUREMENTS,it)},icon=Icons.Outlined.Straighten) {
                            MeasurementsPanel(renderDocument,actions.onMeasurementsVisibility,actions.onUpdateMeasurement,actions.onDeleteElements,
                                onDismiss={setPanel(CanvasPanelId.MEASUREMENTS,PanelVisibility.HIDDEN)},embedded=true)
                        }
                        PersistentPanel(CanvasPanelId.VISIBILITY,stringResource(R.string.panel_visibility),canvasLayout.panel(CanvasPanelId.VISIBILITY),
                            {setPanelPlacement(CanvasPanelId.VISIBILITY,it)},onBoundsChanged={recordPanelBounds(CanvasPanelId.VISIBILITY,it)},icon=Icons.Outlined.Layers) {
                            ObjectVisibilityPanel(document,actions.onLevelVisibility,{id,display->actions.onElementsDisplay(setOf(id),display)},
                                actions.onSystemVisibility,actions.onShowAll,actions.onShowHidden,actions.onAutoXray,actions.onRoomSideDisplay,
                                onLayerVisible=actions.onLayerVisibility,onMeasurementsVisible=actions.onMeasurementsVisibility)
                        }
                    }
                }
                if(editor!=null && selectedIds.isNotEmpty() && !placementActive && !zoomWindow && !cameraOrbiting && !panelsHidden) {
                    val pivot=selectedBounds?.center() ?: if(is3D)orbit.target else Point3(viewport.center.x,viewport.center.y,floorZ)
                    val at=if(is3D)orbit.project(pivot,size.width.toDouble(),size.height.toDouble())?.let {Point2(it.x,it.y)}
                        else viewport.worldToScreen(Point2(pivot.x,pivot.y),size.width.toDouble(),size.height.toDouble())
                    val occupied=panelBounds.values.toList()+listOf(Rect(0f,0f,180*density,128*density),
                        Rect(0f,(size.height-64*density).coerceAtLeast(0f),64*density,size.height.toFloat()))
                    val toolbarOffset=selectionToolbarPosition(at ?: Point2(size.width/2.0,size.height/2.0),size.width,size.height,density,occupied)
                    val selectedElements=document.elements.filter {it.id in selectedIds}
                    val layerIsLocked=selectedElements.any {e->document.layers.any {it.id==e.layerId && it.locked}}
                    SelectionToolbar(gizmoMode,!busy && !lockedSelection && selectedRoomSide==null,editor.canUndo,editor.canRedo,editor.canPaste,
                        document.layers.filter {it.visible && !it.locked},selectedElements.map {it.layerId}.distinct().singleOrNull(),
                        selectedElements.any {it.objectLocked},document.project.settings.rotationSnapDegrees,
                        onMode={gizmoMode=it;preview=null;rotationAngle=null},onCopy=editor.onCopy,onDelete=editor.onDelete,
                        onProperties={openProperties()},
                        onMirrorX={editor.onMirror(MirrorAxis.X,positionLockedPivot ?: elementWorldBounds(document,selectedIds).center())},
                        onMirrorY={editor.onMirror(MirrorAxis.Y,positionLockedPivot ?: elementWorldBounds(document,selectedIds).center())},
                        onArray={arrayOpen=true},onRotate90=editor.onRotate90,onUndo=editor.onUndo,onRedo=editor.onRedo,onPaste=editor.onPaste,
                        onLayer=editor.onSelectionLayer,onColour=editor.onSelectionColour,onLock=editor.onSelectionLocked,
                        onRotationSnap=editor.onRotationSnap,modifier=Modifier.offset {toolbarOffset},
                        keepProportions=options.keepProportions,onKeepProportions={options=options.copy(keepProportions=it)},layerLocked=layerIsLocked,
                        lockMode=selectedElements.maxByOrNull {it.lockMode.ordinal}?.lockMode ?: LockMode.NONE,
                        onLockMode=editor.onSelectionLockMode,onLockLevel={editor.onLevelLock(selectedLevel,it)},
                        display=selectedRoomSide?.let {side->selectedElements.singleOrNull()?.roomSideDisplay(side)} ?: selectedElements.firstOrNull()?.objectDisplay ?: ObjectDisplay.NORMAL,
                        onDisplay={display->selectedRoomSide?.let {editor.onRoomSideDisplay(it,display)} ?: editor.onSelectionDisplay(display)},
                        room=selectedElements.singleOrNull()?.takeIf {it.body is ElementBody.Room},selectedRoomSide=selectedRoomSide,
                        onRoomSide={selectedRoomSide=it},onHideRoomSide={editor.onRoomSideDisplay(it,ObjectDisplay.HIDDEN)},
                        onShowRoomWalls={selectedElements.singleOrNull()?.let {editor.onShowRoomWalls(it.id)}},layout=canvasLayout,
                        onPanelVisibility={id,state->setPanel(id,state)},onHideAllPanels={updateLayout(canvasLayout.copy(hideAll=it))})
                }
                if(pendingLibraryItem!=null) Surface(Modifier.align(Alignment.TopCenter).padding(8.dp).testTag("library_mounting_height"),
                    shape=MaterialTheme.shapes.small,tonalElevation=4.dp) {
                    Text(if(libraryPlacement==null)libraryPlacementResult?.exceptionOrNull()?.message
                        ?: stringResource(R.string.library_placement_needs_wall) else
                        stringResource(R.string.library_placement_mounting_height,coordinate(libraryPlacement.mountingHeight,
                            document.project.settings.lengthUnit,document.project.settings.decimalPlaces),unitSuffix(document.project.settings.lengthUnit)),
                        Modifier.padding(horizontal=12.dp,vertical=8.dp),style=MaterialTheme.typography.labelLarge)
                }
                if(precisionActive && !zoomWindow) PrecisionPointerOverlay(displayedPrecisionPick,precisionFinger,precisionDragging,
                    settings.precisionPointer,viewport,orbit,is3D,ready?.scene,ready?.surfaces,snapSettings.gridSpacing,grid,floorZ,
                    measurementPicks.firstOrNull()?.point ?: points.firstOrNull(),document.project.settings.lengthUnit,
                    document.project.settings.decimalPlaces,onPlace={confirmPrecisionPoint()},onCancel={cancelConstruction()},
                    onNudge={dx,dy->nudgePrecision(dx,dy)},enabled=!busy && geometryMatches,
                    pickContext=ready?.pointPicks,viewMode=document.project.settings.viewMode,levelId=selectedLevel,
                    canPlace=pendingLibrary==null || libraryPlacement!=null)
                CanvasActionButton(if(panelsHidden)Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    stringResource(if(panelsHidden)R.string.panel_show_panels else R.string.panel_hide_panels),
                    {updateLayout(canvasLayout.copy(hideAll=!panelsHidden))},
                    Modifier.align(if(precisionActive && settings.precisionPointer.side==PrecisionOffsetSide.ABOVE_RIGHT)Alignment.BottomEnd else Alignment.BottomStart)
                        .padding(8.dp).testTag(if(panelsHidden)"canvas_show_panels"else"canvas_hide_panels"))
                if(selectedIds.isNotEmpty() && document.elements.any {it.id in selectedIds && it.lockMode!=LockMode.NONE}) {
                    val p=selectedBounds?.center()
                    val at=p?.let {projectPrecisionPoint(it,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble())}
                    if(at!=null) Icon(Icons.Outlined.Lock,stringResource(R.string.presentation_locked),
                        Modifier.offset {IntOffset((at.x+28*density).toInt(),(at.y-32*density).toInt())}.size(20.dp).testTag("selection_padlock"),
                        tint=MaterialTheme.colorScheme.primary)
                }
                if (loading == SceneLoad.Loading) Text(stringResource(R.string.canvas_preparing), Modifier.align(Alignment.Center))
                if (rendererFailed) Text(stringResource(R.string.canvas_renderer_error), Modifier.align(Alignment.Center).padding(32.dp))
                if (loading == SceneLoad.Failed) Text(stringResource(R.string.canvas_prepare_error), Modifier.align(Alignment.Center).padding(32.dp))
            }
        }
    }
    if (snapsOpen) SnapOptions(snapSettings, onSnapSettingsChange, onDismiss = { snapsOpen = false })
    if (arrayOpen && editor != null) ArrayDialog(document.project.settings.lengthUnit,
        onApply = { columns, rows, dx, dy -> editor.onArray(columns,rows,dx,dy); arrayOpen = false },onDismiss = { arrayOpen = false })
    toolError?.let { error -> AlertDialog(onDismissRequest = { toolError = null },title = { Text(stringResource(R.string.canvas_tool_error)) },
        text = { Text(error) },confirmButton = { TextButton({ toolError = null }) { Text(stringResource(R.string.close)) } }) }
}

@Composable
private fun CanvasStatus(
    document: ProjectDocument, is3D: Boolean, orbit: OrbitCamera, cursor: State<SnapResult?>,
    selectedLevel: String?, onUnitsChange: (LengthUnit) -> Unit, onLevel: (String?) -> Unit, onSnaps: () -> Unit,
) {
    var unitsOpen by remember { mutableStateOf(false) }
    var levelsOpen by remember { mutableStateOf(false) }
    val unit = document.project.settings.lengthUnit
    val p = if (is3D) orbit.target else cursor.value?.point?.let { Point3(it.x,it.y,document.levels.find { level -> level.id.value==selectedLevel }?.elevation ?: 0.0) } ?: Point3()
    val precision = document.project.settings.decimalPlaces
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).heightIn(min = 48.dp).testTag("canvas_status"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (is3D) R.string.canvas_target else R.string.canvas_coordinates) + ": " +
                "X ${coordinate(p.x,unit,precision)}  Y ${coordinate(p.y,unit,precision)}  Z ${coordinate(p.z,unit,precision)}",
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp))
            TextButton(onSnaps) { Text(
                if (!document.project.settings.snap.enabled) stringResource(R.string.canvas_snap_off)
                else if(is3D)stringResource(R.string.canvas_snaps)
                else cursor.value?.mode?.let { stringResource(snapLabel(it)) } ?: stringResource(R.string.canvas_snaps),
            ) }
            Box {
                TextButton({ unitsOpen = true }, modifier = Modifier.testTag("canvas_units")) {
                    Text(stringResource(R.string.canvas_units) + ": " + stringResource(lengthUnitLabel(unit)))
                }
                DropdownMenu(unitsOpen, { unitsOpen = false }) {
                    LengthUnit.entries.forEach { item -> DropdownMenuItem(
                        text = { Text(stringResource(lengthUnitLabel(item))) }, onClick = { unitsOpen = false; onUnitsChange(item) },
                    ) }
                }
            }
            Box {
                TextButton({ levelsOpen = true }, modifier = Modifier.testTag("canvas_level")) {
                    Text(stringResource(R.string.canvas_level) + ": " + (document.levels.find { it.id.value==selectedLevel }?.name ?: stringResource(R.string.canvas_all_levels)))
                }
                DropdownMenu(levelsOpen, { levelsOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.canvas_all_levels)) }, onClick = { levelsOpen = false; onLevel(null) })
                    document.levels.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { levelsOpen = false; onLevel(item.id.value) }) }
                }
            }
        }
    }
}

@Composable
private fun SnapOptions(settings: SnapSettings, onChange: (SnapSettings) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.canvas_snaps)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.canvas_snap_hint))
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(settings.enabled, role=Role.Switch, onValueChange={onChange(settings.copy(enabled=it))}), verticalAlignment=Alignment.CenterVertically) {
                    Switch(settings.enabled, onCheckedChange=null)
                    Text(stringResource(R.string.canvas_snap_on), Modifier.padding(start=8.dp))
                }
                SnapMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(mode in settings.modes, role=Role.Checkbox,
                        onValueChange={enabled -> onChange(settings.copy(modes=if(enabled)settings.modes+mode else settings.modes-mode))}), verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(mode in settings.modes, onCheckedChange=null)
                        Text(stringResource(snapLabel(mode)), Modifier.padding(start=8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
internal fun snapLabel(mode: SnapMode): Int = when(mode) {
    SnapMode.GRID -> R.string.canvas_snap_grid
    SnapMode.ENDPOINT -> R.string.canvas_snap_endpoint
    SnapMode.MIDPOINT -> R.string.canvas_snap_midpoint
    SnapMode.INTERSECTION -> R.string.canvas_snap_intersection
    SnapMode.PERPENDICULAR -> R.string.canvas_snap_perpendicular
    SnapMode.ORTHO -> R.string.canvas_snap_ortho
}
