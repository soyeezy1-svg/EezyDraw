package com.eezydraw.core.editing

import com.eezydraw.core.geometry.ClashDetection
import com.eezydraw.core.geometry.ClashRun
import com.eezydraw.core.model.*

object ClashCommands {
    fun reconcile(document:ProjectDocument,run:ClashRun,now:Long):EditCommand {
        val found=run.clashes.associateBy(ClashDetection::resultId)
        val old=document.issues.associateBy {it.id}
        val issues=document.issues.map {issue->
            val clash=issue.clash
            if(clash?.checkKey==run.checkKey && issue.id !in found)issue.copy(status=IssueStatus.RESOLVED,clash=clash.copy(status=ClashStatus.RESOLVED))else issue
        }.filterNot {it.id in found}+found.map {(id,clash)->
            val previous=old[id]?.clash
            val status=previous?.status?.takeUnless {it==ClashStatus.RESOLVED} ?: ClashStatus.NEW
            val next=clash.copy(status=status,note=previous?.note.orEmpty())
            val base=old[id] ?: Issue(id,document.project.id,"Clash",now)
            base.copy(title="${clash.kind}: ${clash.firstName} / ${clash.secondName}",
                status=when(status){ClashStatus.ACTIVE->IssueStatus.IN_PROGRESS;ClashStatus.APPROVED,ClashStatus.IGNORED->IssueStatus.CLOSED;ClashStatus.RESOLVED->IssueStatus.RESOLVED;else->IssueStatus.OPEN},
                elementIds=setOf(clash.firstElementId,clash.secondElementId),position=clash.position,clash=next)
        }
        return EditCommands.between(document,document.copy(issues=issues,project=document.project.copy(settings=document.project.settings.copy(clashCheck=run.options))),"Run clash check")
    }
    fun status(document:ProjectDocument,id:IssueId,status:ClashStatus,note:String):EditCommand {
        require(document.issues.any {it.id==id && it.clash!=null}) {"The clash no longer exists"}
        val issues=document.issues.map {i->if(i.id!=id)i else i.copy(clash=i.clash!!.copy(status=status,note=note),
            status=when(status){ClashStatus.RESOLVED->IssueStatus.RESOLVED;ClashStatus.APPROVED,ClashStatus.IGNORED->IssueStatus.CLOSED;ClashStatus.ACTIVE->IssueStatus.IN_PROGRESS;else->IssueStatus.OPEN})}
        return EditCommands.between(document,document.copy(issues=issues),"Update clash status")
    }
}
