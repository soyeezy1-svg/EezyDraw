package com.eezydraw.ui.canvas

import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.ViewMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoupeCaptureTest {
    // An asymmetric framebuffer catches X/Y offsets, upside-down crops and RGBA/ARGB mistakes.
    private fun pixel(x: Int, y: Int) = (0xff shl 24) or ((x % 256) shl 16) or ((y % 256) shl 8) or ((x+y) % 256)

    @Test fun captureMatchesEverySourcePixelAtOffCentreCrosshairsIncludingAllFourEdges() {
        val width=389; val height=271
        for(zoom in listOf(2.0,3.0,4.0)) for(at in listOf(Point2(203.25,87.75),Point2(.5,.5),
            Point2(388.5,.5),Point2(.5,270.5),Point2(388.5,270.5))) {
            val source=LoupeSource(at,120.0,zoom)
            var calls=0
            val capture=checkNotNull(captureLoupe(source,width,height) {left,bottom,w,h,buffer->
                calls++
                assertTrue(left>=0 && bottom>=0 && left+w<=width && bottom+h<=height)
                for(y in 0 until h) for(x in 0 until w) {
                    val colour=pixel(left+x,height-1-(bottom+y))
                    buffer.put((colour shr 16).toByte()).put((colour shr 8).toByte()).put(colour.toByte()).put(255.toByte())
                }
            })
            assertEquals(1,calls)
            assertTrue("Readback must stay bounded to the loupe, never the whole screen",capture.bitmap.width<=123 && capture.bitmap.height<=123)
            for(y in 0 until capture.bitmap.height) for(x in 0 until capture.bitmap.width)
                assertEquals("$at / $zoom at $x,$y",pixel(capture.region.left.toInt()+x,capture.region.top.toInt()+y),capture.bitmap.getPixel(x,y))
            assertEquals(source,capture.source)
        }
    }

    @Test fun oldCapturesRemainUsableOnlyWhileTheSceneCameraAndViewModeMatch() {
        val source=LoupeSource(Point2(100.0,100.0),120.0,3.0)
        val frame=CanvasFrame(com.eezydraw.core.geometry.Viewport2D(),com.eezydraw.core.geometry.OrbitCamera(),loupe=source)
        val capture=checkNotNull(captureLoupe(source,300,300) {_,_,_,_,buffer->while(buffer.hasRemaining())buffer.put(255.toByte())})
            .copy(renderFrame=frame)
        assertTrue(capture.matches(null,frame.copy(loupe=source.copy(crosshair=Point2(110.0,105.0)))))
        val scene=com.eezydraw.core.geometry.DrawingScene(emptyList(),emptyList(),com.eezydraw.core.model.Bounds3(
            com.eezydraw.core.model.Point3(),com.eezydraw.core.model.Point3()))
        val bound=capture.copy(scene=scene)
        assertTrue(bound.matches(scene,frame))
        assertFalse("A replacement scene needs its own framebuffer crop",bound.matches(scene.copy(),frame))
        assertFalse(capture.matches(null,frame.copy(dark=true)))
        assertFalse(capture.matches(null,frame.copy(viewMode=ViewMode.SHADED)))
        assertFalse(capture.matches(null,frame.copy(is3D=true)))
        assertFalse(capture.matches(null,frame.copy(viewport=frame.viewport.pan(10.0,0.0))))
    }

    @Test fun emptyOrOutsideSurfaceDoesNotReadInvalidFramebufferPixels() {
        val source=LoupeSource(Point2(-500.0,-500.0),120.0,3.0)
        assertNull(captureLoupe(source,400,300) {_,_,_,_,_->fail("Outside surface")})
        assertNull(captureLoupe(source,0,0) {_,_,_,_,_->fail("Empty surface")})
    }
}
