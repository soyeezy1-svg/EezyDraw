package com.eezydraw.ui.canvas

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.*

internal class GraphicsEglChooser:GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl:EGL10,display:EGLDisplay):EGLConfig {
        val count=IntArray(1)
        val attributes=intArrayOf(0x3040,4,EGL10.EGL_SURFACE_TYPE,EGL10.EGL_WINDOW_BIT,EGL10.EGL_NONE)
        check(egl.eglChooseConfig(display,attributes,null,0,count) && count[0]>0){"No GLES2 window configuration"}
        val configs=arrayOfNulls<EGLConfig>(count[0])
        check(egl.eglChooseConfig(display,attributes,configs,configs.size,count)){"Cannot enumerate EGL formats"}
        val all=configs.take(count[0]).filterNotNull()
        fun value(config:EGLConfig,key:Int):Int {val out=IntArray(1);return if(egl.eglGetConfigAttrib(display,config,key,out))out[0]else 0}
        val modern=all.filter {value(it,0x3040) and 64 != 0 && value(it,EGL10.EGL_SAMPLES)==0}
        val available=modern.ifEmpty {all}
        val formats=available.map {c->EglFormat(value(c,EGL10.EGL_RED_SIZE),value(c,EGL10.EGL_GREEN_SIZE),value(c,EGL10.EGL_BLUE_SIZE),value(c,EGL10.EGL_ALPHA_SIZE),value(c,EGL10.EGL_DEPTH_SIZE),
            if(value(c,EGL10.EGL_SAMPLE_BUFFERS)>0)value(c,EGL10.EGL_SAMPLES)else 0)}
        return available[checkNotNull(chooseEglFormat(formats,0)){"No supported depth-buffer format"}]
    }
}

/** Use GLES3's explicit multisample resolve when available; retain the GLES2 shader contract. */
internal class GraphicsEglContextFactory:GLSurfaceView.EGLContextFactory {
    override fun createContext(egl:EGL10,display:EGLDisplay,config:EGLConfig):EGLContext {
        val modern=egl.eglCreateContext(display,config,EGL10.EGL_NO_CONTEXT,intArrayOf(0x3098,3,EGL10.EGL_NONE))
        if(modern!=null && modern!=EGL10.EGL_NO_CONTEXT)return modern
        egl.eglGetError()
        return egl.eglCreateContext(display,config,EGL10.EGL_NO_CONTEXT,intArrayOf(0x3098,2,EGL10.EGL_NONE))
    }
    override fun destroyContext(egl:EGL10,display:EGLDisplay,context:EGLContext){egl.eglDestroyContext(display,context)}
}
