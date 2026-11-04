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
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    ) : SceneLoad
    data object Failed : SceneLoad
}

private fun sameCanvasGeometry(a: ProjectDocument, b: ProjectDocument): Boolean =
    a.elements == b.elements && a.layers == b.layers && a.systems == b.systems && a.libraryItems == b.libraryItems &&
        a.project.settings.lengthUnit == b.project.settings.lengthUnit &&
        a.project.settings.angleFormat == b.project.settings.angleFormat &&
        a.project.settings.decimalPlaces == b.project.settings.decimalPlaces

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
    var arrayOpen by rememberSaveable { mutableStateOf(false) }
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
    val selectedBounds = remember(ready?.picker,selectedIds) { ready?.picker?.boundsFor(selectedIds) }
    val lockedSelection = remember(document.elements,document.layers,selectedIds) {
        val lockedLayers = document.layers.filter { it.locked }.mapTo(HashSet()) { it.id }
        document.elements.any { it.id in selectedIds && it.layerId in lockedLayers }
    }
    val geometryMatches = remember(ready?.document,document) { ready?.let { sameCanvasGeometry(it.document,document) } == true }
    val sceneMatches = geometryMatches && ready?.selectedIds == selectedIds
    val selectionResizeFrame = if (sceneMatches) ready?.resizeFrame else null
    val latestGeometryMatches = rememberUpdatedState(geometryMatches)

    LaunchedEffect(document.project.id, document.elements, document.layers, document.systems, document.libraryItems,
        document.project.settings.lengthUnit, document.project.settings.angleFormat, document.project.settings.decimalPlaces, selectedIds) {
        try {
            val previous = prepared
            val compiled = withContext(Dispatchers.Default) {
                val sameGeometry = previous != null && sameCanvasGeometry(previous.document,document)
                val scene = if (sameGeometry) previous!!.scene else buildDrawingScene(document)
                val index = SpatialIndex(scene.segments)
                val main = if (selectedIds.isEmpty()) scene else buildDrawingScene(document.copy(elements = document.elements.filterNot { it.id in selectedIds }))
                val selected = if (selectedIds.isEmpty()) null else buildDrawingScene(document.copy(elements = document.elements.filter { it.id in selectedIds }))
                val resize = if (sameGeometry && previous!!.selectedIds == selectedIds) previous.resizeFrame else
                    if (selectedIds.size == 1) document.elements.find { it.id in selectedIds }?.let { resizeFrame(document,it) } else null
                SceneLoad.Ready(document, selectedIds, scene, main, selected,
                    if (sameGeometry) previous!!.snaps else SnapEngine(index),
                    if (sameGeometry) previous!!.picker else EditorPicker(document, scene, index),resize)
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
        anchorX = null; anchorY = null
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
        if (actions != null && !busy && latestTool.value == DrawingTool.SELECT && !probe && latestGeometryMatches.value) {
            val picker = latestReady.value?.picker
            val picked = if (is3D) picker?.pick3D(orbit, position.x.toDouble(), position.y.toDouble(),
                size.width.toDouble(), size.height.toDouble(), 14.0 * density, selectedLevel)
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
        MaterialTheme.colorScheme.background.luminance() < .5f, floorZ, ready?.selectionScene, preview)
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
            TopAppBar(
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
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("canvas_toolbar").padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(is3D, onClick = {
                    cancelConstruction()
                    tool = DrawingTool.SELECT
                    val nextLevel = if (is3D) level else orbitLevel
                    editor?.onSelect?.invoke(document.elements.asSequence().filter {
                        it.id in selectedIds && (nextLevel == null || it.levelId == null || it.levelId?.value == nextLevel)
                    }.map { it.id }.toSet())
                    if (!is3D && !orbitShown && geometryMatches && ready != null && ready.scene.segments.isNotEmpty() && size.height > 0) {
                        orbit = orbit.fit(ready.scene.boundsForLevel(nextLevel),size.width.toDouble()/size.height)
                        orbitShown = true
                    }
                    is3D = !is3D
                    zoomWindow = false
                    window.value = null
                }, label = { Text(stringResource(if (is3D) R.string.canvas_3d else R.string.canvas_plan)) }, modifier = Modifier.testTag("canvas_3d"))
                OutlinedButton({ fit() }, enabled = geometryMatches, modifier = Modifier.testTag("canvas_extents")) { Text(stringResource(R.string.canvas_extents)) }
                FilterChip(zoomWindow, { zoomWindow = !zoomWindow; window.value = null }, label = { Text(stringResource(R.string.canvas_window)) }, modifier = Modifier.testTag("canvas_zoom_window"))
                if (is3D) {
                    FilterChip(orbitPan, { orbitPan = !orbitPan }, label = { Text(stringResource(if (orbitPan) R.string.canvas_pan else R.string.canvas_orbit)) })
                    for ((preset, label) in listOf(ViewPreset.TOP to R.string.canvas_top, ViewPreset.FRONT to R.string.canvas_front,
                        ViewPreset.SIDE to R.string.canvas_side, ViewPreset.ISOMETRIC to R.string.canvas_iso)) {
                        OutlinedButton({ orbit = orbit.preset(preset) }, modifier = Modifier.testTag("canvas_${preset.name.lowercase().let { if (it == "isometric") "iso" else it }}")) {
                            Text(stringResource(label))
                        }
                    }
                    FilterChip(orbit.projection == Projection.PERSPECTIVE,
                        { orbit = orbit.withProjection(Projection.PERSPECTIVE) }, label = { Text(stringResource(R.string.canvas_perspective)) }, modifier = Modifier.testTag("canvas_perspective"))
                    FilterChip(orbit.projection == Projection.ORTHOGRAPHIC,
                        { orbit = orbit.withProjection(Projection.ORTHOGRAPHIC) }, label = { Text(stringResource(R.string.canvas_orthographic)) }, modifier = Modifier.testTag("canvas_orthographic"))
                } else {
                    FilterChip(probe, { probe = !probe }, label = { Text(stringResource(R.string.canvas_probe)) }, modifier = Modifier.testTag("canvas_probe"))
                    OutlinedButton({ snapsOpen = true }, modifier = Modifier.testTag("canvas_snaps")) { Text(stringResource(R.string.canvas_snaps)) }
                    TextButton({ anchorX = null; anchorY = null }, enabled = anchor != null, modifier = Modifier.testTag("canvas_clear_reference")) { Text(stringResource(R.string.canvas_clear_reference)) }
                }
                if (editor != null) FilterChip(addSelection, { addSelection = !addSelection },
                    label = { Text(stringResource(R.string.canvas_add_selection)) }, modifier = Modifier.testTag("canvas_add_selection"))
                FilterChip(grid, { grid = !grid }, label = { Text(stringResource(R.string.canvas_grid)) })
            }
            Text(if (editor != null && tool != DrawingTool.SELECT) {
                if (options.useDefaultDimensions && supportsDefaultPlacement(tool)) stringResource(R.string.canvas_place_default,stringResource(drawingToolLabel(tool)))
                else stringResource(R.string.canvas_tool_points,stringResource(drawingToolLabel(tool)), points.size, tool.autoFinishPoints ?: tool.minimumPoints)
            } else stringResource(when {
                zoomWindow -> R.string.canvas_window_hint
                is3D && !orbitPan -> R.string.canvas_orbit_hint
                is3D -> R.string.canvas_pan_hint
                probe -> R.string.canvas_probe_hint
                else -> R.string.canvas_plan_hint
            }), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
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
                            val probing = probe || (down.type == PointerType.Stylus && latestSettings.value.inputMode == InputMode.STYLUS)
                            updatePointer.value(first)
                            if (zoomWindow) window.value = first to first
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                val position = event.changes.first().position
                                if (pressed == 0) {
                                    if (zoomWindow) {
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
                                    val zoom = event.calculateZoom().toDouble().pow(sensitivity)
                                    val centroid = event.calculateCentroid()
                                    if (is3D) orbit = orbit.pan(pan.x.toDouble(), pan.y.toDouble(), size.width.toDouble(), size.height.toDouble()).zoom(zoom)
                                    else viewport = viewport.pan(pan.x.toDouble(), pan.y.toDouble()).zoom(zoom,
                                        centroid.x.toDouble(), centroid.y.toDouble(), size.width.toDouble(), size.height.toDouble())
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
                    if (!zoomWindow) ViewCube(orbit, onFace = { normal ->
                        orbit = orbit.copy(yaw = if (normal.z != 0.0) -PI/2 else atan2(normal.y, normal.x),
                            pitch = if (normal.z > 0) PI/2 else if (normal.z < 0) -PI/2 else 0.0)
                    }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(100.dp))
                }
                if (editor != null && !is3D && tool != DrawingTool.SELECT) {
                    ConstructionPreview(document, tool, options, points, cursor.value?.point, floorZ, viewport)
                }
                if (editor != null && selectedBounds != null && tool == DrawingTool.SELECT && !zoomWindow) {
                    SelectionGizmo(selectedBounds, viewport, orbit, is3D, preview,
                        onStart = { _, _ -> dragPivot = selectedBounds.center(); dragResizeFrame = selectionResizeFrame
                            waitingForCommit = null; preview = EditDelta(pivot = dragPivot) },
                        onDrag = { handle, start, current ->
                            try {
                                preview = when (handle) {
                                    GizmoHandle.MOVE_X, GizmoHandle.MOVE_Y, GizmoHandle.MOVE_Z -> {
                                        val axis = when (handle) {
                                            GizmoHandle.MOVE_X -> Point3(x = 1.0)
                                            GizmoHandle.MOVE_Y -> Point3(y = 1.0)
                                            else -> Point3(z = 1.0)
                                        }
                                        EditDelta(pivot = dragPivot, translation = screenAxisDrag(axis, dragPivot,
                                            Point2(start.x.toDouble(),start.y.toDouble()), Point2(current.x.toDouble(),current.y.toDouble()),
                                            viewport, orbit, is3D, size.width.toDouble(), size.height.toDouble()))
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
                        resizeFrame = selectionResizeFrame)
                }
                editor?.let { actions ->
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
                        onProperties = { propertiesOpen = true },modifier = Modifier.align(Alignment.TopStart).padding(start = 30.dp,top = 30.dp))
                }
                if (loading == SceneLoad.Loading) Text(stringResource(R.string.canvas_preparing), Modifier.align(Alignment.Center))
                if (rendererFailed) Text(stringResource(R.string.canvas_renderer_error), Modifier.align(Alignment.Center).padding(32.dp))
                if (loading == SceneLoad.Failed) Text(stringResource(R.string.canvas_prepare_error), Modifier.align(Alignment.Center).padding(32.dp))
            }
        }
    }
    if (snapsOpen) SnapOptions(snapSettings, onSnapSettingsChange, onDismiss = { snapsOpen = false })
    val selectedElement = remember(document.elements,selectedIds,propertiesOpen) {
        if (propertiesOpen) document.elements.singleOrNull { it.id in selectedIds } else null
    }
    if (propertiesOpen && selectedElement != null && editor != null) {
        PropertiesDialog(readProperties(document,selectedElement),document.project.settings.lengthUnit,document.project.settings.decimalPlaces,
            onApply = { editor.onProperties(it); propertiesOpen = false },onDismiss = { propertiesOpen = false })
    }
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
