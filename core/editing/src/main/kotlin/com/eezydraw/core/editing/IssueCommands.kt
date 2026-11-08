package com.eezydraw.core.editing
import com.eezydraw.core.model.*
object IssueCommands {
    fun put(document:ProjectDocument,issue:Issue,assets:List<ProjectAsset> = emptyList()):EditCommand {
        require(issue.projectId==document.project.id && issue.title.isNotBlank() && issue.title.length<=200 && issue.description.length<=10000)
        issue.dueDate?.let {java.time.LocalDate.parse(it)}
        require(issue.elementIds.all {id->document.elements.any {it.id==id}})
        require(issue.levelId==null || document.levels.any {it.id==issue.levelId})
        val old=document.issues.find {it.id==issue.id}
        require(old?.locked!=true || !issue.locked) {"Unlock this issue before editing"}
        val markupLayer=LayerId("issue-markups")
        require(old?.annotations==issue.annotations || document.layers.none {it.id==markupLayer && it.locked}) {"Unlock the Issue markups layer"}
        val requiredLayers=listOf(Layer(LayerId("issue-pins"),document.project.id,"Issue pins"))+if(issue.annotations.isEmpty())emptyList()else listOf(Layer(markupLayer,document.project.id,"Issue markups"))
        val layers=document.layers+requiredLayers.filter {layer->document.layers.none {it.id==layer.id}}
        val nextAssets=document.assets+assets.filter {a->document.assets.none {it.id==a.id}}
        require((issue.attachments+listOfNotNull(issue.screenshot)).all {id->nextAssets.any {it.id==id}})
        return EditCommands.between(document,document.copy(issues=document.issues.filterNot {it.id==issue.id}+issue,assets=nextAssets,layers=layers),"Save issue")
    }
    fun delete(document:ProjectDocument,id:IssueId):EditCommand {
        require(document.issues.find {it.id==id}?.locked!=true){"Unlock this issue before deleting"}
        return EditCommands.between(document,document.copy(issues=document.issues.filterNot {it.id==id}),"Delete issue")
    }
}
