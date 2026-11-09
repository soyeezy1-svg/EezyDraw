package com.eezydraw.ui.canvas
import com.eezydraw.ui.developer.*
import androidx.compose.foundation.gestures.detectTapGestures

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.nativeCanvas
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
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
        val inspectionKey:String?=null,
        val contactShadows:SurfaceScene?=null,
    ) : SceneLoad
    data object Failed : SceneLoad
}

private fun sameCanvasGeometry(a: ProjectDocument, b: ProjectDocument): Boolean =
    a.elements == b.elements && a.layers == b.layers && a.systems == b.systems && a.libraryItems == b.libraryItems &&
        a.materials == b.materials && a.levels == b.levels && a.project.settings.showMeasurements == b.project.settings.showMeasurements && a.project.settings.showCeilings==b.project.settings.showCeilings &&
        a.project.settings.wallServices==b.project.settings.wallServices && a.project.settings.floorServices==b.project.settings.floorServices && a.project.settings.viewMode==b.project.settings.viewMode && a.project.settings.hiddenLevelIds==b.project.settings.hiddenLevelIds && a.project.settings.showHiddenObjects==b.project.settings.showHiddenObjects &&
        a.project.settings.lengthUnit == b.project.settings.lengthUnit &&
        a.project.settings.angleFormat == b.project.settings.angleFormat &&
        a.project.settings.decimalPlaces == b.project.settings.decimalPlaces

