package com.eezydraw.ui.canvas.editing

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.ui.theme.EezyDrawTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ArrayDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun negativeSpacingConvertsInchesAndApplyDispatchesOnce() {
        val calls = mutableListOf<List<Number>>()
        compose.setContent { EezyDrawTheme {
            ArrayDialog(LengthUnit.INCHES, { columns, rows, dx, dy -> calls += listOf(columns, rows, dx, dy) }, {})
        } }
        compose.onNodeWithTag("array_columns").performTextReplacement("3")
        compose.onNodeWithTag("array_rows").performTextReplacement("2")
        compose.onNodeWithTag("array_dx").performScrollTo().performTextReplacement("-12")
        compose.onNodeWithTag("array_dy").performScrollTo().performTextReplacement("6")
        assertEquals(0, calls.size)
        compose.onNodeWithTag("array_apply").assertIsEnabled().performClick().assertIsNotEnabled()
        assertEquals(1, calls.size)
        assertEquals(3, calls.single()[0])
        assertEquals(2, calls.single()[1])
        assertEquals(-.3048, calls.single()[2].toDouble(), 1e-12)
        assertEquals(.1524, calls.single()[3].toDouble(), 1e-12)
    }

    @Test
    fun rejectsFractionalCountsOversizeArraysZeroRepeatedSpacingAndNonfiniteNumbers() {
        compose.setContent { EezyDrawTheme { ArrayDialog(LengthUnit.METRES, { _, _, _, _ -> }, {}) } }
        compose.onNodeWithTag("array_columns").performTextReplacement("1")
        compose.onNodeWithTag("array_apply").assertIsNotEnabled()
        compose.onNodeWithTag("array_columns").performTextReplacement("2.5")
        compose.onNodeWithTag("array_apply").assertIsNotEnabled()
        compose.onNodeWithTag("array_columns").performTextReplacement("101")
        compose.onNodeWithTag("array_apply").assertIsNotEnabled()
        compose.onNodeWithTag("array_columns").performTextReplacement("100")
        compose.onNodeWithTag("array_rows").performTextReplacement("100")
        compose.onNodeWithTag("array_apply").assertIsNotEnabled()
        compose.onNodeWithTag("array_rows").performTextReplacement("10")
        compose.onNodeWithTag("array_apply").assertIsEnabled()
        compose.onNodeWithTag("array_dx").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("array_apply").assertIsNotEnabled()
        compose.onNodeWithTag("array_dx").performTextReplacement("NaN")
        compose.onNodeWithTag("array_apply").assertIsNotEnabled()
        compose.onNodeWithTag("array_dx").performTextReplacement("-2")
        compose.onNodeWithTag("array_apply").assertIsEnabled()
    }
}
