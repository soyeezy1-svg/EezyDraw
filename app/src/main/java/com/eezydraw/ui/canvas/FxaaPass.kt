package com.eezydraw.ui.canvas

import android.opengl.GLES20 as GL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Consumes resolved scene colour without a new depth attachment. GLES2 copies the
 * single-sample window; GLES3 supplies the explicit MSAA resolve texture. */
internal class FxaaPass {
    private var program=0;private var texture=0;private var buffer=0;private var width=0;private var height=0
    private var failed=false
    fun contextLost(){program=0;texture=0;buffer=0;width=0;height=0;failed=false}
    fun release(){if(program!=0)GL.glDeleteProgram(program);if(texture!=0)GL.glDeleteTextures(1,intArrayOf(texture),0);if(buffer!=0)GL.glDeleteBuffers(1,intArrayOf(buffer),0);contextLost()}
    fun apply(w:Int,h:Int,resolvedTexture:Int=0):Boolean {
        if(failed)return false
        try {
            if(program==0) {
                fun shader(type:Int,source:String):Int {val id=GL.glCreateShader(type);GL.glShaderSource(id,source);GL.glCompileShader(id);val ok=IntArray(1);GL.glGetShaderiv(id,GL.GL_COMPILE_STATUS,ok,0);if(ok[0]==0){val log=GL.glGetShaderInfoLog(id);GL.glDeleteShader(id);error(log)};return id}
                val vertex=shader(GL.GL_VERTEX_SHADER,VERTEX)
                val fragment=try {shader(GL.GL_FRAGMENT_SHADER,FRAGMENT)}catch(error:IllegalStateException){GL.glDeleteShader(vertex);throw error}
                program=GL.glCreateProgram();GL.glAttachShader(program,vertex);GL.glAttachShader(program,fragment);GL.glLinkProgram(program)
                GL.glDeleteShader(vertex);GL.glDeleteShader(fragment)
                val ok=IntArray(1);GL.glGetProgramiv(program,GL.GL_LINK_STATUS,ok,0);check(ok[0]!=0)
                val ids=IntArray(1);GL.glGenTextures(1,ids,0);texture=ids[0];GL.glGenBuffers(1,ids,0);buffer=ids[0]
                val points=floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)
                val data=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().put(points).apply {position(0)}
                GL.glBindBuffer(GL.GL_ARRAY_BUFFER,buffer);GL.glBufferData(GL.GL_ARRAY_BUFFER,32,data,GL.GL_STATIC_DRAW)
            }
            GL.glActiveTexture(GL.GL_TEXTURE0);GL.glBindTexture(GL.GL_TEXTURE_2D,if(resolvedTexture!=0)resolvedTexture else texture)
            if(resolvedTexture==0 && (width!=w || height!=h)) {
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR);GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE);GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
                GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,w,h,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,null)
                width=w;height=h
            }
            if(resolvedTexture==0)GL.glCopyTexSubImage2D(GL.GL_TEXTURE_2D,0,0,0,0,0,w,h)
            check(GL.glGetError()==GL.GL_NO_ERROR)
            GL.glDisable(GL.GL_DEPTH_TEST);GL.glDepthMask(false);GL.glDisable(GL.GL_BLEND);GL.glColorMask(true,true,true,true)
            GL.glUseProgram(program);GL.glUniform1i(GL.glGetUniformLocation(program,"uImage"),0)
            GL.glUniform2f(GL.glGetUniformLocation(program,"uPixel"),1f/w,1f/h)
            GL.glBindBuffer(GL.GL_ARRAY_BUFFER,buffer);val a=GL.glGetAttribLocation(program,"aPosition")
            GL.glEnableVertexAttribArray(a);GL.glVertexAttribPointer(a,2,GL.GL_FLOAT,false,8,0)
            GL.glDrawArrays(GL.GL_TRIANGLE_STRIP,0,4);GL.glDisableVertexAttribArray(a)
            GL.glBindTexture(GL.GL_TEXTURE_2D,0);GL.glBindBuffer(GL.GL_ARRAY_BUFFER,0)
            GL.glDepthMask(true);GL.glEnable(GL.GL_BLEND)
            return true
        } catch(_:IllegalStateException){GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0);release();failed=true;return false}
    }
    companion object {
        const val VERTEX="""attribute vec2 aPosition; varying vec2 vUv; void main(){vUv=aPosition*.5+.5;gl_Position=vec4(aPosition,0.,1.);}"""
        const val FRAGMENT="""
            precision mediump float; uniform sampler2D uImage; uniform vec2 uPixel; varying vec2 vUv;
            void main(){
                vec3 c=texture2D(uImage,vUv).rgb;
                vec3 nw=texture2D(uImage,vUv+vec2(-1.,-1.)*uPixel).rgb;
                vec3 ne=texture2D(uImage,vUv+vec2(1.,-1.)*uPixel).rgb;
                vec3 sw=texture2D(uImage,vUv+vec2(-1.,1.)*uPixel).rgb;
                vec3 se=texture2D(uImage,vUv+vec2(1.,1.)*uPixel).rgb;
                vec3 lum=vec3(.299,.587,.114);float l=dot(c,lum);
                float a=dot(nw,lum),b=dot(ne,lum),d=dot(sw,lum),e=dot(se,lum);
                float low=min(l,min(min(a,b),min(d,e))),high=max(l,max(max(a,b),max(d,e)));
                if(high-low<max(.025,high*.10)){gl_FragColor=vec4(c,1.);return;}
                vec2 dir=vec2(-((a+b)-(d+e)),((a+d)-(b+e)));
                float reduce=max((a+b+d+e)*.03125,.0078125);
                dir=clamp(dir/(min(abs(dir.x),abs(dir.y))+reduce),vec2(-6.),vec2(6.))*uPixel;
                vec3 x=.5*(texture2D(uImage,vUv+dir*(1./3.-.5)).rgb+texture2D(uImage,vUv+dir*(2./3.-.5)).rgb);
                vec3 y=x*.5+.25*(texture2D(uImage,vUv-dir*.5).rgb+texture2D(uImage,vUv+dir*.5).rgb);
                float ly=dot(y,lum);gl_FragColor=vec4((ly<low||ly>high)?x:y,1.);
            }
        """
    }
}
