package com.eezydraw.core.editing

import com.eezydraw.core.geometry.DrawingScene
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.SceneSegment
import com.eezydraw.core.geometry.ViewPreset
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveGestureTest {
    private val projectId = ProjectId("move-project")
    private val selectedId = ElementId("selected")
    private val viewport = Viewport2D(pixelsPerMetre = 100.0)
    private val orbit = OrbitCamera(orthographicHeight = 4.0).preset(ViewPreset.FRONT)
    private val width = 1000.0
    private val height = 800.0

    @Test fun freePlanDraggingUsesMetresAndKeepsElevation() {
        val pivot = Point3(2.0, 3.0, 1.2)
        val target = Point3(2.4, 2.7, 1.2)
        val delta = planMove(context(), pivot, target, SnapSettings(enabled = false))
        assertPoint(Point3(.4, -.3, 0.0), delta.translation)
        assertPoint(target, delta.apply(pivot))
    }

    @Test fun planMoveSupportsEverySnapModeAndExcludesTheMovingSelection() {
        val selectedEdge = edge(Point3(.29, .39), Point3(1.0, .39), selectedId.value)
        val reference = edge(Point3(.3, .4), Point3(2.0, .4))
        assertPoint(Point3(.3, .4), planMove(context(listOf(selectedEdge, reference)), Point3(), Point3(.29, .39),
            snaps(SnapMode.ENDPOINT)).translation)
        assertPoint(Point3(1.0, 1.0), planMove(context(listOf(edge(Point3(0.0, 1.0), Point3(2.0, 1.0)))),
            Point3(), Point3(1.02, .99), snaps(SnapMode.MIDPOINT)).translation)
        val crossing = listOf(edge(Point3(), Point3(2.0, 2.0)), edge(Point3(0.0, 2.0), Point3(2.0, 0.0), "other"))
        assertPoint(Point3(1.0, 1.0), planMove(context(crossing), Point3(), Point3(1.02, .99), snaps(SnapMode.INTERSECTION)).translation)
        assertPoint(Point3(1.0), planMove(context(listOf(edge(Point3(1.0), Point3(1.0, 2.0)))),
            Point3(0.0, 1.0), Point3(1.01, 1.02), snaps(SnapMode.PERPENDICULAR)).translation)
        assertPoint(Point3(.3, .2), planMove(context(), Point3(), Point3(.282, .218), snaps(SnapMode.GRID)).translation)
        assertPoint(Point3(.7), planMove(context(), Point3(), Point3(.7, .2), snaps(SnapMode.ORTHO)).translation)
    }

    @Test fun axisControlsKeepOtherCoordinatesFixedWhileSnapping() {
        val pivot = Point3(.02, .17, 1.2)
        val ctx = context(listOf(edge(Point3(1.0, 2.0), Point3(2.0, 2.0))))
        val target = Point3(.98, .17, 1.2)
        val delta = planMove(ctx, pivot, target, snaps(SnapMode.GRID, SnapMode.ENDPOINT), Point3(1.0))
        assertPoint(Point3(.98, 0.0, 0.0), delta.translation)
        assertPoint(Point3(1.0, .17, 1.2), delta.apply(pivot))
        val stationaryZ = planMove(ctx, pivot, target, snaps(SnapMode.GRID), Point3(z = 1.0))
        assertTrue(stationaryZ.isIdentity)
        val perpendicular = planMove(ctx, pivot, pivot.copy(y = pivot.y + .5), snaps(SnapMode.GRID), Point3(1.0))
        assertTrue("A perpendicular drag must not jump the move axis onto a grid", perpendicular.isIdentity)
    }

    @Test fun freeOrbitDraggingStaysOnTheHorizontalGroundPlaneInBothLensTypes() {
        val pivot = Point3(1.0, 2.0, 3.0)
        val target = Point3(1.6, 2.4, 3.0)
        for (projection in Projection.entries) {
            val camera = orbit.copy(target = pivot, projection = projection).preset(ViewPreset.ISOMETRIC)
            val delta = orbitMove(context(), pivot, target, camera, SnapSettings(enabled = false))
            assertPoint(Point3(.6, .4, 0.0), delta.translation)
        }
    }

    @Test fun axisSnapsUseActualElevationAndRejectGeometryOffTheConstrainedLine() {
        val pivot = Point3(.5, .5, 0.0)
        val target = Point3(.5, .5, 1.98)
        val endpoint = Point3(.5, .5, 2.0)
        val ctx = context(listOf(edge(endpoint, Point3(.5, .5, 3.0))))
        for (projection in Projection.entries) {
            val camera = orbit.copy(target=pivot,projection = projection).preset(ViewPreset.ISOMETRIC)
            assertPoint(Point3(0.0,0.0,2.0), orbitMove(ctx, pivot, target, camera, snaps(SnapMode.ENDPOINT),Point3(z=1.0)).translation)
            val offAxis = context(listOf(edge(Point3(.54, .5, 1.98), Point3(.54, .5, 3.0))))
            assertPoint(Point3(0.0,0.0,1.98), orbitMove(offAxis, pivot, target, camera, snaps(SnapMode.ENDPOINT),Point3(z=1.0)).translation)
        }
    }

    @Test fun orbitGridAndZConstrainedMovesPreserveTheirPlaneOrAxis() {
        val ctx = context()
        val free = orbitMove(ctx, Point3(), Point3(.23, .28, 0.0), orbit.preset(ViewPreset.ISOMETRIC), snaps(SnapMode.GRID))
        assertPoint(Point3(.2, .3, 0.0), free.translation)
        val pivot = Point3(.17, .21, 1.2)
        val camera = orbit.copy(target = pivot)
        val z = orbitMove(ctx, pivot, pivot.copy(z = 1.463), camera, snaps(SnapMode.GRID), Point3(z = 1.0))
        assertPoint(Point3(0.0, 0.0, .3), z.translation)
    }

    @Test fun moveUnitsAndZoomAreIndependentAtSurveyCoordinates() {
        for (unit in LengthUnit.entries) for (scale in listOf(10.0, 100.0, 1000.0)) {
            val pivot = Point3(1e9, -1e9, 50.0)
            val view = Viewport2D(Point2(pivot.x, pivot.y), scale)
            val doc = document().let { it.copy(project = it.project.copy(settings = it.project.settings.copy(lengthUnit = unit))) }
            val ctx = MoveContext(doc, setOf(selectedId), scene(emptyList()))
            val start = view.worldToScreen(Point2(pivot.x, pivot.y), width, height)
            val current = view.worldToScreen(Point2(pivot.x + .25, pivot.y - .5), width, height)
            assertPoint(Point3(.25, -.5), ctx.delta(pivot, start, current, null, view, orbit,
                false, width, height, SnapSettings(enabled = false), 14.0).translation, 2e-7)
        }
    }

    @Test fun hiddenLevelsDoNotAttractSnapsAndStationaryGestureIsExactIdentity() {
        val reference = edge(Point3(.31, .4), Point3(2.0, .4)).copy(levelId = "other-level")
        val ctx = context(listOf(reference))
        val start = viewport.worldToScreen(Point2(0.0, 0.0), width, height)
        val current = viewport.worldToScreen(Point2(.3, .4), width, height)
        assertPoint(Point3(.3, .4), ctx.delta(Point3(), start, current, null, viewport, orbit, false, width, height,
            snaps(SnapMode.ENDPOINT), 14.0, "active-level").translation)
        val pivot = Point3(.123, .234, .345)
        assertTrue(ctx.delta(pivot, start, start, null, viewport, orbit, false, width, height, snaps(SnapMode.GRID), 14.0).isIdentity)
    }

    @Test fun associativeMeasurementsDoNotSnapTheirMovingHostBackToOldAnchors() {
        val original = document()
        val measurement = Element(ElementId("dimension"), projectId, LayerId("drawing"),
            ElementBody.DrawingShape(Shape.Dimension(DimensionKind.ALIGNED, listOf(Point3(), Point3(2.0)), Point3(),
                MeasurementData(anchors = listOf(MeasurementAnchor(Point3(), selectedId), MeasurementAnchor(Point3(2.0)))))))
        val doc = original.copy(elements = original.elements + measurement)
        val ctx = MoveContext(doc, setOf(selectedId), scene(listOf(edge(Point3(.3, .4), Point3(2.0, .4), measurement.id.value))))
        val target = Point3(.29, .39)
        assertPoint(target, planMove(ctx, Point3(), target, snaps(SnapMode.ENDPOINT)).translation)
    }

    @Test fun everyWorldArrowWorksAndSnapsInPlanTopFrontSideAndIsometricViews() {
        val axes=listOf(Point3(x=1.0),Point3(y=1.0),Point3(z=1.0))
        val cameras=ViewPreset.entries.map {orbit.copy(target=Point3(),projection=Projection.ORTHOGRAPHIC).preset(it)}
        for(is3D in listOf(false,true))for(camera in cameras)for((index,axis)in axes.withIndex()) {
            val pivot=Point3()
            val start=if(is3D)camera.project(pivot,width,height)!!.let {Point2(it.x,it.y)} else viewport.worldToScreen(Point2(0.0,0.0),width,height)
            val endWorld=Point3(axis.x*.28,axis.y*.28,axis.z*.28)
            var end=if(is3D)camera.project(endWorld,width,height)!!.let {Point2(it.x,it.y)} else viewport.worldToScreen(Point2(endWorld.x,endWorld.y),width,height)
            if(kotlin.math.hypot(end.x-start.x,end.y-start.y)<1.0) {
                val direction=moveAxisScreenDirection(axis,viewport,camera,is3D,width,height,pivot)
                val metresPerPixel=if(is3D)camera.orthographicHeight/height else 1.0/viewport.pixelsPerMetre
                end=Point2(start.x+direction.x*.28/metresPerPixel,start.y+direction.y*.28/metresPerPixel)
            }
            val result=context().delta(pivot,start,end,axis,viewport,camera,is3D,width,height,snaps(SnapMode.GRID),14.0).translation
            assertPoint(Point3(if(index==0).3 else 0.0,if(index==1).3 else 0.0,if(index==2).3 else 0.0),result)
        }
    }

    @Test fun freeGroundSquareStillMovesBothHorizontalAxesInEdgeOnFrontAndSideViews() {
        for(preset in listOf(ViewPreset.FRONT,ViewPreset.SIDE)) {
            val camera=orbit.copy(target=Point3(2.0,3.0,1.2),projection=Projection.ORTHOGRAPHIC).preset(preset)
            val result=context().delta(camera.target,Point2(500.0,400.0),Point2(580.0,360.0),null,viewport,camera,true,width,height,
                SnapSettings(enabled=false),14.0).translation
            assertEquals(0.0,result.z,0.0)
            assertTrue("Both X and Y stay controllable from $preset",kotlin.math.abs(result.x)>.1 && kotlin.math.abs(result.y)>.1)
        }
    }

    @Test fun planZVirtualArrowRaisesAndLowersWithoutChangingXYAndIsUndoable() {
        val document=document().copy(layers=listOf(Layer(LayerId("drawing"),projectId,"Drawing")))
        val pivot=Point3(2.0,3.0,1.2)
        val raised=context().delta(pivot,Point2(500.0,400.0),Point2(500.0,350.0),Point3(z=1.0),viewport,orbit,false,width,height,
            SnapSettings(enabled=false),14.0)
        assertPoint(Point3(0.0,0.0,.5),raised.translation)
        val history=EditHistory()
        val after=history.execute(document,EditCommands.transform(document,setOf(selectedId),raised))
        assertPoint(Point3(z=.5),after.elements.single().transform.translation)
        assertEquals(document,history.undo(after))
        assertEquals(after,history.redo(document))
        val lowered=context().delta(pivot,Point2(500.0,400.0),Point2(500.0,450.0),Point3(z=1.0),viewport,orbit,false,width,height,
            SnapSettings(enabled=false),14.0)
        assertPoint(Point3(0.0,0.0,-.5),lowered.translation)
    }

    private fun context(edges: List<SceneSegment> = emptyList()) = MoveContext(document(), setOf(selectedId), scene(edges))
    private fun document() = ProjectDocument(Project(projectId, "Move", 0L, 0L), elements = listOf(
        Element(selectedId, projectId, LayerId("drawing"), ElementBody.DrawingShape(Shape.Line(Point3(), Point3(1.0)))),
    ))
    private fun scene(edges: List<SceneSegment>) = DrawingScene(edges, emptyList(), Bounds3(Point3(), Point3()))
    private fun edge(a: Point3, b: Point3, id: String = "reference") = SceneSegment(a, b, id, null)
    private fun snaps(vararg modes: SnapMode) = SnapSettings(modes = modes.toSet(), gridSpacing = .1, tolerance = .001)
    private fun planMove(ctx: MoveContext, pivot: Point3, target: Point3, snaps: SnapSettings, axis: Point3? = null): EditDelta =
        ctx.delta(pivot, viewport.worldToScreen(Point2(pivot.x, pivot.y), width, height),
            viewport.worldToScreen(Point2(target.x, target.y), width, height), axis, viewport, orbit, false, width, height, snaps, 14.0)
    private fun orbitMove(ctx: MoveContext, pivot: Point3, target: Point3, camera: OrbitCamera, snaps: SnapSettings, axis: Point3? = null): EditDelta {
        fun screen(point: Point3) = checkNotNull(camera.project(point, width, height)).let { Point2(it.x, it.y) }
        return ctx.delta(pivot, screen(pivot), screen(target), axis, viewport, camera, true, width, height, snaps, 14.0)
    }
    private fun assertPoint(expected: Point3, actual: Point3, epsilon: Double = 1e-8) {
        assertEquals("X", expected.x, actual.x, epsilon)
        assertEquals("Y", expected.y, actual.y, epsilon)
        assertEquals("Z", expected.z, actual.z, epsilon)
    }
}
