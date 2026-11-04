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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
    ) : SceneLoad
    data object Failed : SceneLoad
}

private fun sameCanvasGeometry(a: ProjectDocument, b: ProjectDocument): Boolean =
    a.elements == b.elements && a.layers == b.layers && a.systems == b.systems && a.libraryItems == b.libraryItems &&
        a.materials == b.materials && a.levels == b.levels && a.project.settings.showMeasurements == b.project.settings.showMeasurements &&
        a.project.settings.lengthUnit == b.project.settings.lengthUnit &&
        a.project.settings.angleFormat == b.project.settings.angleFormat &&
        a.project.settings.decimalPlaces == b.project.settings.decimalPlaces

private enum class MeasurementMode { NONE, DISTANCE, REFERENCE }
private data class MeasurementPick(val point: Point3,val elementId: ElementId? = null)
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
    var addSelection by rememberSaveable { mutableStateOf(false) }
    var propertiesOpen by rememberSaveable { mutableStateOf(false) }
    var propertiesSession by rememberSaveable { mutableIntStateOf(0) }
    var arrayOpen by rememberSaveable { mutableStateOf(false) }
    var measurementsOpen by rememberSaveable { mutableStateOf(false) }
    var libraryOpen by rememberSaveable { mutableStateOf(false) }
    var toolsExpanded by rememberSaveable { mutableStateOf(false) }
    var focusedTools by rememberSaveable { mutableStateOf<CanvasToolGroup?>(null) }
    var viewOpen by rememberSaveable { mutableStateOf(true) }
    val panelStates=rememberSaveableStateHolder()
    var panelsHidden by rememberSaveable { mutableStateOf(false) }
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
    val selectedIds = editor?.selectedIds ?: emptySet()
    val density = LocalDensity.current.density
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
    val lockedSelection = remember(document.elements,document.layers,selectedIds) {
        val lockedLayers = document.layers.filter { it.locked }.mapTo(HashSet()) { it.id }
        document.elements.any { it.id in selectedIds && it.layerId in lockedLayers }
    }
    val geometryMatches = remember(ready?.document,document) { ready?.let { sameCanvasGeometry(it.document,document) } == true }
    val sceneMatches = geometryMatches && ready?.selectedIds == selectedIds
    val selectionResizeFrame = if (sceneMatches) ready?.resizeFrame else null
    val latestGeometryMatches = rememberUpdatedState(geometryMatches)
    val latestSceneMatches = rememberUpdatedState(sceneMatches)
    val latestBounds = rememberUpdatedState(selectedBounds)
    val latestLocked = rememberUpdatedState(lockedSelection)
    val latestBusy = rememberUpdatedState(busy)
    val needsSurfaces = document.project.settings.viewMode != ViewMode.WIREFRAME || measurementMode != MeasurementMode.NONE

    LaunchedEffect(document.levels) {
        if(level!=null && document.levels.none {it.id.value==level})level=document.levels.firstOrNull()?.id?.value
        if(orbitLevel!=null && document.levels.none {it.id.value==orbitLevel})orbitLevel=null
    }

    LaunchedEffect(document.project.id, document.elements, document.layers, document.systems, document.libraryItems,document.materials,document.levels,
        document.project.settings.lengthUnit, document.project.settings.angleFormat, document.project.settings.decimalPlaces,
        document.project.settings.showMeasurements,selectedIds,needsSurfaces) {
        try {
            val previous = prepared
            val compiled = withContext(Dispatchers.Default) {
                val sameGeometry = previous != null && sameCanvasGeometry(previous.document,document)
                val scene = if (sameGeometry) previous!!.scene else buildDrawingScene(document)
                val index = SpatialIndex(scene.segments)
                val allIds=document.elements.mapTo(HashSet()) {it.id}
                val mainIds=allIds-selectedIds
                val main = if (selectedIds.isEmpty()) scene else buildDrawingScene(document,mainIds)
                val selected = if (selectedIds.isEmpty()) null else buildDrawingScene(document,selectedIds)
                val surfaces=if(sameGeometry && previous!!.surfaces!=null) previous.surfaces else if(needsSurfaces)buildSurfaceScene(document) else null
                val mainSurfaces=surfaces?.let {if(selectedIds.isEmpty())it else it.partition(selectedIds,false)}
                val selectedSurfaces=if(selectedIds.isEmpty())null else surfaces?.partition(selectedIds,true)
                val surfacedIds=surfaces?.chunks.orEmpty().mapTo(HashSet()) {ElementId(it.elementId)}
                val annotationIds=allIds-surfacedIds
                val annotations=if(surfaces==null)main else buildDrawingScene(document,annotationIds-selectedIds)
                val selectedAnnotations=if(selectedIds.isEmpty())null else if(surfaces==null)selected else buildDrawingScene(document,annotationIds.intersect(selectedIds))
                val dimensionIds=document.elements.filter {((it.body as? ElementBody.DrawingShape)?.shape is Shape.Dimension)}.mapTo(HashSet()){it.id}
                val measurementScene=buildDrawingScene(document,dimensionIds-selectedIds)
                val selectedMeasurements=if(selectedIds.isEmpty())null else buildDrawingScene(document,dimensionIds.intersect(selectedIds))
                val resize = if (sameGeometry && previous!!.selectedIds == selectedIds) previous.resizeFrame else
                    if (selectedIds.size == 1) document.elements.find { it.id in selectedIds && !isMeasurement(it) }?.let { resizeFrame(document,it) } else null
                SceneLoad.Ready(document, selectedIds, scene, main, selected,
                    if (sameGeometry) previous!!.snaps else SnapEngine(index),
                    if (sameGeometry) previous!!.picker else EditorPicker(document, scene, index),resize,
                    MoveContext(document,selectedIds,scene),surfaces,mainSurfaces,selectedSurfaces,annotations,selectedAnnotations,measurementScene,selectedMeasurements)
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
    fun cancelConstruction() {
        points = emptyList(); preview = null; waitingForCommit = null
        measurementPicks=emptyList();measurementMode=MeasurementMode.NONE
        anchorX = null; anchorY = null
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
                showHeightAboveFloor=source!=null,floorLevelId=sourceLevel)
            val success=editor?.onAddSketch?.invoke(geometry,selectedLevel)==true
            if(success) {measurementPicks=emptyList();measurementsOpen=true;propertiesOpen=false;libraryOpen=false}
            success
        } catch(error:IllegalArgumentException) {toolError=error.message;measurementPicks=picks.take(1);false}
    }
    fun finishConstruction(picks: List<Point3> = points) {
        if (busy) return
        try {
            val sketch = if (options.useDefaultDimensions && supportsDefaultPlacement(tool))
                createDefaultSketch(tool,picks.first(),options) else createSketch(tool, picks, options)
            if (editor?.onAddSketch?.invoke(sketch, level) == true) {
                points = emptyList()
                anchorX = null; anchorY = null
            }
        } catch (error: IllegalArgumentException) {
            toolError = error.message
            if (tool.autoFinishPoints == picks.size) points = picks.dropLast(1)
        }
    }
    val onCanvasTap = rememberUpdatedState<(Offset) -> Unit>({ position ->
        val actions = latestEditor.value
        if(actions!=null && !busy && latestMeasurementMode.value!=MeasurementMode.NONE && latestGeometryMatches.value && ready!=null) {
            val sceneHit=if(is3D)pickSceneHit(ready.scene,ready.surfaces,CanvasFrame(viewport,orbit,true,selectedLevel,floorElevation=floorZ),
                position.x.toDouble(),position.y.toDouble(),size.width.toDouble(),size.height.toDouble(),14.0*density) else null
            val picked=if(is3D)ready.picker.pick3D(orbit,position.x.toDouble(),position.y.toDouble(),size.width.toDouble(),size.height.toDouble(),14.0*density,selectedLevel) ?: sceneHit?.elementId
                else ready.picker.pickPlan(viewport.screenToWorld(position.x.toDouble(),position.y.toDouble(),size.width.toDouble(),size.height.toDouble()),14.0*density/viewport.pixelsPerMetre,selectedLevel)
            val raw=if(is3D)sceneHit?.point
                else cursor.value?.point?.let {Point3(it.x,it.y,floorZ)}
            raw?.let {point->
                if(measurementMode==MeasurementMode.REFERENCE && measurementPicks.isEmpty() && picked==null) toolError="Tap a visible object for the first reference."
                else if(measurementMode==MeasurementMode.REFERENCE && measurementPicks.size==2) commitMeasurement(measurementPicks,point)
                else {
                    var at=if(measurementMode==MeasurementMode.REFERENCE && picked!=null)measurementPointForElement(document,picked,point) else point
                    if(measurementMode==MeasurementMode.REFERENCE && measurementPicks.size==1 && picked==null && grid) {
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
                if (picked == null) actions.selectedIds else if (picked in actions.selectedIds) actions.selectedIds - picked else actions.selectedIds + picked
            } else setOfNotNull(picked))
        } else if (actions != null && !busy && latestTool.value != DrawingTool.SELECT && !is3D) {
            cursor.value?.point?.let { point ->
                if (points.size >= 512) toolError = "Finish this shape before adding more than 512 points."
                else {
                    val picks = points + Point3(point.x, point.y, latestFloorZ.value)
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
        val bounds = scene.boundsForLevel(selectedLevel)
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
        }
    })
    val accent = MaterialTheme.colorScheme.primary
    val frame = CanvasFrame(viewport, orbit, is3D, selectedLevel, snapSettings.gridSpacing, grid,
        MaterialTheme.colorScheme.background.luminance() < .5f, floorZ, ready?.selectionScene, preview,
        surfaceScene=ready?.mainSurfaces,selectionSurfaceScene=ready?.selectedSurfaces,viewMode=document.project.settings.viewMode,
        annotationScene=ready?.annotationScene,selectionAnnotationScene=ready?.selectedAnnotationScene,
        measurementScene=ready?.measurementScene,selectionMeasurementScene=ready?.selectedMeasurementScene)
    Scaffold(
        modifier = modifier.testTag("drawing_canvas").focusRequester(focus).onPreviewKeyEvent { event ->
            val actions = editor
            if (actions == null || busy || !canvasFocused || event.type != KeyEventType.KeyDown) false
            else when {
                event.key == Key.Escape -> { cancelConstruction(); tool = DrawingTool.SELECT; true }
                event.isCtrlPressed && event.key == Key.C -> { actions.onCopy(); true }
                event.isCtrlPressed && event.key == Key.V -> { cancelConstruction(); actions.onPaste(); true }
                event.isCtrlPressed && event.key == Key.Z && event.isShiftPressed -> { cancelConstruction(); actions.onRedo(); true }
                event.isCtrlPressed && event.key == Key.Z -> { cancelConstruction(); actions.onUndo(); true }
                event.isCtrlPressed && event.key == Key.Y -> { cancelConstruction(); actions.onRedo(); true }
                event.key == Key.Delete || event.key == Key.Backspace -> { cancelConstruction(); actions.onDelete(); true }
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
                    IconButton(onSave, enabled = dirty && !busy, modifier = Modifier.testTag("canvas_save")) {
                        Icon(Icons.Outlined.Save, stringResource(R.string.canvas_save))
                    }
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
                    onDraw={toolsExpanded=!(toolsExpanded && focusedTools==CanvasToolGroup.DRAW);focusedTools=CanvasToolGroup.DRAW},
                    onEdit={toolsExpanded=!(toolsExpanded && focusedTools==CanvasToolGroup.EDIT);focusedTools=CanvasToolGroup.EDIT},
                    onView={viewOpen=!viewOpen},
                    onMeasure={cancelConstruction();tool=DrawingTool.SELECT;measurementMode=MeasurementMode.DISTANCE;probe=false;toolsExpanded=false;measurementsOpen=false;viewOpen=true},
                    onReferenceDimension={cancelConstruction();tool=DrawingTool.SELECT;measurementMode=MeasurementMode.REFERENCE;probe=false;toolsExpanded=false;measurementsOpen=false;viewOpen=true},
                    onMeasurements={measurementsOpen=!measurementsOpen;propertiesOpen=false;libraryOpen=false},
                    onLibrary={libraryOpen=!libraryOpen;propertiesOpen=false;measurementsOpen=false},
                    onTogglePanels={panelsHidden=true},panelsHidden=false,modifier=Modifier.fillMaxWidth())
                if(viewOpen) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("canvas_toolbar").padding(horizontal=8.dp),
                    horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(measurementMode!=MeasurementMode.NONE)CanvasActionButton(Icons.Outlined.Close,stringResource(R.string.editing_cancel),{cancelConstruction()},Modifier.testTag("canvas_cancel_measurement"))
                    CanvasActionButton(Icons.Outlined.ViewInAr,stringResource(if(is3D)R.string.canvas_plan else R.string.canvas_3d),{
                        cancelConstruction();tool=DrawingTool.SELECT
                        val nextLevel=if(is3D)level else orbitLevel
                        editor?.onSelect?.invoke(document.elements.asSequence().filter {
                            it.id in selectedIds && (nextLevel==null || it.levelId==null || it.levelId?.value==nextLevel)
                        }.map {it.id}.toSet())
                        if(!is3D && !orbitShown && geometryMatches && ready!=null && ready.scene.segments.isNotEmpty() && size.height>0) {
                            orbit=orbit.fit(ready.scene.boundsForLevel(nextLevel),size.width.toDouble()/size.height);orbitShown=true
                        }
                        is3D=!is3D;zoomWindow=false;window.value=null
                    },Modifier.testTag("canvas_3d"),selected=is3D)
                    CanvasActionButton(Icons.Outlined.FitScreen,stringResource(R.string.canvas_extents),{fit()},Modifier.testTag("canvas_extents"),enabled=geometryMatches)
                    CanvasActionButton(Icons.Outlined.CropFree,stringResource(R.string.canvas_window),{zoomWindow=!zoomWindow;window.value=null},Modifier.testTag("canvas_zoom_window"),selected=zoomWindow)
                    editor?.let {actions->ViewControls(document,document.project.settings.viewMode,actions.onViewMode,actions.onLayerVisibility,actions.onSystemVisibility,actions.onMeasurementsVisibility)}
                    if(is3D) {
                        CanvasActionButton(Icons.Outlined.PanTool,stringResource(if(orbitPan)R.string.canvas_pan else R.string.canvas_orbit),{orbitPan=!orbitPan},selected=orbitPan)
                        for((preset,label)in listOf(ViewPreset.TOP to R.string.canvas_top,ViewPreset.FRONT to R.string.canvas_front,
                            ViewPreset.SIDE to R.string.canvas_side,ViewPreset.ISOMETRIC to R.string.canvas_iso)) {
                            CanvasActionButton(if(preset==ViewPreset.ISOMETRIC)Icons.Outlined.ViewInAr else Icons.Outlined.CropSquare,
                                stringResource(label),{orbit=orbit.preset(preset)},Modifier.testTag("canvas_${preset.name.lowercase().let {if(it=="isometric")"iso" else it}}"))
                        }
                        CanvasActionButton(Icons.Outlined.CameraAlt,stringResource(R.string.canvas_perspective),{orbit=orbit.withProjection(Projection.PERSPECTIVE)},Modifier.testTag("canvas_perspective"),selected=orbit.projection==Projection.PERSPECTIVE)
                        CanvasActionButton(Icons.Outlined.GridView,stringResource(R.string.canvas_orthographic),{orbit=orbit.withProjection(Projection.ORTHOGRAPHIC)},Modifier.testTag("canvas_orthographic"),selected=orbit.projection==Projection.ORTHOGRAPHIC)
                    } else {
                        CanvasActionButton(Icons.Outlined.MyLocation,stringResource(R.string.canvas_probe),{probe=!probe},Modifier.testTag("canvas_probe"),selected=probe)
                        CanvasActionButton(Icons.Outlined.Grid4x4,stringResource(R.string.canvas_snaps),{snapsOpen=true},Modifier.testTag("canvas_snaps"))
                        CanvasActionButton(Icons.Outlined.Clear,stringResource(R.string.canvas_clear_reference),{anchorX=null;anchorY=null},Modifier.testTag("canvas_clear_reference"),enabled=anchor!=null)
                    }
                    if(editor!=null) CanvasActionButton(Icons.Outlined.LibraryAdd,stringResource(R.string.canvas_add_selection),{addSelection=!addSelection},Modifier.testTag("canvas_add_selection"),selected=addSelection)
                    CanvasActionButton(Icons.Outlined.GridOn,stringResource(R.string.canvas_grid),{grid=!grid},selected=grid)
                    CanvasActionButton(Icons.Outlined.Image,stringResource(R.string.canvas_export_png),{onExportView(ViewExportRequest(document,frame,size.width,size.height),ViewExportFormat.PNG)},Modifier.testTag("canvas_export_png"),enabled=geometryMatches && size.width>0)
                    CanvasActionButton(Icons.Outlined.PictureAsPdf,stringResource(R.string.canvas_export_pdf),{onExportView(ViewExportRequest(document,frame,size.width,size.height),ViewExportFormat.PDF)},Modifier.testTag("canvas_export_pdf"),enabled=geometryMatches && size.width>0)
                }
                if(measurementMode!=MeasurementMode.NONE || tool!=DrawingTool.SELECT || zoomWindow || probe) Text(
                    if(measurementMode!=MeasurementMode.NONE) stringResource(when {
                        measurementMode==MeasurementMode.DISTANCE && measurementPicks.isEmpty()->R.string.canvas_measure_pick_first
                        measurementMode==MeasurementMode.DISTANCE->R.string.canvas_measure_pick_second
                        measurementPicks.isEmpty()->R.string.canvas_reference_pick_object
                        measurementPicks.size==1->R.string.canvas_reference_pick_reference
                        else->R.string.canvas_reference_place_label
                    }) else if(editor!=null && tool!=DrawingTool.SELECT) {
                        if(options.useDefaultDimensions && supportsDefaultPlacement(tool)) stringResource(R.string.canvas_place_default,stringResource(drawingToolLabel(tool)))
                        else stringResource(R.string.canvas_tool_points,stringResource(drawingToolLabel(tool)),points.size,tool.autoFinishPoints ?: tool.minimumPoints)
                    } else stringResource(if(zoomWindow)R.string.canvas_window_hint else R.string.canvas_probe_hint),
                    style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=12.dp,vertical=4.dp))
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
                                latestMeasurementMode.value==MeasurementMode.NONE && latestSceneMatches.value && !latestLocked.value && !latestBusy.value && !zoomWindow && !probe) {
                                if(is3D)bodyReady?.picker?.pick3D(orbit,first.x.toDouble(),first.y.toDouble(),size.width.toDouble(),size.height.toDouble(),14.0*density,latestLevel.value)
                                else bodyReady?.picker?.pickPlan(viewport.screenToWorld(first.x.toDouble(),first.y.toDouble(),size.width.toDouble(),size.height.toDouble()),14.0*density/viewport.pixelsPerMetre,latestLevel.value)
                            } else null
                            val bodyMove=bodyHit!=null && bodyHit in bodySelection
                            val bodyPivot=latestBounds.value?.center() ?: Point3()
                            var bodyCancelled=false
                            val probing = probe || (down.type == PointerType.Stylus && latestSettings.value.inputMode == InputMode.STYLUS)
                            updatePointer.value(first)
                            if (zoomWindow) window.value = first to first
                            down.consume()
                            while (true) {
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
                                    } else if (!multi && moved < viewConfiguration.touchSlop) {
                                        updatePointer.value(position)
                                        onCanvasTap.value(position)
                                    }
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                                moved = max(moved, (position - first).getDistance())
                                val pan = event.calculatePan()
                                val sensitivity = latestSettings.value.gestureSensitivity
                                if (pressed >= 2) {
                                    multi = true
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
                                else if (is3D) orbit = if (orbitPan) orbit.pan(pan.x*sensitivity, pan.y*sensitivity, size.width.toDouble(), size.height.toDouble())
                                    else orbit.orbit(-pan.x*.006*sensitivity, pan.y*.006*sensitivity)
                                else if (!probing) viewport = viewport.pan(pan.x*sensitivity, pan.y*sensitivity)
                                updatePointer.value(position)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                    .pointerInput(document.project.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (event.type == PointerEventType.Move && event.changes.none { it.pressed }) {
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
                    if (!zoomWindow && !panelsHidden) ViewCube(orbit, onFace = { normal ->
                        orbit = orbit.copy(yaw = if (normal.z != 0.0) -PI/2 else atan2(normal.y, normal.x),
                            pitch = if (normal.z > 0) PI/2 else if (normal.z < 0) -PI/2 else 0.0)
                    }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(100.dp))
                }
                if (editor != null && !is3D && tool != DrawingTool.SELECT) {
                    ConstructionPreview(document, tool, options, points, cursor.value?.point, floorZ, viewport)
                }
                if(measurementPicks.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
                    fun screen(p:Point3):Offset?=if(is3D)orbit.project(p,size.width.toDouble(),size.height.toDouble())?.let {Offset(it.x.toFloat(),it.y.toFloat())}
                        else viewport.worldToScreen(Point2(p.x,p.y),size.width.toDouble(),size.height.toDouble()).let {Offset(it.x.toFloat(),it.y.toFloat())}
                    val projected=measurementPicks.mapNotNull {screen(it.point)}
                    projected.zipWithNext().forEach {(a,b)->drawLine(accent,a,b,2*density)}
                    projected.forEach {drawCircle(accent,5*density,it)}
                }
                if (editor != null && selectedBounds != null && tool == DrawingTool.SELECT && measurementMode==MeasurementMode.NONE && !zoomWindow) {
                    SelectionGizmo(selectedBounds, viewport, orbit, is3D, preview,
                        onStart = { _, _ -> dragPivot = selectedBounds.center(); dragResizeFrame = selectionResizeFrame
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
                                    GizmoHandle.ROTATE_Z -> EditDelta(pivot = dragPivot, rotationZ = gizmoRotationZ(dragPivot,
                                        start,current,viewport,orbit,is3D,size.width.toDouble(),size.height.toDouble()))
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
                            if (!cancelled && delta != null) {
                                waitingForCommit = document
                                val applied = editor.onTransform(delta)
                                // A rejected/no-op command has no new scene to wait for.
                                if (!applied || delta.isIdentity) {
                                    waitingForCommit = null; preview = null
                                }
                            } else { preview = null; waitingForCommit = null }
                        }, enabled = !busy && !lockedSelection && waitingForCommit == null && sceneMatches,
                        resizeFrame = selectionResizeFrame,allowResize=!selectionHasMeasurements)
                }
                if(!panelsHidden) editor?.let { actions ->
                    panelStates.SaveableStateProvider("tools") {
                    ToolsPanel(tool,options,document.project.settings.lengthUnit,selectedIds.size,
                        actions.canUndo,actions.canRedo,actions.canPaste,points.size >= tool.minimumPoints && points.isNotEmpty(),
                        onTool = { next -> cancelConstruction(); tool = next; probe = false; zoomWindow = false
                            if (next != DrawingTool.SELECT) { is3D = false; actions.onSelect(emptySet()) } },
                        onOptions = { if (it.useDefaultDimensions != options.useDefaultDimensions) cancelConstruction(); options = it },
                        onFinish = { finishConstruction() },onCancel = { cancelConstruction(); tool = DrawingTool.SELECT },
                        onUndo = { cancelConstruction(); actions.onUndo() },onRedo = { cancelConstruction(); actions.onRedo() },
                        onCopy = actions.onCopy,onPaste = { cancelConstruction(); tool = DrawingTool.SELECT; actions.onPaste() },
                        onMirrorX = { if (selectedIds.isNotEmpty()) actions.onMirror(MirrorAxis.X,elementWorldBounds(document,selectedIds).center()) },
                        onMirrorY = { if (selectedIds.isNotEmpty()) actions.onMirror(MirrorAxis.Y,elementWorldBounds(document,selectedIds).center()) },
                        onArray = { arrayOpen = true },onDelete = { cancelConstruction(); actions.onDelete() },
                        onProperties = { propertiesSession++;if(selectionHasMeasurements){measurementsOpen=true;propertiesOpen=false} else {propertiesOpen=true;measurementsOpen=false}
                            libraryOpen=false },modifier = Modifier.align(Alignment.TopStart).padding(start = 30.dp,top = 30.dp),
                        expandedOverride=toolsExpanded,onExpandedChange={toolsExpanded=it;focusedTools=null},focusGroup=focusedTools)
                    }
                    if(propertiesOpen && selectedProperties!=null) panelStates.SaveableStateProvider("properties:$propertiesSession:${selectedElement?.id?.value}") {
                        PropertiesSidePanel(selectedProperties,document.project.settings.lengthUnit,
                        document.project.settings.decimalPlaces,onApply={actions.onProperties(it);propertiesOpen=false},
                        onDismiss={propertiesOpen=false},modifier=Modifier.align(Alignment.TopEnd).padding(8.dp))
                    }
                    if(measurementsOpen) MeasurementsPanel(document,actions.onMeasurementsVisibility,actions.onUpdateMeasurement,
                        actions.onDeleteElements,onDismiss={measurementsOpen=false},modifier=Modifier.align(Alignment.TopEnd).padding(8.dp))
                    if(libraryOpen) LibraryPanel(document.libraryItems,onInsert={id->
                        val at=if(is3D)orbit.target.copy(z=floorZ) else Point3(viewport.center.x,viewport.center.y,floorZ)
                        actions.onInsertLibrary(id,at,selectedLevel);libraryOpen=false
                    },onDismiss={libraryOpen=false},modifier=Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
                if(panelsHidden) CanvasToolbars({},{},{},{},{},{},{},{panelsHidden=false},true,
                    Modifier.align(Alignment.TopEnd).padding(8.dp))
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
            TextButton(onSnaps, enabled = !is3D) { Text(
                if (is3D) stringResource(R.string.canvas_snap_plan_only)
                else if (!document.project.settings.snap.enabled) stringResource(R.string.canvas_snap_off)
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
