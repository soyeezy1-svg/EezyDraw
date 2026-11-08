package com.eezydraw.ui.developer

import android.os.Debug
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eezydraw.core.model.ProjectId
import java.util.Locale

/** Called only after successful GLES draw submission, not on Compose recomposition or idle time. */
class RenderFrameCounter(val loadStartedNanos:Long) {
    @Volatile var firstFrameNanos:Long?=null;private set
    private var start:Long?=null
    private var frames=0L
    @Synchronized fun frame(now:Long) {if(firstFrameNanos==null)firstFrameNanos=now;if(start?.let {now>=it}==true)frames++}
    @Synchronized fun begin(now:Long) {start=now;frames=0}
    @Synchronized fun finish(now:Long):FrameRate {
        val seconds=start?.let {(now-it)/1e9} ?: 0.0
        return FrameRate(frames,seconds,if(frames>=2 && seconds>0)frames/seconds else null)
    }
}
data class FrameRate(val frames:Long,val seconds:Double,val fps:Double?)
data class MemoryReading(val javaBytes:Long,val nativeBytes:Long,val processPssBytes:Long?) {
    companion object {fun read():MemoryReading {val rt=Runtime.getRuntime();val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
        return MemoryReading(rt.totalMemory()-rt.freeMemory(),Debug.getNativeHeapAllocatedSize(),info.totalPss.takeIf {it>0}?.toLong()?.times(1024))}}
}
data class StressResult(val rate:FrameRate,val firstFrameMillis:Double?,val memory:MemoryReading,val message:String?=null)
data class StressRun(val projectId:ProjectId,val levels:Int,val objects:Int,val generationMillis:Double,val loadMillis:Double,val counter:RenderFrameCounter,val result:StressResult?=null)

@Composable fun StressStatus(run:StressRun,onClose:()->Unit) {
    val result=run.result
    if(result==null)Surface(Modifier.widthIn(max=420.dp).padding(8.dp).testTag("stress_running"),tonalElevation=6.dp) {
        Column(Modifier.padding(12.dp)) {Text("Stress test · ${run.levels} levels / ${run.objects} objects")
            Text("Preparing 3D, then measuring a 5-second orbit. Keep the app in the foreground.")
            LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClose,Modifier.testTag("stress_stop")){Text("Stop benchmark")}}
    } else AlertDialog(onDismissRequest=onClose,title={Text("Stress test results")},text={Column(Modifier.testTag("stress_results"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("${run.levels} levels · ${run.objects} objects")
        Text("Generation and save: ${number(run.generationMillis)} ms\nStorage load: ${number(run.loadMillis)} ms\nFirst frame after load: ${result.firstFrameMillis?.let(::number) ?: "Unavailable"} ms")
        Text("Rendered FPS: ${result.rate.fps?.let(::number) ?: "Unavailable"}\n${result.rate.frames} GL frames in ${number(result.rate.seconds)} s")
        Text("Java heap: ${mib(result.memory.javaBytes)} MiB\nNative heap: ${mib(result.memory.nativeBytes)} MiB\nProcess PSS: ${result.memory.processPssBytes?.let(::mib) ?: "Unavailable"} MiB")
        Text("FPS counts actual GL frame submissions during the orbit, including slow frames; it is not an idle-screen refresh estimate.",style=MaterialTheme.typography.bodySmall)
        result.message?.let {Text(it)}
    }},confirmButton={TextButton(onClose,Modifier.testTag("stress_close")){Text("Close")}})
}
private fun number(value:Double)=String.format(Locale.ROOT,"%.1f",value)
private fun mib(value:Long)=number(value/1048576.0)

/** The clock drives camera movement; the GL thread independently counts completed draw callbacks. */
@Composable fun StressBenchmarkEffect(run:StressRun?,ready:Boolean,onPrepare:()->Unit,onOrbit:(Double)->Unit,onComplete:(StressResult)->Unit) {
    val latestReady=androidx.compose.runtime.rememberUpdatedState(ready)
    val prepare=androidx.compose.runtime.rememberUpdatedState(onPrepare)
    val orbit=androidx.compose.runtime.rememberUpdatedState(onOrbit)
    val complete=androidx.compose.runtime.rememberUpdatedState(onComplete)
    androidx.compose.runtime.LaunchedEffect(run?.counter) {
        if(run==null || run.result!=null)return@LaunchedEffect
        val prepared=kotlinx.coroutines.withTimeoutOrNull(90000) {
            while(!latestReady.value)kotlinx.coroutines.delay(16)
            prepare.value()
            repeat(2){androidx.compose.runtime.withFrameNanos {}}
            while(!latestReady.value || run.counter.firstFrameNanos==null)kotlinx.coroutines.delay(16)
            true
        } ?: false
        var rate=FrameRate(0,0.0,null)
        if(prepared) {
            val start=System.nanoTime();run.counter.begin(start)
            do {androidx.compose.runtime.withFrameNanos {};orbit.value(((System.nanoTime()-start)/5e9).coerceIn(0.0,1.0))}while(System.nanoTime()-start<5_000_000_000L)
            rate=run.counter.finish(System.nanoTime())
        }
        val memory=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default){MemoryReading.read()}
        complete.value(StressResult(rate,run.counter.firstFrameNanos?.let {(it-run.counter.loadStartedNanos)/1e6},memory,
            if(!prepared)"No rendered model frame arrived within 90 seconds. Check renderer availability and keep the app visible."else if(rate.fps==null)"Too few renderer frames to calculate FPS."else null))
    }
}
