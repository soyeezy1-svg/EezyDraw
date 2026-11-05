package com.eezydraw.core.editing

import com.eezydraw.core.model.*

/** Presentation changes never rewrite shape coordinates, parameters or measurement anchors. */
object PresentationCommands {
    fun setLock(document:ProjectDocument,ids:Set<ElementId>,mode:LockMode):EditCommand =
        EditCommands.presentation(document,selected(document,ids).map {it.withLock(mode)},when(mode) {
            LockMode.NONE->"Object unlocked"
            LockMode.POSITION_ONLY->"Object position locked"
            LockMode.ALL->"Object locked"
        })

    /** Null is the all-level view. An explicit level changes only records assigned to that level. */
    fun lockLevel(document:ProjectDocument,levelId:LevelId?,mode:LockMode):EditCommand {
        require(levelId==null || document.levels.any {it.id==levelId}) {"The level no longer exists."}
        val objects=document.elements.filter {levelId==null || it.levelId==levelId}
        return EditCommands.presentation(document,objects.map {it.withLock(mode)},
            if(mode==LockMode.NONE)"Level objects unlocked" else "Level objects locked")
    }

    fun setDisplay(document:ProjectDocument,ids:Set<ElementId>,display:ObjectDisplay):EditCommand =
        EditCommands.presentation(document,selected(document,ids).map {it.withDisplay(display)},when(display) {
            ObjectDisplay.NORMAL->"Object shown"
            ObjectDisplay.HIDDEN->"Object hidden"
            ObjectDisplay.XRAY->"Object X-ray enabled"
            ObjectDisplay.WIREFRAME->"Object wireframe enabled"
        })

    fun setRoomSideDisplay(document:ProjectDocument,side:RoomSideRef,display:ObjectDisplay):EditCommand {
        val room=requireNotNull(document.elements.find {it.id==side.roomId}) {"The room no longer exists."}
        return EditCommands.presentation(document,listOf(room.withRoomSideDisplay(side,display)),when(display) {
            ObjectDisplay.HIDDEN->"Wall hidden"
            ObjectDisplay.NORMAL->"Wall shown"
            ObjectDisplay.XRAY->"Wall X-ray enabled"
            ObjectDisplay.WIREFRAME->"Wall wireframe enabled"
        })
    }

    /** Restore the room's side overrides and any explicit wall elements referenced by the room. */
    fun showRoomWalls(document:ProjectDocument,roomId:ElementId):EditCommand {
        val room=requireNotNull(document.elements.find {it.id==roomId}) {"The room no longer exists."}
        val body=requireNotNull(room.body as? ElementBody.Room) {"Select a room."}
        val wallIds=body.wallFaces.mapTo(HashSet()) {it.wallId}
        val replacements=document.elements.mapNotNull {element->
            when {
                element.id==roomId->element.withDisplay(ObjectDisplay.NORMAL).withAllRoomSidesNormal()
                element.id in wallIds->element.withDisplay(ObjectDisplay.NORMAL)
                element.body is ElementBody.Room->{
                    val linked=(element.body as ElementBody.Room).wallFaces.filter {it.wallId in wallIds}
                    if(linked.isEmpty())null else linked.fold(element) {result,face->
                        result.withRoomSideDisplay(RoomSideRef(element.id,face.edgeIndex,face.ringIndex),ObjectDisplay.NORMAL)
                    }
                }
                else->null
            }
        }
        return EditCommands.presentation(document,replacements,"Room walls shown")
    }

    /** Showing all restores visibility, while retaining intentional X-ray/wireframe styles. */
    fun showAll(document:ProjectDocument):EditCommand {
        val replacements=document.elements.map {element->
            val visible=if(element.objectDisplay==ObjectDisplay.HIDDEN)element.withDisplay(ObjectDisplay.NORMAL) else element
            visible.copy(properties=visible.properties.filterNot { (key,value)->
                key.startsWith(ROOM_SIDE_DISPLAY_PREFIX) && value==ParameterValue.Text(ObjectDisplay.HIDDEN.name)
            })
        }
        val changed=document.copy(elements=replacements,layers=document.layers.map {it.copy(visible=true)},
            systems=document.systems.map {it.copy(visible=true)},project=document.project.copy(
                settings=document.project.settings.copy(hiddenLevelIds=emptySet(),showMeasurements=true)))
        return EditCommands.presentationBetween(document,changed,"All objects shown")
    }

    fun setLevelVisibility(document:ProjectDocument,levelId:LevelId,visible:Boolean):EditCommand {
        require(document.levels.any {it.id==levelId}) {"The level no longer exists."}
        val settings=document.project.settings
        val hidden=if(visible)settings.hiddenLevelIds-levelId else settings.hiddenLevelIds+levelId
        return EditCommands.between(document,document.copy(project=document.project.copy(
            settings=settings.copy(hiddenLevelIds=hidden))),if(visible)"Level shown"else"Level hidden")
    }

    private fun selected(document:ProjectDocument,ids:Set<ElementId>):List<Element> {
        val elements=document.elements.filter {it.id in ids}
        require(elements.size==ids.size) {"A selected object no longer exists."}
        return elements
    }
}

/** Apply the generation preference only to new walls; loading/importing existing records is unaffected. */
fun lockGeneratedWalls(before:ProjectDocument,after:ProjectDocument,enabled:Boolean):ProjectDocument {
    require(before.project.id==after.project.id) {"Generated objects must belong to the same project."}
    if(!enabled)return after
    val existing=before.elements.mapTo(HashSet()) {it.id}
    return after.copy(elements=after.elements.map {element->
        if(element.id !in existing && element.body is ElementBody.Wall && element.lockMode==LockMode.NONE)
            element.withLock(LockMode.ALL) else element
    })
}
