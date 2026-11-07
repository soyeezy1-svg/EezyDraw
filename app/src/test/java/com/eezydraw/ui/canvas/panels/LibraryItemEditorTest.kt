package com.eezydraw.ui.canvas.panels

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eezydraw.core.model.*
import com.eezydraw.core.editing.*
import com.eezydraw.ui.theme.EezyDrawTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryItemEditorTest {
    @get:Rule val compose=createComposeRule()
    @Test fun editDefaultsChangesNameCategoryAndDimensionsWithoutTouchingOriginal() {
        val original=BuiltInLibrary.items.first()
        var saved:LibraryItem?=null
        compose.setContent {EezyDrawTheme {LibraryItemEditor(original,false,{saved=it},{})}}
        compose.onNodeWithTag("library_edit_name").performTextReplacement("Office item")
        compose.onNodeWithTag("library_edit_category").performTextReplacement("Office")
        compose.onNodeWithTag("library_edit_width").performScrollTo().performTextReplacement("250")
        compose.onNodeWithTag("library_editor_save").performClick()
        compose.runOnIdle {
            assertEquals(original.id,saved!!.id)
            assertEquals("Office item",saved!!.name)
            assertEquals("Office",saved!!.category)
            assertEquals(.25,saved!!.defaultSize.width,0.0)
            assertEquals(original.defaultSize,saved!!.geometrySize)
            assertEquals(original,BuiltInLibrary.find(original.id))
        }
    }
    @Test fun saveAsNewKeepsBuiltInAndGivesCustomIdentity() {
        val original=BuiltInLibrary.items.first()
        var saved:LibraryItem?=null
        compose.setContent {EezyDrawTheme {LibraryItemEditor(original,false,{saved=it},{})}}
        compose.onNodeWithTag("library_editor_save_as_new").performClick()
        compose.runOnIdle {
            assertNotEquals(original.id,saved!!.id)
            assertFalse(saved!!.builtIn)
            assertEquals(original.geometry,saved!!.geometry)
            assertEquals(original,BuiltInLibrary.find(original.id))
        }
    }
    @Test fun invalidDimensionsDoNotCommitAndCancelDiscardsTheDraft() {
        var saves=0
        var cancels=0
        val item=createLibraryItem(LibraryItemId("new"),LibraryBaseShape.BOX)
        compose.setContent {EezyDrawTheme {LibraryItemEditor(item,true,{saves++},{cancels++})}}
        compose.onNodeWithTag("library_edit_width").performScrollTo().performTextReplacement("-10")
        compose.onNodeWithTag("library_editor_save").performClick()
        compose.runOnIdle {assertEquals(0,saves)}
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {assertEquals(1,cancels)}
    }
    @Test fun panelManagementControlsCreateCustomItemsAndExposeImportExportAndHistory() {
        var imports=0;var exports=0;var undos=0;var redos=0
        compose.setContent {EezyDrawTheme {LibraryPanel(emptyList(),{},{},onImportLibrary={imports++},onExportLibrary={exports++},
            onUndoLibrary={undos++},onRedoLibrary={redos++},canUndoLibrary=true,canRedoLibrary=true)}}
        for(tag in listOf("library_import","library_export","library_undo","library_redo")) {
            compose.onNodeWithTag("library_manage").performClick()
            compose.onNodeWithTag(tag).performClick()
        }
        compose.runOnIdle {assertEquals(listOf(1,1,1,1),listOf(imports,exports,undos,redos))}
        compose.onNodeWithTag("library_manage").performClick()
        compose.onNodeWithTag("library_create").performClick()
        compose.onNodeWithTag("library_item_editor").assertExists()
    }
}
