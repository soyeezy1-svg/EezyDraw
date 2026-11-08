package com.eezydraw.ui

import com.eezydraw.ui.developer.*
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eezydraw.R
import com.eezydraw.core.data.LibraryRepository
import com.eezydraw.core.data.DeviceLibraryRepository
import com.eezydraw.core.data.LibraryArchive
import com.eezydraw.core.editing.*
import com.eezydraw.core.editing.libraryPlacementDefinition
import com.eezydraw.core.data.ProjectRepository
import com.eezydraw.core.data.ProjectStorageException
import com.eezydraw.core.data.SettingsRepository
import com.eezydraw.core.data.StoredProject
import com.eezydraw.core.editing.PresentationCommands
import com.eezydraw.core.editing.lockGeneratedWalls
import com.eezydraw.core.geometry.ScenePresentation
import kotlinx.coroutines.Job
import com.eezydraw.core.editing.EditCommand
import com.eezydraw.core.editing.EditCommands
import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.editing.EditHistory
import com.eezydraw.core.editing.ElementProperties
import com.eezydraw.core.editing.LibraryCommands
import com.eezydraw.core.editing.LibraryInstanceProperties
import com.eezydraw.core.editing.previewLibraryPlacement
import com.eezydraw.core.editing.MirrorAxis
import com.eezydraw.core.editing.SketchGeometry
import com.eezydraw.core.editing.applyProperties
import com.eezydraw.core.editing.createMeasurement
import com.eezydraw.core.editing.updateMeasurement
import com.eezydraw.core.model.*
import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.Element
import com.eezydraw.core.model.ElementBody
import com.eezydraw.core.model.ElementId
import com.eezydraw.core.model.Layer
import com.eezydraw.core.model.LayerId
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.LevelId
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.ProjectTemplate
import com.eezydraw.core.model.SnapSettings
import com.eezydraw.storage.ProjectExchange
import com.eezydraw.storage.ViewExport
import com.eezydraw.storage.ViewExportFormat
import com.eezydraw.storage.ViewExportRequest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AppScreen { MENU, CREATE, PROJECT, PROJECT_DETAILS, SETTINGS, HELP }

data class AppUiState(
    val screen: AppScreen = AppScreen.MENU,
    val settings: AppSettings = AppSettings(),
    val recentProjects: List<StoredProject> = emptyList(),
    val document: ProjectDocument? = null,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val dirty: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val failedProjectId: String? = null,
    val selectedIds: Set<ElementId> = emptySet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val canPaste: Boolean = false,
    val autosaving: Boolean = false,
    val noticeUndo: Boolean = false,
    val libraryCatalog: LibraryCatalog = LibraryCatalog(),
    val libraryReady: Boolean = false,
    val libraryCanUndo: Boolean = false,
    val libraryCanRedo: Boolean = false,
    val clashProgress: Float? = null,
    val clashSummary: String? = null,
    val generationProgress:Float?=null,
    val stressRun:StressRun?=null,
)

