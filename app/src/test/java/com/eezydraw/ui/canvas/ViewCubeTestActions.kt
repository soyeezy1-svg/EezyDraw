package com.eezydraw.ui.canvas

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel

/** Tests use the same face/corner navigation actions available to accessibility clients. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.cubeFace(context: Context, labelResource: Int) {
    onNodeWithTag("canvas_view_cube").performCustomAccessibilityActionWithLabel(context.getString(labelResource))
    waitForIdle()
}

/** The first body-diagonal action is an isometric view looking from negative X/Y/Z. */
internal fun ComposeContentTestRule.cubeIsometric() {
    val actions = onNodeWithTag("canvas_view_cube").fetchSemanticsNode().config[SemanticsActions.CustomActions]
    check(actions.size >= 14) { "The view cube must expose six faces and eight isometric corners." }
    runOnIdle { check(actions[6].action()) { "The isometric corner action was rejected." } }
    waitForIdle()
}

internal fun ComposeContentTestRule.cubeHome() {
    onNodeWithTag("canvas_home").performClick()
    waitForIdle()
}
