package com.eezydraw.ui.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.SnapMode
import com.eezydraw.core.model.ThemeMode
import com.eezydraw.ui.theme.EezyDrawTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun invalidPrecisionCannotBeSavedAndCanBeCorrected() {
        var saved: AppSettings? = null
        showSettings(onSave = { saved = it })

        enter("settings_precision", "9")
        compose.onNodeWithTag("settings_save").assertIsNotEnabled()
        compose.runOnIdle { assertNull(saved) }

        enter("settings_precision", "4")
        compose.onNodeWithTag("settings_save").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals(4, saved!!.projectDefaults.decimalPlaces)
        }
    }

    @Test
    fun savedSnapshotContainsChoicesNumericValuesAndSnapChanges() {
        var saved: AppSettings? = null
        showSettings(onSave = { saved = it })

        choose("settings_units", "m")
        choose("settings_theme", "Dark")
        enter("settings_grid", "0.25")
        enter("settings_tolerance", "0.02")
        enter("settings_precision", "3")
        enter("settings_autosave", "15")
        enter("settings_undo", "250")
        enter("settings_gesture", "1.5")
        enter("settings_line_weight", "0.5")
        visible("settings_snap_INTERSECTION").performClick()
        visible("settings_backup").performClick()

        compose.onNodeWithTag("settings_save").assertIsEnabled().performClick()
        compose.runOnIdle {
            val value = saved ?: throw AssertionError("Saving valid settings must emit a snapshot")
            assertEquals(LengthUnit.METRES, value.projectDefaults.lengthUnit)
            assertEquals(ThemeMode.DARK, value.theme)
            assertEquals(0.25, value.projectDefaults.snap.gridSpacing, 0.000001)
            assertEquals(0.02, value.projectDefaults.snap.tolerance, 0.000001)
            assertEquals(3, value.projectDefaults.decimalPlaces)
            assertEquals(15, value.autosaveIntervalSeconds)
            assertEquals(250, value.undoLimit)
            assertEquals(1.5, value.gestureSensitivity, 0.000001)
            assertEquals(0.5, value.defaultLineWeightMm, 0.000001)
            assertEquals(setOf(SnapMode.GRID, SnapMode.ENDPOINT, SnapMode.INTERSECTION), value.projectDefaults.snap.modes)
            assertEquals(false, value.backupEnabled)
        }
    }

    @Test
    fun folderAndPermissionsActionsUseTheirOwnCallbacks() {
        var folderSelections = 0
        var folderResets = 0
        var permissionRequests = 0
        showSettings(
            settings = AppSettings(saveLocationUri = "content://example/tree/primary%3AProjects"),
            onChooseSaveLocation = { folderSelections++ },
            onResetSaveLocation = { folderResets++ },
            onOpenPermissions = { permissionRequests++ },
        )

        visible("settings_choose_folder").performClick()
        visible("settings_reset_folder").performClick()
        visible("settings_permissions").performClick()

        compose.runOnIdle {
            assertEquals(1, folderSelections)
            assertEquals(1, folderResets)
            assertEquals(1, permissionRequests)
        }
    }

    @Test
    fun unsavedDraftSurvivesRestorationAndBackRequiresDiscard() {
        var backRequests = 0
        var saveRequests = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            EezyDrawTheme {
                Surface {
                    SettingsScreen(
                        settings = AppSettings(),
                        onSave = { saveRequests++ },
                        onBack = { backRequests++ },
                        onChooseSaveLocation = {},
                        onResetSaveLocation = {},
                        onOpenPermissions = {},
                    )
                }
            }
        }
        enter("settings_autosave", "30")

        restoration.emulateSavedInstanceStateRestore()

        visible("settings_autosave").assertTextContains("30")
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_discard").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, backRequests)
            assertEquals(0, saveRequests)
        }
        compose.onNodeWithTag("settings_discard").performClick()
        compose.runOnIdle {
            assertEquals(1, backRequests)
            assertEquals(0, saveRequests)
        }
    }

    private fun showSettings(
        settings: AppSettings = AppSettings(),
        onSave: (AppSettings) -> Unit = {},
        onChooseSaveLocation: () -> Unit = {},
        onResetSaveLocation: () -> Unit = {},
        onOpenPermissions: () -> Unit = {},
    ) {
        compose.setContent {
            EezyDrawTheme {
                Surface {
                    SettingsScreen(
                        settings = settings,
                        onSave = onSave,
                        onBack = {},
                        onChooseSaveLocation = onChooseSaveLocation,
                        onResetSaveLocation = onResetSaveLocation,
                        onOpenPermissions = onOpenPermissions,
                    )
                }
            }
        }
    }

    private fun visible(tag: String) = compose.onNodeWithTag("settings_list")
        .performScrollToNode(hasTestTag(tag))
        .let { compose.onNodeWithTag(tag) }

    private fun enter(tag: String, value: String) {
        visible(tag).performTextReplacement(value)
    }

    private fun choose(tag: String, label: String) {
        visible(tag).performClick()
        compose.onNodeWithText(label, substring = false).performClick()
    }
}
