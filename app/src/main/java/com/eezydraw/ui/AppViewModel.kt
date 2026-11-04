package com.eezydraw.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eezydraw.R
import com.eezydraw.core.data.ProjectRepository
import com.eezydraw.core.data.ProjectStorageException
import com.eezydraw.core.data.SettingsRepository
import com.eezydraw.core.data.StoredProject
import com.eezydraw.core.editing.EditCommand
import com.eezydraw.core.editing.EditCommands
import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.editing.EditHistory
import com.eezydraw.core.editing.ElementProperties
import com.eezydraw.core.editing.MirrorAxis
import com.eezydraw.core.editing.SketchGeometry
import com.eezydraw.core.editing.applyProperties
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
)

class AppViewModel(
    context: Context,
    private val projects: ProjectRepository,
    private val preferences: SettingsRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : AndroidViewModel(context.applicationContext as Application) {
    private val application: Application = getApplication()
    private val exchange = ProjectExchange(application, projects)
    private val initialScreen = savedState.get<String>("screen")?.let { name ->
        AppScreen.entries.find { it.name == name }
    } ?: AppScreen.MENU
    private val mutableState = MutableStateFlow(AppUiState(screen = initialScreen))
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    private var preferencesReady = false
    private var catalogueReady = false
    private val history = EditHistory()
    private var savedDocument: ProjectDocument? = null
    private var clipboard = emptyList<Element>()
    private var pasteSequence = 0L

    init {
        viewModelScope.launch {
            preferences.settings.catch { error ->
                preferencesReady = true
                mutableState.update { it.copy(ready = catalogueReady, error = userMessage(error)) }
            }.collect { settings ->
                preferencesReady = true
                history.setLimit(settings.undoLimit)
                mutableState.update {
                    it.copy(settings = settings, ready = catalogueReady, canUndo = history.canUndo, canRedo = history.canRedo)
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
        if (document.layers.any { it.lineWeightMm != settings.defaultLineWeightMm }) {
            document = projects.save(
                document.copy(layers = document.layers.map { it.copy(lineWeightMm = settings.defaultLineWeightMm) }),
                backupEnabled = false,
            )
        }
        showProject(document)
        mirror(document)
    }

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

    fun changeUnits(unit: LengthUnit) = edit {
        it.copy(project = it.project.copy(settings = it.project.settings.copy(lengthUnit = unit)))
    }

    fun changeSnaps(settings: SnapSettings) = edit {
        it.copy(project = it.project.copy(settings = it.project.settings.copy(snap = settings)))
    }

    private fun edit(change: (ProjectDocument) -> ProjectDocument) {
        if (state.value.busy) return
        mutableState.update { current ->
            val document = current.document ?: return@update current
            val changed = change(document)
            current.copy(document = changed, dirty = changed != savedDocument)
        }
    }

    fun select(ids: Set<ElementId>) {
        val current = state.value
        if (current.busy) return
        val existing = current.document?.elements?.mapTo(HashSet()) { it.id } ?: emptySet()
        mutableState.update { it.copy(selectedIds = ids.intersect(existing)) }
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
        drawingEdit { current, document -> EditCommands.delete(document, current.selectedIds) }
    }

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
            val command = build(current, document)
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
        val existing = document.elements.mapTo(HashSet()) { it.id }
        mutableState.value = current.copy(
            document = document,
            dirty = document != savedDocument,
            selectedIds = selected.intersect(existing),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            canPaste = clipboard.isNotEmpty(),
            error = null,
            notice = null,
        )
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
        if (state.value.dirty && !state.value.busy) save()
    }

    fun save() = operation {
        if (persistCurrent()) {
            mutableState.update { it.copy(notice = application.getString(R.string.project_saved_notice)) }
        }
    }

    fun backFromProject() {
        if (state.value.dirty) operation { persistCurrent(); navigate(AppScreen.MENU) }
        else navigate(AppScreen.MENU)
    }

    fun saveSettings(settings: AppSettings) = operation {
        preferences.save(settings)
        mutableState.update { it.copy(settings = settings, notice = application.getString(R.string.settings_saved_notice)) }
        navigate(AppScreen.MENU)
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
        mutableState.update { it.copy(notice = application.getString(R.string.project_imported_notice)) }
    }

    fun exportProject(uri: String) = operation {
        if (state.value.dirty) persistCurrent()
        val document = state.value.document ?: return@operation
        withContext(Dispatchers.IO) {
            application.contentResolver.openOutputStream(uri.toUri(), "w")?.use {
                projects.exportProject(document.project.id.value, it)
            } ?: error(application.getString(R.string.file_unavailable_error))
        }
        mutableState.update { it.copy(notice = application.getString(R.string.project_exported_notice)) }
    }

    fun restoreBackup() {
        val id = state.value.failedProjectId ?: return
        operation {
            showProject(projects.restoreBackup(id))
            mutableState.update { it.copy(notice = application.getString(R.string.backup_restored_notice)) }
        }
    }

    fun clearError() = mutableState.update { it.copy(error = null, failedProjectId = null) }
    fun clearNotice() = mutableState.update { it.copy(notice = null) }
    fun reportError(message: String) = mutableState.update { it.copy(error = message) }

    private suspend fun persistCurrent(): Boolean {
        val snapshot = state.value.document ?: return false
        val saved = projects.save(snapshot, state.value.settings.backupEnabled)
        savedDocument = saved
        mutableState.update { current ->
            if (current.document == snapshot) current.copy(document = saved, dirty = false)
            else current.copy(dirty = true)
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
            mutableState.update { it.copy(notice = application.getString(R.string.folder_copy_failed_notice)) }
            false
        }
    }

    private fun showProject(document: ProjectDocument) {
        savedDocument = document
        history.clear()
        history.setLimit(state.value.settings.undoLimit)
        clipboard = emptyList()
        pasteSequence = 0L
        savedState["projectId"] = document.project.id.value
        savedState["screen"] = AppScreen.PROJECT.name
        mutableState.update {
            it.copy(
                screen = AppScreen.PROJECT, document = document, dirty = false, failedProjectId = null,
                selectedIds = emptySet(), canUndo = false, canRedo = false, canPaste = false,
            )
        }
    }

    private fun operation(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                action()
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
        is SecurityException -> application.getString(R.string.folder_permission_error)
        else -> application.getString(R.string.project_storage_error)
    }
}
