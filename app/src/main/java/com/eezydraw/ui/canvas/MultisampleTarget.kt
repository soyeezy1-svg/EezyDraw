package com.eezydraw.ui.canvas

import android.opengl.GLES20 as GL
import android.opengl.GLES30 as GL3

/** Bounded GLES3 MSAA colour/depth renderbuffers with an explicit single-sample resolve.
 * The window always stays single-sample, so post-processing and loupe readback are defined. */
internal class MultisampleTarget {
    private var multisample=0;private var resolve=0;private var colour=0;private var depth=0;private var texture=0
    private var width=0;private var height=0;private var requested=-1;private var supported=emptySet<Int>()
    var samples=0;private set
    fun contextCreated(supported:Boolean) {
        multisample=0;resolve=0;colour=0;depth=0;texture=0;width=0;height=0;requested=-1;samples=0
        this.supported=if(supported) {
            fun counts(format:Int):Set<Int> {
                val size=IntArray(1);GL3.glGetInternalformativ(GL.GL_RENDERBUFFER,format,GL3.GL_NUM_SAMPLE_COUNTS,1,size,0)
                val values=IntArray(size[0].coerceIn(0,16))
                if(values.isNotEmpty())GL3.glGetInternalformativ(GL.GL_RENDERBUFFER,format,GL.GL_SAMPLES,values.size,values,0)
                return values.toSet()
            }
            counts(GL3.GL_RGBA8).intersect(counts(GL3.GL_DEPTH_COMPONENT24))
        }else emptySet()
    }
    fun release() {
        if(multisample!=0)GL.glDeleteFramebuffers(1,intArrayOf(multisample),0)
        if(resolve!=0)GL.glDeleteFramebuffers(1,intArrayOf(resolve),0)
        if(colour!=0)GL.glDeleteRenderbuffers(1,intArrayOf(colour),0)
        if(depth!=0)GL.glDeleteRenderbuffers(1,intArrayOf(depth),0)
        if(texture!=0)GL.glDeleteTextures(1,intArrayOf(texture),0)
        multisample=0;resolve=0;colour=0;depth=0;texture=0;samples=0
    }
    fun begin(w:Int,h:Int,desired:Int) {
        val count=listOf(4,2).firstOrNull {it in supported && it<=desired && boundedSamples(it,it,w,h)==it} ?: 0
        if(w!=width || h!=height || desired!=requested) {
            release();width=w;height=h;requested=desired
            if(count>0)try {
                fun name(gen:(IntArray)->Unit):Int {val id=IntArray(1);gen(id);check(id[0]!=0);return id[0]}
                multisample=name {GL.glGenFramebuffers(1,it,0)};resolve=name {GL.glGenFramebuffers(1,it,0)}
                colour=name {GL.glGenRenderbuffers(1,it,0)};depth=name {GL.glGenRenderbuffers(1,it,0)};texture=name {GL.glGenTextures(1,it,0)}
                GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,multisample)
                GL.glBindRenderbuffer(GL.GL_RENDERBUFFER,colour)
                GL3.glRenderbufferStorageMultisample(GL.GL_RENDERBUFFER,count,GL3.GL_RGBA8,w,h)
                GL.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_RENDERBUFFER,colour)
                GL.glBindRenderbuffer(GL.GL_RENDERBUFFER,depth)
                GL3.glRenderbufferStorageMultisample(GL.GL_RENDERBUFFER,count,GL3.GL_DEPTH_COMPONENT24,w,h)
                GL.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER,GL.GL_DEPTH_ATTACHMENT,GL.GL_RENDERBUFFER,depth)
                check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER)==GL.GL_FRAMEBUFFER_COMPLETE)
                GL.glBindTexture(GL.GL_TEXTURE_2D,texture)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR);GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE);GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
                GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL3.GL_RGBA8,w,h,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,null)
                GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,resolve)
                GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,texture,0)
                check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER)==GL.GL_FRAMEBUFFER_COMPLETE && GL.glGetError()==GL.GL_NO_ERROR)
                val actual=IntArray(1);GL.glGetRenderbufferParameteriv(GL.GL_RENDERBUFFER,GL3.GL_RENDERBUFFER_SAMPLES,actual,0)
                samples=actual[0]
            }catch(_:IllegalStateException){release();for(i in 0..7){if(GL.glGetError()==GL.GL_NO_ERROR)break}}
        }
        GL.glBindTexture(GL.GL_TEXTURE_2D,0);GL.glBindRenderbuffer(GL.GL_RENDERBUFFER,0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,if(samples>0)multisample else 0)
    }
    fun present() {
        if(samples==0)return
        GL3.glBindFramebuffer(GL3.GL_READ_FRAMEBUFFER,resolve);GL3.glBindFramebuffer(GL3.GL_DRAW_FRAMEBUFFER,0)
        GL3.glBlitFramebuffer(0,0,width,height,0,0,width,height,GL.GL_COLOR_BUFFER_BIT,GL.GL_NEAREST)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0)
    }
    /** Resolve the scene once. The returned texture is sampled only after returning to the window. */
    fun finish():Int {
        if(samples==0){GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0);return 0}
        GL3.glBindFramebuffer(GL3.GL_READ_FRAMEBUFFER,multisample);GL3.glBindFramebuffer(GL3.GL_DRAW_FRAMEBUFFER,resolve)
        GL3.glBlitFramebuffer(0,0,width,height,0,0,width,height,GL.GL_COLOR_BUFFER_BIT,GL.GL_NEAREST)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0)
        return texture
    }
}
