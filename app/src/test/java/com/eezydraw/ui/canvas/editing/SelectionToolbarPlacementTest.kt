package com.eezydraw.ui.canvas.editing

import androidx.compose.ui.geometry.Rect
import com.eezydraw.core.model.Point2
import org.junit.Assert.*
import org.junit.Test

class SelectionToolbarPlacementTest {
    @Test fun contextActionsAvoidTheViewToolbarAndPropertiesWithoutMovingEitherPanel() {
        val occupied=listOf(Rect(504f,544f,1024f,600f),Rect(704f,200f,1024f,544f),Rect(0f,0f,180f,128f))
        val before=occupied.toList()
        val at=selectionToolbarPosition(Point2(800.0,460.0),1024,600,1f,occupied)
        val toolbar=Rect(at.x.toFloat(),at.y.toFloat(),at.x+240f,at.y+48f)
        assertTrue(occupied.none {it.overlaps(toolbar)})
        assertEquals(before,occupied)
    }
    @Test fun boundedActionsStayOnScreenEvenWhenNoFreeRegionExists() {
        val at=selectionToolbarPosition(Point2(1e6,-1e6),320,200,1f,listOf(Rect(0f,0f,320f,200f)))
        assertTrue(at.x>=0 && at.y>=0 && at.x+240<=320 && at.y+48<=200)
    }
}
