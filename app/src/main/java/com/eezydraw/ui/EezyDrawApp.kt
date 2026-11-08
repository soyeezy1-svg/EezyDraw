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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
import androidx.compose.material3.SnackbarResult
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
    val importLibrary = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {viewModel.importLibrary(it.toString())} }
    val exportLibrary = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let {viewModel.exportLibrary(it.toString())} }
    var modelOptions by remember {mutableStateOf<com.eezydraw.core.geometry.ModelExportOptions?>(null)}
    var modelDialog by remember {mutableStateOf(false)}
    val exportModel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->
        if(uri!=null)viewModel.exportModel(uri.toString())else viewModel.cancelModelExport()
    }
    if(modelDialog)state.document?.let {document->com.eezydraw.ui.canvas.ModelExportDialog(document,modelOptions ?: com.eezydraw.core.geometry.ModelExportOptions(),{options->
        modelOptions=options;viewModel.prepareModelExport(options);modelDialog=false
        exportModel.launch(document.project.name.replace(Regex("[^A-Za-z0-9 _-]"),"_").take(80)+"."+options.format.extension)
    },{modelDialog=false})}
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.exportProject(it.toString()) }
    }
    val exportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if(uri!=null)viewModel.exportDrawing(uri.toString()) else viewModel.cancelDrawingExport()
    }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if(uri!=null)viewModel.exportDrawing(uri.toString()) else viewModel.cancelDrawingExport()
    }
    var drawingRequest by remember {mutableStateOf<com.eezydraw.storage.ViewExportRequest?>(null)}
    var drawingOptions by remember {mutableStateOf(com.eezydraw.storage.DrawingExportOptions())}
    val exportDxf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/dxf")) {uri->if(uri!=null)viewModel.exportDrawing(uri.toString())else viewModel.cancelDrawingExport()}
    drawingRequest?.let {request->com.eezydraw.ui.canvas.DrawingExportDialog(request,drawingOptions,{snapshot,options->
        viewModel.prepareDrawingExport(snapshot,options);drawingRequest=null
        val name=snapshot.document.project.name.replace(Regex("[^A-Za-z0-9 _-]"),"_").take(80)+"."+options.format.extension
        when(options.format){com.eezydraw.storage.DrawingFormat.PDF->exportPdf.launch(name);com.eezydraw.storage.DrawingFormat.PNG->exportPng.launch(name);com.eezydraw.storage.DrawingFormat.DXF->exportDxf.launch(name)}
    },{drawingRequest=null})}
    var photoIssue by remember {mutableStateOf<com.eezydraw.core.model.Issue?>(null)}
    var photoIssueKey by rememberSaveable {mutableStateOf<String?>(null)}
    var cameraUriText by rememberSaveable {mutableStateOf<String?>(null)}
    var settingsReturnToProject by rememberSaveable {mutableStateOf(false)}
    var issueExportFormat by remember {mutableStateOf("pdf")}
    val issuePdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")){uri->uri?.let {viewModel.exportIssues(it.toString(),"pdf")}}
    val issueCsv=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->uri?.let {viewModel.exportIssues(it.toString(),"csv")}}
    val galleryIssue=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->(photoIssue ?: state.document?.issues?.find {it.id.value==photoIssueKey})?.let {i->uri?.let {viewModel.attachIssueImage(i,it.toString())}};photoIssue=null;photoIssueKey=null}
    val cameraIssue=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){success->if(success)(photoIssue ?: state.document?.issues?.find {it.id.value==photoIssueKey})?.let {i->cameraUriText?.let {viewModel.attachIssueImage(i,it)}};photoIssue=null;photoIssueKey=null}
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.chooseFolder(it.toString()) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (state.settings.autosaveIntervalSeconds > 0) viewModel.saveIfDirty()
    }
    LaunchedEffect(state.notice,state.noticeUndo) {
        state.notice?.let { message ->
            val result=snackbar.showSnackbar(message,actionLabel=if(state.noticeUndo)context.getString(R.string.editing_undo)else null,withDismissAction=true)
            if(result==SnackbarResult.ActionPerformed)viewModel.undo()
            viewModel.clearNotice()
        }
    }
    EezyDrawTheme(themeMode = state.settings.theme) {
        SyncSystemBars(dark = !startupComplete || MaterialTheme.colorScheme.background.luminance() < .5f)
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = {if(state.screen!=AppScreen.PROJECT)SnackbarHost(snackbar, Modifier.navigationBarsPadding())},
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
                                            onInsertWallLibrary={id,p,l,options->viewModel.insertWallLibraryItem(id,p,l,options)},onEditWallService=viewModel::editWallService,onWallSettings=viewModel::wallServiceSettings,onFloorSettings=viewModel::floorServiceSettings,onFloorRise=viewModel::floorRise,
                                            onRoomCorner=viewModel::roomCorner,onGenerateWalls=viewModel::generateRoomWalls,onCreateBuilding=viewModel::createBuilding,onCreateRoute=viewModel::createRoute,onRoutingSettings=viewModel::rememberRoutingSettings,
                                            onEditRoute=viewModel::editRoute,onRouteFitting=viewModel::routeFitting,onRouteSupports=viewModel::routeSupports,
                                            onViewMode=viewModel::changeViewMode,onLayerVisibility=viewModel::setLayerVisibility,
                                            onSystemVisibility=viewModel::setSystemVisibility,onMeasurementsVisibility=viewModel::showMeasurements,
                                            onUpdateMeasurement=viewModel::updateSavedMeasurement,onDeleteElements=viewModel::deleteElements,
                                            onInsertLibrary=viewModel::insertLibraryItem,
                                            onSelectionLayer=viewModel::setSelectionLayer,onSelectionColour=viewModel::setSelectionColour,
                                            onSelectionLocked=viewModel::setSelectionLocked,onRotate90=viewModel::rotateSelection90,
                                            onRotationSnap=viewModel::changeRotationSnap,
                                            onCanvasLayout=viewModel::setCanvasLayout,onSelectionLockMode=viewModel::setSelectionLockMode,
                                            onLevelLock=viewModel::setLevelLock,onSelectionDisplay=viewModel::setSelectionDisplay,
                                            onElementsDisplay=viewModel::setElementsDisplay,onRoomSideDisplay=viewModel::setRoomSideDisplay,
                                            onShowRoomWalls=viewModel::showRoomWalls,onLevelVisibility=viewModel::setLevelVisibility,
                                            onShowAll=viewModel::showAllObjects,onShowHidden=viewModel::showHiddenObjects,onAutoXray=viewModel::autoXrayFrontWalls,
                                            onLibraryState=viewModel::setLibraryState,onLibraryProperties=viewModel::updateLibraryProperties,
                                            onResetLibraryProperties=viewModel::resetLibraryProperties,
                                            libraryCatalog=state.libraryCatalog,libraryReady=state.libraryReady,
                                            onSaveLibraryItem=viewModel::saveLibraryItem,onRestoreLibraryItem=viewModel::restoreLibraryItem,
                                            onDeleteLibraryItem=viewModel::deleteLibraryItem,onDuplicateLibraryItem=viewModel::duplicateLibraryItem,
                                            onImportLibrary={importLibrary.launch(arrayOf("*/*"))},
                                            onExportLibrary={exportLibrary.launch("EEZY-DRAW-library.eezy-library")},
                                            onUndoLibrary=viewModel::undoLibrary,onRedoLibrary=viewModel::redoLibrary,
                                            libraryCanUndo=state.libraryCanUndo,libraryCanRedo=state.libraryCanRedo,
                                        ),
                                        onSaveCameraView=viewModel::saveCameraView,onDeleteCameraView=viewModel::deleteCameraView,onDiscardExit=viewModel::discardProjectAndExit,
                                        onOpenSettings={settingsReturnToProject=true;viewModel.navigate(AppScreen.SETTINGS)},onNavigationSettings=viewModel::navigationSettings,
                                        onTakeoffSettings=viewModel::takeoffSettings,onTakeoffRate=viewModel::takeoffRate,onShareSchedule=viewModel::shareSchedule,
                                        onSaveIssue=viewModel::saveIssue,onDeleteIssue=viewModel::deleteIssue,onIssueScreenshot=viewModel::attachIssueScreenshot,onShareIssue=viewModel::shareIssue,
                                        onIssueExport={format->if(format=="pdf")issuePdf.launch("issues.pdf")else issueCsv.launch("issues.csv")},
                                        onIssuePhoto={issue,camera->viewModel.saveIssue(issue);photoIssue=issue;photoIssueKey=issue.id.value;if(camera){val file=java.io.File(context.cacheDir,"issue-share/camera-${java.util.UUID.randomUUID()}.jpg");file.parentFile!!.mkdirs();val uri=androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".issues",file);cameraUriText=uri.toString();cameraIssue.launch(uri)}else galleryIssue.launch(arrayOf("image/png","image/jpeg"))},
                                        clashProgress=state.clashProgress,clashSummary=state.clashSummary,onRunClash=viewModel::runClashCheck,onCancelClash=viewModel::cancelClashCheck,onClashStatus=viewModel::updateClashStatus,
                                        onExportModel={level,plan->modelOptions=com.eezydraw.core.geometry.ModelExportOptions(levelId=level?.let {com.eezydraw.core.model.LevelId(it)},selected=state.selectedIds,planView=plan);modelDialog=true},
                                        onExportDrawing={request->drawingOptions=com.eezydraw.storage.DrawingExportOptions();drawingRequest=request},
                                        onExportView={request,format->drawingOptions=com.eezydraw.storage.DrawingExportOptions(format=if(format==ViewExportFormat.PNG)com.eezydraw.storage.DrawingFormat.PNG else com.eezydraw.storage.DrawingFormat.PDF);drawingRequest=request},
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
                                onSave = {value->viewModel.saveSettings(value,settingsReturnToProject);settingsReturnToProject=false},
                                onBack = {viewModel.navigate(if(settingsReturnToProject)AppScreen.PROJECT else AppScreen.MENU);settingsReturnToProject=false},
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
                    if(state.screen==AppScreen.PROJECT && state.document?.project?.settings?.canvasLayout?.hideAll!=true) {
                        val width=LocalWindowInfo.current.containerSize.width/LocalDensity.current.density
                        val noticeModifier=if(width>=760)Modifier.padding(start=184.dp,end=256.dp,top=8.dp)else Modifier.padding(start=8.dp,end=8.dp,top=if(width<520)260.dp else 136.dp)
                        SnackbarHost(snackbar,Modifier.align(Alignment.TopCenter).statusBarsPadding().then(noticeModifier).fillMaxWidth())
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
    BackHandler(enabled = startupComplete && state.ready && state.screen != AppScreen.MENU && state.screen != AppScreen.SETTINGS && state.screen != AppScreen.PROJECT) {
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
