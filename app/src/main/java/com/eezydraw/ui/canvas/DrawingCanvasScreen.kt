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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
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
import com.eezydraw.core.model.*
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

private sealed interface SceneLoad {
    data object Loading : SceneLoad
    data class Ready(val scene: DrawingScene, val snaps: SnapEngine) : SceneLoad
    data object Failed : SceneLoad
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
) {
    var viewport by rememberSaveable(document.project.id.value, stateSaver = PlanCameraSaver) { mutableStateOf(Viewport2D()) }
    var orbit by rememberSaveable(document.project.id.value, stateSaver = OrbitCameraSaver) { mutableStateOf(OrbitCamera()) }
    var is3D by rememberSaveable(document.project.id.value) { mutableStateOf(false) }
    var initialized by rememberSaveable(document.project.id.value) { mutableStateOf(false) }
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
    val cursor = remember { mutableStateOf<SnapResult?>(null) }
    val window = remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val ready = loading as? SceneLoad.Ready
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

    LaunchedEffect(document.project.id, document.elements, document.layers, document.systems, document.libraryItems) {
        loading = SceneLoad.Loading
        try {
            loading = withContext(Dispatchers.Default) {
                val scene = buildDrawingScene(document)
                SceneLoad.Ready(scene, SnapEngine(SpatialIndex(scene.segments)))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            loading = SceneLoad.Failed
        }
    }
    fun fit() {
        val scene = ready?.scene ?: return
        if (size.width <= 0 || size.height <= 0) return
        val bounds = scene.boundsForLevel(selectedLevel)
        if (is3D) orbit = orbit.fit(bounds, size.width.toDouble() / size.height)
        else viewport = viewport.fit(bounds, size.width.toDouble(), size.height.toDouble(), 48.0 * density)
    }
    LaunchedEffect(ready, size, selectedLevel) {
        if (!initialized && ready != null && size.width > 0 && size.height > 0) {
            viewport = viewport.fit(ready.scene.boundsForLevel(level), size.width.toDouble(), size.height.toDouble(), 48.0 * density)
            orbit = orbit.fit(ready.scene.bounds, size.width.toDouble() / size.height)
            initialized = true
        }
    }
    val updatePointer = rememberUpdatedState<(Offset) -> Unit>({ position ->
        if (!is3D && size.width > 0 && size.height > 0) {
            val point = viewport.screenToWorld(position.x.toDouble(), position.y.toDouble(), size.width.toDouble(), size.height.toDouble())
            cursor.value = latestReady.value?.snaps?.snap(
                point, latestSnaps.value, max(latestSnaps.value.tolerance, 14.0 * density / viewport.pixelsPerMetre),
                latestAnchor.value, latestLevel.value,
            ) ?: SnapResult(point, null)
        }
    })
    val accent = MaterialTheme.colorScheme.primary
    val frame = CanvasFrame(viewport, orbit, is3D, selectedLevel, snapSettings.gridSpacing, grid,
        MaterialTheme.colorScheme.background.luminance() < .5f, floorZ)
    Scaffold(
        modifier = modifier.testTag("drawing_canvas"),
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
                onLevel = { if (is3D) orbitLevel = it else level = it }, onSnaps = { snapsOpen = true })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("canvas_toolbar").padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(is3D, onClick = {
                    is3D = !is3D
                    zoomWindow = false
                    window.value = null
                }, label = { Text(stringResource(if (is3D) R.string.canvas_3d else R.string.canvas_plan)) }, modifier = Modifier.testTag("canvas_3d"))
                OutlinedButton({ fit() }, enabled = ready != null, modifier = Modifier.testTag("canvas_extents")) { Text(stringResource(R.string.canvas_extents)) }
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
                FilterChip(grid, { grid = !grid }, label = { Text(stringResource(R.string.canvas_grid)) })
            }
            Text(stringResource(when {
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
                            val down = awaitFirstDown()
                            if (latestReady.value == null) return@awaitEachGesture
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
                                    } else if (!multi && !is3D && moved < viewConfiguration.touchSlop) {
                                        updatePointer.value(position)
                                        cursor.value?.point?.let { anchorX = it.x; anchorY = it.y }
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
                if (surface != null) surface(ready?.scene, frame, Modifier.fillMaxSize())
                else NativeDrawingSurface(ready?.scene, frame, Modifier.fillMaxSize(), onError = { rendererFailed = true })
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
                if (loading == SceneLoad.Loading) Text(stringResource(R.string.canvas_preparing), Modifier.align(Alignment.Center))
                if (rendererFailed) Text(stringResource(R.string.canvas_renderer_error), Modifier.align(Alignment.Center).padding(32.dp))
                if (loading == SceneLoad.Failed) Text(stringResource(R.string.canvas_prepare_error), Modifier.align(Alignment.Center).padding(32.dp))
            }
        }
    }
    if (snapsOpen) SnapOptions(snapSettings, onSnapSettingsChange, onDismiss = { snapsOpen = false })
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
