package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import kotlin.math.*

data class ClipDistances(val near:Double,val far:Double) {
    init {require(near.isFinite() && far.isFinite() && near>0 && far>near)}
}
/** Tight camera-space range, independent of project datum. A local perspective horizon avoids
 * spending the depth buffer on kilometres behind an eye inside a building. */
fun adaptiveClip(camera:OrbitCamera,bounds:Bounds3?=null):ClipDistances {
    val extent=bounds?.let {maxOf(it.max.x-it.min.x,it.max.y-it.min.y,it.max.z-it.min.z)} ?: max(camera.distance,camera.orthographicHeight)
    val minimum=(extent*1e-4).coerceIn(1e-7,.02)
    val outward=cameraOutward(camera)
    val depths=bounds?.let {b->listOf(b.min.x,b.max.x).flatMap {x->listOf(b.min.y,b.max.y).flatMap {y->listOf(b.min.z,b.max.z).map {z->
        camera.distance-((x-camera.target.x)*outward.x+(y-camera.target.y)*outward.y+(z-camera.target.z)*outward.z)
    }}}}
    val first=depths?.minOrNull() ?: 0.0
    val last=depths?.maxOrNull() ?: max(camera.distance*4,camera.orthographicHeight*2)
    val near=if(first>minimum*2)max(minimum,first*.5)else minimum
    val horizon=if(camera.projection==Projection.PERSPECTIVE && first<=minimum)maxOf(500.0,camera.distance*20,camera.orthographicHeight*4)else Double.POSITIVE_INFINITY
    val far=min(max(near*2,last+max(minimum,extent*.05)),horizon).coerceAtLeast(near*2)
    return ClipDistances(near,far)
}
fun cameraOutward(camera:OrbitCamera)=Point3(cos(camera.pitch)*cos(camera.yaw),cos(camera.pitch)*sin(camera.yaw),sin(camera.pitch))
fun cameraKeyLight(camera:OrbitCamera):Point3 {
    val outward=cameraOutward(camera);val right=Point3(-sin(camera.yaw),cos(camera.yaw))
    val up=Point3(-sin(camera.pitch)*cos(camera.yaw),-sin(camera.pitch)*sin(camera.yaw),cos(camera.pitch))
    return normalizeRender(Point3(outward.x*.75-right.x*.45+up.x*.55,outward.y*.75-right.y*.45+up.y*.55,outward.z*.75+up.z*.55))
}
fun normalizeRender(p:Point3):Point3 {val n=sqrt(p.x*p.x+p.y*p.y+p.z*p.z);return if(n<1e-12)Point3(z=1.0)else Point3(p.x/n,p.y/n,p.z/n)}
fun lightCoefficients(strength:LightingStrength):Pair<Double,Double> = when(strength) {
    LightingStrength.SOFT->.64 to .36;LightingStrength.NORMAL->.48 to .52;LightingStrength.STRONG->.32 to .68
}
fun matteIllumination(normal:Point3,key:Point3,strength:LightingStrength):Double {
    val (ambient,diffuse)=lightCoefficients(strength)
    return ambient+diffuse*max(0.0,normal.x*key.x+normal.y*key.y+normal.z*key.z)
}
fun textureFrequency(detail:TextureDetail)=when(detail){TextureDetail.LOW->0.0;TextureDetail.MEDIUM->8.0;TextureDetail.HIGH->24.0}
fun textureStrength(detail:TextureDetail,pixelsPerMetre:Double):Double {
    val frequency=textureFrequency(detail)
    return if(frequency==0.0)0.0 else ((pixelsPerMetre/frequency-4)/8).coerceIn(0.0,1.0)*if(detail==TextureDetail.HIGH).035 else .02
}
fun matteTexture(point:Point3,detail:TextureDetail,strength:Double):Double {
    val x=point.x%64;val y=point.y%64;val z=point.z%64;val f=textureFrequency(detail)*2*PI
    val noise=(sin((x*.75+y*.5+z)*f)+sin((x+y*.75-z*.5)*f+1.7)+sin((-x*.5+y+z*.75)*f+4.1))/3
    return 1.0-strength*.5+strength*.5*noise
}
fun requestedSamples(quality:RenderQuality,aa:Boolean):Int=if(!aa)0 else when(quality){RenderQuality.PERFORMANCE->0;RenderQuality.BALANCED->2;RenderQuality.QUALITY->4}
fun lodPixelThreshold(quality:RenderQuality)=when(quality){RenderQuality.PERFORMANCE->40.0;RenderQuality.BALANCED->20.0;RenderQuality.QUALITY->8.0}
/** Bounded, camera-dependent presentation LOD. Never used by model, picks or exports. */
fun projectedDiameter(bounds:Bounds3,camera:OrbitCamera,height:Int):Double=LodProjection(camera,height).diameter(bounds)
/** Precompute the camera basis once per rendered frame; no per-batch point allocations. */
class LodProjection(private val camera:OrbitCamera,private val height:Int) {
    private val o=cameraOutward(camera)
    private val tangent=tan(OrbitCamera.FOV_Y/2)
    fun diameter(bounds:Bounds3):Double {
        val dx=bounds.max.x-bounds.min.x;val dy=bounds.max.y-bounds.min.y;val dz=bounds.max.z-bounds.min.z
        val radius=sqrt(dx*dx+dy*dy+dz*dz)/2
        val depth=camera.distance-((bounds.min.x+dx/2-camera.target.x)*o.x+(bounds.min.y+dy/2-camera.target.y)*o.y+(bounds.min.z+dz/2-camera.target.z)*o.z)
        if(depth<=radius)return Double.POSITIVE_INFINITY
        val span=if(camera.projection==Projection.PERSPECTIVE)2*(depth-radius)*tangent else camera.orthographicHeight
        return radius*2*height/span
    }
}
