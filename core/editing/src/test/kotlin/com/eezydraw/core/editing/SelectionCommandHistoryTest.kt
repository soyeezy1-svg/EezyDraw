package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class SelectionCommandHistoryTest {
    @Test fun projectSettingsAndSystemVisibilityAreSparseReversibleTransactions() {
        val original=EditFixtures.document().copy(systems=listOf(BuildingSystem(SystemId("electrical"),EditFixtures.projectId,"Electrical",Discipline.ELECTRICAL,0xFF008800)))
        val expected=original.copy(project=original.project.copy(settings=original.project.settings.copy(lengthUnit=LengthUnit.FEET,
            viewMode=ViewMode.SHADED,showMeasurements=false,rotationSnapDegrees=45)),systems=original.systems.map {it.copy(visible=false)})
        val command=EditCommands.between(original,expected,"View preferences")
        assertEquals(0,command.changedElementCount)
        assertTrue(command.retainedChangeCount<=4)
        val history=EditHistory()
        val edited=history.execute(original,command)
        assertSame("A settings edit must not duplicate the drawing",original.elements,edited.elements)
        val saved=edited.copy(project=edited.project.copy(updatedAtEpochMillis=100L))
        val undone=history.undo(saved)
        assertEquals(original.project.settings,undone.project.settings)
        assertEquals(original.systems,undone.systems)
        assertEquals(100L,undone.project.updatedAtEpochMillis)
        assertEquals(saved,history.redo(undone))
        assertThrows(IllegalArgumentException::class.java) {EditHistory().execute(original.copy(project=original.project.copy(settings=original.project.settings.copy(decimalPlaces=4))),command)}
    }

    @Test fun elementColourLayerAndLockEachUndoAndUnlockCannotSmuggleOtherChanges() {
        val original=EditFixtures.document().let {it.copy(layers=it.layers+Layer(LayerId("other"),it.project.id,"Other"))}
        val item=original.elements.single()
        val history=EditHistory()
        val coloured=item.copy(layerId=LayerId("other"),properties=mapOf("eezydraw.colour" to ParameterValue.Colour(0xFF336699)))
        val styled=history.execute(original,EditCommands.replace(original,listOf(coloured)))
        val locked=coloured.copy(properties=coloured.properties+ ("eezydraw.locked" to ParameterValue.Flag(true)))
        val lockDocument=history.execute(styled,EditCommands.replace(styled,listOf(locked),"Lock object"))
        assertThrows(IllegalArgumentException::class.java){EditCommands.transform(lockDocument,setOf(item.id),EditDelta(translation=Point3(1.0)))}
        assertThrows(IllegalArgumentException::class.java){EditCommands.delete(lockDocument,setOf(item.id))}
        assertThrows(IllegalArgumentException::class.java){EditCommands.array(lockDocument,setOf(item.id),2,1,1.0,0.0)}
        val unlocked=locked.copy(properties=locked.properties+ ("eezydraw.locked" to ParameterValue.Flag(false)))
        assertThrows(IllegalArgumentException::class.java){EditCommands.replace(lockDocument,listOf(unlocked.copy(name="Also renamed")))}
        val unlockDocument=history.execute(lockDocument,EditCommands.replace(lockDocument,listOf(unlocked),"Unlock object"))
        assertEquals(unlocked,unlockDocument.elements.single())
        assertEquals(lockDocument,history.undo(unlockDocument))
        assertEquals(styled,history.undo(lockDocument))
        assertEquals(original,history.undo(styled))
        assertEquals(styled,history.redo(original))
        assertEquals(lockDocument,history.redo(styled))
        assertEquals(unlockDocument,history.redo(lockDocument))
    }

    @Test fun layerLocksRemainAuthoritativeAndLockedDependentsBlockDestructiveCleanup() {
        val document=EditFixtures.document().let {it.copy(layers=it.layers.map {layer->layer.copy(locked=true)},
            elements=it.elements.map {item->item.copy(properties=mapOf("eezydraw.locked" to ParameterValue.Flag(true)))})}
        assertThrows(IllegalArgumentException::class.java){EditCommands.replace(document,document.elements.map {it.copy(properties=mapOf("eezydraw.locked" to ParameterValue.Flag(false)))})}
        val linked=EditFixtures.linkedDocument().let {it.copy(elements=it.elements.map {element->
            if(element.body is ElementBody.Component)element.copy(properties=mapOf("eezydraw.locked" to ParameterValue.Flag(true))) else element})}
        assertThrows(IllegalArgumentException::class.java){EditCommands.delete(linked,setOf(EditFixtures.wallId))}
    }
}