private enum class MeasurementMode { NONE, DISTANCE, REFERENCE }
private data class MeasurementPick(val point: Point3,val elementId: ElementId? = null)
private data class PointInputSnapshot(val points:List<Point3>,val measurements:List<MeasurementPick>,val anchor:Point2?,val candidate:PrecisionPick?,val routes:List<RoutePick> = emptyList())
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
    onTakeoffSettings:(TakeoffSettings)->Unit={},
    onTakeoffRate:(String,Set<LibraryItemId>,Double?,Boolean)->Unit={_,_,_,_->},
    onShareSchedule:(ScheduleDefinition,Boolean,com.eezydraw.storage.ScheduleFormat)->Unit={_,_,_->},
    onSaveIssue:(Issue)->Unit={},onDeleteIssue:(Issue)->Unit={},
    onIssuePhoto:(Issue,Boolean)->Unit={_,_->},onShareIssue:(Issue,String)->Unit={_,_->},onIssueExport:(String)->Unit={},
    onIssueScreenshot:(Issue,List<IssueMarkup>,android.graphics.Bitmap)->Unit={_,_,_->},
    clashProgress:Float?=null,
    clashSummary:String?=null,
    onRunClash:(ClashCheckOptions)->Unit={},
    onCancelClash:()->Unit={},
    onClashStatus:(IssueId,ClashStatus,String)->Unit={_,_,_->},
    onExportDrawing:(ViewExportRequest)->Unit={},
    onExportModel:(String?,Boolean)->Unit={_,_->},
    onExportView: (ViewExportRequest,ViewExportFormat)->Unit = { _,_-> },
    onDiscardExit:()->Unit=onBack,onOpenSettings:()->Unit={},onNavigationSettings:(AppSettings)->Unit={},
    onSaveCameraView:(SavedView)->Unit={},onDeleteCameraView:(ViewId)->Unit={},
    benchmark:StressRun?=null,onBenchmarkComplete:(StressResult)->Unit={},onDismissBenchmark:()->Unit={},
) {
    var viewport by rememberSaveable(document.project.id.value, stateSaver = PlanCameraSaver) { mutableStateOf(Viewport2D()) }
    var orbit by rememberSaveable(document.project.id.value, stateSaver = OrbitCameraSaver) { mutableStateOf(OrbitCamera()) }
    var is3D by rememberSaveable(document.project.id.value) { mutableStateOf(false) }
    var initialized by rememberSaveable(document.project.id.value) { mutableStateOf(false) }
    var orbitShown by rememberSaveable(document.project.id.value) { mutableStateOf(false) }
    var gizmoMode by rememberSaveable(document.project.id.value) { mutableStateOf(GizmoMode.MOVE) }
    var graphicsStats by remember {mutableStateOf(RenderDiagnostics())}
    var graphicsClock by remember {mutableLongStateOf(System.nanoTime())}
    LaunchedEffect(settings.graphics.showFrameRate) {
        while(settings.graphics.showFrameRate){kotlinx.coroutines.delay(500);graphicsClock=System.nanoTime()}
    }
    var cameraOrbiting by remember {mutableStateOf(false)}
    var rotationAngle by remember {mutableStateOf<Double?>(null)}
    var level by rememberSaveable(document.project.id.value) { mutableStateOf(document.levels.firstOrNull()?.id?.value) }
    var orbitLevel by rememberSaveable(document.project.id.value) { mutableStateOf<String?>(null) }
    var anchorX by rememberSaveable(document.project.id.value) { mutableStateOf<Double?>(null) }
    var anchorY by rememberSaveable(document.project.id.value) { mutableStateOf<Double?>(null) }
    var zoomWindow by rememberSaveable { mutableStateOf(false) }
    var probe by rememberSaveable { mutableStateOf(false) }
    var navigationTool by rememberSaveable(document.project.id.value) {mutableStateOf(NavigationTool.SELECT)}
    var planRotationDialog by remember {mutableStateOf(false)}
    var navigationPreferences by remember {mutableStateOf(false)}
    var viewsOpen by remember {mutableStateOf(false)}
    var sectionOptions by remember {mutableStateOf(false)}
    var sectionBox by rememberSaveable(document.project.id.value,stateSaver=listSaver<Bounds3?,Double>(save={it?.let {b->listOf(b.min.x,b.min.y,b.min.z,b.max.x,b.max.y,b.max.z)} ?: emptyList()},restore={if(it.size==6)Bounds3(Point3(it[0],it[1],it[2]),Point3(it[3],it[4],it[5]))else null})) {mutableStateOf<Bounds3?>(null)}
    var closeConfirmation by remember {mutableStateOf(false)}
    var exitAfterSave by remember {mutableStateOf(false)}
    var chromeHeight by remember {mutableIntStateOf(0)}
    var libraryLaunch by remember {mutableStateOf<String?>(null)}
    var selectionMenu by rememberSaveable {mutableStateOf<String?>(null)}
    var selectionToolbarVisible by rememberSaveable {mutableStateOf(true)}
    var activeDetail by rememberSaveable(document.project.id.value) {mutableStateOf<CanvasPanelId?>(null)}
    var snapsOpen by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var rendererFailed by remember(document.project.id.value) { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var loading: SceneLoad by remember(document.project.id.value) { mutableStateOf(SceneLoad.Loading) }
    var prepared: SceneLoad.Ready? by remember(document.project.id.value) { mutableStateOf(null) }
    var buildingTool by remember {mutableStateOf<String?>(null)}
    var generateWallsDialog by remember {mutableStateOf(false)}
    var wallOptions by remember {mutableStateOf(RoomWallOptions(document.project.settings.defaultWallThickness,document.project.settings.defaultWallHeight))}
    var automaticWalls by remember {mutableStateOf(settings.projectDefaults.autoCreateWalls)}
    LaunchedEffect(settings.projectDefaults.autoCreateWalls){automaticWalls=settings.projectDefaults.autoCreateWalls}
    var floorScheduleOpen by remember {mutableStateOf(false)}
    var wallSchedule by remember {mutableStateOf(false)}
    var libraryWallOptions by remember {mutableStateOf<WallServiceOptions?>(null)}
    var routeKind by remember(document.project.id) {mutableStateOf<RouteKind?>(null)}
    var routeDraft by remember(document.project.id) {mutableStateOf(emptyList<RoutePick>())}
    var routeConfig by remember(document.project.id) {mutableStateOf(RoutingSettings())}
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
    val loupeObstacles = remember { LoupeObstacles() }
    var canvasBounds by remember { mutableStateOf(Rect.Zero) }
    var previousLoupe by remember { mutableStateOf<PointerRect?>(null) }
    var loupeCapture by remember { mutableStateOf<CapturedLoupe?>(null) }
    var precisionSnap by remember {mutableStateOf<PrecisionSnapKey?>(null)}
    var pointUndo by remember(document.project.id) {mutableStateOf(emptyList<PointInputSnapshot>())}
    var pointRedo by remember(document.project.id) {mutableStateOf(emptyList<PointInputSnapshot>())}
    var pendingLibrary by rememberSaveable(document.project.id.value) {mutableStateOf<String?>(null)}
    var placedToken by remember {mutableIntStateOf(0)}
    var placedFeedback by remember {mutableStateOf(false)}
    var addSelection by rememberSaveable { mutableStateOf(false) }
    var propertiesSession by rememberSaveable { mutableIntStateOf(0) }
    var canvasLayout by remember(document.project.id) {mutableStateOf(document.project.settings.canvasLayout)}
    var ceilingRoomDialog by remember(document.project.id){mutableStateOf<ElementId?>(null)}
    var ceilingLevelDialog by remember(document.project.id){mutableStateOf<LevelId?>(null)}
    var selectedRoomSide by remember(document.project.id) {mutableStateOf<RoomSideRef?>(null)}
    LaunchedEffect(document.project.settings.canvasLayout) {canvasLayout=document.project.settings.canvasLayout}
    val panelsHidden=canvasLayout.hideAll
    fun updateLayout(next:CanvasLayout) {if(next.openGroup!=canvasLayout.openGroup){activeDetail=null;viewsOpen=false;sectionOptions=false};canvasLayout=next;editor?.onCanvasLayout?.invoke(next)}
    fun setPanel(id:CanvasPanelId,state:PanelVisibility) {
        if(state==PanelVisibility.OPEN){val group=when(id){CanvasPanelId.DRAW->CanvasGroup.ROUTE;CanvasPanelId.EDIT,CanvasPanelId.PROPERTIES->CanvasGroup.EDIT;CanvasPanelId.VIEW->CanvasGroup.VIEW;CanvasPanelId.MEASURE,CanvasPanelId.MEASUREMENTS->CanvasGroup.MEASURE;CanvasPanelId.LIBRARY->CanvasGroup.LIBRARY;CanvasPanelId.VISIBILITY->CanvasGroup.LAYERS;CanvasPanelId.CLASHES,CanvasPanelId.ISSUES->CanvasGroup.ISSUES;CanvasPanelId.SCHEDULES->CanvasGroup.SCHEDULE}
            updateLayout(canvasLayout.copy(openGroup=group,bottomBarVisible=true));activeDetail=id
        }else if(activeDetail==id)activeDetail=null
    }
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
    var issueEdit by remember {mutableStateOf<Issue?>(null)}
    var issueMarkup by remember {mutableStateOf<Issue?>(null)}
    var issueView by remember {mutableStateOf<IssueView?>(null)}
    var issuePicking by remember {mutableStateOf(false)}
    var scheduleFocus by remember(document.project.id) {mutableStateOf(emptySet<ElementId>())}
    var clashDialog by remember {mutableStateOf(false)}
    var clashFocus by remember(document.project.id) {mutableStateOf<IssueId?>(null)}
    var clashXray by remember {mutableStateOf(true)}
    val focusClash=document.issues.find {it.id==clashFocus}?.clash
    val inspectionKey=(focusClash?.let {"${clashFocus?.value}:$clashXray"} ?: scheduleFocus.takeIf {it.isNotEmpty()}?.joinToString {it.value}).orEmpty()+sectionBox.toString()
    val renderDocument=remember(document,settings.showDetailedMeasurement,inspectionKey,issueView) {clashInspection(scheduleInspection(restoreIssuePresentation(document,issueView),scheduleFocus).copy(project=document.project.copy(
        settings=(issueView?.settings ?: document.project.settings).copy(showDetailedMeasurements=settings.showDetailedMeasurement))),focusClash,clashXray)}
    val selectedIds = editor?.selectedIds ?: emptySet()
    val density = LocalDensity.current.density
    val haptics=LocalHapticFeedback.current
    val selectedLevel = if (is3D) orbitLevel else level
    val floorZ = document.levels.find { it.id.value == selectedLevel }?.elevation ?: 0.0
    val constructionZ=if(routeKind!=null)floorRoutingElevation(document,routeConfig,selectedLevel?.let(::LevelId),pointerWorld ?: Point3())else floorZ
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
    val propertiesOpen=activeDetail==CanvasPanelId.PROPERTIES
    val selectedElement=remember(document.elements,selectedIds) {
        document.elements.singleOrNull {it.id in selectedIds}
    }
    val selectedProperties=remember(document,selectedElement,propertiesOpen) { selectedElement?.takeIf {propertiesOpen}?.let {readProperties(document,it)} }
    val selectedLibraryProperties=remember(document,selectedElement) {
        selectedElement?.takeIf {it.body is ElementBody.Component}?.let {readLibraryProperties(document,it)}
    }
    val deviceCatalog=editor?.libraryCatalog ?: LibraryCatalog()
    val pendingLibraryItem=remember(document.libraryItems,pendingLibrary,deviceCatalog) {
        pendingLibrary?.let {id->deviceCatalog.find(LibraryItemId(id),document.libraryItems)?.let {libraryPlacementDefinition(document,it)}}
    }
    val libraryAim=precisionPick?.point ?: pointerWorld ?: Point3(viewport.center.x,viewport.center.y,floorZ)
    val libraryPlacementResult=remember(document,pendingLibraryItem,libraryAim,selectedLevel,libraryWallOptions) {
        pendingLibraryItem?.let {item->runCatching {
            previewLibraryPlacement(libraryPreviewDocument(document),item,libraryAim,selectedLevel?.let(::LevelId),wallOptions=libraryWallOptions)
        }}
    }
    val libraryPlacement=libraryPlacementResult?.getOrNull()
    // Keep the raw aim for recomputing attachment; show the resolved insertion or fitting port.
    val displayedPrecisionPick=if(pendingLibraryItem!=null && libraryPlacement!=null)
        resolvedLibraryPrecisionPick(precisionPick,libraryPlacement,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble())
        else precisionPick
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
    val needsSurfaces = issueView!=null || focusClash!=null || document.project.settings.viewMode != ViewMode.WIREFRAME || measurementMode != MeasurementMode.NONE ||
        document.project.settings.autoXrayFrontWalls || document.elements.any {element->element.objectDisplay==ObjectDisplay.XRAY ||
            element.properties.any {(key,value)->key.startsWith(ROOM_SIDE_DISPLAY_PREFIX) && (value as? ParameterValue.Text)?.value==ObjectDisplay.XRAY.name}}
    val placementActive=routeKind!=null || tool!=DrawingTool.SELECT || measurementMode!=MeasurementMode.NONE || pendingLibrary!=null
    val latestPlacementActive=rememberUpdatedState(placementActive)
    val precisionActive=placementActive && settings.precisionPointer.enabled && navigationTool==NavigationTool.SELECT
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
        document.project.settings.showCeilings,document.project.settings.wallServices,document.project.settings.floorServices,document.project.settings.viewMode,document.project.settings.showMeasurements,document.project.settings.hiddenLevelIds,document.project.settings.showHiddenObjects,
        settings.graphics.contactShadows,settings.showDetailedMeasurement,selectedIds,needsSurfaces,is3D,inspectionKey,issueView,sectionBox) {
        try {
            val previous = prepared
            val compiled = withContext(Dispatchers.Default) {
                val planSymbols=!is3D
                val sameGeometry = previous != null && issueView==null && previous.inspectionKey==inspectionKey && sameCanvasGeometry(previous.document,document) && previous.detailedMeasurements==settings.showDetailedMeasurement && previous.planSymbols==planSymbols
                val scene = if (sameGeometry) previous!!.scene else SectionClip.drawing(buildDrawingScene(renderDocument,planSymbols=planSymbols),sectionBox)
                val index = SpatialIndex(scene.segments)
                val allIds=document.elements.mapTo(HashSet()) {it.id}
                val mainIds=allIds-selectedIds
                val main = if (selectedIds.isEmpty()) scene else SectionClip.drawing(buildDrawingScene(renderDocument,mainIds,planSymbols),sectionBox)
                val selected = if (selectedIds.isEmpty()) null else SectionClip.drawing(buildDrawingScene(renderDocument,selectedIds,planSymbols),sectionBox)
                val surfaces=if(sameGeometry && previous!!.surfaces!=null) previous.surfaces else if(needsSurfaces)SectionClip.surfaces(buildSurfaceScene(renderDocument,planView=planSymbols),sectionBox) else null
                val mainSurfaces=surfaces?.let {if(selectedIds.isEmpty())it else it.partition(selectedIds,false)}
                val selectedSurfaces=if(selectedIds.isEmpty())null else surfaces?.partition(selectedIds,true)
                val surfacedIds=surfaces?.chunks.orEmpty().mapTo(HashSet()) {ElementId(it.elementId)}
                val annotationIds=allIds-surfacedIds+if(planSymbols)document.elements.filter {it.body is ElementBody.Component}.map {it.id} else emptyList()
                val annotations=if(surfaces==null)main else SectionClip.drawing(buildDrawingScene(renderDocument,annotationIds-selectedIds,planSymbols),sectionBox)
                val selectedAnnotations=if(selectedIds.isEmpty())null else if(surfaces==null)selected else SectionClip.drawing(buildDrawingScene(renderDocument,annotationIds.intersect(selectedIds),planSymbols),sectionBox)
                val dimensionIds=document.elements.filter {((it.body as? ElementBody.DrawingShape)?.shape is Shape.Dimension)}.mapTo(HashSet()){it.id}
                val measurementScene=SectionClip.drawing(buildDrawingScene(renderDocument,dimensionIds-selectedIds),sectionBox)
                val selectedMeasurements=if(selectedIds.isEmpty())null else SectionClip.drawing(buildDrawingScene(renderDocument,dimensionIds.intersect(selectedIds)),sectionBox)
                val resize = if (sameGeometry && previous!!.selectedIds == selectedIds) previous.resizeFrame else
                    if (selectedIds.size == 1) document.elements.find { it.id in selectedIds && !isMeasurement(it) }?.let { resizeFrame(document,it) } else null
                SceneLoad.Ready(document, selectedIds, scene, main, selected,
                    if (sameGeometry) previous!!.snaps else SnapEngine(index),
                    if (sameGeometry) previous!!.picker else EditorPicker(renderDocument, scene, index,planView=planSymbols),resize,
                    MoveContext(document,selectedIds,scene),surfaces,mainSurfaces,selectedSurfaces,annotations,selectedAnnotations,measurementScene,selectedMeasurements,settings.showDetailedMeasurement,
                    if(sameGeometry)previous!!.pointPicks else PointPickContext(document,scene),
                    if(sameGeometry)previous!!.componentReferences else document.elements.filter {it.body is ElementBody.Component}.associate {it.id to it.transform.translation},planSymbols,inspectionKey,
                    if(is3D && settings.graphics.contactShadows) {
                        if(sameGeometry && previous?.contactShadows!=null)previous.contactShadows else SectionClip.surfaces(buildContactShadows(renderDocument),sectionBox)
                    }else null)
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
    fun inputSnapshot()=PointInputSnapshot(points,measurementPicks,anchor,precisionPick,routeDraft)
    fun clearPrecisionCandidate() {precisionPick=null;precisionFinger=null;precisionDragging=false;precisionSnap=null}
    fun retainInput(before:PointInputSnapshot) {
        pointUndo=(pointUndo+before).takeLast(settings.undoLimit);pointRedo=emptyList()
    }
    fun applyPrecisionPick(pick:PrecisionPick,feedback:Boolean=true) {
        val resolved=if(routeKind!=null) {
            val draft=runCatching {appendRoutingPick(document,routeKind!!,routeDraft,pick.point,routeConfig,document.levels.find {it.id.value==selectedLevel})}.getOrNull()
            if(draft.isNullOrEmpty()){clearPrecisionCandidate();return}
            val next=draft.last().point
            val snap=snapRoute(document,routeKind!!,next)
            val screen=projectPrecisionPoint(snap.point,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble()) ?: pick.screen
            pick.copy(point=snap.point,screen=screen)
        } else pick
        precisionPick=resolved;pointerWorld=resolved.point
        cursor.value=SnapResult(Point2(pick.point.x,pick.point.y),pick.mode,pick.elementId?.value)
        val key=if(pick.mode==null && !pick.wallEdge && pick.elementId==null)null else PrecisionSnapKey(pick.mode,pick.elementId,pick.wallEdge,
            pick.point.takeIf {pick.mode in setOf(SnapMode.GRID,SnapMode.ENDPOINT,SnapMode.MIDPOINT,SnapMode.INTERSECTION) || pick.elementId!=null && !pick.wallEdge && pick.mode==null})
        if(feedback && key!=null && key!=precisionSnap && settings.precisionPointer.hapticFeedback)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        precisionSnap=key
    }
    fun restoreInput(snapshot:PointInputSnapshot) {
        routeDraft=snapshot.routes;points=snapshot.points;measurementPicks=snapshot.measurements
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
        buildingTool=null;routeKind=null;routeDraft=emptyList();points = emptyList(); preview = null; waitingForCommit = null
        pointUndo=emptyList();pointRedo=emptyList();clearPrecisionCandidate()
        measurementPicks=emptyList();measurementMode=MeasurementMode.NONE
        pendingLibrary=null;tool=DrawingTool.SELECT;rotationAngle=null
        anchorX = null; anchorY = null
    }
    fun placed(library:Boolean=false) {
        pointUndo=emptyList();pointRedo=emptyList();clearPrecisionCandidate()
        routeDraft=emptyList();points=emptyList();measurementPicks=emptyList();anchorX=null;anchorY=null
        gizmoMode=GizmoMode.MOVE
        placedToken++
        if(library || !settings.keepToolActiveAfterPlacing) {routeKind=null;tool=DrawingTool.SELECT;measurementMode=MeasurementMode.NONE;pendingLibrary=null}
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
        if(buildingTool!=null) {
            val mode=buildingTool!!
            if(editor?.onCreateBuilding?.invoke(mode,picks,selectedLevel,wallOptions,automaticWalls)==true) {
                val last=picks.last();placed()
                if(mode=="wall"){buildingTool=mode;tool=DrawingTool.LINE;points=listOf(last);anchorX=last.x;anchorY=last.y}
                else buildingTool=null
            }
            return
        }
        if(routeKind!=null) {
            if(editor?.onCreateRoute?.invoke(routeKind!!,routeDraft)==true)placed()
            return
        }
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
        if(actions!=null && !busy && routeKind!=null) {
            val raw=confirmed?.point ?: pointerWorld
            val prior=inputSnapshot()
            if(raw!=null)try {
                val next=appendRoutingPick(document,routeKind!!,routeDraft,raw,routeConfig,document.levels.find {it.id.value==selectedLevel})
                val last=next.last()
                val captured=snapRoute(document,routeKind!!,last.point)
                routeDraft=if(routeConfig.wallOptions!=null)next else next.dropLast(1)+last.copy(point=captured.point)
                points=routeDraft.map {it.point};anchorX=points.last().x;anchorY=points.last().y
                if(!precisionActive && prior.points!=points)retainInput(prior)
            } catch(error:IllegalArgumentException){toolError=error.message}
        } else if(actions!=null && !busy && pendingLibrary!=null) {
            val at=confirmed?.point ?: pointerWorld ?: Point3(viewport.center.x,viewport.center.y,floorZ)
            if((!precisionActive || libraryPlacement!=null) && (libraryWallOptions?.let {actions.onInsertWallLibrary(LibraryItemId(pendingLibrary!!),at,selectedLevel,it)} ?: actions.onInsertLibrary(LibraryItemId(pendingLibrary!!),at,selectedLevel)))placed(library=true)
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
            val picked = if(is3D && sectionBox!=null && ready!=null)pickSceneHit(ready.scene,ready.surfaces,CanvasFrame(viewport,orbit,true,selectedLevel,floorElevation=floorZ,sectionBox=sectionBox),position.x.toDouble(),position.y.toDouble(),size.width.toDouble(),size.height.toDouble(),14.0*density)?.elementId else if (is3D) picker?.pick3D(orbit, position.x.toDouble(), position.y.toDouble(),
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
                    if (buildingTool==null && options.useDefaultDimensions && supportsDefaultPlacement(latestTool.value) ||
                        latestTool.value.autoFinishPoints == picks.size) finishConstruction(picks)
                }
            }
        } else if (!is3D) cursor.value?.point?.let { anchorX = it.x; anchorY = it.y }
    })
    fun fitMargin():Double=maxOf(48.0*density,minOf(maxOf(128.0*density,chromeHeight+36.0*density),minOf(size.width,size.height)*.30))
    fun fitOrbit(camera:OrbitCamera,bounds:Bounds3):OrbitCamera {
        val factor=(1-2*fitMargin()/minOf(size.width,size.height).coerceAtLeast(1)).coerceIn(.05,1.0)
        return camera.fit(bounds,size.width.toDouble()/size.height.coerceAtLeast(1)).zoom(factor)
    }
    fun fit() {
        if (!geometryMatches) return
        val scene = ready?.scene ?: return
        if (size.width <= 0 || size.height <= 0) return
        val bounds = if(scene.segments.isEmpty())Bounds3(Point3(-2.0,-2.0,floorZ),Point3(2.0,2.0,floorZ+2.0)) else scene.boundsForLevel(selectedLevel)
        if (is3D) orbit = fitOrbit(orbit,bounds)
        else viewport = viewport.fit(bounds, size.width.toDouble(), size.height.toDouble(), fitMargin())
    }
    LaunchedEffect(ready, size, selectedLevel) {
        if (!initialized && ready != null && size.width > 0 && size.height > 0) {
            val emptyBounds = Bounds3(Point3(-2.0,-2.0,floorZ),Point3(2.0,2.0,floorZ+2.0))
            val planBounds = if (ready.scene.segments.isEmpty()) emptyBounds else ready.scene.boundsForLevel(level)
            viewport = viewport.fit(planBounds, size.width.toDouble(), size.height.toDouble(), fitMargin())
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
            pointerWorld=cursor.value?.point?.let {Point3(it.x,it.y,constructionZ)}
        } else if(size.width>0 && size.height>0 && placementActive) {
            val point=orbit.rayToPlaneZ(position.x.toDouble(),position.y.toDouble(),size.width.toDouble(),size.height.toDouble(),constructionZ)
                ?: viewPlanePoint(orbit,position.x.toDouble(),position.y.toDouble(),size.width.toDouble(),size.height.toDouble())
            val wallPick=if(routeKind!=null && routeConfig.wallOptions!=null || pendingLibraryItem?.mounting==MountingType.WALL)WallServices.pick(document,Point2(position.x.toDouble(),position.y.toDouble()),orbit,size.width.toDouble(),size.height.toDouble(),selectedLevel?.let(::LevelId)) else null
            pointerWorld=wallPick?.point ?: if(measurementMode==MeasurementMode.NONE)point.copy(z=constructionZ) else point
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
                var picked=context.pick(target,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble(),constructionZ,
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
                if((routeKind!=null && routeConfig.wallOptions!=null || pendingLibraryItem?.mounting==MountingType.WALL) && is3D) {
                    WallServices.pick(document,target,orbit,size.width.toDouble(),size.height.toDouble(),selectedLevel?.let(::LevelId))?.let {wall->picked=picked.copy(point=wall.point)}
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
    LaunchedEffect(deviceCatalog) {
        if(pendingLibrary?.let {deviceCatalog.find(LibraryItemId(it),document.libraryItems)==null}==true) cancelConstruction()
    }
    LaunchedEffect(document.elements,document.layers,document.systems,document.libraryItems,selectedLevel,snapSettings,settings.precisionPointer.enabled) {
        // A pending snap must never confirm a host that was hidden/removed while the tool stayed active.
        clearPrecisionCandidate()
        pointUndo=pointUndo.map {it.copy(candidate=null)};pointRedo=pointRedo.map {it.copy(candidate=null)}
    }
    fun beginTool(next:DrawingTool) {
        cancelConstruction();tool=next;navigationTool=NavigationTool.SELECT;probe=false;zoomWindow=false;gizmoMode=GizmoMode.MOVE
        if(next!=DrawingTool.SELECT) {
            is3D=false;editor?.onSelect?.invoke(emptySet())
            pointerWorld=Point3(viewport.center.x,viewport.center.y,floorZ)
            cursor.value=SnapResult(viewport.center,null)
        }
    }
    fun beginMeasurement(mode:MeasurementMode) {
        cancelConstruction();navigationTool=NavigationTool.SELECT;measurementMode=mode;editor?.onSelect?.invoke(emptySet());probe=false
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
        if(routeKind!=null && routeDraft.size>=2)finishConstruction()
        else if(tool!=DrawingTool.SELECT && points.size>=tool.minimumPoints && points.isNotEmpty())finishConstruction()
        else if(!precisionActive && measurementPicks.size>=2)commitMeasurement(measurementPicks,pointerWorld ?: measurementPicks.last().point)
        else cancelConstruction()
    }
    val liveRoutePicks=if(routeKind!=null)pointerWorld?.let {runCatching {appendRoutingPick(document,routeKind!!,routeDraft,it,routeConfig,document.levels.find {l->l.id.value==selectedLevel})}.getOrNull()} ?: routeDraft else emptyList()
    val liveRouteDocument=remember(document,routeKind,liveRoutePicks) {routeKind?.let {kind->runCatching {ServiceRouting.create(document,kind,liveRoutePicks).preview(document)}.getOrNull()}}
    val liveRouteLength=liveRoutePicks.map {it.point}.zipWithNext().sumOf {(a,b)->kotlin.math.sqrt((a.x-b.x)*(a.x-b.x)+(a.y-b.y)*(a.y-b.y)+(a.z-b.z)*(a.z-b.z))}
    val liveFittingCount=liveRouteDocument?.elements?.count {e->ROUTING_OWNER in e.properties && document.elements.none {it.id==e.id && it==e}} ?: 0
    val activeLabel=when {
        buildingTool!=null->if(buildingTool=="wall")"Wall"else if(buildingTool=="slab")"Floor slab"else "Room"
        routeKind!=null->routeKind!!.name.replace('_',' ')
        pendingLibrary!=null->pendingLibraryItem?.name ?: "Library item"
        measurementMode==MeasurementMode.DISTANCE->stringResource(R.string.panel_measure_distance)
        measurementMode==MeasurementMode.REFERENCE->stringResource(R.string.panel_reference_dimension)
        tool!=DrawingTool.SELECT->stringResource(drawingToolLabel(tool))
        else->""
    }
    val accent = MaterialTheme.colorScheme.primary
    val activeBenchmark=benchmark?.takeIf {it.projectId==document.project.id}
    var benchmarkOrbit by remember {mutableStateOf(OrbitCamera())}
    val safeInsets=WindowInsets.safeDrawing
    val layoutDirection=LocalLayoutDirection.current
    val safeRect=PointerRect(safeInsets.getLeft(LocalDensity.current,layoutDirection).toDouble(),
        safeInsets.getTop(LocalDensity.current).toDouble(),
        size.width-safeInsets.getRight(LocalDensity.current,layoutDirection).toDouble(),
        size.height-safeInsets.getBottom(LocalDensity.current).toDouble())
    val loupeBounds=if(precisionActive && precisionDragging && !zoomWindow && settings.precisionPointer.magnifier)
        displayedPrecisionPick?.let {pick->precisionLoupeBounds(precisionFinger,pick.screen,size.width.toDouble(),size.height.toDouble(),density.toDouble(),
            occupied=loupeObstacles.bounds.values.map {r->PointerRect((r.left-canvasBounds.left).toDouble(),(r.top-canvasBounds.top).toDouble(),
                (r.right-canvasBounds.left).toDouble(),(r.bottom-canvasBounds.top).toDouble())},safe=safeRect,previous=previousLoupe)} else null
    SideEffect {if(previousLoupe!=loupeBounds)previousLoupe=loupeBounds}
    val loupeSource=loupeBounds?.let {LoupeSource(displayedPrecisionPick!!.screen,it.width,settings.precisionPointer.magnifierZoom)}
    val clippedOrbit=ready?.scene?.boundsForLevel(selectedLevel)?.let {b->orbit.withSceneClipping(renderClipBounds(b,ready.selectionScene?.bounds,preview))} ?: orbit
    val frame = CanvasFrame(viewport, clippedOrbit, is3D, selectedLevel, snapSettings.gridSpacing, grid,
        MaterialTheme.colorScheme.background.luminance() < .5f, floorZ, ready?.selectionScene, preview,
        surfaceScene=ready?.mainSurfaces,selectionSurfaceScene=ready?.selectedSurfaces,viewMode=renderDocument.project.settings.viewMode,
        annotationScene=ready?.annotationScene,selectionAnnotationScene=ready?.selectedAnnotationScene,
        measurementScene=ready?.measurementScene,selectionMeasurementScene=ready?.selectedMeasurementScene,
        autoXrayFrontWalls=renderDocument.project.settings.autoXrayFrontWalls,loupe=loupeSource,sectionBox=sectionBox,graphics=settings.graphics,quality=settings.renderQuality,contactShadowScene=ready?.contactShadows,motionActive=cameraOrbiting || navigationTool==NavigationTool.WALK,performanceCounter=activeBenchmark?.takeIf {it.result==null}?.counter)
    StressBenchmarkEffect(activeBenchmark,geometryMatches && initialized && !busy && ready!=null,
        onPrepare={cancelConstruction();level=null;orbitLevel=null;is3D=true;benchmarkOrbit=orbit.preset(ViewPreset.ISOMETRIC).fit(ready!!.scene.bounds,size.width.toDouble()/size.height.coerceAtLeast(1));orbit=benchmarkOrbit},
        onOrbit={orbit=benchmarkOrbit.copy(yaw=benchmarkOrbit.yaw+it*PI/2)},onComplete=onBenchmarkComplete)
    val issueRequest=com.eezydraw.storage.ViewExportRequest(renderDocument,frame,size.width.coerceAtLeast(1),size.height.coerceAtLeast(1))
    fun freshIssue(position:Point3,links:Set<ElementId> = emptySet(),source:Issue?=null):Issue = Issue(IssueId(java.util.UUID.randomUUID().toString()),document.project.id,source?.title ?: "New issue",System.currentTimeMillis(),elementIds=links,position=position,levelId=selectedLevel?.let(::LevelId) ?: links.firstNotNullOfOrNull {id->document.elements.find {it.id==id}?.levelId} ?: source?.clash?.levelIds?.singleOrNull(),sourceClashId=source?.id)
    fun openIssue(issue:Issue) {
        issueView=issue.capture;sectionBox=issue.capture?.sectionBox;clashFocus=null;scheduleFocus=emptySet();issueEdit=issue
        issue.capture?.let {v->viewport=Viewport2D(v.centre,v.pixelsPerMetre,v.planRotation);orbit=OrbitCamera(v.target,v.yaw,v.pitch,v.distance,v.orthographicHeight,v.projection);is3D=v.is3D;level=v.levelId?.value;orbitLevel=v.levelId?.value;grid=v.grid} ?: issue.position?.let {p->val bounds=Bounds3(Point3(p.x-1,p.y-1,p.z-1),Point3(p.x+1,p.y+1,p.z+1));viewport=viewport.fit(bounds,size.width.toDouble(),size.height.toDouble());orbit=orbit.fit(bounds,size.width.toDouble()/size.height.coerceAtLeast(1));level=issue.levelId?.value;orbitLevel=issue.levelId?.value}
    }
    issueEdit?.let {draft->val stored=document.issues.find {it.id==draft.id};val live=if(stored!=null && draft.capture!=null && stored.capture?.id!=draft.capture?.id)stored.copy(capture=draft.capture,screenshot=draft.capture?.screenshot)else stored ?: draft
        IssueEditor(live,document,{onSaveIssue(it);issueEdit=null},{issueEdit=null}, {i->onSaveIssue(i);issueEdit=null;val marked=i.copy(capture=i.capture ?: captureIssueView(issueRequest));issueView=marked.capture;issueMarkup=marked},
            {i->val next=i.copy(capture=captureIssueView(issueRequest),screenshot=null,savedViews=(i.savedViews+listOfNotNull(i.capture)).distinctBy {it.id});onSaveIssue(next);issueEdit=next},onIssuePhoto,{i,format->onShareIssue(i.copy(capture=i.capture ?: captureIssueView(issueRequest)),format)},{onDeleteIssue(it);issueEdit=null},{i->openIssue(i)})
    }
    issueMarkup?.let {i->IssueMarkupEditor(i,issueRequest,{marks,image->onIssueScreenshot(i,marks,image);issueMarkup=null},{issueMarkup=null})}
    fun requestClose(){if(!busy){if(dirty)closeConfirmation=true else onBack()}}
    BackHandler(enabled=!busy){requestClose()}
    LaunchedEffect(exitAfterSave,dirty,busy){if(exitAfterSave && !dirty && !busy){exitAfterSave=false;onBack()}}
    if(closeConfirmation)AlertDialog(onDismissRequest={closeConfirmation=false},title={Text("Save changes?")},text={Text("Save changes to ${document.project.name} before leaving?")},
        confirmButton={TextButton({closeConfirmation=false;exitAfterSave=true;onSave()},Modifier.testTag("canvas_exit_save")){Text("Save")}},
        dismissButton={Row {TextButton({closeConfirmation=false;onDiscardExit()},Modifier.testTag("canvas_exit_discard")){Text("Don't save")};TextButton({closeConfirmation=false}){Text("Cancel")}}})
    if(planRotationDialog)PlanRotationDialog(viewport.rotation,{viewport=viewport.copy(rotation=it);is3D=false;planRotationDialog=false},{planRotationDialog=false})
    if(navigationPreferences)NavigationPreferences(settings,{onNavigationSettings(it);navigationPreferences=false},{navigationPreferences=false})
    fun beginBuilding(mode:String){cancelConstruction();navigationTool=NavigationTool.SELECT;buildingTool=mode;tool=if(mode=="wall")DrawingTool.LINE else if(mode in setOf("room","slab"))DrawingTool.RECTANGLE else DrawingTool.POLYGON;editor?.onSelect?.invoke(emptySet())}
    fun beginRoute(kind:RouteKind){cancelConstruction();navigationTool=NavigationTool.SELECT;routeKind=kind
        val defaults=settings.projectDefaults
        routeConfig=settings.lastRoutingSettings[kind] ?: (defaults.routeDefaults[kind] ?: defaultRoutingSettings(kind)).let {it.copy(elevation=defaults.defaultRouteElevations[kind] ?: it.elevation)}
        if(routeConfig.floorInstallation!=null)is3D=false
        editor?.onSelect?.invoke(emptySet());probe=false;zoomWindow=false
    }
    fun navigate(next:NavigationTool){navigationTool=if(navigationTool==next)NavigationTool.SELECT else next;zoomWindow=false;probe=false;clearPrecisionCandidate()
        if(next in setOf(NavigationTool.ORBIT,NavigationTool.LOOK_AROUND,NavigationTool.WALK)){is3D=true;orbitLevel=level;if(next!=NavigationTool.ORBIT)orbit=orbit.withProjection(Projection.PERSPECTIVE)}
    }
    fun toggle3D(){if(is3D)is3D=false else {is3D=true;orbitLevel=level;if(!orbitShown){fit();orbitShown=true}}}
    fun zoomBy(factor:Double){if(is3D)orbit=orbit.zoom(factor)else viewport=viewport.zoom(factor,size.width/2.0,size.height/2.0,size.width.toDouble(),size.height.toDouble())}
    fun fitSelection(){if(selectedIds.isNotEmpty()){val bounds=elementWorldBounds(document,selectedIds);if(is3D)orbit=orbit.fit(bounds,size.width.toDouble()/size.height.coerceAtLeast(1))else viewport=viewport.fit(bounds,size.width.toDouble(),size.height.toDouble(),fitMargin())}}
    fun restoreCameraView(view:SavedView){
        val saved=view.capture ?: run {val c=view.camera;val dx=c.position.x-c.target.x;val dy=c.position.y-c.target.y;val dz=c.position.z-c.target.z
            captureIssueView(issueRequest).copy(target=c.target,yaw=atan2(dy,dx),pitch=atan2(dz,hypot(dx,dy)),distance=hypot(hypot(dx,dy),dz).coerceIn(1e-5,1e12),orthographicHeight=c.orthographicHeight,projection=c.projection,is3D=true,levelId=view.levelId,sectionBox=view.sectionBox,settings=document.project.settings.copy(viewMode=view.mode),layerVisibility=document.layers.associate {it.id to (it.id !in view.hiddenLayerIds)},systemVisibility=document.systems.associate {it.id to (it.id !in view.hiddenSystemIds)})}
        issueView=saved;sectionBox=saved.sectionBox;viewport=Viewport2D(saved.centre,saved.pixelsPerMetre,saved.planRotation);orbit=OrbitCamera(saved.target,saved.yaw,saved.pitch,saved.distance,saved.orthographicHeight,saved.projection)
        is3D=saved.is3D;level=saved.levelId?.value;orbitLevel=saved.levelId?.value;grid=saved.grid;clashFocus=null;scheduleFocus=emptySet()
    }
    fun saveCameraView(name:String){val capture=captureIssueView(issueRequest);val target=if(is3D)orbit.target else Point3(viewport.center.x,viewport.center.y,floorZ)
        val camera=Camera(if(is3D)orbit.eye()else target.copy(z=target.z+100),target,up=if(is3D)Point3(z=1.0)else Point3(y=1.0),projection=if(is3D)orbit.projection else Projection.ORTHOGRAPHIC,orthographicHeight=if(is3D)orbit.orthographicHeight else size.height/viewport.pixelsPerMetre)
        onSaveCameraView(SavedView(ViewId(java.util.UUID.randomUUID().toString()),document.project.id,name,camera,frame.viewMode,selectedLevel?.let(::LevelId),document.layers.filterNot {it.visible}.mapTo(HashSet()){it.id},document.systems.filterNot {it.visible}.mapTo(HashSet()){it.id},sectionBox=sectionBox,capture=capture))
    }
    fun openSection(){viewsOpen=false;activeDetail=null;sectionOptions=true
        if(sectionBox==null){val b=selectedBounds ?: ready?.scene?.bounds ?: Bounds3(Point3(-2.0,-2.0,floorZ),Point3(2.0,2.0,floorZ+3))
            sectionBox=Bounds3(Point3(b.min.x-.05,b.min.y-.05,b.min.z-.05),Point3(b.max.x+.05,b.max.y+.05,b.max.z+.05))}
    }
    fun action(id:String,label:String,icon:androidx.compose.ui.graphics.vector.ImageVector=Icons.Outlined.Tune,selected:Boolean=false,enabled:Boolean=true,run:()->Unit)=CadAction(id,label,icon,selected,enabled,run)
    val toolNames=DrawingTool.entries.associateWith {stringResource(drawingToolLabel(it))}
    val buildStripActions: @Composable () -> List<CadAction> = {when(canvasLayout.openGroup){
        CanvasGroup.DRAW->listOf(action("tool_select",toolNames.getValue(DrawingTool.SELECT),drawingToolIcon(DrawingTool.SELECT),tool==DrawingTool.SELECT && navigationTool==NavigationTool.SELECT){beginTool(DrawingTool.SELECT)})+
            listOf("wall" to "Wall","slab" to "Floor slab","room" to "Room","room_polygon" to "Room polygon").map {(id,label)->action("tool_$id",label,Icons.Outlined.CropSquare,buildingTool==id){beginBuilding(id)}}+
            DrawingTool.entries.filter {it!=DrawingTool.SELECT}.map {t->action("tool_${t.name.lowercase()}",toolNames.getValue(t),drawingToolIcon(t),tool==t && buildingTool==null){beginTool(t)}}
        CanvasGroup.EDIT->listOf(action("edit_select","Select",Icons.Outlined.NearMe){beginTool(DrawingTool.SELECT)},
            action("tools_properties","Properties",Icons.Outlined.Tune,enabled=selectedIds.size==1){openProperties()},
            action("tools_copy","Copy",Icons.Outlined.ContentCopy,enabled=selectedIds.isNotEmpty()){editor?.onCopy?.invoke()},
            action("tools_paste","Paste",Icons.Outlined.ContentPaste,enabled=editor?.canPaste==true){cancelConstruction();editor?.onPaste?.invoke()},
            action("tools_delete","Delete",Icons.Outlined.Delete,enabled=selectedIds.isNotEmpty()){editor?.onDelete?.invoke()},
            action("tools_mirror_x","Mirror X",Icons.Outlined.Flip,enabled=selectedIds.isNotEmpty()){editor?.onMirror?.invoke(MirrorAxis.X,positionLockedPivot ?: elementWorldBounds(document,selectedIds).center())},
            action("tools_mirror_y","Mirror Y",Icons.Outlined.Flip,enabled=selectedIds.isNotEmpty()){editor?.onMirror?.invoke(MirrorAxis.Y,positionLockedPivot ?: elementWorldBounds(document,selectedIds).center())},
            action("tools_array","Array",Icons.Outlined.GridView,enabled=selectedIds.isNotEmpty()){arrayOpen=true},
            action("selection_tools","Selection tools",Icons.Outlined.MoreHoriz){selectionToolbarVisible=!selectionToolbarVisible},
            action("edit_move","Move",Icons.Outlined.OpenWith,enabled=selectedIds.isNotEmpty()){gizmoMode=GizmoMode.MOVE},
            action("edit_rotate","Rotate",Icons.Outlined.Rotate90DegreesCcw,enabled=selectedIds.isNotEmpty()){gizmoMode=GizmoMode.ROTATE},
            action("edit_scale","Scale",Icons.Outlined.AspectRatio,enabled=selectedIds.isNotEmpty()){gizmoMode=GizmoMode.SCALE},
            action("edit_rotate_90","Rotate 90°",Icons.Outlined.Rotate90DegreesCcw,enabled=selectedIds.isNotEmpty()){editor?.onRotate90?.invoke()},
            action("edit_proportions","Keep proportions",Icons.Outlined.AspectRatio,options.keepProportions){options=options.copy(keepProportions=!options.keepProportions)},
            action("edit_layer","Layer",Icons.Outlined.Layers,enabled=selectedIds.isNotEmpty()){selectionToolbarVisible=true;selectionMenu="LAYER"},
            action("edit_colour","Colour",Icons.Outlined.Palette,enabled=selectedIds.isNotEmpty()){selectionToolbarVisible=true;selectionMenu="COLOUR"},
            action("edit_lock","Locks",Icons.Outlined.Lock,enabled=selectedIds.isNotEmpty()){selectionToolbarVisible=true;selectionMenu="LOCK"},
            action("edit_display","Object display",Icons.Outlined.Visibility,enabled=selectedIds.isNotEmpty()){selectionToolbarVisible=true;selectionMenu="DISPLAY"},
            action("edit_rotation_snap","Rotation snap",Icons.Outlined.Rotate90DegreesCcw,enabled=selectedIds.isNotEmpty()){selectionToolbarVisible=true;selectionMenu="ROTATION_SNAP"},
            action("edit_room_walls","Room walls",Icons.Outlined.CropSquare,enabled=document.elements.any {it.id in selectedIds && it.body is ElementBody.Room}){selectionToolbarVisible=true;selectionMenu="ROOM_WALLS"},
            action("edit_issue","Issue from selection",Icons.Outlined.PushPin,enabled=selectedIds.isNotEmpty()){issueEdit=freshIssue(elementWorldBounds(document,selectedIds).center(),selectedIds)})+
            if(document.elements.any {it.id in selectedIds && it.body is ElementBody.Room})listOf(action("edit_generate_walls","Generate walls",Icons.Outlined.CropSquare){generateWallsDialog=true})else emptyList()
        CanvasGroup.LIBRARY->listOf(action("canvas_library","Library",Icons.Outlined.Inventory2){setPanel(CanvasPanelId.LIBRARY,PanelVisibility.OPEN)},
            action("library_new_item","Create custom item",Icons.Outlined.AddBox){libraryLaunch="create";setPanel(CanvasPanelId.LIBRARY,PanelVisibility.OPEN)},action("library_selected_drawing","From drawing",Icons.Outlined.Draw,enabled=selectedIds.isNotEmpty()){libraryLaunch="drawing";setPanel(CanvasPanelId.LIBRARY,PanelVisibility.OPEN)},
            action("library_import","Import library",Icons.Outlined.FileOpen){editor?.onImportLibrary?.invoke()},action("library_export","Export library",Icons.Outlined.Share){editor?.onExportLibrary?.invoke()})
        CanvasGroup.ROUTE->RouteKind.entries.map {kind->action("tool_route_${kind.name}",kind.name.replace('_',' '),Icons.Outlined.Route,routeKind==kind){beginRoute(kind)}}+
            action("canvas_route_list","Route list",Icons.AutoMirrored.Outlined.List){setPanel(CanvasPanelId.DRAW,PanelVisibility.OPEN)}
        CanvasGroup.LEVEL->listOf(action("level_all","All levels",Icons.Outlined.Layers,selectedLevel==null){if(routeKind==null)cancelConstruction();if(is3D)orbitLevel=null else level=null},action("level_units","Units and levels",Icons.Outlined.Tune){setPanel(CanvasPanelId.VIEW,PanelVisibility.OPEN)},action("level_ceiling","Ceiling",Icons.Outlined.Layers,enabled=document.levels.isNotEmpty()){ceilingLevelDialog=(selectedLevel?.let(::LevelId) ?: document.levels.firstOrNull()?.id)})+
            document.levels.map {l->action("level_${l.id.value}",l.name,Icons.Outlined.Layers,selectedLevel==l.id.value){if(routeKind==null)cancelConstruction()else clearPrecisionCandidate();editor?.onSelect?.invoke(emptySet());if(is3D)orbitLevel=l.id.value else level=l.id.value}}
        CanvasGroup.MEASURE->listOf(action("canvas_measure","Measure distance",Icons.Outlined.Straighten){beginMeasurement(MeasurementMode.DISTANCE)},
            action("canvas_reference_dimension","Reference dimension",Icons.Outlined.Straighten){beginMeasurement(MeasurementMode.REFERENCE)},
            action("canvas_measurements","Saved measurements",Icons.AutoMirrored.Outlined.List){setPanel(CanvasPanelId.MEASUREMENTS,PanelVisibility.OPEN)})
        CanvasGroup.VIEW->listOf("Top" to Point3(z=1.0),"Bottom" to Point3(z=-1.0),"Left" to Point3(x=-1.0),"Right" to Point3(x=1.0),"Front" to Point3(y=-1.0),"Back" to Point3(y=1.0)).map {(label,normal)->action("view_${label.lowercase()}",label,Icons.Outlined.ViewInAr){cubeDirection(normal)}}+
            listOf(action("view_isometric","Isometric",Icons.Outlined.ViewInAr){cubeDirection(Point3(1.0,1.0,1.0))},action("canvas_home",stringResource(R.string.canvas_home),Icons.Outlined.Home){homeView()},
            action("view_projection",if(orbit.projection==Projection.PERSPECTIVE)"Orthographic"else "Perspective",Icons.Outlined.ViewInAr){is3D=true;orbit=orbit.withProjection(if(orbit.projection==Projection.PERSPECTIVE)Projection.ORTHOGRAPHIC else Projection.PERSPECTIVE)},
            action("view_plan","Plan 2D",Icons.Outlined.GridOn){is3D=false},action("view_grid",stringResource(R.string.canvas_grid),Icons.Outlined.GridOn,grid){grid=!grid})
        CanvasGroup.NAVIGATE->listOf(action("navigate_select","Select",Icons.Outlined.NearMe,navigationTool==NavigationTool.SELECT){beginTool(DrawingTool.SELECT)},
            action("navigate_pan","Pan",Icons.Outlined.PanTool,navigationTool==NavigationTool.PAN){navigate(NavigationTool.PAN)},
            action("navigate_orbit","Orbit",Icons.Outlined.Public,navigationTool==NavigationTool.ORBIT){navigate(NavigationTool.ORBIT)},
            action("canvas_zoom_window",stringResource(R.string.canvas_window),Icons.Outlined.CropFree,zoomWindow){zoomWindow=!zoomWindow;window.value=null},
            action("navigate_zoom_in","Zoom in",Icons.Outlined.ZoomIn){zoomBy(1.25)},action("navigate_zoom_out","Zoom out",Icons.Outlined.ZoomOut){zoomBy(.8)},
            action("navigate_extents","Zoom extents",Icons.Outlined.FitScreen){fit()},action("navigate_selection","Zoom to selection",Icons.Outlined.CenterFocusStrong,enabled=selectedIds.isNotEmpty()){fitSelection()},
            action("navigate_look","Look around",Icons.Outlined.Visibility,navigationTool==NavigationTool.LOOK_AROUND){navigate(NavigationTool.LOOK_AROUND)},
            action("navigate_walk","Walk / Roam",Icons.AutoMirrored.Outlined.DirectionsWalk,navigationTool==NavigationTool.WALK){navigate(NavigationTool.WALK)},
            action("navigate_rotate_plan","Rotate view in 2D",Icons.Outlined.Rotate90DegreesCcw){planRotationDialog=true},
            action("navigate_reset_north","Reset north",Icons.Outlined.North){viewport=viewport.copy(rotation=0.0)},
            action("navigate_projection",if(orbit.projection==Projection.PERSPECTIVE)"Orthographic"else "Perspective",Icons.Outlined.ViewInAr){is3D=true;orbit=orbit.withProjection(if(orbit.projection==Projection.PERSPECTIVE)Projection.ORTHOGRAPHIC else Projection.PERSPECTIVE)},
            action("navigate_saved_views","Saved views",Icons.Outlined.Bookmarks){activeDetail=null;sectionOptions=false;viewsOpen=!viewsOpen},
            action("navigate_section_box","Section box",Icons.Outlined.Crop,sectionBox!=null){openSection()},
            action("navigate_project_display","Return to project visibility",Icons.Outlined.Visibility){issueView=null})
        CanvasGroup.DISPLAY->ViewMode.entries.map {mode->action("display_${mode.name}",mode.name.replace('_',' '),Icons.Outlined.Visibility,renderDocument.project.settings.viewMode==mode){issueView=null;editor?.onViewMode?.invoke(mode)}}
        CanvasGroup.LAYERS->listOf(action("canvas_visibility","Visibility",Icons.Outlined.Layers){setPanel(CanvasPanelId.VISIBILITY,PanelVisibility.OPEN)},action("layers_show_all","Show all",Icons.Outlined.Visibility){editor?.onShowAll?.invoke()})
        CanvasGroup.ISSUES->listOf(action("canvas_issues","Issues",Icons.Outlined.PushPin){setPanel(CanvasPanelId.ISSUES,PanelVisibility.OPEN)},action("issue_at_point","Issue at point",Icons.Outlined.AddLocation){issuePicking=true},
            action("clash_check_tool","Clash check",Icons.Outlined.FindInPage,enabled=!busy && clashProgress==null){clashDialog=true},action("canvas_clashes","Clashes",Icons.AutoMirrored.Outlined.List){setPanel(CanvasPanelId.CLASHES,PanelVisibility.OPEN)})
        CanvasGroup.SCHEDULE->listOf(action("canvas_schedules","Schedules",Icons.Outlined.TableChart){setPanel(CanvasPanelId.SCHEDULES,PanelVisibility.OPEN)},action("wall_schedule","Wall schedule",Icons.Outlined.CropSquare){wallSchedule=true},action("floor_schedule","Floor schedule",Icons.Outlined.Layers){floorScheduleOpen=true})
        CanvasGroup.EXPORT->listOf(action("export_2d","Export 2D drawing",Icons.Outlined.PictureAsPdf){onExportDrawing(issueRequest)},action("export_3d","Export 3D model",Icons.Outlined.ViewInAr){onExportModel(selectedLevel,!is3D)},
            action("export_project",stringResource(R.string.canvas_export),Icons.Outlined.Share){onExport()},action("canvas_export_png",stringResource(R.string.canvas_export_png),Icons.Outlined.Image){onExportView(issueRequest,ViewExportFormat.PNG)},action("canvas_export_pdf",stringResource(R.string.canvas_export_pdf),Icons.Outlined.PictureAsPdf){onExportView(issueRequest,ViewExportFormat.PDF)})
        CanvasGroup.SETTINGS->listOf(action("canvas_save",stringResource(R.string.canvas_save),Icons.Outlined.Save,enabled=dirty && !busy){onSave()},action("canvas_details",stringResource(R.string.canvas_project_details),Icons.Outlined.Description){onDetails()},
            action("canvas_settings","Settings",Icons.Outlined.Settings){onOpenSettings()},action("navigation_settings","Navigation settings",Icons.Outlined.Tune){navigationPreferences=true},
            action("canvas_snaps","Snap options",Icons.Outlined.Grid4x4){snapsOpen=true},action("canvas_probe",stringResource(R.string.canvas_probe),Icons.Outlined.MyLocation,probe){probe=!probe},
            action("canvas_clear_reference",stringResource(R.string.canvas_clear_reference),Icons.Outlined.Clear,enabled=anchor!=null){anchorX=null;anchorY=null},action("canvas_units","Units and levels",Icons.Outlined.Tune){setPanel(CanvasPanelId.VIEW,PanelVisibility.OPEN)},
            action("canvas_add_selection",stringResource(R.string.canvas_add_selection),Icons.Outlined.LibraryAdd,addSelection){addSelection=!addSelection},action("canvas_bottom_hide","Hide bottom bar",Icons.Outlined.ExpandMore){updateLayout(canvasLayout.copy(bottomBarVisible=false))})
        null->emptyList()
    }}
    val stripActions=buildStripActions()

    val selectionControls:(@Composable ()->Unit)?=if(editor!=null && selectedIds.isNotEmpty())({
                    val selectedElements=document.elements.filter {it.id in selectedIds}
                    val layerIsLocked=selectedElements.any {e->document.layers.any {it.id==e.layerId && it.locked}}
                    SelectionToolbar(gizmoMode,!busy && !lockedSelection && selectedRoomSide==null,editor.canUndo,editor.canRedo,editor.canPaste,
                        document.layers.filter {it.visible && !it.locked},selectedElements.map {it.layerId}.distinct().singleOrNull(),
                        selectedElements.any {it.objectLocked},document.project.settings.rotationSnapDegrees,
                        onMode={gizmoMode=it;preview=null;rotationAngle=null},onCopy=editor.onCopy,onDelete=editor.onDelete,
                        onGenerateWalls=if(selectedElements.any {it.body is ElementBody.Room})({generateWallsDialog=true})else null,onProperties={openProperties()},
                        onMirrorX={editor.onMirror(MirrorAxis.X,positionLockedPivot ?: elementWorldBounds(document,selectedIds).center())},
                        onMirrorY={editor.onMirror(MirrorAxis.Y,positionLockedPivot ?: elementWorldBounds(document,selectedIds).center())},
                        onArray={arrayOpen=true},onRotate90=editor.onRotate90,onUndo=editor.onUndo,onRedo=editor.onRedo,onPaste=editor.onPaste,
                        onLayer=editor.onSelectionLayer,onColour=editor.onSelectionColour,onLock=editor.onSelectionLocked,
                        onRotationSnap=editor.onRotationSnap,modifier=Modifier.horizontalScroll(rememberScrollState()),menuRequest=selectionMenu,onMenuClosed={selectionMenu=null},modern=true,
                        keepProportions=options.keepProportions,onKeepProportions={options=options.copy(keepProportions=it)},layerLocked=layerIsLocked,
                        lockMode=selectedElements.maxByOrNull {it.lockMode.ordinal}?.lockMode ?: LockMode.NONE,
                        onLockMode=editor.onSelectionLockMode,onLockLevel={editor.onLevelLock(selectedLevel,it)},
                        display=selectedRoomSide?.let {side->selectedElements.singleOrNull()?.roomSideDisplay(side)} ?: selectedElements.firstOrNull()?.objectDisplay ?: ObjectDisplay.NORMAL,
                        onDisplay={display->selectedRoomSide?.let {editor.onRoomSideDisplay(it,display)} ?: editor.onSelectionDisplay(display)},onResetDisplay={selectedRoomSide?.let {editor.onRoomSideDisplay(it,ObjectDisplay.NORMAL)} ?: editor.onResetDisplay()},
                        room=selectedElements.singleOrNull()?.takeIf {it.body is ElementBody.Room},selectedRoomSide=selectedRoomSide,
                        onRoomSide={selectedRoomSide=it},onHideRoomSide={editor.onRoomSideDisplay(it,ObjectDisplay.HIDDEN)},
                        onShowRoomWalls={selectedElements.singleOrNull()?.let {editor.onShowRoomWalls(it.id)}},layout=canvasLayout.copy(panels=canvasLayout.panels.mapValues {(id,p)->p.copy(state=if(id==activeDetail)PanelVisibility.OPEN else PanelVisibility.HIDDEN)}),
                        onPanelVisibility={id,state->setPanel(id,state)},onHideAllPanels={updateLayout(canvasLayout.copy(hideAll=it))})
    })else null
    fun groupTapped(group:CanvasGroup) {
        val opening=canvasLayout.openGroup!=group
        updateLayout(canvasLayout.toggleGroup(group))
        if(opening)activeDetail=when(group){CanvasGroup.LIBRARY->CanvasPanelId.LIBRARY;CanvasGroup.LAYERS->CanvasPanelId.VISIBILITY;CanvasGroup.ISSUES->CanvasPanelId.ISSUES;CanvasGroup.SCHEDULE->CanvasPanelId.SCHEDULES;else->null}
    }
    val detailContent:(@Composable ()->Unit)?=if(activeDetail!=null || viewsOpen || sectionOptions)({
            Box(Modifier.fillMaxSize()) {
                if(viewsOpen)CameraViews(document,::saveCameraView,::restoreCameraView,onDeleteCameraView)
                else if(sectionOptions)sectionBox?.let {box->SectionBoxOptions(box,{sectionBox=it},{sectionBox=null;sectionOptions=false})}
                else if(activeDetail==CanvasPanelId.VIEW)CanvasStatus(document,is3D,orbit,cursor,selectedLevel,onUnitsChange,{if(is3D)orbitLevel=it else level=it},{snapsOpen=true})
                else editor?.let {actions->when(activeDetail){
                    CanvasPanelId.LIBRARY->panelStates.SaveableStateProvider("library") {
                        LibraryPanel(actions.libraryCatalog.effectiveItems(document.libraryItems),onInsert={id->cancelConstruction();navigationTool=NavigationTool.SELECT;pendingLibrary=id.value;libraryWallOptions=actions.libraryCatalog.find(id,document.libraryItems)?.takeIf {it.mounting==MountingType.WALL}?.let(::defaultWallDeviceOptions);actions.onSelect(emptySet());probe=false;zoomWindow=false;pointerWorld=if(is3D)orbit.target.copy(z=floorZ)else Point3(viewport.center.x,viewport.center.y,floorZ)},onDismiss={activeDetail=null},embedded=true,compactHeader=true,launchAction=libraryLaunch,onLaunchHandled={libraryLaunch=null},
                            state=document.libraryState,onStateChange=actions.onLibraryState,units=document.project.settings.lengthUnit,decimalPlaces=document.project.settings.decimalPlaces,activeItemId=pendingLibrary?.let(::LibraryItemId),
                            onSaveItem=actions.onSaveLibraryItem,onRestoreItem=actions.onRestoreLibraryItem,onDeleteItem=actions.onDeleteLibraryItem,onDuplicateItem=actions.onDuplicateLibraryItem,
                            onFromDrawing={runCatching {libraryItemFromDrawing(document,actions.selectedIds,LibraryItemId(java.util.UUID.randomUUID().toString()))}.getOrNull()},onImportLibrary=actions.onImportLibrary,onExportLibrary=actions.onExportLibrary,
                            onUndoLibrary=actions.onUndoLibrary,onRedoLibrary=actions.onRedoLibrary,canUndoLibrary=actions.libraryCanUndo,canRedoLibrary=actions.libraryCanRedo,authoringEnabled=actions.libraryReady && !busy,projectItemIds=document.libraryItems.mapTo(HashSet()){it.id})
                    }
                    CanvasPanelId.DRAW->Column(Modifier.verticalScroll(rememberScrollState())){RoutingRouteList(document,actions.onSelect);document.elements.singleOrNull {it.id in selectedIds}?.let {WallServiceSelection(it,document,actions.onEditWallService,{wallSchedule=true});RoutingSelectionActions(document,it,actions)}}
                    CanvasPanelId.PROPERTIES->{val source=document.elements.singleOrNull {it.id in selectedIds}
                        if(selectionHasMeasurements)MeasurementsPanel(renderDocument,actions.onMeasurementsVisibility,actions.onUpdateMeasurement,actions.onDeleteElements,{activeDetail=null},embedded=true)
                        else if(selectedProperties!=null)panelStates.SaveableStateProvider("properties:$propertiesSession:${source?.id?.value}") {
                            PropertiesSidePanel(selectedProperties,document.project.settings.lengthUnit,document.project.settings.decimalPlaces,onApply=actions.onProperties,onDismiss={activeDetail=null},lockMode=source?.lockMode ?: LockMode.NONE,layerLocked=document.layers.any {it.id==source?.layerId && it.locked},embedded=true,libraryProperties=selectedLibraryProperties,
                                onApplyLibrary={transform,parameters->actions.onLibraryProperties(transform,parameters,false)},onUpdateAllOfType={transform,parameters->actions.onLibraryProperties(transform,parameters,true)},onResetToLibraryDefault=actions.onResetLibraryProperties,extraContent=source?.takeIf {it.body is ElementBody.Room}?.let {room->({TextButton({ceilingRoomDialog=room.id},Modifier.testTag("property_room_ceiling")){Text("Ceiling: ${roomCeiling(document,room).type.name.lowercase().replaceFirstChar(Char::uppercase)}")}})})
                        }else Text(stringResource(R.string.presentation_select_properties),Modifier.padding(12.dp))
                    }
                    CanvasPanelId.MEASURE,CanvasPanelId.MEASUREMENTS->MeasurementsPanel(renderDocument,actions.onMeasurementsVisibility,actions.onUpdateMeasurement,actions.onDeleteElements,{activeDetail=null},embedded=true)
                    CanvasPanelId.SCHEDULES->SchedulesPanel(document,!is3D,{ids->actions.onSelect(ids);scheduleFocus=ids;clashFocus=null;issueView=null;level=null;orbitLevel=null;if(ids.isNotEmpty())fitSelection()},onTakeoffSettings,onTakeoffRate,onShareSchedule)
                    CanvasPanelId.ISSUES->Column {IssuesPanel(document,::openIssue,{val ids=actions.selectedIds;val p=if(ids.isEmpty())pointerWorld ?: Point3(viewport.center.x,viewport.center.y,floorZ)else elementWorldBounds(document,ids).center();issueEdit=freshIssue(p,ids)},onIssueExport);TextButton({issueView=null}){Text("Return to project visibility")}}
                    CanvasPanelId.CLASHES->ClashPanel(document,clashProgress,clashSummary,{clashDialog=true},onCancelClash,{issue->clashFocus=issue.id;scheduleFocus=emptySet();actions.onSelect(emptySet());is3D=true;orbitLevel=null;issue.clash?.let {c->val p=c.position;val r=max(.4,min(3.0,max(c.overlap,c.requiredClearance)*3));orbit=orbit.preset(ViewPreset.ISOMETRIC).withProjection(Projection.ORTHOGRAPHIC).fit(Bounds3(Point3(p.x-r,p.y-r,p.z-r),Point3(p.x+r,p.y+r,p.z+r)),size.width.toDouble()/size.height.coerceAtLeast(1))}}, {clashFocus=null},clashXray,{clashXray=it},onClashStatus,{source->issueEdit=freshIssue(source.clash!!.position,source.elementIds,source)})
                    CanvasPanelId.VISIBILITY->ObjectVisibilityPanel(document,actions.onLevelVisibility,{id,display->actions.onElementsDisplay(setOf(id),display)},actions.onSystemVisibility,actions.onShowAll,actions.onShowHidden,actions.onAutoXray,actions.onRoomSideDisplay,embedded=true,onLayerVisible=actions.onLayerVisibility,onWallSettings=actions.onWallSettings,onWallSchedule={wallSchedule=true},onFloorSettings=actions.onFloorSettings,onFloorSchedule={floorScheduleOpen=true},onMeasurementsVisible=actions.onMeasurementsVisibility,onResetAllDisplay=actions.onResetAllDisplay,onShowCeilings=actions.onShowCeilings,onConfigureRoomCeiling={ceilingRoomDialog=it},onConfigureLevelCeiling={ceilingLevelDialog=it})
                    CanvasPanelId.VIEW->CanvasStatus(document,is3D,orbit,cursor,selectedLevel,onUnitsChange,{if(routeKind==null)cancelConstruction()else clearPrecisionCandidate();actions.onSelect(emptySet());if(is3D)orbitLevel=it else level=it},{snapsOpen=true})
                    CanvasPanelId.EDIT->Unit
                    null->Unit
                }}
            }

    })else null

    if(clashDialog)ClashCheckDialog(document,selectedLevel,{options->clashDialog=false;setPanel(CanvasPanelId.CLASHES,PanelVisibility.OPEN);onRunClash(options.copy(planView=!is3D))},{clashDialog=false})
    CompositionLocalProvider(LocalLoupeObstacles provides loupeObstacles) {
    Box(
        modifier = modifier.onGloballyPositioned {canvasBounds=it.boundsInRoot()}.testTag("drawing_canvas").focusRequester(focus).onPreviewKeyEvent { event ->
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
    ) {
            Box(
                Modifier.fillMaxSize().onSizeChanged { size = it }
                    .testTag("canvas_surface").semantics {
                        set(PlanCenterX, viewport.center.x); set(PlanCenterY, viewport.center.y)
                        set(PlanScale, viewport.pixelsPerMetre); set(PlanRotation,viewport.rotation); set(OrbitYaw, orbit.yaw); set(OrbitPitch, orbit.pitch)
                    }
                    .pointerInput(document.project.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            if (latestReady.value == null) { return@awaitEachGesture }
                            focus.requestFocus()
                            val first = down.position
                            var moved = 0f
                            var multi = false
                            val bodyReady=latestReady.value
                            val bodySelection=latestEditor.value?.selectedIds.orEmpty()
                            val bodyHit=if(navigationTool==NavigationTool.SELECT && latestSettings.value.bodyDragMovesSelection && latestTool.value==DrawingTool.SELECT &&
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
                                    } else if (navigationTool==NavigationTool.SELECT && !multi && moved < viewConfiguration.touchSlop) {
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
                                    val anchor=if(latestSettings.value.zoomTowardFinger)centroid else Offset(size.width/2f,size.height/2f)
                                    if (is3D) orbit=orbit.pan(pan.x*sensitivity,pan.y*sensitivity,size.width.toDouble(),size.height.toDouble()).zoomAt(zoom,anchor.x.toDouble(),anchor.y.toDouble(),size.width.toDouble(),size.height.toDouble())
                                    else viewport=viewport.pan(pan.x*sensitivity,pan.y*sensitivity).zoom(zoom,anchor.x.toDouble(),anchor.y.toDouble(),size.width.toDouble(),size.height.toDouble())
                                        .rotate(-event.calculateRotation()*PI/180,centroid.x.toDouble(),centroid.y.toDouble(),size.width.toDouble(),size.height.toDouble())
                                } else if(bodyMove) {
                                    if(!bodyCancelled && moved>=viewConfiguration.touchSlop) preview=bodyReady?.moves?.delta(bodyPivot,
                                        Point2(first.x.toDouble(),first.y.toDouble()),Point2(position.x.toDouble(),position.y.toDouble()),null,
                                        viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble(),latestSnaps.value,14.0*density,latestLevel.value)
                                } else if (zoomWindow) window.value = first to position
                                else if(navigationTool==NavigationTool.PAN) {
                                    if(is3D)orbit=orbit.pan(pan.x*sensitivity,pan.y*sensitivity,size.width.toDouble(),size.height.toDouble())else viewport=viewport.pan(pan.x*sensitivity,pan.y*sensitivity)
                                }
                                else if(navigationTool in setOf(NavigationTool.LOOK_AROUND,NavigationTool.WALK)) {
                                    val invert=if(latestSettings.value.invertOrbit)-1 else 1
                                    orbit=orbit.lookAround(-pan.x*.006*sensitivity*invert,pan.y*.006*sensitivity*invert);cameraOrbiting=true
                                }
                                else if(latestPlacementActive.value && navigationTool!=NavigationTool.ORBIT) { /* Only the ghost follows a placement drag. */ }
                                else if (is3D) {
                                    if(moved>=viewConfiguration.touchSlop)cameraOrbiting=true
                                    val invert=if(latestSettings.value.invertOrbit)-1 else 1
                                    orbit=orbit.orbit(-pan.x*.006*sensitivity*invert,pan.y*.006*sensitivity*invert)
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
                else NativeDrawingSurface(ready?.mainScene, frame, Modifier.fillMaxSize(), onError = { rendererFailed = true }, onLoupe = {loupeCapture=it},onDiagnostics={graphicsStats=it})
                issuePins(renderDocument,frame,size.width,size.height).forEach {(id,screen)->
                    TextButton({document.issues.find {it.id==id}?.let(::openIssue)},Modifier.offset {androidx.compose.ui.unit.IntOffset((screen.x-20*density).toInt(),(screen.y-20*density).toInt())}.size(40.dp)){Text("●",color=androidx.compose.ui.graphics.Color(0xFFFF8800))}
                }
                if(issuePicking) {
                    Box(Modifier.fillMaxSize().pointerInput(frame){detectTapGestures {p->val point=if(is3D)orbit.rayToPlaneZ(p.x.toDouble(),p.y.toDouble(),size.width.toDouble(),size.height.toDouble(),floorZ)else viewport.screenToWorld(p.x.toDouble(),p.y.toDouble(),size.width.toDouble(),size.height.toDouble()).let {Point3(it.x,it.y,floorZ)};point?.let {issueEdit=freshIssue(it);issuePicking=false}}})
                    TextButton({issuePicking=false}){Text("Tap issue position · Cancel")}
                }
                val visibleMarks=issueMarkups(renderDocument,frame,issueView)
                if(visibleMarks.isNotEmpty())Canvas(Modifier.fillMaxSize()) {com.eezydraw.storage.paintIssueMarkups(drawContext.canvas.nativeCanvas,visibleMarks,size.width.toInt(),size.height.toInt())}
                if (ready != null) Box(Modifier.size(1.dp).testTag("canvas_ready"))
                if (!is3D) PlanOverlays(viewport, document.project.settings.lengthUnit, cursor, anchor, window)
                else {
                    Canvas(Modifier.fillMaxSize()) {
                        window.value?.let { (a,b) ->
                            drawRect(accent, Offset(min(a.x,b.x), min(a.y,b.y)), Size(abs(a.x-b.x),abs(a.y-b.y)), style = Stroke(2f*density))
                        }
                    }
                }
                if(routeKind!=null) {
                    RouteGhost(document,routeKind!!,liveRoutePicks,viewport,orbit,is3D,preview=liveRouteDocument)
                }
                if(editor!=null && navigationTool==NavigationTool.SELECT && !placementActive && !panelsHidden && !zoomWindow)document.elements.singleOrNull {it.id in selectedIds && it.body is ElementBody.Room}?.let {e->
                    RoomCornerHandles(e,viewport,orbit,is3D,size.width,size.height){i,p->editor.onRoomCorner(e.id,i,p)}
                }
                if(editor!=null && navigationTool==NavigationTool.SELECT && !placementActive && !panelsHidden && !zoomWindow)selectedElement?.let {e->
                    if(document.layers.none {it.id==e.layerId && it.locked})RouteEndHandles(e,viewport,orbit,is3D,size.width,size.height) {a,b->
                        (e.body as? ElementBody.Route)?.routing?.let {editor.onEditRoute(e.id,it.settings,a,b)}
                    }
                }
                if (editor != null && tool != DrawingTool.SELECT) {
                    ConstructionPreview(document,tool,if(buildingTool==null)options else options.copy(useDefaultDimensions=false),points,cursor.value?.point,floorZ,viewport,
                        orbit=orbit,is3D=is3D,cursorWorld=pointerWorld)
                }
                if(measurementMode!=MeasurementMode.NONE) MeasurementGhostPreview(measurementPicks.map {it.point},pointerWorld,viewport,orbit=orbit,is3D=is3D)
                libraryPlacement?.let { LibraryGhostPreview(document,it.item.id,libraryAim,viewport,orbit,is3D,placement=it) }
                if (editor != null && navigationTool==NavigationTool.SELECT && selectedBounds != null && !placementActive && !zoomWindow && !cameraOrbiting && !panelsHidden && selectedRoomSide==null) {
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
                if(pendingLibraryItem!=null) LibraryPlacementFeedback(document,pendingLibraryItem,libraryPlacement,
                    libraryPlacementResult?.exceptionOrNull()?.message,Modifier.align(Alignment.TopCenter).padding(8.dp).loupeObstacle("library_feedback"))
                if(precisionActive && !zoomWindow) PrecisionPointerOverlay(displayedPrecisionPick,precisionFinger,settings.precisionPointer,
                    measurementPicks.firstOrNull()?.point ?: points.firstOrNull(),document.project.settings.lengthUnit,
                    document.project.settings.decimalPlaces,onPlace={confirmPrecisionPoint()},onCancel={cancelConstruction()},
                    onNudge={dx,dy->nudgePrecision(dx,dy)},enabled=!busy && geometryMatches,
                    loupeBounds=loupeBounds,capture=loupeCapture?.takeIf {it.matches(ready?.mainScene,frame)},
                    canPlace=pendingLibrary==null || libraryPlacement!=null,controlsBottomInset=if(panelsHidden || !canvasLayout.bottomBarVisible)0.dp else with(LocalDensity.current){chromeHeight.toDp()},controlsVisible=!panelsHidden,controlsLeftInset=if(settings.barPosition==CanvasBarPosition.LEFT && canvasLayout.bottomBarVisible && !panelsHidden)76.dp else 0.dp)
                if(!is3D && (probe || placementActive))cursor.value?.mode?.let {mode->Surface(Modifier.align(Alignment.TopCenter).padding(top=140.dp).loupeObstacle("snap_hint"),tonalElevation=3.dp){Text(stringResource(snapLabel(mode)),Modifier.padding(8.dp))}}
                if(placedFeedback && !placementActive)Text(stringResource(R.string.placement_placed),Modifier.align(Alignment.TopCenter).padding(top=140.dp).testTag("placement_placed"))
                if(settings.showAxisIndicator)AxisIndicator(viewport,orbit,is3D,Modifier.align(Alignment.BottomStart).padding(start=if(settings.barPosition==CanvasBarPosition.LEFT && canvasLayout.bottomBarVisible && !panelsHidden)84.dp else 8.dp,bottom=if(panelsHidden || !canvasLayout.bottomBarVisible)8.dp else with(LocalDensity.current){chromeHeight.toDp()+8.dp}).loupeObstacle("axis_indicator"))
                if(sectionOptions && !panelsHidden)sectionBox?.let {SectionBoxOverlay(it,viewport,orbit,is3D,size.width,size.height,{sectionBox=it})}
                if(settings.graphics.showFrameRate && is3D)Surface(Modifier.align(Alignment.TopCenter).padding(top=112.dp).testTag("graphics_fps").loupeObstacle("graphics_fps"),tonalElevation=3.dp) {
                    val label=frameRateLabel(graphicsStats,graphicsClock)
                    Text("$label · ${when(graphicsStats.quality){RenderQuality.PERFORMANCE->"Low";RenderQuality.BALANCED->"Medium";RenderQuality.QUALITY->"High"}} · ${if(!settings.graphics.antiAliasing)"AA off"else if(graphicsStats.fxaa)if(graphicsStats.samples>0)"${graphicsStats.samples}× MSAA + FXAA"else "FXAA"else "${graphicsStats.samples}× MSAA"} · ${graphicsStats.depthBits}-bit depth",Modifier.padding(6.dp),style=MaterialTheme.typography.labelSmall)
                }
                if(navigationTool==NavigationTool.WALK && !panelsHidden)WalkPad({right,forward->orbit=orbit.walk(right*.02*settings.gestureSensitivity,forward*.02*settings.gestureSensitivity)},Modifier.align(Alignment.BottomEnd).padding(end=8.dp,bottom=with(LocalDensity.current){chromeHeight.toDp()+12.dp}))
            } // Gesture surface ends here; control overlays are siblings.
                if(selectionToolbarVisible && navigationTool==NavigationTool.SELECT && !placementActive && !panelsHidden && !zoomWindow && !cameraOrbiting && selectedBounds!=null)selectionControls?.let {controls->
                    SelectionAnchor(selectedBounds,viewport,orbit,is3D,size.width,size.height,if(canvasLayout.bottomBarVisible)chromeHeight else 0,settings.barPosition==CanvasBarPosition.LEFT && canvasLayout.bottomBarVisible,controls)
                }
                MobileCanvasChrome(canvasLayout,::updateLayout,stripActions,::requestClose,{undoInputOrDocument()},{redoInputOrDocument()},::fit,::toggle3D,
                    editor?.canUndo==true || canPointUndo,editor?.canRedo==true || canPointRedo,is3D,canFit=geometryMatches && !busy,onBottomHeight={chromeHeight=it},
                    showLabels=settings.bottomBarLabels,barPosition=settings.barPosition,onGroup=::groupTapped,detailKey=activeDetail?.name ?: if(viewsOpen)"views"else "section",
                    detailTitle=if(viewsOpen)"Saved views"else if(sectionOptions)"Section box"else when(activeDetail){CanvasPanelId.DRAW->"Routes";CanvasPanelId.VISIBILITY->"Layers / Visibility";CanvasPanelId.VIEW->"Units and levels";CanvasPanelId.SCHEDULES->"Schedules / BOQ";else->activeDetail?.name.orEmpty().lowercase().replaceFirstChar {it.uppercase()}},onCloseDetail={activeDetail=null;viewsOpen=false;sectionOptions=false},
                    cube={ViewCube(orbit,onFace={cubeDirection(it)},modifier=Modifier.size(112.dp),onCorner={cubeDirection(it)},onOrbit={dx,dy->if(!is3D){is3D=true;orbitLevel=level};val invert=if(settings.invertOrbit)-1 else 1;orbit=orbit.orbit(-dx*.006*settings.gestureSensitivity*invert,dy*.006*settings.gestureSensitivity*invert)},onGestureActive={cameraOrbiting=it},onPlan={is3D=false;zoomWindow=false;window.value=null},onProjection={projection->if(!is3D){is3D=true;orbitLevel=level};orbit=orbit.withProjection(projection);orbitShown=true},onCompass={yaw->orbit=orbit.copy(yaw=yaw);if(!is3D){is3D=true;orbitLevel=level}})},
                    options=if(placementActive)({Column {
                if(buildingTool!=null)BuildingOptions(wallOptions,{wallOptions=it},automaticWalls,{automaticWalls=it},buildingTool !in setOf("wall","slab"),wallOnly=buildingTool in setOf("wall","slab"),slab=buildingTool=="slab")

                if(pendingLibraryItem?.mounting==MountingType.WALL && wallFrames(document).none {f->(selectedLevel==null || f.host.levelId==null || f.host.levelId?.value==selectedLevel) && com.eezydraw.core.geometry.ScenePresentation(document).display(f.host)!=ObjectDisplay.HIDDEN}) {
                    Row(Modifier.fillMaxWidth()) {Text("No wall on this level. Draw a room or wall first.",Modifier.weight(1f));TextButton({cancelConstruction();buildingTool="wall";tool=DrawingTool.LINE},Modifier.testTag("missing_wall_open_tool")){Text("Open Wall tool")}}
                }
                if(pendingLibraryItem?.mounting==MountingType.WALL) { WallDeviceBar(pendingLibraryItem,libraryWallOptions ?: defaultWallDeviceOptions(pendingLibraryItem),{libraryWallOptions=it},libraryPlacement?.let {readWallMount(document,it.element)},document.project.settings.snap.gridSpacing) }
                if(routeKind!=null) {
                    RoutingSettingsBar(routeKind!!,routeConfig,{
                        routeConfig=it
                        if(it.floorInstallation!=null) { is3D=false }
                        if(routeDraft.isNotEmpty()) { routeDraft=routeDraft.dropLast(1)+routeDraft.last().copy(settings=it) }
                        editor?.onRoutingSettings?.invoke(routeKind!!,it)
                        clearPrecisionCandidate()
                    },floorDefaults=document.project.settings.floorServices)
                    Text("${coordinate(if(routeConfig.floorInstallation!=null)-routeConfig.floorDepth else routeConfig.elevation.value,LengthUnit.MILLIMETRES,0)} mm ${if(routeConfig.floorInstallation!=null)"Above finished floor"else routingReferenceLabel(routeConfig.elevation.reference)} · %.2f m · %d fittings".format(java.util.Locale.ROOT,
                        liveRouteLength,liveFittingCount),Modifier.testTag("route_live_label"))
                }
                if(placementActive) PlacementBanner(activeLabel,
                    when {
                        precisionActive->stringResource(R.string.precision_aim_hint,activeLabel)
                        pendingLibrary!=null || buildingTool==null && options.useDefaultDimensions && supportsDefaultPlacement(tool)->stringResource(R.string.placement_tap_to_place,activeLabel)
                        measurementMode==MeasurementMode.DISTANCE && measurementPicks.isEmpty()->stringResource(R.string.canvas_measure_pick_first)
                        measurementMode==MeasurementMode.DISTANCE->stringResource(R.string.canvas_measure_pick_second)
                        measurementMode==MeasurementMode.REFERENCE && measurementPicks.isEmpty()->stringResource(R.string.canvas_reference_pick_object)
                        measurementMode==MeasurementMode.REFERENCE && measurementPicks.size==1->stringResource(R.string.canvas_reference_pick_reference)
                        measurementMode==MeasurementMode.REFERENCE->stringResource(R.string.canvas_reference_place_label)
                        points.isEmpty()->stringResource(R.string.placement_tap_to_start,activeLabel)
                        else->stringResource(R.string.placement_add_points)
                    },canDone=routeKind==null || routeDraft.size>=2,onCancel={cancelConstruction()},onDone={finishTool()},showCancel=!precisionActive)
                if(placedFeedback) Text(stringResource(R.string.placement_placed),Modifier.padding(8.dp).testTag("placement_placed"),style=MaterialTheme.typography.labelMedium)
                        if(tool!=DrawingTool.SELECT && buildingTool==null && routeKind==null)ToolOptionsFields(tool,options,document.project.settings.lengthUnit,{options=it})
                    }})else null,detail=detailContent)
                activeBenchmark?.let {run->Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top=136.dp).loupeObstacle("benchmark")){StressStatus(run,onDismissBenchmark)}}
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
    } // Measured canvas chrome / loupe obstacles.
    ceilingRoomDialog?.let {id->document.elements.find {it.id==id && it.body is ElementBody.Room}?.let {room->
        CeilingOptionsDialog("Room ceiling",roomCeiling(document,room),{editor?.onRoomCeiling?.invoke(id,it);ceilingRoomDialog=null},{ceilingRoomDialog=null},
            onInherit={editor?.onRoomCeiling?.invoke(id,null);ceilingRoomDialog=null})
    }}
    ceilingLevelDialog?.let {id->document.levels.find {it.id==id}?.let {l->
        CeilingOptionsDialog("${l.name} ceiling",l.ceiling,{editor?.onLevelCeiling?.invoke(id,it);ceilingLevelDialog=null},{ceilingLevelDialog=null})
    }}
    if (snapsOpen) SnapOptions(snapSettings, onSnapSettingsChange, onDismiss = { snapsOpen = false })
    if (arrayOpen && editor != null) ArrayDialog(document.project.settings.lengthUnit,
        onApply = { columns, rows, dx, dy -> editor.onArray(columns,rows,dx,dy); arrayOpen = false },onDismiss = { arrayOpen = false })
    if(generateWallsDialog)GenerateWallsDialog(wallOptions,{wallOptions=it},{editor?.onGenerateWalls?.invoke(selectedIds.filter {id->document.elements.any {it.id==id && it.body is ElementBody.Room}}.toSet(),wallOptions);generateWallsDialog=false},{generateWallsDialog=false})
    if(floorScheduleOpen)FloorScheduleDialog(document,{editor?.onFloorSettings?.invoke(it)},{floorScheduleOpen=false})
    if(wallSchedule)WallScheduleDialog(document,{editor?.onWallSettings?.invoke(it)},{editor?.onViewMode?.invoke(ViewMode.SECTION);wallSchedule=false},{wallSchedule=false})
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
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).heightIn(min = 48.dp).testTag("canvas_status"),
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