class AppViewModel(
    context: Context,
    private val projects: ProjectRepository,
    private val preferences: SettingsRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val deviceLibrary: LibraryRepository = DeviceLibraryRepository(context.filesDir),
) : AndroidViewModel(context.applicationContext as Application) {
    private val application: Application = getApplication()
    private val exchange = ProjectExchange(application, projects)
    private val initialScreen = savedState.get<String>("screen")?.let { name ->
        AppScreen.entries.find { it.name == name }
    } ?: AppScreen.MENU
    private val mutableState = MutableStateFlow(AppUiState(screen = initialScreen))
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    private val libraryHistory = LibraryCatalogHistory()
    private var preferencesReady = false
    private var catalogueReady = false
    private val history = EditHistory()
    private var savedDocument: ProjectDocument? = null
    private var clipboard = emptyList<Element>()
    private var pasteSequence = 0L
    private val storageMutex = Mutex()
    private var panelLayoutSave:Job?=null
    private var pendingViewExport: Pair<ViewExportRequest, ViewExportFormat>? = null

    init {
        viewModelScope.launch {
            try {
                storageMutex.withLock {
                    val catalog = deviceLibrary.load()
                    mutableState.update { it.copy(libraryCatalog = catalog, libraryReady = true) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = "Could not load device library: ${error.message}") }
            }
        }
        viewModelScope.launch {
            preferences.settings.catch { error ->
                preferencesReady = true
                mutableState.update { it.copy(ready = catalogueReady, error = userMessage(error)) }
            }.collect { settings ->
                val initialPreferences=!preferencesReady
                preferencesReady = true
                history.setLimit(settings.undoLimit)
                val active=state.value.document
                val restoredLayout=if(initialPreferences)active?.project?.id?.value?.let {settings.canvasLayouts[it]} else null
                if(restoredLayout!=null && savedDocument?.project?.id==active?.project?.id) {
                    savedDocument=savedDocument?.let {it.copy(project=it.project.copy(settings=it.project.settings.copy(canvasLayout=restoredLayout)))}
                }
                mutableState.update { current->
                    val document=if(restoredLayout!=null && current.document?.project?.id==active?.project?.id)current.document?.let {
                        it.copy(project=it.project.copy(settings=it.project.settings.copy(canvasLayout=restoredLayout)))
                    } else current.document
                    current.copy(settings = settings,document=document,dirty=if(document!=current.document)document!=savedDocument else current.dirty,
                        ready = catalogueReady, canUndo = history.canUndo, canRedo = history.canRedo)
                }
            }
        }
        viewModelScope.launch {
            projects.recentProjects.catch { error ->
                catalogueReady = true
                mutableState.update { it.copy(ready = preferencesReady, error = userMessage(error)) }
            }.collect { recent ->
                catalogueReady = true
                mutableState.update { it.copy(recentProjects = recent, ready = preferencesReady) }
            }
        }
        viewModelScope.launch {
            state.map { it.settings.autosaveIntervalSeconds }.distinctUntilChanged().collectLatest { seconds ->
                if (seconds > 0) while (isActive) {
                    delay(seconds * 1000L)
                    saveIfDirty()
                }
            }
        }
        if (initialScreen == AppScreen.PROJECT || initialScreen == AppScreen.PROJECT_DETAILS) {
            val id = savedState.get<String>("projectId")
            if (id == null) navigate(AppScreen.MENU) else openProject(id, initialScreen)
        }
    }

    fun navigate(screen: AppScreen) {
        savedState["screen"] = screen.name
        if (screen != AppScreen.PROJECT && screen != AppScreen.PROJECT_DETAILS) savedState["projectId"] = null
        mutableState.update { it.copy(screen = screen) }
    }

    fun create(name: String, template: ProjectTemplate, unit: LengthUnit) = operation {
        require(name.isNotBlank() && name.trim().length <= 120)
        val settings = state.value.settings
        var document = projects.create(name.trim(), template, settings.projectDefaults.copy(lengthUnit = unit))
        if(settings.projectDefaults.autoCreateWalls) {
            val marked=document.copy(elements=document.elements.map {e->val room=e.body as? ElementBody.Room
                if(room==null)e else e.copy(body=room.copy(wallOptions=RoomWallOptions(document.project.settings.defaultWallThickness,document.project.settings.defaultWallHeight)))})
            document=RoomWalls.reconcile(document,marked).let {if(it==document)it else projects.save(it,backupEnabled=false)}
        }
        val generated=lockGeneratedWalls(document.copy(elements=emptyList()),document,settings.lockWallsByDefault)
        if(generated!=document)document=projects.save(generated,backupEnabled=false)
        if (document.layers.any { it.lineWeightMm != settings.defaultLineWeightMm }) {
            document = projects.save(
                document.copy(layers = document.layers.map { it.copy(lineWeightMm = settings.defaultLineWeightMm) }),
                backupEnabled = false,
            )
        }
        showProject(document)
        mirror(document)
    }

    fun createTestBuilding(levels:Int=3) = operation {
        require(levels==3 || levels in TestBuilding.stressLevels)
        if(state.value.dirty)check(persistCurrent()) {"Save the active project before generating the sample"}
        mutableState.update {it.copy(generationProgress=0f,stressRun=null)}
        try {
            val began=System.nanoTime()
            val base=projects.create(if(levels==3)TestBuilding.NAME else "${TestBuilding.NAME} — $levels levels",ProjectTemplate.EMPTY,state.value.settings.projectDefaults)
            val built=withContext(Dispatchers.Default) {val context=coroutineContext
                TestBuilding.build(base,levels,progress={value->mutableState.update {it.copy(generationProgress=value)}},cancel={context.ensureActive()})}
            val saved=projects.save(built,backupEnabled=false)
            mirror(saved)
            val generated=(System.nanoTime()-began)/1e6
            val loadStart=System.nanoTime();val loaded=projects.load(base.project.id.value);val loadMillis=(System.nanoTime()-loadStart)/1e6
            showProject(loaded)
            if(levels!=3)mutableState.update {it.copy(stressRun=StressRun(loaded.project.id,levels,loaded.elements.size,generated,loadMillis,RenderFrameCounter(loadStart)))}
        } finally {mutableState.update {it.copy(generationProgress=null)}}
    }
    fun completeStress(result:StressResult) {mutableState.update {state->state.copy(stressRun=state.stressRun?.copy(result=result))}}
    fun dismissStress() {mutableState.update {it.copy(stressRun=null)}}

    fun open(id: String) = openProject(id, AppScreen.PROJECT)

    private fun openProject(id: String, destination: AppScreen) = operation {
        mutableState.update { it.copy(failedProjectId = id) }
        showProject(projects.load(id))
        if (destination == AppScreen.PROJECT_DETAILS) navigate(destination)
    }

    fun continueProject() {
        state.value.recentProjects.firstOrNull()?.let { open(it.id) }
    }

    fun rename(name: String) {
        if (name.isBlank() || name.length > 120) return
        edit { it.copy(project = it.project.copy(name = name.trim())) }
    }

    fun describe(description: String) = edit { it.copy(project = it.project.copy(description = description)) }

    fun changeUnits(unit: LengthUnit) = updateProjectSettings("Change units") {it.copy(lengthUnit=unit)}

    fun changeSnaps(settings: SnapSettings) = updateProjectSettings("Change snaps") {it.copy(snap=settings)}

    fun changeViewMode(mode: ViewMode) = updateProjectSettings("Change view mode") {it.copy(viewMode=mode)}

    fun showMeasurements(show: Boolean) = updateProjectSettings("Measurement visibility") {it.copy(showMeasurements=show)}

    fun changeRotationSnap(degrees:Int)=updateProjectSettings("Rotation snapping") {it.copy(rotationSnapDegrees=degrees)}

    private fun updateProjectSettings(label:String,change:(ProjectSettings)->ProjectSettings) {
        drawingEdit {_,document->EditCommands.between(document,document.copy(project=document.project.copy(settings=change(document.project.settings))),label)}
    }

    fun setLayerVisibility(id: LayerId, visible: Boolean) {
        drawingEdit { _, document -> EditCommands.between(document,
            document.copy(layers = document.layers.map { if (it.id == id) it.copy(visible = visible) else it }), "Layer visibility") }
    }

    fun setSystemVisibility(id: SystemId, visible: Boolean) {
        drawingEdit {_,document->EditCommands.between(document,document.copy(systems=document.systems.map {if(it.id==id)it.copy(visible=visible)else it}),"System visibility")}
    }

    fun setSelectionLayer(id:LayerId) {
        drawingEdit {current,document->
            val layer=requireNotNull(document.layers.find {it.id==id}) {"The layer no longer exists."}
            require(layer.visible && !layer.locked) {"Choose a visible, unlocked layer."}
            EditCommands.replace(document,document.elements.filter {it.id in current.selectedIds}.map {it.copy(layerId=id)},"Change layer")
        }
    }

    fun setSelectionColour(argb:Long) {
        drawingEdit {current,document->EditCommands.replace(document,document.elements.filter {it.id in current.selectedIds}
            .map {it.copy(properties=it.properties+(ELEMENT_COLOUR_KEY to ParameterValue.Colour(argb)))},"Change colour")}
    }

    fun setSelectionLocked(locked:Boolean)=setSelectionLockMode(if(locked)LockMode.ALL else LockMode.NONE)

    private fun presentationEdit(message:String,build:(AppUiState,ProjectDocument)->EditCommand) {
        val before=state.value.document
        if(drawingEdit(build=build) && state.value.document!=before)mutableState.update {it.copy(notice=message,noticeUndo=it.canUndo)}
    }

    fun setSelectionLockMode(mode:LockMode) {
        val current=state.value;val document=current.document ?: return
        val selectedLayers=document.elements.filter {it.id in current.selectedIds}.mapTo(HashSet()) {it.layerId}
        val lockedLayers=document.layers.filter {it.id in selectedLayers && it.locked}.mapTo(HashSet()) {it.id}
        if(mode==LockMode.NONE && lockedLayers.isNotEmpty())presentationEdit("Layer unlocked") {_,source->
            EditCommands.between(source,source.copy(layers=source.layers.map {if(it.id in lockedLayers)it.copy(locked=false)else it}),"Unlock layer")
        } else presentationEdit(if(mode==LockMode.NONE)"Object unlocked"else"Object locked") {state,source->
            PresentationCommands.setLock(source,state.selectedIds,mode)
        }
    }
    fun setLevelLock(levelId:String?,mode:LockMode) = presentationEdit(if(mode==LockMode.NONE)"Level unlocked"else"Level locked") {_,document->
        PresentationCommands.lockLevel(document,levelId?.let(::LevelId),mode)
    }
    fun setSelectionDisplay(display:ObjectDisplay)=setElementsDisplay(state.value.selectedIds,display)
    fun setElementsDisplay(ids:Set<ElementId>,display:ObjectDisplay)=presentationEdit(when(display) {
        ObjectDisplay.HIDDEN->"Object hidden";ObjectDisplay.XRAY->"Object X-ray";ObjectDisplay.WIREFRAME->"Object wireframe";else->"Object shown"
    }) {_,document->PresentationCommands.setDisplay(document,ids,display)}
    fun setRoomSideDisplay(side:RoomSideRef,display:ObjectDisplay)=presentationEdit(if(display==ObjectDisplay.HIDDEN)"Wall hidden"else"Wall display changed") {_,document->
        PresentationCommands.setRoomSideDisplay(document,side,display)
    }
    fun showRoomWalls(roomId:ElementId)=presentationEdit("Room walls shown") {_,document->PresentationCommands.showRoomWalls(document,roomId)}
    fun showAllObjects()=presentationEdit("All objects shown") {_,document->PresentationCommands.showAll(document)}
    fun setLevelVisibility(id:LevelId,visible:Boolean)=presentationEdit(if(visible)"Level shown"else"Level hidden") {_,document->
        PresentationCommands.setLevelVisibility(document,id,visible)
    }
    fun showHiddenObjects(show:Boolean)=updateProjectSettings("Show hidden objects") {it.copy(showHiddenObjects=show)}
    fun autoXrayFrontWalls(enabled:Boolean)=updateProjectSettings("Auto X-ray front walls") {it.copy(autoXrayFrontWalls=enabled)}

    fun setCanvasLayout(layout:CanvasLayout) {
        val current=state.value;val document=current.document ?: return
        if(document.project.settings.canvasLayout==layout)return
        val changed=document.copy(project=document.project.copy(settings=document.project.settings.copy(canvasLayout=layout)))
        val settings=current.settings.copy(canvasLayouts=current.settings.canvasLayouts+(document.project.id.value to layout))
        mutableState.value=current.copy(document=changed,settings=settings,dirty=changed!=savedDocument)
        panelLayoutSave?.cancel()
        panelLayoutSave=viewModelScope.launch {
            delay(150)
            try {preferences.save(state.value.settings)} catch(error:Exception) {
                if(error is CancellationException)throw error
                reportError("Panel layout could not be saved: ${error.message.orEmpty()}")
            }
        }
    }

    fun rotateSelection90() {
        val current=state.value;val document=current.document ?: return
        if(current.selectedIds.isEmpty())return
        try {
            val bounds=com.eezydraw.core.editing.elementWorldBounds(document,current.selectedIds)
            val pivot=document.elements.singleOrNull {it.id in current.selectedIds && it.lockMode==LockMode.POSITION_ONLY}?.transform?.translation
                ?: Point3(bounds.min.x+(bounds.max.x-bounds.min.x)/2,bounds.min.y+(bounds.max.y-bounds.min.y)/2,bounds.min.z+(bounds.max.z-bounds.min.z)/2)
            transformSelection(EditDelta(pivot=pivot,rotation=Rotation3(z=Math.PI/2)))
        } catch(error:IllegalArgumentException) {reportError(error.message ?: "The object could not be rotated.")}
    }

    private fun edit(change: (ProjectDocument) -> ProjectDocument) {
        if (state.value.busy) return
        mutableState.update { current ->
            val document = current.document ?: return@update current
            val changed = change(document)
            current.copy(document = changed, dirty = changed != savedDocument,
                selectedIds = visibleSelection(changed,current.selectedIds))
        }
    }

    fun select(ids: Set<ElementId>) {
        val current = state.value
        if (current.busy) return
        val document = current.document ?: return
        mutableState.update { it.copy(selectedIds = visibleSelection(document,ids)) }
    }

    fun addSketch(geometry: SketchGeometry, levelId: String?): Boolean {
        return drawingEdit(selectAdded = true) { current, document ->
            val (prepared, layer) = drawingLayer(document, current.settings.defaultLineWeightMm)
            val element = Element(
                id = ElementId(UUID.randomUUID().toString()),
                projectId = document.project.id,
                layerId = layer.id,
                body = ElementBody.DrawingShape(geometry.shape),
                levelId = levelId?.let(::LevelId),
                transform = geometry.transform,
            )
            EditCommands.between(document, prepared.copy(elements = document.elements + element), "Create object")
        }
    }

    fun transformSelection(delta: EditDelta): Boolean {
        return drawingEdit { current, document -> EditCommands.transform(document, current.selectedIds, delta) }
    }

    fun updateProperties(properties: ElementProperties) {
        drawingEdit { current, document ->
            require(current.selectedIds.size == 1) { "Select one object to edit its properties." }
            val element = requireNotNull(document.elements.find { it.id in current.selectedIds }) {
                "The selected object no longer exists."
            }
            EditCommands.replace(document, listOf(applyProperties(document, element, properties)))
        }
    }

    fun updateSavedMeasurement(id: ElementId, start: Point3, end: Point3, label: Point3,
        name: String, coordinates: Boolean, heightAboveFloor: Boolean) {
        drawingEdit { _, document ->
            val element = requireNotNull(document.elements.find { it.id == id }) { "The measurement no longer exists." }
            EditCommands.replace(document,listOf(updateMeasurement(document,element,start,end,label,name,coordinates,heightAboveFloor)),"Edit measurement")
        }
    }

    fun deleteElements(ids: Set<ElementId>) {
        drawingEdit { _, document -> EditCommands.delete(document,ids) }
    }

    fun insertLibraryItem(id:LibraryItemId,at:Point3,levelId:String?):Boolean=insertWallLibraryItem(id,at,levelId,null)
    fun insertWallLibraryItem(id: LibraryItemId, at: Point3, levelId: String?,options:WallServiceOptions?):Boolean {
        val placed = drawingEdit(selectAdded = true) { current, document ->
            val source = requireNotNull(current.libraryCatalog.find(id,document.libraryItems)) {
                "The library item is no longer available."
            }
            val item = libraryPlacementDefinition(document, source)
            val (prepared, layer) = drawingLayer(document,current.settings.defaultLineWeightMm)
            val placement = previewLibraryPlacement(prepared,item,at,levelId?.let(::LevelId),layer.id,
                ElementId(UUID.randomUUID().toString()),wallOptions=options)
            val placed=LibraryCommands.place(prepared,placement).preview(prepared)
            EditCommands.between(document,WallServices.connectDevice(placed,placement.element.id),"Insert library item")
        }
        if (placed) state.value.document?.libraryState?.recordUsed(id)?.let(::setLibraryState)
        return placed
    }

    /** Browsing preferences persist with the project, independently of geometry undo. */
    fun setLibraryState(libraryState: LibraryState) = edit { document ->
        val ids = libraryState.favoriteIds + libraryState.recentIds
        val missing = ids.filter { id -> BuiltInLibrary.find(id) == null && document.libraryItems.none { it.id == id } }
            .mapNotNull { state.value.libraryCatalog.find(it)?.copy(thumbnailBase64=null) }
        document.copy(libraryState=libraryState,libraryItems=document.libraryItems+missing)
    }

    fun saveLibraryItem(item: LibraryItem) = libraryChange { it.save(item) }
    fun restoreLibraryItem(id: LibraryItemId) = libraryChange { it.restore(id) }
    fun deleteLibraryItem(id: LibraryItemId) = libraryChange { it.remove(id) }
    fun duplicateLibraryItem(id: LibraryItemId) = libraryChange { catalog ->
        val item = requireNotNull(catalog.find(id,state.value.document?.libraryItems.orEmpty()))
        val newId = LibraryItemId(UUID.randomUUID().toString())
        catalog.save(item.copy(id=newId,builtIn=false,version=1,catalogSourceId=null,
            name=item.name.take(110)+" copy",fitting=item.fitting?.copy(baseTypeId=newId)))
    }
    private fun libraryChange(change: (LibraryCatalog) -> LibraryCatalog) = operation {
        check(state.value.libraryReady) { "The device library has not loaded." }
        val previous = state.value.libraryCatalog
        val next = change(previous)
        if (next != previous) {
            deviceLibrary.save(next)
            libraryHistory.record(previous)
            publishLibrary(next)
        }
    }
    private fun publishLibrary(catalog: LibraryCatalog) = mutableState.update {
        it.copy(libraryCatalog=catalog,libraryReady=true,libraryCanUndo=libraryHistory.canUndo,libraryCanRedo=libraryHistory.canRedo)
    }
    fun undoLibrary() = operation {
        libraryHistory.undoCandidate()?.let { next ->
            deviceLibrary.save(next)
            libraryHistory.acceptUndo(state.value.libraryCatalog)
            publishLibrary(next)
        }
    }
    fun redoLibrary() = operation {
        libraryHistory.redoCandidate()?.let { next ->
            deviceLibrary.save(next)
            libraryHistory.acceptRedo(state.value.libraryCatalog)
            publishLibrary(next)
        }
    }
    fun importLibrary(uri: String) = operation {
        val imported = withContext(Dispatchers.IO) {
            requireNotNull(application.contentResolver.openInputStream(uri.toUri())).use(LibraryArchive::decode)
        }
        val previous = state.value.libraryCatalog
        val next = previous.merge(imported)
        if (next != previous || !state.value.libraryReady) {
            deviceLibrary.save(next)
            if (next != previous) libraryHistory.record(previous)
            publishLibrary(next)
        }
        mutableState.update { it.copy(notice="Library imported") }
    }
    fun exportLibrary(uri: String) = operation {
        check(state.value.libraryReady)
        val catalog = state.value.libraryCatalog
        withContext(Dispatchers.IO) {
            requireNotNull(application.contentResolver.openOutputStream(uri.toUri(),"wt")).use { LibraryArchive.write(catalog,it) }
        }
        mutableState.update { it.copy(notice="Library exported") }
    }

    fun updateLibraryProperties(transform: ElementProperties, properties: LibraryInstanceProperties, updateAll: Boolean = false) {
        drawingEdit { current, document ->
            require(current.selectedIds.size == 1) { "Select one library item to edit its properties." }
            LibraryCommands.edit(document,current.selectedIds.single(),properties,transform,updateAll)
        }
    }

    fun resetLibraryProperties() {
        drawingEdit { current, document ->
            require(current.selectedIds.size == 1) { "Select one library item to reset." }
            LibraryCommands.reset(document,current.selectedIds.single())
        }
    }

    fun copySelection() {
        val current = state.value
        if (current.busy || current.selectedIds.isEmpty()) return
        clipboard = current.document?.elements?.filter { it.id in current.selectedIds } ?: emptyList()
        pasteSequence = 0L
        mutableState.update { it.copy(canPaste = clipboard.isNotEmpty(), error = null) }
    }

    fun pasteSelection() {
        if (clipboard.isEmpty()) return
        val sequence = pasteSequence + 1L
        if (drawingEdit(selectAdded = true) { current, document ->
            val distance = document.project.settings.snap.gridSpacing * 5.0 * sequence
            require(distance.isFinite()) { "The paste offset is too large. Reduce the grid size." }
            val offset = Point3(distance, distance)
            val editableLayers = document.layers.filter { it.visible && !it.locked }.mapTo(HashSet()) { it.id }
            if (clipboard.all { it.layerId in editableLayers }) {
                EditCommands.paste(document, clipboard, offset)
            } else {
                val (prepared, layer) = drawingLayer(document, current.settings.defaultLineWeightMm)
                val paste = EditCommands.paste(prepared, clipboard, offset, layer.id)
                if (prepared === document) paste else
                    EditCommands.between(document, paste.preview(prepared), "Paste objects")
            }
        }) pasteSequence = sequence
    }

    fun mirrorSelection(axis: MirrorAxis, pivot: Point3) {
        drawingEdit { current, document -> EditCommands.mirror(document, current.selectedIds, axis, pivot) }
    }

    fun arraySelection(columns: Int, rows: Int, dx: Double, dy: Double) {
        drawingEdit { current, document -> EditCommands.array(document, current.selectedIds, columns, rows, dx, dy) }
    }

    fun deleteSelection() {
        drawingEdit { current, document ->
            var after=document
            val managed=current.selectedIds.filter {id->document.elements.any {it.id==id && ROUTING_OWNER in it.properties}}
            for(id in managed)after=ServiceRouting.fitting(after,id,null).preview(after)
            val rest=current.selectedIds-managed.toSet()
            if(rest.isNotEmpty())after=EditCommands.delete(after,rest).preview(after)
            EditCommands.between(document,ServiceRouting.reconcile(document,after),"Delete objects")
        }
    }

    fun editWallService(id:ElementId,options:WallServiceOptions?) {drawingEdit {_,document->
        val old=document.elements.first {it.id==id}.wallService()?.options
        var changed=WallServices.edit(document,id,options).preview(document)
        if(old?.drop!=options?.drop) {
            val feeds=changed.elements.filter {(it.properties[WALL_DEVICE_FEED] as? ParameterValue.Text)?.value==id.value}.mapTo(hashSetOf()){it.id}
            if(feeds.isNotEmpty())changed=EditCommands.delete(changed,feeds).preview(changed)
            if(options!=null)changed=WallServices.connectDevice(changed,id)
        }
        EditCommands.between(document,changed,"Change wall service mounting")
    }}
    fun floorServiceSettings(settings:FloorServiceSettings)=updateProjectSettings("Floor services visibility and warnings") {it.copy(floorServices=settings)}
    fun floorRise(id:ElementId,target:ElementId) {drawingEdit {_,doc->FloorServices.rise(doc,id,target)}}
    fun wallServiceSettings(settings:WallServiceSettings)=updateProjectSettings("Wall cut visibility and warnings") {it.copy(wallServices=settings)}
    fun roomCorner(id:ElementId,index:Int,point:Point3) {drawingEdit {_,doc->
        val e=doc.elements.first {it.id==id};val room=e.body as ElementBody.Room;val local=measurementToLocal(e.transform,point)
        EditCommands.replace(doc,listOf(e.copy(body=room.copy(boundary=room.boundary.copy(outer=room.boundary.outer.mapIndexed {i,p->if(i==index)Point2(local.x,local.y)else p})))),"Edit room corner")
    }}
    fun generateRoomWalls(ids:Set<ElementId>,options:RoomWallOptions) {drawingEdit {_,doc->RoomWalls.generate(doc,ids,options)}}
    fun createBuilding(kind:String,points:List<Point3>,level:String?,options:RoomWallOptions,automatic:Boolean):Boolean=drawingEdit {_,doc->RoomWalls.create(doc,kind,points,level?.let(::LevelId),options,automatic)}
    fun createRoute(kind:RouteKind,picks:List<RoutePick>):Boolean = drawingEdit(selectAdded=false) {_,doc->ServiceRouting.create(doc,kind,picks)}
    fun rememberRoutingSettings(kind:RouteKind,value:RoutingSettings) {
        mutableState.update {it.copy(settings=it.settings.copy(lastRoutingSettings=it.settings.lastRoutingSettings+(kind to value)))}
        viewModelScope.launch {try {preferences.save(state.value.settings)}catch(error:Exception){mutableState.update {it.copy(error=error.message)}}}
    }
    fun editRoute(id:ElementId,value:RoutingSettings,start:Point3?,end:Point3?) {drawingEdit {_,doc->ServiceRouting.edit(doc,id,value,start,end)}}
    fun routeFitting(id:ElementId,kind:FittingKind?) {drawingEdit {_,doc->ServiceRouting.fitting(doc,id,kind)}}
    fun routeSupports(id:ElementId,spacing:Double,kind:FittingKind) {drawingEdit {_,doc->ServiceRouting.supports(doc,id,spacing,kind)}}

    fun undo() = restoreDrawing(redo = false)
    fun redo() = restoreDrawing(redo = true)

    /** Commands are sparse and session-only; saving changes the baseline without clearing history. */
    private fun drawingEdit(
        selectAdded: Boolean = false,
        build: (AppUiState, ProjectDocument) -> EditCommand,
    ): Boolean {
        val current = state.value
        if (current.busy) return false
        val document = current.document ?: return false
        return try {
            val prepared = build(current, document)
            val preview=prepared.preview(document)
            val roomWalls=RoomWalls.reconcile(document,preview)
            val attached=FloorServices.reconcile(document,WallServices.reconcile(document,roomWalls))
            val connected=ServiceRouting.reconcile(document,attached)
            FloorServices.validateCuts(document,connected)
            val generated=lockGeneratedWalls(document,connected,current.settings.lockWallsByDefault)
            val command=if(generated==preview)prepared else EditCommands.between(document,generated,prepared.label)
            val changed = history.execute(document, command)
            publishDrawing(current, changed, if (selectAdded && !command.isEmpty) command.addedElementIds else current.selectedIds)
            true
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.message?.takeIf(String::isNotBlank) ?: "The drawing could not be changed.") }
            false
        }
    }

    private fun restoreDrawing(redo: Boolean) {
        val current = state.value
        if (current.busy) return
        val document = current.document ?: return
        try {
            val changed = if (redo) history.redo(document) else history.undo(document)
            publishDrawing(current, changed, current.selectedIds)
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.message?.takeIf(String::isNotBlank) ?: "The drawing history could not be restored.") }
        }
    }

    private fun publishDrawing(current: AppUiState, document: ProjectDocument, selected: Set<ElementId>) {
        mutableState.value = current.copy(
            document = document,
            dirty = document != savedDocument,
            selectedIds = visibleSelection(document,selected),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            canPaste = clipboard.isNotEmpty(),
            error = null,
            notice = (wallDepthWarnings(document)+floorServiceWarnings(document)).firstOrNull(),noticeUndo=false,
        )
    }

    private fun visibleSelection(document: ProjectDocument, ids: Set<ElementId>): Set<ElementId> {
        if (ids.isEmpty()) return emptySet()
        val presentation=ScenePresentation(document,planView=true)
        return document.elements.asSequence().filter { it.id in ids && presentation.shown(presentation.display(it)) &&
            (document.project.settings.showMeasurements || !isMeasurement(it)) }.map {it.id}.toSet()
    }

    private var clashJob: Job? = null
    fun cancelClashCheck() { clashJob?.cancel() }
    fun runClashCheck(options:ClashCheckOptions) {
        if(clashJob?.isCompleted==false)return
        val snapshot=state.value.document ?: return
        mutableState.update {it.copy(clashProgress=0f,clashSummary="Preparing geometry…")}
        clashJob=viewModelScope.launch {
            try {
                val run=withContext(Dispatchers.Default) {
                    val context=kotlin.coroutines.coroutineContext
                    com.eezydraw.core.geometry.ClashDetection.run(snapshot,options,
                        {p->mutableState.update {it.copy(clashProgress=p)}},{context.ensureActive()})
                }
                val current=state.value;val doc=current.document ?: return@launch
                require(doc.project.id==snapshot.project.id && doc.elements==snapshot.elements && doc.layers==snapshot.layers && doc.systems==snapshot.systems && doc.levels==snapshot.levels && doc.libraryItems==snapshot.libraryItems && doc.project.settings.copy(canvasLayout=snapshot.project.settings.canvasLayout)==snapshot.project.settings) {"The drawing changed during the check. Run Clash check again."}
                val command=ClashCommands.reconcile(doc,run,System.currentTimeMillis())
                publishDrawing(current,history.execute(doc,command),current.selectedIds)
                mutableState.update {it.copy(clashSummary="${run.clashes.size} clashes · ${run.checkedObjects} objects · ${run.candidatePairs} nearby pairs")}
            } catch(cancelled:CancellationException) {
                mutableState.update {it.copy(clashSummary="Clash check cancelled; previous results kept")}
            } catch(error:Exception) {reportError(error.message ?: "Clash check failed")}
            finally {mutableState.update {it.copy(clashProgress=null)}}
        }
        val running=clashJob
        running?.invokeOnCompletion {error->if(error is CancellationException && clashJob===running)mutableState.update {it.copy(clashProgress=null,clashSummary="Clash check cancelled; previous results kept")}}
    }
    fun updateClashStatus(id:IssueId,status:ClashStatus,note:String) {
        val current=state.value;val doc=current.document ?: return
        try {publishDrawing(current,history.execute(doc,ClashCommands.status(doc,id,status,note)),current.selectedIds)}
        catch(error:Exception){reportError(error.message ?: "Could not update the clash")}
    }

    fun takeoffSettings(value:TakeoffSettings) {drawingEdit {_,doc->TakeoffCommands.settings(doc,value)}}
    fun takeoffRate(key:String,ids:Set<LibraryItemId>,amount:Double?,reusable:Boolean) = operation {
        val current=state.value;val doc=current.document ?: return@operation
        val command=TakeoffCommands.rate(doc,key,amount,ids)
        if(reusable) {
            val currency=doc.project.settings.takeoff.currency
            var catalog=current.libraryCatalog
            for(id in ids) {
                val definition=doc.libraryItems.find {it.id==id} ?: BuiltInLibrary.find(id) ?: continue
                val sourceId=definition.catalogSourceId ?: definition.fitting?.baseTypeId ?: id
                val item=catalog.find(sourceId,doc.libraryItems) ?: definition.copy(builtIn=BuiltInLibrary.find(definition.id)!=null)
                catalog=catalog.save(item.copy(unitRates=if(amount==null)item.unitRates-currency else item.unitRates+(currency to amount)))
            }
            deviceLibrary.save(catalog);libraryHistory.record(current.libraryCatalog);publishLibrary(catalog)
        }
        publishDrawing(state.value,history.execute(doc,command),current.selectedIds)
    }
    fun shareSchedule(definition:ScheduleDefinition,planView:Boolean,format:com.eezydraw.storage.ScheduleFormat) = operation {
        val doc=state.value.document ?: return@operation
        val table=withContext(Dispatchers.Default){com.eezydraw.core.geometry.ScheduleEngine.build(doc,definition,planView)}
        val file=withContext(Dispatchers.IO){java.io.File(application.cacheDir,"issue-share/schedule-${UUID.randomUUID()}.${format.extension}").also {file->file.parentFile!!.mkdirs();file.outputStream().use {com.eezydraw.storage.ScheduleExport.write(doc,table,format,it)}}}
        val uri=androidx.core.content.FileProvider.getUriForFile(application,application.packageName+".issues",file)
        val send=Intent(Intent.ACTION_SEND).setType(format.mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        application.startActivity(Intent.createChooser(send,"Share schedule").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun saveIssue(issue:Issue) {
        val current=state.value;val doc=current.document ?: return
        try {publishDrawing(current,history.execute(doc,IssueCommands.put(doc,issue)),current.selectedIds)}catch(e:Exception){reportError(e.message ?: "Cannot save issue")}
    }
    fun deleteIssue(issue:Issue) {
        val current=state.value;val doc=current.document ?: return
        try {publishDrawing(current,history.execute(doc,IssueCommands.delete(doc,issue.id)),current.selectedIds)}catch(e:Exception){reportError(e.message ?: "Cannot delete issue")}
    }
    fun attachIssueImage(issue:Issue,uri:String) = operation {
        val bitmap=withContext(Dispatchers.IO) {com.eezydraw.storage.IssueMedia.decode(application,uri.toUri())}
        try {storeIssueBitmap(issue,bitmap,false)}finally {bitmap.recycle()}
    }
    fun attachIssueScreenshot(issue:Issue,marks:List<IssueMarkup>,bitmap:android.graphics.Bitmap) {
        val owned=bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888,true)
        operation {try {withContext(Dispatchers.Default){com.eezydraw.storage.paintIssueMarkups(android.graphics.Canvas(owned),marks,owned.width,owned.height)};storeIssueBitmap(issue.copy(annotations=issue.annotations.filterNot {it.viewId==issue.capture?.id || it.viewId.isEmpty()}+marks),owned,true)}finally {owned.recycle()}}
    }
    private suspend fun storeIssueBitmap(issue:Issue,bitmap:android.graphics.Bitmap,screenshot:Boolean) {
        val current=state.value;val doc=current.document ?: return
        require(!issue.locked){"Unlock the issue first"}
        val asset=withContext(Dispatchers.IO) {com.eezydraw.storage.IssueMedia.store(application,doc,bitmap)}
        val next=if(screenshot)issue.copy(screenshot=asset.id,capture=issue.capture?.copy(screenshot=asset.id))else issue.copy(attachments=issue.attachments+asset.id)
        publishDrawing(current,history.execute(doc,IssueCommands.put(doc,next,listOf(asset))),current.selectedIds)
    }
    fun exportIssues(uri:String,format:String,id:IssueId?=null) = operation {
        val doc=state.value.document ?: return@operation
        withContext(Dispatchers.IO){application.contentResolver.openOutputStream(uri.toUri(),"w")?.use {com.eezydraw.storage.IssueMedia.export(application,doc,format,id,it)} ?: error("Cannot open issue export")}
    }
    fun shareIssue(issue:Issue,format:String) = operation {
        val doc=state.value.document ?: return@operation
        val file=withContext(Dispatchers.IO){java.io.File(application.cacheDir,"issue-share/${UUID.randomUUID()}.$format").also {it.parentFile!!.mkdirs();it.outputStream().use {out->com.eezydraw.storage.IssueMedia.export(application,doc.copy(issues=doc.issues.filterNot {it.id==issue.id}+issue),format,issue.id,out)}}}
        val send=com.eezydraw.storage.IssueMedia.shareIntent(application,file,format)
        application.startActivity(Intent.createChooser(send,"Share issue").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private var pendingDrawingExport:Pair<ViewExportRequest,com.eezydraw.storage.DrawingExportOptions>?=null
    fun prepareDrawingExport(request:ViewExportRequest,options:com.eezydraw.storage.DrawingExportOptions) {pendingDrawingExport=request to options}
    fun cancelDrawingExport() {pendingDrawingExport=null}
    fun exportDrawing(uri:String) = operation {
        val (request,options)=pendingDrawingExport ?: error("Choose Export 2D drawing again.")
        withContext(Dispatchers.IO) {
            val assets=java.io.File(application.filesDir,"projects/${request.document.project.id.value}/assets")
            application.contentResolver.openOutputStream(uri.toUri(),"w")?.use {com.eezydraw.storage.DrawingExport.write(request,options,assets,it)} ?: error("The drawing export file cannot be opened.")
        }
        pendingDrawingExport=null
        mutableState.update {it.copy(notice="2D drawing exported",noticeUndo=false)}
    }

    private var pendingModelExport:Pair<ProjectDocument,com.eezydraw.core.geometry.ModelExportOptions>?=null
    fun prepareModelExport(options:com.eezydraw.core.geometry.ModelExportOptions) {
        state.value.document?.let {pendingModelExport=it to options.copy(appVersion=application.packageManager.getPackageInfo(application.packageName,0).versionName ?: "unknown")}
    }
    fun cancelModelExport() {pendingModelExport=null}
    fun exportModel(uri:String) = operation {
        val (snapshot,options)=pendingModelExport ?: error("Choose Export 3D model and its options again.")
        withContext(Dispatchers.IO) {
            application.contentResolver.openOutputStream(uri.toUri(),"w")?.use {com.eezydraw.core.geometry.ModelExport.write(snapshot,options,it)} ?: error("The selected model export file cannot be opened.")
        }
        pendingModelExport=null
        mutableState.update {it.copy(notice="3D model exported",noticeUndo=false)}
    }

    fun prepareViewExport(request: ViewExportRequest, format: ViewExportFormat) {
        pendingViewExport = request to format
    }

    fun cancelViewExport() { pendingViewExport = null }

    fun exportView(uri: String) = operation {
        val (request, format) = pendingViewExport ?: error("Choose a current view to export again.")
        withContext(Dispatchers.IO) {
            application.contentResolver.openOutputStream(uri.toUri(),"w")?.use { ViewExport.write(request,format,it) }
                ?: error(application.getString(R.string.file_unavailable_error))
        }
        pendingViewExport = null
        mutableState.update { it.copy(notice = application.getString(R.string.canvas_view_exported),noticeUndo=false) }
    }

    private fun drawingLayer(document: ProjectDocument, lineWeight: Double): Pair<ProjectDocument, Layer> {
        document.layers.firstOrNull { it.visible && !it.locked }?.let { return document to it }
        val names = document.layers.mapTo(HashSet()) { it.name }
        var name = "Drawing"
        var suffix = 2
        while (name in names) name = "Drawing ${suffix++}"
        val layer = Layer(LayerId(UUID.randomUUID().toString()), document.project.id, name, lineWeightMm = lineWeight)
        return document.copy(layers = document.layers + layer) to layer
    }

    fun saveIfDirty() {
        val current = state.value
        if (!current.dirty || current.busy || current.autosaving || current.document == null) return
        mutableState.update { it.copy(autosaving = true) }
        viewModelScope.launch {
            try {
                storageMutex.withLock {
                    if (state.value.dirty && persistCurrent() && state.value.settings.showAutosaveNotification) {
                        mutableState.update { it.copy(notice = application.getString(R.string.project_autosaved_notice),noticeUndo=false) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(error = userMessage(error)) }
            } finally {
                mutableState.update { it.copy(autosaving = false) }
            }
        }
    }

    fun save() = operation {
        if (persistCurrent()) {
            mutableState.update { it.copy(notice = application.getString(R.string.project_saved_notice),noticeUndo=false) }
        }
    }

    fun saveCameraView(view:SavedView){drawingEdit {_,doc->NavigationCommands.save(doc,view)}}
    fun deleteCameraView(id:ViewId){drawingEdit {_,doc->NavigationCommands.delete(doc,id)}}
    fun navigationSettings(value:AppSettings) = operation {
        val merged=state.value.settings.copy(gestureSensitivity=value.gestureSensitivity,invertOrbit=value.invertOrbit,zoomTowardFinger=value.zoomTowardFinger,showAxisIndicator=value.showAxisIndicator)
        preferences.save(merged);mutableState.update {it.copy(settings=merged)}
    }
    fun discardProjectAndExit() = operation {
        history.clear();mutableState.update {it.copy(document=savedDocument,dirty=false,selectedIds=emptySet(),canUndo=false,canRedo=false)}
        navigate(AppScreen.MENU)
    }

    fun backFromProject() {
        if (state.value.dirty) operation { persistCurrent(); navigate(AppScreen.MENU) }
        else navigate(AppScreen.MENU)
    }

    fun saveSettings(settings: AppSettings,returnToProject:Boolean=false) = operation {
        val oldDefaults=state.value.settings.projectDefaults
        val changed=RouteKind.entries.filter {oldDefaults.routeDefaults[it]!=settings.projectDefaults.routeDefaults[it] || oldDefaults.defaultRouteElevations[it]!=settings.projectDefaults.defaultRouteElevations[it] || oldDefaults.floorServices!=settings.projectDefaults.floorServices}.toSet()
        val merged=settings.copy(canvasLayouts=state.value.settings.canvasLayouts,lastRoutingSettings=settings.lastRoutingSettings-changed)
        preferences.save(merged)
        mutableState.update { it.copy(settings = merged, notice = application.getString(R.string.settings_saved_notice),noticeUndo=false) }
        navigate(if(returnToProject && state.value.document!=null)AppScreen.PROJECT else AppScreen.MENU)
    }

    fun chooseFolder(uri: String) = operation {
        withContext(Dispatchers.IO) {
            application.contentResolver.takePersistableUriPermission(
                uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val settings = state.value.settings.copy(saveLocationUri = uri)
        preferences.save(settings)
        mutableState.update { it.copy(settings = settings) }
    }

    fun resetFolder() = operation {
        val settings = state.value.settings.copy(saveLocationUri = null)
        preferences.save(settings)
        mutableState.update { it.copy(settings = settings) }
    }

    fun importProject(uri: String) = operation {
        val document = withContext(Dispatchers.IO) {
            application.contentResolver.openInputStream(uri.toUri())?.use { projects.importProject(it) }
                ?: error(application.getString(R.string.file_unavailable_error))
        }
        showProject(document)
        mutableState.update { it.copy(notice = application.getString(R.string.project_imported_notice),noticeUndo=false) }
    }

    fun exportProject(uri: String) = operation {
        if (state.value.dirty) persistCurrent()
        val document = state.value.document ?: return@operation
        withContext(Dispatchers.IO) {
            application.contentResolver.openOutputStream(uri.toUri(), "w")?.use {
                projects.exportProject(document.project.id.value, it)
            } ?: error(application.getString(R.string.file_unavailable_error))
        }
        mutableState.update { it.copy(notice = application.getString(R.string.project_exported_notice),noticeUndo=false) }
    }

    fun restoreBackup() {
        val id = state.value.failedProjectId ?: return
        operation {
            showProject(projects.restoreBackup(id))
            mutableState.update { it.copy(notice = application.getString(R.string.backup_restored_notice),noticeUndo=false) }
        }
    }

    fun clearError() = mutableState.update { it.copy(error = null, failedProjectId = null) }
    fun clearNotice() = mutableState.update { it.copy(notice = null,noticeUndo=false) }
    fun reportError(message: String) = mutableState.update { it.copy(error = message) }

    private suspend fun persistCurrent(): Boolean {
        val snapshot = state.value.document ?: return false
        val saved = projects.save(snapshot, state.value.settings.backupEnabled)
        if (state.value.document?.project?.id == snapshot.project.id) savedDocument = saved
        mutableState.update { current ->
            val draft = current.document ?: return@update current
            if (draft.project.id != snapshot.project.id) current
            else {
                // Keep edits made while the snapshot was being written, while accepting its
                // repository timestamp so undo back to the saved contents becomes clean.
                val reconciled = if (draft == snapshot) saved else draft.copy(
                    project = draft.project.copy(updatedAtEpochMillis = saved.project.updatedAtEpochMillis),
                )
                current.copy(document = reconciled, dirty = reconciled != saved)
            }
        }
        return mirror(saved)
    }

    private suspend fun mirror(document: ProjectDocument): Boolean {
        return try {
            exchange.mirror(document, state.value.settings)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(notice = application.getString(R.string.folder_copy_failed_notice),noticeUndo=false) }
            false
        }
    }

    private fun showProject(loaded: ProjectDocument) {
        val remembered=state.value.settings.canvasLayouts[loaded.project.id.value]
        val document=if(remembered==null)loaded else loaded.copy(project=loaded.project.copy(settings=loaded.project.settings.copy(canvasLayout=remembered)))
        savedDocument = document
        history.clear()
        history.setLimit(state.value.settings.undoLimit)
        clipboard = emptyList()
        pasteSequence = 0L
        savedState["projectId"] = document.project.id.value
        savedState["screen"] = AppScreen.PROJECT.name
        mutableState.update {
            it.copy(
                screen = AppScreen.PROJECT, document = document, dirty = false, failedProjectId = null,stressRun=null,
                selectedIds = emptySet(), canUndo = false, canRedo = false, canPaste = false,
            )
        }
    }

    private fun operation(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, notice = null,noticeUndo=false) }
        viewModelScope.launch {
            try {
                storageMutex.withLock { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if ((state.value.screen == AppScreen.PROJECT || state.value.screen == AppScreen.PROJECT_DETAILS) && state.value.document == null) {
                    navigate(AppScreen.MENU)
                }
                mutableState.update { it.copy(error = userMessage(error)) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun userMessage(error: Throwable): String = when (error) {
        is ProjectStorageException -> error.message ?: application.getString(R.string.project_storage_error)
        is IllegalArgumentException -> error.message ?: application.getString(R.string.project_storage_error)
        is IllegalStateException -> error.message ?: application.getString(R.string.project_storage_error)
        is SecurityException -> application.getString(R.string.folder_permission_error)
        else -> application.getString(R.string.project_storage_error)
    }
}
