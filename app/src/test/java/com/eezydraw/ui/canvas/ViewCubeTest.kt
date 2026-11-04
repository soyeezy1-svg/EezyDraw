package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.ViewPreset
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Projection
import com.eezydraw.ui.theme.EezyDrawTheme
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ViewCubeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyFaceCenterNavigatesToItsActualFaceForBothLenses() {
        val views = listOf(
            OrbitCamera().preset(ViewPreset.TOP) to Point3(z = 1.0),
            OrbitCamera().preset(ViewPreset.FRONT) to Point3(y = -1.0),
            OrbitCamera().preset(ViewPreset.SIDE) to Point3(x = 1.0),
            OrbitCamera(yaw = -PI / 2, pitch = -PI / 2) to Point3(z = -1.0),
            OrbitCamera(yaw = PI / 2, pitch = 0.0) to Point3(y = 1.0),
            OrbitCamera(yaw = PI, pitch = 0.0) to Point3(x = -1.0),
        )
        for ((camera, face) in views) for (projection in Projection.entries) {
            assertEquals("$projection $face", face,
                cubeNavigationHit(Offset(128f, 128f), camera.copy(projection = projection), 256.0, 256.0, 1.0))
        }
        assertNull(cubeNavigationHit(Offset(-100f, -100f), OrbitCamera(), 256.0, 256.0, 1.0))
        assertNull(cubeNavigationHit(Offset.Zero, OrbitCamera(), 0.0, 256.0, 1.0))
    }

    @Test fun visibleCornersChooseTheBodyDiagonalAndFaceCentersStayDistinct() {
        for (x in listOf(-1.0, 1.0)) for (y in listOf(-1.0, 1.0)) for (z in listOf(-1.0, 1.0)) {
            val direction = Point3(x, y, z)
            val camera = OrbitCamera(yaw = kotlin.math.atan2(y, x), pitch = kotlin.math.atan2(z, kotlin.math.sqrt(2.0)))
            val local = camera.copy(target = Point3(), distance = 4.0, orthographicHeight = 1.9,
                projection = Projection.ORTHOGRAPHIC)
            val corner = local.project(Point3(x / 2, y / 2, z / 2), 256.0, 256.0)!!
            assertEquals(direction, cubeNavigationHit(Offset(corner.x.toFloat(), corner.y.toFloat()), camera,
                256.0, 256.0, 1.0))
        }
        val top = OrbitCamera().preset(ViewPreset.TOP)
        val local = top.copy(target = Point3(), distance = 4.0, orthographicHeight = 1.9)
        val corner = local.project(Point3(.5, -.5, .5), 256.0, 256.0)!!
        assertEquals(Point3(1.0, -1.0, 1.0), cubeNavigationHit(
            Offset(corner.x.toFloat() + 12f, corner.y.toFloat()), top, 256.0, 256.0, 1.0))
        assertEquals(Point3(z = 1.0), cubeNavigationHit(Offset(128f, 128f), top, 256.0, 256.0, 1.0))
    }

    @Test fun draggingTheCubeKeepsOrbitingAcrossItsOwnCameraRecompositions() {
        val initial = OrbitCamera().preset(ViewPreset.TOP)
        var camera by mutableStateOf(initial)
        val active = mutableListOf<Boolean>()
        val faces = mutableListOf<Point3>()
        val corners = mutableListOf<Point3>()
        val drag = mutableListOf<Pair<Double, Double>>()
        compose.setContent { EezyDrawTheme { Surface { Box(Modifier.size(180.dp)) {
            ViewCube(camera, faces::add, Modifier.size(160.dp),
                onOrbit = { dx, dy -> drag += dx to dy; camera = camera.orbit(-dx * .006, dy * .006) },
                onCorner = corners::add, onGestureActive = active::add)
        } } } }
        compose.onNodeWithTag("canvas_view_cube").performTouchInput {
            swipe(Offset(width * .5f, height * .5f), Offset(width * .83f, height * .67f), 320)
        }
        compose.runOnIdle {
            assertEquals(listOf(true, false), active)
            assertTrue("Camera changes must not restart/cancel the captured pointer", drag.size > 2)
            assertTrue(abs(camera.yaw - initial.yaw) > .1)
            assertTrue(camera.pitch < initial.pitch || camera.yaw != initial.yaw)
            assertTrue(faces.isEmpty())
            assertTrue(corners.isEmpty())
        }
    }

    @Test fun tapAndAccessibilityExposeFacesAndAllEightIsometricCorners() {
        val faceCalls = mutableListOf<Point3>()
        val cornerCalls = mutableListOf<Point3>()
        var camera by mutableStateOf(OrbitCamera().preset(ViewPreset.FRONT))
        compose.setContent { EezyDrawTheme { ViewCube(camera, faceCalls::add, Modifier.size(160.dp), onCorner = cornerCalls::add) } }
        val cube = compose.onNodeWithTag("canvas_view_cube")
        cube.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf(Point3(y = -1.0)), faceCalls) }
        val actions = cube.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(17, actions.size)
        compose.runOnIdle { actions.drop(6).take(8).forEach { assertTrue(it.action()) } }
        assertEquals(8, cornerCalls.toSet().size)
        assertTrue(cornerCalls.all { abs(it.x) == 1.0 && abs(it.y) == 1.0 && abs(it.z) == 1.0 })
        compose.runOnIdle { camera = OrbitCamera().preset(ViewPreset.ISOMETRIC) }
        cube.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(Point3(1.0, 1.0, 1.0), cornerCalls.last()) }
    }

    @Test fun longPressOffersPlanAndProjectionWithoutTriggeringAFaceOrOrbit() {
        val faces = mutableListOf<Point3>()
        val corners = mutableListOf<Point3>()
        val orbits = mutableListOf<Pair<Double, Double>>()
        val projections = mutableListOf<Projection>()
        var plans = 0
        compose.setContent { EezyDrawTheme { ViewCube(OrbitCamera(), faces::add, Modifier.size(160.dp),
            onOrbit = { dx, dy -> orbits += dx to dy }, onCorner = corners::add,
            onPlan = { plans++ }, onProjection = projections::add) } }
        val cube = compose.onNodeWithTag("canvas_view_cube")
        cube.performTouchInput { longClick(center) }
        compose.onNodeWithTag("cube_perspective").assertIsDisplayed().performClick()
        cube.performTouchInput { longClick(center) }
        compose.onNodeWithTag("cube_orthographic").assertIsDisplayed().performClick()
        cube.performTouchInput { longClick(center) }
        compose.onNodeWithTag("cube_plan_2d").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(Projection.PERSPECTIVE, Projection.ORTHOGRAPHIC), projections)
            assertEquals(1, plans)
            assertTrue(faces.isEmpty()); assertTrue(corners.isEmpty()); assertTrue(orbits.isEmpty())
        }
        val actions = cube.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.takeLast(3).forEach { assertTrue(it.action()) } }
        assertEquals(2, plans)
        assertEquals(listOf(Projection.PERSPECTIVE, Projection.ORTHOGRAPHIC,
            Projection.PERSPECTIVE, Projection.ORTHOGRAPHIC), projections)
    }

    @Test fun aSecondPointerCancelsAnOrbitAndAlwaysReleasesTheGestureFlag() {
        val active = mutableListOf<Boolean>()
        val taps = mutableListOf<Point3>()
        var camera by mutableStateOf(OrbitCamera())
        compose.setContent { EezyDrawTheme { ViewCube(camera, taps::add, Modifier.size(160.dp),
            onOrbit = { dx, dy -> camera = camera.orbit(-dx * .006, dy * .006) },
            onCorner = taps::add, onGestureActive = active::add) } }
        compose.onNodeWithTag("canvas_view_cube").performTouchInput {
            down(0, Offset(width * .5f, height * .5f))
            moveTo(0, Offset(width * .7f, height * .55f), delayMillis = 32)
            down(1, Offset(width * .35f, height * .5f))
            moveTo(0, Offset(width * .75f, height * .55f), delayMillis = 16)
            up(1); up(0)
        }
        compose.runOnIdle {
            assertEquals(listOf(true, false), active)
            assertTrue(taps.isEmpty())
            assertFalse(active.last())
        }
    }
}
