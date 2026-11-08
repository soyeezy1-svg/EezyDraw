package com.eezydraw.core.editing

import com.eezydraw.core.model.*

object NavigationCommands {
    fun save(document:ProjectDocument,view:SavedView):EditCommand {
        require(view.projectId==document.project.id && view.name.isNotBlank() && view.name.length<=120)
        require(view.levelId==null || document.levels.any {it.id==view.levelId})
        val views=document.views.filterNot {it.id==view.id}+view
        require(views.size<=200){"A project supports up to 200 saved views"}
        return EditCommands.between(document,document.copy(views=views),"Save camera view")
    }
    fun delete(document:ProjectDocument,id:ViewId):EditCommand {
        require(document.issues.none {it.viewId==id} && document.sheets.none {sheet->sheet.viewports.any {it.viewId==id}}){"This view is used by an issue or sheet"}
        return EditCommands.between(document,document.copy(views=document.views.filterNot {it.id==id}),"Delete camera view")
    }
}
