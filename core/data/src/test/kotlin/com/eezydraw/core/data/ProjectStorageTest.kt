package com.eezydraw.core.data

import com.eezydraw.core.editing.TestBuilding
import android.content.Context
import androidx.room.Room
import com.eezydraw.core.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectStorageTest {
    @Test fun testBuildingLoadsSavesExportsImportsAndRestoresWithAllReferences() = runBlocking {
        val base=repository.create(TestBuilding.NAME,ProjectTemplate.EMPTY,ProjectSettings())
        val sample=TestBuilding.build(base)
        val saved=repository.save(sample,backupEnabled=false)
        assertEquals(saved,repository.load(saved.project.id.value))
        val out=ByteArrayOutputStream();repository.exportProject(saved.project.id.value,out)
        val imported=repository.importProject(ByteArrayInputStream(out.toByteArray()))
        assertEquals(3,imported.levels.size);assertEquals(saved.elements.size,imported.elements.size)
        assertEquals(saved.issues.size,imported.issues.size);assertEquals(saved.views.map {it.name},imported.views.map {it.name})
        assertEquals(saved.libraryItems,imported.libraryItems)
        repository.save(saved.copy(project=saved.project.copy(description="Edited sample")))
        val restored=repository.restoreBackup(saved.project.id.value)
        assertEquals(saved.copy(project=saved.project.copy(updatedAtEpochMillis=restored.project.updatedAtEpochMillis)),restored)
    }

    @Test fun mobileLayoutAndNamedCameraRotationSectionSurviveSaveArchiveAndBackup() = runBlocking {
        val source=repository.create("Mobile canvas",ProjectTemplate.EMPTY,ProjectSettings())
        val box=Bounds3(Point3(-1.0,-2.0,-3.0),Point3(4.0,5.0,6.0))
        val layout=CanvasLayout(hideAll=true,openGroup=CanvasGroup.NAVIGATE,bottomBarVisible=false)
        val capture=IssueView("capture",Point2(2.0,3.0),120.0,Point3(),.5,.5,10.0,10.0,Projection.ORTHOGRAPHIC,false,source.levels.first().id,source.project.settings,emptyMap(),emptyMap(),emptyMap(),400,300,true,planRotation=.4,sectionBox=box)
        val view=SavedView(ViewId("named"),source.project.id,"Rotated section",Camera(Point3(z=10.0),Point3()),sectionBox=box,capture=capture)
        val saved=repository.save(source.copy(views=listOf(view),project=source.project.copy(settings=source.project.settings.copy(canvasLayout=layout))))
        assertEquals(saved,repository.load(source.project.id.value))
        val output=ByteArrayOutputStream();repository.exportProject(source.project.id.value,output)
        val imported=repository.importProject(ByteArrayInputStream(output.toByteArray()))
        assertEquals(layout,imported.project.settings.canvasLayout);assertEquals(view.copy(projectId=imported.project.id),imported.views.single())
        repository.save(saved.copy(views=emptyList()))
        assertEquals(view,repository.restoreBackup(saved.project.id.value).views.single())
    }

    @Test fun schedulesRatesAndLibraryDefaultsSurviveSaveArchiveAndBackup() = runBlocking {
        val source=repository.create("Takeoff",ProjectTemplate.EMPTY,ProjectSettings())
        val item=LibraryItem(LibraryItemId("priced"),"Priced item",Discipline.ELECTRICAL,"Devices",Size3(.1,.1,.1),unitRates=mapOf("USD" to 25.0))
        val definitions=providedSchedules().map {it.copy(levels=setOf(source.levels.first().id),visibleOnly=false)}
        val settings=TakeoffSettings(10.0,"USD",definitions,mapOf("rate" to mapOf("USD" to 4.5)))
        val saved=repository.save(source.copy(libraryItems=listOf(item),project=source.project.copy(settings=source.project.settings.copy(takeoff=settings))))
        assertEquals(saved,repository.load(source.project.id.value))
        val output=ByteArrayOutputStream();repository.exportProject(source.project.id.value,output)
        val imported=repository.importProject(ByteArrayInputStream(output.toByteArray()))
        assertEquals(settings,imported.project.settings.takeoff);assertEquals(item,imported.libraryItems.single())
        assertEquals(mapOf("USD" to 25.0),LibraryArchive.decode(ByteArrayInputStream(LibraryArchive.encode(LibraryCatalog(items=listOf(item))))).items.single().unitRates)
        repository.save(saved.copy(project=saved.project.copy(settings=saved.project.settings.copy(takeoff=settings.copy(currency="EUR")))))
        assertEquals(settings,repository.restoreBackup(saved.project.id.value).project.settings.takeoff)
    }

    @Test fun issueReviewAndAttachmentsSurviveLoadArchiveAndBackup() = runBlocking {
        val source=repository.create("Issue review",ProjectTemplate.EMPTY,ProjectSettings())
        val id=source.project.id;val asset=ProjectAsset(AssetId("photo"),id,"photo.png","image/png")
        val bytes=byteArrayOf(1,2,3,4)
        ProjectFiles(File(context.filesDir,"projects")).writeAssets(id.value,mapOf(asset.relativePath to bytes))
        val capture=IssueView("view",Point2(2.0,3.0),100.0,Point3(),.5,.5,10.0,10.0,Projection.ORTHOGRAPHIC,true,source.levels.first().id,source.project.settings,emptyMap(),emptyMap(),emptyMap(),400,300,true)
        val issue=Issue(IssueId("issue"),id,"Repair",1,description="Description",priority=IssuePriority.HIGH,dueDate="2026-12-01",assignedTo="Pat",position=Point3(2.0,3.0),capture=capture,attachments=listOf(asset.id),screenshot=asset.id,annotations=listOf(IssueMarkup(MarkupTool.CLOUD,listOf(Point2(.1,.1),Point2(.8,.8)),viewId=capture.id)))
        val saved=repository.save(source.copy(issues=listOf(issue),assets=listOf(asset)))
        assertEquals(saved,repository.load(id.value))
        val out=ByteArrayOutputStream();repository.exportProject(id.value,out)
        val imported=repository.importProject(ByteArrayInputStream(out.toByteArray()))
        assertEquals(issue.copy(projectId=imported.project.id),imported.issues.single())
        repository.save(saved.copy(issues=listOf(issue.copy(status=IssueStatus.CLOSED))))
        assertEquals(IssueStatus.OPEN,repository.restoreBackup(id.value).issues.single().status)
    }

    @Test fun clashStatusesNotesOptionsAndResolvedAuditSurviveSaveLoadArchiveAndBackup() = runBlocking {
        val created=repository.create("Coordination",ProjectTemplate.EMPTY,ProjectSettings())
        val p=created.project.id;val layer=created.layers.first().id
        fun element(id:String)=Element(ElementId(id),p,layer,ElementBody.DrawingShape(Shape.Box(Size3(1.0,1.0,1.0))))
        val a=element("a");val b=element("b")
        val clash=Clash(a.id,b.id,ClashKind.DUPLICATE,Point3(.5,.5,.5),status=ClashStatus.ACTIVE,note="Check bracket",checkKey="all",firstName="A",secondName="B")
        val issue=Issue(IssueId("clash-stable"),p,"Duplicate",1,elementIds=setOf(a.id,b.id),clash=clash)
        val checked=repository.save(created.copy(elements=listOf(a,b),issues=listOf(issue),project=created.project.copy(settings=created.project.settings.copy(clashCheck=ClashCheckOptions(clearance=.1,tolerance=.002)))))
        assertEquals(checked,repository.load(p.value))
        val archive=ByteArrayOutputStream();repository.exportProject(p.value,archive)
        val imported=repository.importProject(ByteArrayInputStream(archive.toByteArray()))
        assertEquals(checked.issues.single().clash,imported.issues.single().clash)
        val approved=repository.save(checked.copy(issues=listOf(issue.copy(clash=clash.copy(status=ClashStatus.APPROVED,note="Accepted")))))
        assertEquals(ClashStatus.APPROVED,repository.load(p.value).issues.single().clash!!.status)
        assertEquals(ClashStatus.ACTIVE,repository.restoreBackup(p.value).issues.single().clash!!.status)
        val audit=approved.copy(elements=listOf(a),issues=listOf(issue.copy(elementIds=setOf(a.id),clash=clash.copy(status=ClashStatus.RESOLVED))))
        assertEquals(ClashStatus.RESOLVED,repository.save(audit).issues.single().clash!!.status)
        assertEquals(ClashStatus.RESOLVED,repository.load(p.value).issues.single().clash!!.status)
    }
    private lateinit var context: Context
    private lateinit var database: ProjectDatabase
    private lateinit var repository: RoomProjectRepository
    private lateinit var databaseName: String

    @Before
    fun openDatabase() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "test-${UUID.randomUUID()}.db"
        database = open()
        repository = RoomProjectRepository(context, database)
    }

    @After
    fun closeDatabase() {
        database.close()
        context.deleteDatabase(databaseName)
        File(context.filesDir, "projects").deleteRecursively()
    }

    @Test
    fun createdTemplateAndEditedDocumentSurviveDatabaseReopen() = runBlocking {
        val settings = ProjectSettings(lengthUnit = LengthUnit.FEET, decimalPlaces = 3)
        val created = repository.create("  West Wing  ", ProjectTemplate.OFFICE, settings)
        assertEquals("West Wing", created.project.name)
        assertEquals(ProjectTemplate.OFFICE, created.project.template)
        assertTrue(created.elements.any { it.body is ElementBody.Room })
        val edited = repository.save(created.copy(project = created.project.copy(description = "Coordinated layout")))
        val summary = repository.recentProjects.first().single()
        assertNotNull(summary.thumbnailUri)
        assertTrue(File(java.net.URI(summary.thumbnailUri)).length() > 0)

        database.close()
        database = open()
        repository = RoomProjectRepository(context, database)
        assertEquals(edited, repository.load(created.project.id.value))
        assertEquals(LengthUnit.FEET, repository.recentProjects.first().single().units)
    }

    @Test
    fun panelLayoutLocksAndIndividualRoomSideDisplaySurviveReopenAndPortableFiles() = runBlocking {
        val original=repository.create("Presentation",ProjectTemplate.OFFICE,ProjectSettings())
        val room=original.elements.first {it.body is ElementBody.Room}
        val side=RoomSideRef(room.id,0)
        val displayed=room.withLock(LockMode.POSITION_ONLY).withDisplay(ObjectDisplay.XRAY)
            .withRoomSideDisplay(side,ObjectDisplay.HIDDEN)
        val layout=CanvasLayout(hideAll=true).withPanel(CanvasPanelId.PROPERTIES,
            PanelPlacement(PanelVisibility.COLLAPSED,.82,.44)).withPanel(CanvasPanelId.DRAW,
            PanelPlacement(PanelVisibility.OPEN,.12,.7))
        val settings=original.project.settings.copy(canvasLayout=layout,showHiddenObjects=true,
            hiddenLevelIds=original.levels.take(1).mapTo(mutableSetOf()) {it.id},autoXrayFrontWalls=true)
        val saved=repository.save(original.copy(project=original.project.copy(settings=settings),
            elements=original.elements.map {if(it.id==room.id)displayed else it}))
        val archive=ByteArrayOutputStream()
        repository.exportProject(saved.project.id.value,archive)
        database.close();database=open();repository=RoomProjectRepository(context,database)
        assertEquals(saved,repository.load(saved.project.id.value))
        val imported=repository.importProject(ByteArrayInputStream(archive.toByteArray()))
        assertEquals(settings,imported.project.settings)
        val importedRoom=imported.elements.first {it.id==room.id}
        assertEquals(LockMode.POSITION_ONLY,importedRoom.lockMode)
        assertEquals(ObjectDisplay.XRAY,importedRoom.objectDisplay)
        assertEquals(ObjectDisplay.HIDDEN,importedRoom.roomSideDisplay(RoomSideRef(importedRoom.id,0)))
        assertEquals(room.body,importedRoom.body)
        assertEquals(room.transform,importedRoom.transform)
    }

    @Test
    fun openingAProjectMovesItToTheFrontOfRecents() = runBlocking {
        val first = repository.create("First", ProjectTemplate.EMPTY, ProjectSettings())
        val second = repository.create("Second", ProjectTemplate.RESIDENTIAL, ProjectSettings())
        assertEquals(second.project.id.value, repository.recentProjects.first().first().id)

        repository.load(first.project.id.value)

        assertEquals(first.project.id.value, repository.recentProjects.first().first().id)
    }

    @Test
    fun damagedPrimaryDoesNotOverwriteGoodBackupAndExplicitRestoreRecoversIt() = runBlocking {
        val original = repository.create("Original", ProjectTemplate.DATA_CENTER, ProjectSettings())
        val changed = repository.save(original.copy(project = original.project.copy(name = "Changed")))
        val row = database.projectDao().find(changed.project.id.value)!!
        projectFile(row.id, row.currentFile).writeText("damaged project bytes")

        expectStorageFailure { repository.load(row.id) }
        val restored = repository.restoreBackup(row.id)

        assertEquals("Original", restored.project.name)
        assertEquals(restored, repository.load(row.id))
        assertEquals("Original", repository.recentProjects.first().single().name)
    }

    @Test
    fun failedPreviewWriteKeepsPreviousCatalogueAndDocumentIntact() = runBlocking {
        val original = repository.create("Stable", ProjectTemplate.EMPTY, ProjectSettings())
        val row = database.projectDao().find(original.project.id.value)!!
        val previews = projectFile(row.id, "previews")
        assertTrue(previews.deleteRecursively())
        previews.writeText("Block the generated preview directory")

        expectStorageFailure {
            repository.save(original.copy(project = original.project.copy(name = "Unsaved")))
        }

        assertEquals(row, database.projectDao().find(row.id))
        assertEquals(original, repository.load(row.id))
        assertEquals(1, projectFile(row.id, "revisions").listFiles()!!.size)
    }

    @Test
    fun exportImportClonesOwnershipAndRetainsAttachmentsWithoutOverwritingTheSource() = runBlocking {
        val original = repository.create("Portable", ProjectTemplate.OFFICE, ProjectSettings())
        val bytes = "portable attachment".toByteArray()
        val asset = ProjectAsset(AssetId("notes"), original.project.id, "notes/readme.txt", "text/plain", sha256(bytes))
        val source = original.copy(assets = listOf(asset))
        ProjectFiles.writeAtomic(projectFile(original.project.id.value, "assets/${asset.relativePath}"), bytes)
        val saved = repository.save(source)
        val archive = ByteArrayOutputStream()
        repository.exportProject(saved.project.id.value, archive)

        val imported = repository.importProject(ByteArrayInputStream(archive.toByteArray()))

        assertNotEquals(saved.project.id, imported.project.id)
        assertEquals(saved.elements.map { it.body }, imported.elements.map { it.body })
        assertTrue(imported.elements.all { it.projectId == imported.project.id })
        assertEquals(imported.project.id, imported.assets.single().projectId)
        assertArrayEquals(bytes, projectFile(imported.project.id.value, "assets/${asset.relativePath}").readBytes())
        assertEquals(saved, repository.load(saved.project.id.value))
        assertEquals(2, repository.recentProjects.first().size)
    }

    @Test
    fun unsafeTruncatedAndFutureArchivesAreRejectedWithoutAddingRecents() = runBlocking {
        val original = repository.create("Safe", ProjectTemplate.EMPTY, ProjectSettings())
        val encoded = ProjectArchive.encodeDocument(original)
        val future = encoded.toString(Charsets.UTF_8).replace("\"version\":1", "\"version\":2").toByteArray()
        expectStorageFailure { repository.importProject(ByteArrayInputStream(zip("document.json" to future))) }
        expectStorageFailure { repository.importProject(ByteArrayInputStream(zip("../outside.txt" to byteArrayOf(1)))) }
        val valid = zip("document.json" to encoded)
        expectStorageFailure { repository.importProject(ByteArrayInputStream(valid.copyOf(valid.size - 5))) }
        val duplicateLayer = original.copy(layers = original.layers + original.layers.first())
        expectStorageFailure { repository.save(duplicateLayer) }
        assertEquals(listOf(original.project.id.value), repository.recentProjects.first().map { it.id })
        assertEquals(original, repository.load(original.project.id.value))
    }

    @Test
    fun settingsAreDurableAndAnInvalidReplacementIsNotWritten() = runBlocking {
        val settingsRepository = RoomSettingsRepository(database)
        assertEquals(AppSettings(), settingsRepository.settings.first())
        val chosen = AppSettings(theme = ThemeMode.DARK, inputMode = InputMode.STYLUS, undoLimit = 250,
            backupEnabled = false, showAutosaveNotification = true, bodyDragMovesSelection = true,
            showDetailedMeasurement = true, keepToolActiveAfterPlacing = true,
            autoHidePanelsWhileDrawing=true,lockWallsByDefault=true,
            bottomBarLabels=false,barPosition=CanvasBarPosition.LEFT,
            canvasLayouts=mapOf("restored-project" to CanvasLayout(hideAll=true).withPanel(CanvasPanelId.PROPERTIES,
                PanelPlacement(PanelVisibility.OPEN,.62,.27))),
            precisionPointer=PrecisionPointerSettings(enabled=false,offsetDp=120.0,side=PrecisionOffsetSide.ABOVE_RIGHT,
                magnifier=false,magnifierZoom=4.0,nudgeStepMetres=.025,hapticFeedback=false))
        settingsRepository.save(chosen)
        database.close()
        database = open()

        assertEquals(chosen, RoomSettingsRepository(database).settings.first())
        assertThrows(IllegalArgumentException::class.java) { chosen.copy(undoLimit = -1) }
        assertEquals(chosen, RoomSettingsRepository(database).settings.first())
    }

    @Test
    fun disabledBackupsDoNotLeaveARecoverablePreviousRevision() = runBlocking {
        val original = repository.create("Original", ProjectTemplate.EMPTY, ProjectSettings())
        val saved = repository.save(original.copy(project = original.project.copy(name = "Current")), backupEnabled = false)
        val row = database.projectDao().find(saved.project.id.value)!!
        assertNull(row.backupFile)
        assertEquals(1, projectFile(row.id, "revisions").listFiles()!!.size)
        expectStorageFailure { repository.restoreBackup(row.id) }
        assertEquals(saved, repository.load(row.id))
    }

    @Test
    fun cancellationDuringCataloguePublicationDoesNotDeleteThePublishedSnapshot() = runBlocking {
        database.close()
        var creation: kotlinx.coroutines.Job? = null
        database = Room.databaseBuilder(context, ProjectDatabase::class.java, databaseName)
            .setQueryCallback(
                androidx.room.RoomDatabase.QueryCallback { sql, _ ->
                    if (sql.trimStart().startsWith("INSERT", ignoreCase = true) && sql.contains("`projects`")) {
                        creation?.cancel()
                    }
                },
                java.util.concurrent.Executor { it.run() },
            ).build()
        repository = RoomProjectRepository(context, database)
        creation = launch(kotlinx.coroutines.Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            repository.create("Published before cancellation", ProjectTemplate.OFFICE, ProjectSettings())
        }
        creation.start()
        kotlinx.coroutines.withTimeout(10_000) { creation.join() }
        assertTrue(creation.isCancelled)
        val row = database.projectDao().observeAll().first().single()
        assertEquals("Published before cancellation", repository.load(row.id).project.name)
    }

    private fun open(): ProjectDatabase = Room.databaseBuilder(context, ProjectDatabase::class.java, databaseName).build()

    private fun projectFile(id: String, relative: String): File = File(File(File(context.filesDir, "projects"), id), relative)

    private suspend fun expectStorageFailure(block: suspend () -> Any?) {
        try {
            block()
            fail("Expected a recoverable project storage failure")
        } catch (_: ProjectStorageException) {
            // The failed operation must leave the prior catalogue revision readable.
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
