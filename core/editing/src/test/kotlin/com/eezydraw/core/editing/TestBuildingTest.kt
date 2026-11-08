package com.eezydraw.core.editing
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TestBuildingTest {
    private fun base()=ProjectDocument(Project(ProjectId("sample-test"),"Sample",1,1))
    @Test fun sampleUsesRealRoomsWallsLibraryRoutingCutsAndCoordination() {
        val doc=TestBuilding.build(base())
        assertEquals(TestBuilding.NAME,doc.project.name);assertEquals(3,doc.levels.size)
        assertEquals(27,doc.elements.count {it.body is ElementBody.Room})
        assertTrue(doc.elements.any {(it.body as? ElementBody.Room)?.boundary?.outer?.size==6})
        val wallIds=doc.elements.mapNotNull {(it.body as? ElementBody.Room)?.wallFaces}.flatten().groupBy {it.wallId}
        assertTrue(wallIds.any {it.value.size>1})
        assertEquals(doc.elements.size,doc.elements.map {it.id}.distinct().size)
        assertEquals(RouteKind.entries.toSet(),doc.elements.mapNotNull {(it.body as? ElementBody.Route)?.kind}.toSet())
        assertTrue(doc.elements.any {it.floorRouting()!=null})
        assertTrue(doc.elements.any {it.name=="Inter-level water riser"})
        assertTrue(wallServiceCuts(doc).isNotEmpty())
        assertTrue(doc.elements.filter {it.body is ElementBody.Slab}.flatMap {floorServiceCuts(doc,it)}.isNotEmpty())
        val fittings=doc.elements.mapNotNull {(it.body as? ElementBody.Component)?.libraryItemId}.mapNotNull {id->doc.libraryItems.find {it.id==id}?.fitting?.kind}.toSet()
        assertTrue(fittings.containsAll(setOf(FittingKind.TEE,FittingKind.REDUCER,FittingKind.ELBOW_45,FittingKind.ELBOW_90)))
        assertEquals(6,doc.issues.count {it.clash?.kind==ClashKind.HARD})
        assertEquals(2,doc.issues.count {it.clash==null});assertTrue(doc.issues.any {it.annotations.isNotEmpty()})
        assertEquals(4,doc.views.size);assertTrue(doc.elements.any {it.lockMode==LockMode.ALL});assertTrue(doc.elements.any {it.objectDisplay==ObjectDisplay.HIDDEN})
        assertTrue(ScheduleEngine.build(doc,providedSchedules().first {it.category==ScheduleCategory.BOQ}).pricedTotal.signum()>0)
        val scene=buildDrawingScene(doc);assertTrue(scene.segments.isNotEmpty());assertTrue(buildSurfaceScene(doc).chunks.isNotEmpty())
    }
    @Test fun stressLevelsHaveUniqueHostsContinuousRisersAndBoundedRenderableGeometry() {
        for(n in TestBuilding.stressLevels) {
            val doc=TestBuilding.build(base(),n)
            assertEquals(n,doc.levels.size);assertEquals(9*n,doc.elements.count {it.body is ElementBody.Room})
            assertEquals(n-1,doc.elements.count {it.name=="Inter-level water riser"})
            assertEquals(n-1,doc.elements.count {it.name=="Stair flight"})
            val ids=doc.elements.mapTo(hashSetOf()){it.id}
            assertTrue(doc.elements.all {e->val host=when(val b=e.body){is ElementBody.Component->b.host;is ElementBody.Route->b.host;else->null};host==null || host.hostElementId in ids})
            val nodes=doc.elements.flatMap {e->(e.body as? ElementBody.Route)?.routing?.let {listOf(it.startNode to e.levelId,it.endNode to e.levelId)}.orEmpty()}.groupBy {it.first}
            assertTrue(nodes.values.any {node->node.mapNotNull {it.second}.distinct().size>1})
            assertTrue(buildDrawingScene(doc).segments.size<500000)
        }
    }
    @Test fun generationCancelsAndRejectsUnsupportedCountsWithoutMutatingSource() {
        val source=base();var calls=0
        try {TestBuilding.build(source,10,cancel={if(++calls>12)throw InterruptedException("stop")});fail()}catch(_:InterruptedException){}
        assertTrue(source.elements.isEmpty())
        try {TestBuilding.build(source,200);fail()}catch(_:IllegalArgumentException){}
    }
}
