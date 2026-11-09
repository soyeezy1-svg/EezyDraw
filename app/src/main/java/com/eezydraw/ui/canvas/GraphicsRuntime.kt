package com.eezydraw.ui.canvas

import com.eezydraw.core.model.*

internal data class EglFormat(val red:Int,val green:Int,val blue:Int,val alpha:Int,val depth:Int,val samples:Int)
/** Depth precision takes priority over multisampling; FXAA covers a non-MSAA fallback. */
internal fun chooseEglFormat(formats:List<EglFormat>,samples:Int):Int? = formats.indices
    .filter {val f=formats[it];f.red>=5 && f.green>=6 && f.blue>=5 && f.depth>=16 && f.samples<=samples}
    .maxWithOrNull(compareBy<Int> {if(formats[it].depth>=24)1 else 0}
        .thenBy {val f=formats[it];if(f.red>=8 && f.green>=8 && f.blue>=8 && f.alpha>=8)1 else 0}
        .thenBy {formats[it].samples}.thenBy {formats[it].depth.coerceAtMost(32)}.thenBy {formats[it].alpha.coerceAtMost(8)})
internal data class RenderDiagnostics(val fps:Double?=null,val quality:RenderQuality=RenderQuality.QUALITY,
    val samples:Int=0,val depthBits:Int=0,val fxaa:Boolean=false,val lodObjects:Int=0,val supportsMsaaResolve:Boolean=true,val atNanos:Long=System.nanoTime())
/** Counts active rendered frame intervals, not idle time or Compose recompositions. Quality
 * changes need sustained evidence and recover slowly; the user's preference is never rewritten. */
internal class AdaptiveGraphics {
    private var requested:RenderQuality?=null
    var quality=RenderQuality.QUALITY;private set
    private var previous=0L;private var start=0L;private var count=0
    private var slow=0;private var fast=0
    fun reset(){previous=0;start=0;count=0;slow=0;fast=0;requested=null}
    fun frame(now:Long,desired:RenderQuality,automatic:Boolean,motionActive:Boolean=false):Pair<Double?,RenderQuality>? {
        if(requested!=desired || !automatic){quality=desired;if(requested!=desired){previous=0;start=0;count=0};requested=desired;slow=0;fast=0}
        val delta=now-previous;previous=now
        if(delta !in 1_000_000L..(if(motionActive)1_000_000_000L else 250_000_000L)){start=now;count=0;slow=0;fast=0;return null to quality}
        if(start==0L)start=now
        count++
        val elapsed=now-start
        if(elapsed<1_000_000_000L)return null
        val fps=count*1e9/elapsed
        start=now;count=0
        if(automatic) {
            slow=if(fps<28)slow+1 else 0;fast=if(fps>52)fast+1 else 0
            if(slow>=2){quality=RenderQuality.entries[(quality.ordinal-1).coerceAtLeast(0)];slow=0;fast=0}
            else if(fast>=8){quality=RenderQuality.entries[(quality.ordinal+1).coerceAtMost(desired.ordinal)];fast=0}
        }
        return fps to quality
    }
}

internal fun renderClipBounds(scene:com.eezydraw.core.model.Bounds3,selection:com.eezydraw.core.model.Bounds3?,delta:com.eezydraw.core.editing.EditDelta?):com.eezydraw.core.model.Bounds3 {
    if(selection==null || delta==null)return scene
    var low=scene.min;var high=scene.max
    for(i in 0..7) {
        val p=runCatching {delta.apply(Point3(if(i and 1==0)selection.min.x else selection.max.x,if(i and 2==0)selection.min.y else selection.max.y,if(i and 4==0)selection.min.z else selection.max.z))}.getOrNull() ?: continue
        low=Point3(minOf(low.x,p.x),minOf(low.y,p.y),minOf(low.z,p.z));high=Point3(maxOf(high.x,p.x),maxOf(high.y,p.y),maxOf(high.z,p.z))
    }
    return Bounds3(low,high)
}

/** Colour + 24-bit depth MSAA storage plus one RGBA resolve, capped at 256MiB. */
internal fun boundedSamples(desired:Int,maximum:Int,width:Int,height:Int):Int {
    val pixels=width.toLong()*height
    return listOf(4,2).firstOrNull {it<=desired && it<=maximum && pixels*(it*8+4)<=256L*1024*1024} ?: 0
}

internal fun frameRateLabel(stats:RenderDiagnostics,now:Long):String = if(stats.fps==null || now-stats.atNanos>1_500_000_000L)"FPS — idle"else "%.0f FPS".format(java.util.Locale.ROOT,stats.fps)
