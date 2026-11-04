package com.eezydraw.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eezydraw.R
import com.eezydraw.storage.ProjectExchange
import com.eezydraw.ui.help.HelpScreen
import com.eezydraw.ui.canvas.DrawingCanvasScreen
import com.eezydraw.ui.canvas.CanvasFrame
import com.eezydraw.ui.canvas.CanvasEditorActions
import com.eezydraw.storage.ViewExportFormat
import com.eezydraw.core.geometry.DrawingScene
import com.eezydraw.ui.projects.CreateProjectScreen
import com.eezydraw.ui.projects.ProjectDetailsScreen
import com.eezydraw.ui.projects.lengthUnitLabel
import com.eezydraw.ui.projects.projectTemplateLabel
import com.eezydraw.ui.settings.SettingsScreen
import com.eezydraw.ui.start.RecentProject
import com.eezydraw.ui.start.SplashScreen
import com.eezydraw.ui.start.StartMenuAction
import com.eezydraw.ui.start.StartMenuScreen
import com.eezydraw.ui.start.StartMenuUiState
import com.eezydraw.ui.theme.EezyDrawTheme

/** ViewModel owns durable workflows; only animation completion belongs to local saved UI state. */
@Composable
fun EezyDrawApp(
    viewModel: AppViewModel,
    canvasSurface: (@Composable (DrawingScene?, CanvasFrame, Modifier) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var startupComplete by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val canvasStates = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importProject(it.toString()) }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.exportProject(it.toString()) }
    }
    val exportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if(uri!=null)viewModel.exportView(uri.toString()) else viewModel.cancelViewExport()
    }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if(uri!=null)viewModel.exportView(uri.toString()) else viewModel.cancelViewExport()
    }
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.chooseFolder(it.toString()) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (state.settings.autosaveIntervalSeconds > 0) viewModel.saveIfDirty()
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { message ->
            snackbar.showSnackbar(message)
            viewModel.clearNotice()
        }
    }
    EezyDrawTheme(themeMode = state.settings.theme) {
        SyncSystemBars(dark = !startupComplete || MaterialTheme.colorScheme.background.luminance() < .5f)
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    AnimatedContent(
                        targetState = startupComplete,
                        transitionSpec = {
                            (fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 24 })
                                .togetherWith(fadeOut(tween(300)))
                        },
                        label = "Splash to application",
                    ) { complete ->
                        if (!complete) SplashScreen(onFinished = { startupComplete = true })
                        else if (!state.ready) LoadingIndicator()
                        else when (state.screen) {
                            AppScreen.MENU -> StartMenuScreen(
                                state = StartMenuUiState(state.recentProjects.map { project ->
                                    RecentProject(
                                        project.id, project.name,
                                        stringResource(projectTemplateLabel(project.template)) + " · " +
                                            stringResource(lengthUnitLabel(project.units)),
                                        project.thumbnailUri,
                                    )
                                }),
                                onAction = { action ->
                                    when (action) {
                                        StartMenuAction.CreateProject, StartMenuAction.Templates -> viewModel.navigate(AppScreen.CREATE)
                                        StartMenuAction.ContinueProject -> viewModel.continueProject()
                                        StartMenuAction.OpenFile -> openFile.launch(arrayOf("*/*"))
                                        StartMenuAction.Settings -> viewModel.navigate(AppScreen.SETTINGS)
                                        StartMenuAction.Help -> viewModel.navigate(AppScreen.HELP)
                                        is StartMenuAction.OpenRecent -> viewModel.open(action.projectId)
                                    }
                                },
                            )
                            AppScreen.CREATE -> CreateProjectScreen(
                                state.settings.projectDefaults, state.settings.defaultTemplate, state.busy,
                                viewModel::create, { viewModel.navigate(AppScreen.MENU) },
                            )
                            AppScreen.PROJECT -> state.document?.let { document ->
                                canvasStates.SaveableStateProvider("canvas:${document.project.id.value}") {
                                    DrawingCanvasScreen(
                                        document, state.settings, state.dirty, state.busy,
                                        viewModel::changeSnaps, viewModel::changeUnits, viewModel::save,
                                        { exportFile.launch(ProjectExchange.fileName(document, state.settings)) },
                                        { viewModel.navigate(AppScreen.PROJECT_DETAILS) }, viewModel::backFromProject,
                                        surface = canvasSurface,
                                        editor = CanvasEditorActions(
                                            state.selectedIds,state.canUndo,state.canRedo,state.canPaste,
                                            viewModel::addSketch,viewModel::select,viewModel::transformSelection,viewModel::updateProperties,
                                            viewModel::copySelection,viewModel::pasteSelection,viewModel::deleteSelection,viewModel::undo,viewModel::redo,
                                            viewModel::mirrorSelection,viewModel::arraySelection,
                                            onViewMode=viewModel::changeViewMode,onLayerVisibility=viewModel::setLayerVisibility,
                                            onSystemVisibility=viewModel::setSystemVisibility,onMeasurementsVisibility=viewModel::showMeasurements,
                                            onUpdateMeasurement=viewModel::updateSavedMeasurement,onDeleteElements=viewModel::deleteElements,
                                            onInsertLibrary=viewModel::insertLibraryItem,
                                        ),
                                        onExportView={request,format->
                                            viewModel.prepareViewExport(request,format)
                                            val base=document.project.name.replace(Regex("[^A-Za-z0-9 _-]"),"_").take(80)
                                            if(format==ViewExportFormat.PNG)exportPng.launch("$base-view.png") else exportPdf.launch("$base-view.pdf")
                                        },
                                    )
                                }
                            } ?: LoadingIndicator()
                            AppScreen.PROJECT_DETAILS -> state.document?.let { document ->
                                ProjectDetailsScreen(
                                    document, state.dirty, state.busy, viewModel::rename, viewModel::describe,
                                    viewModel::changeUnits, viewModel::save,
                                    { exportFile.launch(ProjectExchange.fileName(document, state.settings)) },
                                    { viewModel.navigate(AppScreen.PROJECT) },
                                )
                            } ?: LoadingIndicator()
                            AppScreen.SETTINGS -> SettingsScreen(
                                settings = state.settings,
                                onSave = viewModel::saveSettings,
                                onBack = { viewModel.navigate(AppScreen.MENU) },
                                onChooseSaveLocation = { chooseFolder.launch(state.settings.saveLocationUri?.toUri()) },
                                onResetSaveLocation = viewModel::resetFolder,
                                onOpenPermissions = {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            "package:${context.packageName}".toUri()))
                                    } catch (_: ActivityNotFoundException) {
                                        viewModel.reportError(context.getString(R.string.permissions_unavailable_error))
                                    }
                                },
                            )
                            AppScreen.HELP -> HelpScreen(
                                onBack = { viewModel.navigate(AppScreen.MENU) },
                                onFeedback = { feedback ->
                                    try {
                                        context.startActivity(Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.feedback_share_title))
                                                putExtra(Intent.EXTRA_TEXT, "EEZY DRAW 0.1.0\n\n$feedback")
                                            }, context.getString(R.string.feedback_share_title),
                                        ))
                                    } catch (_: ActivityNotFoundException) {
                                        viewModel.reportError(context.getString(R.string.share_unavailable_error))
                                    }
                                },
                            )
                        }
                    }
                    if (state.busy && startupComplete) {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .7f))
                                .pointerInput(Unit) { awaitPointerEventScope {
                                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                                } },
                        ) { LoadingIndicator() }
                    }
                }
            }
            state.error?.let { error ->
                AlertDialog(
                    onDismissRequest = viewModel::clearError,
                    title = { Text(stringResource(R.string.operation_error_title)) },
                    text = { Text(error) },
                    confirmButton = { TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.close)) } },
                    dismissButton = state.failedProjectId?.let {
                        { TextButton(onClick = viewModel::restoreBackup) { Text(stringResource(R.string.restore_project_backup)) } }
                    },
                )
            }
        }
    }
    BackHandler(enabled = startupComplete && state.ready && state.screen != AppScreen.MENU && state.screen != AppScreen.SETTINGS) {
        if (!state.busy) {
            if (state.screen == AppScreen.PROJECT) viewModel.backFromProject()
            else if (state.screen == AppScreen.PROJECT_DETAILS) viewModel.navigate(AppScreen.PROJECT)
            else viewModel.navigate(AppScreen.MENU)
        }
    }
    BackHandler(enabled = state.busy) { /* Protect an in-flight save/open from navigation. */ }
}

@Composable
private fun LoadingIndicator() {
    val description = stringResource(R.string.working_description)
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = description })
    }
}
