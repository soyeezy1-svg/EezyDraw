package com.eezydraw.ui.canvas

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class GraphicsRuntimeTest {
    private fun format(depth:Int,samples:Int)=EglFormat(8,8,8,8,depth,samples)
    @Test fun chooses4x24BitAndFallsBackWithoutSacrificingDepthPrecision() {
        val formats=listOf(format(16,4),format(24,0),format(24,2),format(24,4),format(24,8))
        assertEquals(3,chooseEglFormat(formats,4));assertEquals(2,chooseEglFormat(formats,2));assertEquals(1,chooseEglFormat(formats,0))
        assertEquals(1,chooseEglFormat(formats.take(2),4))
        assertEquals(0,chooseEglFormat(listOf(format(16,0)),4))
        assertNull(chooseEglFormat(listOf(format(8,0)),4))
    }
    @Test fun multisampleStorageRespectsDeviceSupportAndTheMemoryBudget() {
        assertEquals(4,boundedSamples(4,4,2560,1600))
        assertEquals(2,boundedSamples(4,2,1920,1200))
        assertEquals(2,boundedSamples(4,4,3840,2160))
        assertEquals(0,boundedSamples(4,0,1024,768))
        assertEquals(0,boundedSamples(4,4,16000,16000))
    }
    @Test fun slowActiveFramesStepDownAndRecoveryUsesHysteresis() {
        val controller=AdaptiveGraphics();var now=1_000_000_000L
        repeat(110){now+=50_000_000;controller.frame(now,RenderQuality.QUALITY,true)}
        assertEquals(RenderQuality.PERFORMANCE,controller.quality)
        repeat(1100){now+=16_000_000;controller.frame(now,RenderQuality.QUALITY,true)}
        assertEquals(RenderQuality.QUALITY,controller.quality)
    }
    @Test fun frameRateReadoutExpiresWithoutSchedulingFakeGlFrames() {
        val stats=RenderDiagnostics(fps=59.6,atNanos=2_000_000_000)
        assertEquals("60 FPS",frameRateLabel(stats,2_100_000_000))
        assertEquals("FPS — idle",frameRateLabel(stats,4_000_000_000))
    }
    @Test fun idleDoesNotCountAsLowFpsAndManualQualityAlwaysWins() {
        val controller=AdaptiveGraphics();var now=1_000_000_000L
        repeat(20){now+=5_000_000_000;assertNull(controller.frame(now,RenderQuality.QUALITY,true)!!.first)}
        assertEquals(RenderQuality.QUALITY,controller.quality)
        repeat(100){now+=50_000_000;controller.frame(now,RenderQuality.BALANCED,false)}
        assertEquals(RenderQuality.BALANCED,controller.quality)
    }
    @Test fun selectionPreviewExpandsTheClipBoundsWithoutChangingTheScene() {
        val scene=Bounds3(Point3(),Point3(1.0,1.0,1.0))
        val bounds=renderClipBounds(scene,scene,com.eezydraw.core.editing.EditDelta(translation=Point3(10.0,0.0,0.0)))
        assertEquals(Point3(11.0,1.0,1.0),bounds.max)
        assertEquals(Point3(1.0,1.0,1.0),scene.max)
    }
    @Test fun continuousSlowWalkCanLowerQualityEvenBelowFourFramesPerSecond() {
        val controller=AdaptiveGraphics();var now=1_000_000_000L
        repeat(16){now+=500_000_000;controller.frame(now,RenderQuality.QUALITY,true,motionActive=true)}
        assertEquals(RenderQuality.PERFORMANCE,controller.quality)
    }
}
