package com.eezydraw.core.model

import org.junit.Assert.*
import org.junit.Test

class LibraryCatalogTest {
    private val original = BuiltInLibrary.items.first()
    private val customId = LibraryItemId("my-item")
    @Test fun `built-in edit overrides only the device default and restore recovers shipped settings`() {
        val edited = original.copy(name="My switch",category="My category",defaultSize=Size3(.2,.1,.3),geometrySize=original.defaultSize)
        val catalog = LibraryCatalog().save(edited)
        assertEquals(edited,catalog.find(original.id))
        assertEquals(original,BuiltInLibrary.find(original.id))
        assertEquals(BuiltInLibrary.items.size,catalog.effectiveItems().size)
        assertEquals(original,catalog.restore(original.id).find(original.id))
        assertThrows(IllegalArgumentException::class.java) { catalog.remove(original.id) }
    }
    @Test fun `save as new retains original and copies symbol geometry metadata and photo`() {
        val catalog = LibraryCatalog().duplicate(original.id,customId,"My version")
        val copy = catalog.find(customId)!!
        assertEquals(original,catalog.find(original.id))
        assertFalse(copy.builtIn)
        assertEquals(original.geometry,copy.geometry)
        assertEquals(original.symbolGeometry,copy.symbolGeometry)
        assertEquals(original.mounting,copy.mounting)
        assertNull(catalog.remove(customId).find(customId))
        assertTrue(customId in catalog.remove(customId).removedIds)
    }
    @Test fun `custom definitions support all mounting types and library search`() {
        val item=original.copy(id=customId,builtIn=false,mounting=MountingType.FREE,name="Pump custom",category="Workshop")
        val catalog=LibraryCatalog().save(item)
        assertEquals(listOf(item),librarySearch(catalog.effectiveItems(),"workshop pump"))
        assertEquals(item,catalog.find(customId))
        assertThrows(IllegalArgumentException::class.java) { catalog.restore(customId) }
    }
    @Test fun `import merges stable identities and rejects duplicate or impersonated defaults`() {
        val item=original.copy(id=customId,builtIn=false)
        val catalog=LibraryCatalog(listOf(item))
        assertEquals(catalog,catalog.merge(catalog))
        assertThrows(IllegalArgumentException::class.java) {LibraryCatalog(listOf(item,item))}
        assertThrows(IllegalArgumentException::class.java) {LibraryCatalog(listOf(item.copy(builtIn=true)))}
    }
    @Test fun `project-carried definitions remain reusable while deleted entries and snapshots stay out of the panel`() {
        val item=original.copy(id=customId,builtIn=false)
        val snapshot=item.copy(id=LibraryItemId("snapshot"),catalogSourceId=item.id)
        val catalog=LibraryCatalog()
        assertEquals(item,catalog.find(customId,listOf(item)))
        assertTrue(item in catalog.effectiveItems(listOf(item,snapshot)))
        assertFalse(snapshot in catalog.effectiveItems(listOf(item,snapshot)))
        val deleted=catalog.remove(customId)
        assertNull(deleted.find(customId,listOf(item)))
        assertFalse(item in deleted.effectiveItems(listOf(item)))
        assertEquals(item,deleted.save(item).find(customId))
    }
    @Test fun `history permits reversible edit restore duplicate delete and import without recording export`() {
        val history=LibraryCatalogHistory(3)
        val empty=LibraryCatalog()
        val edited=empty.save(original.copy(name="Changed"))
        history.record(empty)
        assertEquals(empty,history.undoCandidate())
        history.acceptUndo(edited)
        assertFalse(history.canUndo)
        assertEquals(edited,history.redoCandidate())
        history.acceptRedo(empty)
        assertEquals(empty,history.undoCandidate())
        history.record(edited)
        assertFalse(history.canRedo)
    }
}
