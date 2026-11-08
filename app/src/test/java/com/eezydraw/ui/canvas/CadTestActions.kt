package com.eezydraw.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Follow the public bottom-strip workflow; no hidden aliases for the removed floating panels. */
internal fun ComposeContentTestRule.cadGroup(name:String) {
    val node=onNodeWithTag("cad_group_$name").performScrollTo()
    if(!node.fetchSemanticsNode().config[SemanticsProperties.Selected])node.performClick()
    waitUntil(5000){onAllNodesWithTag("cad_substrip_$name").fetchSemanticsNodes().isNotEmpty()}
    waitForIdle()
}
internal fun ComposeContentTestRule.cadCloseDetail() {
    if(onAllNodesWithTag("cad_detail_close").fetchSemanticsNodes().isNotEmpty())onNodeWithTag("cad_detail_close").performClick()
}
internal fun ComposeContentTestRule.cadCloseStrip(name:String="draw") {
    val nodes=onAllNodesWithTag("cad_group_$name").fetchSemanticsNodes()
    if(nodes.isNotEmpty() && nodes.single().config[SemanticsProperties.Selected])onNodeWithTag("cad_group_$name").performScrollTo().performClick()
}
internal fun ComposeContentTestRule.cadTool(name:String) {
    cadGroup(if(name.startsWith("route_"))"route"else "draw")
    onNodeWithTag("tool_$name").performScrollTo().performClick()
    cadCloseStrip(if(name.startsWith("route_"))"route"else "draw")
    waitForIdle()
}
internal fun ComposeContentTestRule.cadSelection() {
    cadGroup("edit")
    if(onAllNodesWithTag("selection_toolbar").fetchSemanticsNodes().isEmpty())onNodeWithTag("selection_tools").performScrollTo().performClick()
}
internal fun ComposeContentTestRule.cadAction(action:String) {
    if(action in setOf("undo","redo")){onNodeWithTag("view_$action").performClick();return}
    if(action in setOf("finish","cancel")){onNodeWithTag(if(action=="finish")"placement_done"else "placement_cancel").performClick();return}
    if(action in setOf("copy","paste","delete","properties","mirror_x","mirror_y","array")) {
        cadGroup("edit");onNodeWithTag("tools_$action").performScrollTo().performClick()
        if(action!="properties")cadCloseStrip("edit")
    }else {
        cadSelection();onNodeWithTag("selection_more").performClick();onNodeWithTag("selection_$action").performScrollTo().performClick()
        cadCloseDetail()
    }
    waitForIdle()
}
internal fun ComposeContentTestRule.cadLibrary(){cadGroup("library");onNodeWithTag("canvas_library").performScrollTo().performClick()}
/** Pick in the unobscured drawing area now that chrome overlays rather than insets the surface. */
internal fun ComposeContentTestRule.cadPoint(x:Float,y:Float):Offset {
    val area=onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
    val cube=onAllNodesWithTag("canvas_view_cube").fetchSemanticsNodes().firstOrNull()?.boundsInRoot
    val bottom=onAllNodesWithTag("cad_bottom_controls").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top ?: area.bottom
    val top=(cube?.bottom ?: area.top)+8f
    val usableBottom=maxOf(top+4f,bottom-12f)
    return Offset(area.width*x,top-area.top+(usableBottom-top)*y)
}
internal fun ComposeContentTestRule.cadTap(x:Float,y:Float){val point=cadPoint(x,y);onNodeWithTag("canvas_surface").performTouchInput {click(point)};waitForIdle()}

internal fun ComposeContentTestRule.cadLibraryItem(tag:String):SemanticsNodeInteraction {
    onNodeWithTag("library_items").performScrollToNode(hasTestTag(tag))
    return onNodeWithTag(tag).performScrollTo()
}
