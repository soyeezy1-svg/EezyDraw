package com.eezydraw.ui.canvas
import com.eezydraw.ui.developer.RenderFrameCounter
import org.junit.Assert.*
import org.junit.Test
class RenderFrameCounterTest {
    @Test fun idleFramesDoNotFabricateFpsAndOnlyTheMeasuredWindowCounts() {
        val counter=RenderFrameCounter(1000)
        assertNull(counter.finish(2000).fps)
        counter.frame(3000);assertEquals(3000L,counter.firstFrameNanos)
        counter.begin(1_000_000_000)
        counter.frame(1_500_000_000);counter.frame(2_000_000_000)
        val result=counter.finish(3_000_000_000)
        assertEquals(2L,result.frames);assertEquals(2.0,result.seconds,0.0);assertEquals(1.0,result.fps!!,0.0)
        counter.begin(4_000_000_000);assertNull(counter.finish(5_000_000_000).fps)
    }
    @Test fun glThreadCountingIsSafeDuringSampling() {
        val counter=RenderFrameCounter(0);counter.begin(0)
        val threads=List(4){Thread {repeat(1000){counter.frame(1_000_000_000)}}}
        threads.forEach {it.start()};threads.forEach {it.join()}
        assertEquals(4000L,counter.finish(2_000_000_000).frames)
    }
}
