package com.eezydraw.ui

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@LooperMode(LooperMode.Mode.PAUSED)
class AppViewModelModelExportTest {
    @get:Rule val main=AppMainDispatcherRule()
    @Test fun singleDocumentObjSaveIncludesItsMaterialsAndUsesTheFrozenSnapshot()=runTest {
        val context=ApplicationProvider.getApplicationContext<Context>()
        TestAppRepositories(context).use {store->
            val p=ProjectId("export");val layer=LayerId("base")
            val wall=Element(ElementId("wall"),p,layer,ElementBody.Wall(Point2(0.0,0.0),Point2(2.0,0.0),.2,3.0),name="Wall",properties=mapOf(ELEMENT_COLOUR_KEY to ParameterValue.Colour(0x804488CCL)))
            val document=ProjectDocument(Project(p,"Export",0,0),layers=listOf(Layer(layer,p,"Base")),elements=listOf(wall))
            store.projects.documents[p.value]=document
            val model=store.newViewModel();runCurrent();model.open(p.value);runCurrent()
            model.prepareModelExport(ModelExportOptions(format=ModelFormat.OBJ))
            model.select(setOf(wall.id));model.setSelectionColour(0xFFFF0000L)
            val output=File.createTempFile("model-export-",".zip",context.cacheDir)
            try {
                model.exportModel(Uri.fromFile(output).toString())
                model.state.first {!it.busy}
                assertNull(model.state.value.error)
                assertEquals("OBJ and materials exported together as ZIP",model.state.value.notice)
                val files=buildMap {
                    ZipInputStream(output.inputStream()).use {zip->while(true){val entry=zip.nextEntry ?: break;put(entry.name,zip.readBytes().toString(Charsets.UTF_8))}}
                }
                assertEquals(setOf("model.obj","model.mtl"),files.keys)
                assertTrue("mtllib model.mtl" in files.getValue("model.obj"))
                val mtl=files.getValue("model.mtl")
                assertTrue("Kd ${68/255.0} ${136/255.0} ${204/255.0}" in mtl)
                assertTrue("d ${128/255.0}" in mtl)
                assertFalse("Kd 1.0 0.0 0.0" in mtl)
            }finally {output.delete()}
        }
    }
}
