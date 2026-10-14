package com.cadviewer.mobile

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class ViewerSurface(context:Context):GLSurfaceView(context) {
    val scene=SceneRenderer()
    private var lastX=0f;private var lastY=0f;private var lastSpan=0f
    private var lastCenterX=0f;private var lastCenterY=0f
    init {
        setEGLContextClientVersion(2)
        setRenderer(scene)
        renderMode=RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause=true
    }
    fun updateModel(m:ModelLoader.Model){queueEvent{scene.install(m)};requestRender()}
    fun view(which:String){queueEvent{scene.setView(which)};requestRender()}
    fun reset(){queueEvent{scene.reset()};requestRender()}
    fun setWire(value:Boolean){queueEvent{scene.wire=value};requestRender()}
    fun setEdges(value:Boolean){queueEvent{scene.edges=value};requestRender()}
    fun setDark(value:Boolean){queueEvent{scene.dark=value};requestRender()}
    fun setAxes(value:Boolean){queueEvent{scene.axes=value};requestRender()}
    fun setPerspective(value:Boolean){queueEvent{scene.perspective=value};requestRender()}
    fun setColor(index:Int){queueEvent{scene.colorIndex=index};requestRender()}
    override fun onTouchEvent(e:MotionEvent):Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> {
                lastX=e.x;lastY=e.y
                if(e.pointerCount>=2){
                    lastSpan=span(e)
                    lastCenterX=(e.getX(0)+e.getX(1))/2
                    lastCenterY=(e.getY(0)+e.getY(1))/2
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(e.pointerCount>=2){
                    val s=span(e);val cx=(e.getX(0)+e.getX(1))/2;val cy=(e.getY(0)+e.getY(1))/2
                    val factor=if(lastSpan>2) s/lastSpan else 1f
                    val dx=(cx-lastCenterX)/width.coerceAtLeast(1)*4
                    val dy=(cy-lastCenterY)/height.coerceAtLeast(1)*4
                    queueEvent {
                        scene.distance=(scene.distance/factor.coerceIn(.7f,1.4f)).coerceIn(1.5f,30f)
                        scene.panX+=dx
                        scene.panY-=dy
                    }
                    lastSpan=s;lastCenterX=cx;lastCenterY=cy
                } else {
                    val dx=e.x-lastX;val dy=e.y-lastY
                    queueEvent{
                        scene.yaw+=dx*.45f
                        scene.pitch=(scene.pitch+dy*.45f).coerceIn(-89f,89f)
                    }
                    lastX=e.x;lastY=e.y
                }
                requestRender();return true
            }
            MotionEvent.ACTION_POINTER_UP,MotionEvent.ACTION_UP -> {
                lastSpan=0f
                if(e.pointerCount>0){lastX=e.x;lastY=e.y}
                return true
            }
        }
        return true
    }
    private fun span(e:MotionEvent):Float=hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1))
}

class SceneRenderer:GLSurfaceView.Renderer {
    var yaw=35f;var pitch=25f;var distance=4f;var panX=0f;var panY=0f
    var wire=false;var edges=false;var dark=true;var axes=true;var perspective=true;var colorIndex=0
    private var shader=0;private var flatShader=0
    private var model=ModelLoader.demo()
    private var vertices:FloatBuffer=buffer(model.vertices)
    private var normals:FloatBuffer=buffer(model.normals)
    private var viewportWidth=1;private var viewportHeight=1
    private val projection=FloatArray(16)
    private val view=FloatArray(16)
    private val rotation=FloatArray(16)
    private val mv=FloatArray(16)
    private val mvp=FloatArray(16)
    fun install(m:ModelLoader.Model){
        model=m;vertices=buffer(m.vertices);normals=buffer(m.normals);reset()
    }
    fun reset(){yaw=35f;pitch=25f;distance=4f;panX=0f;panY=0f}
    fun setView(v:String){
        when(v){
            "Front"->{yaw=0f;pitch=0f}
            "Back"->{yaw=180f;pitch=0f}
            "Right"->{yaw=90f;pitch=0f}
            "Left"->{yaw=-90f;pitch=0f}
            "Top"->{yaw=0f;pitch=89f}
            "Bottom"->{yaw=0f;pitch=-89f}
            else->{yaw=35f;pitch=25f}
        }
    }
    override fun onSurfaceCreated(unused:GL10?,config:EGLConfig?) {
        shader=program(VERTEX,FRAGMENT)
        flatShader=program(FLAT_VERTEX,FLAT_FRAGMENT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }
    override fun onSurfaceChanged(unused:GL10?,width:Int,height:Int){
        viewportWidth=width.coerceAtLeast(1)
        viewportHeight=height.coerceAtLeast(1)
        GLES20.glViewport(0,0,viewportWidth,viewportHeight)
    }
    override fun onDrawFrame(unused:GL10?) {
        if(dark) GLES20.glClearColor(.055f,.075f,.11f,1f)
        else GLES20.glClearColor(.89f,.92f,.95f,1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val aspect=viewportWidth.toFloat()/viewportHeight
        if(perspective) Matrix.perspectiveM(projection,0,45f,aspect,.1f,100f)
        else {
            val ext=distance*.42f
            Matrix.orthoM(projection,0,-ext*aspect,ext*aspect,-ext,ext,.1f,100f)
        }
        Matrix.setLookAtM(view,0,0f,0f,distance,0f,0f,0f,0f,1f,0f)
        Matrix.translateM(view,0,panX,panY,0f)
        Matrix.setIdentityM(rotation,0)
        Matrix.rotateM(rotation,0,pitch,1f,0f,0f)
        Matrix.rotateM(rotation,0,yaw,0f,1f,0f)
        Matrix.multiplyMM(mv,0,view,0,rotation,0)
        Matrix.multiplyMM(mvp,0,projection,0,mv,0)
        val palette=arrayOf(
            floatArrayOf(.30f,.69f,.94f),
            floatArrayOf(.85f,.86f,.90f),
            floatArrayOf(.96f,.65f,.28f),
            floatArrayOf(.35f,.83f,.65f)
        )
        if(!wire) {
            GLES20.glUseProgram(shader)
            val pos=GLES20.glGetAttribLocation(shader,"aPosition")
            val norm=GLES20.glGetAttribLocation(shader,"aNormal")
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(shader,"uMVP"),1,false,mvp,0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(shader,"uMV"),1,false,mv,0)
            GLES20.glUniform3fv(GLES20.glGetUniformLocation(shader,"uColor"),1,palette[colorIndex.coerceIn(0,3)],0)
            vertices.position(0);normals.position(0)
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos,3,GLES20.GL_FLOAT,false,0,vertices)
            GLES20.glEnableVertexAttribArray(norm)
            GLES20.glVertexAttribPointer(norm,3,GLES20.GL_FLOAT,false,0,normals)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,model.vertices.size/3)
            GLES20.glDisableVertexAttribArray(pos)
            GLES20.glDisableVertexAttribArray(norm)
        }
        if(wire||edges) {
            GLES20.glUseProgram(flatShader)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(flatShader,"uMVP"),1,false,mvp,0)
            val c=if(dark) floatArrayOf(.95f,.97f,1f) else floatArrayOf(.12f,.16f,.22f)
            GLES20.glUniform3fv(GLES20.glGetUniformLocation(flatShader,"uColor"),1,c,0)
            val loc=GLES20.glGetAttribLocation(flatShader,"aPosition")
            GLES20.glEnableVertexAttribArray(loc)
            for(start in model.vertices.indices step 9) {
                vertices.position(start)
                GLES20.glVertexAttribPointer(loc,3,GLES20.GL_FLOAT,false,0,vertices)
                GLES20.glDrawArrays(GLES20.GL_LINE_LOOP,0,3)
            }
            GLES20.glDisableVertexAttribArray(loc)
        }
        if(axes) drawAxes()
    }
    private fun drawAxes(){
        GLES20.glUseProgram(flatShader)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(flatShader,"uMVP"),1,false,mvp,0)
        val loc=GLES20.glGetAttribLocation(flatShader,"aPosition")
        GLES20.glEnableVertexAttribArray(loc)
        val coords=arrayOf(
            floatArrayOf(0f,0f,0f,1.4f,0f,0f),
            floatArrayOf(0f,0f,0f,0f,1.4f,0f),
            floatArrayOf(0f,0f,0f,0f,0f,1.4f)
        )
        val colors=arrayOf(
            floatArrayOf(1f,.3f,.3f),
            floatArrayOf(.3f,1f,.45f),
            floatArrayOf(.4f,.65f,1f)
        )
        for(i in 0..2){
            val b=buffer(coords[i])
            GLES20.glVertexAttribPointer(loc,3,GLES20.GL_FLOAT,false,0,b)
            GLES20.glUniform3fv(GLES20.glGetUniformLocation(flatShader,"uColor"),1,colors[i],0)
            GLES20.glDrawArrays(GLES20.GL_LINES,0,2)
        }
        GLES20.glDisableVertexAttribArray(loc)
    }
    companion object {
        private fun buffer(a:FloatArray):FloatBuffer=
            ByteBuffer.allocateDirect(a.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(a);position(0)
            }
        private fun compile(type:Int,src:String):Int {
            val id=GLES20.glCreateShader(type)
            GLES20.glShaderSource(id,src)
            GLES20.glCompileShader(id)
            val ok=IntArray(1)
            GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0)
            check(ok[0]!=0){GLES20.glGetShaderInfoLog(id)}
            return id
        }
        private fun program(v:String,f:String):Int {
            val id=GLES20.glCreateProgram()
            GLES20.glAttachShader(id,compile(GLES20.GL_VERTEX_SHADER,v))
            GLES20.glAttachShader(id,compile(GLES20.GL_FRAGMENT_SHADER,f))
            GLES20.glLinkProgram(id)
            return id
        }
        private const val VERTEX="attribute vec3 aPosition; attribute vec3 aNormal; uniform mat4 uMVP; uniform mat4 uMV; varying vec3 vNormal; varying vec3 vEye; void main(){gl_Position=uMVP*vec4(aPosition,1.0);vNormal=mat3(uMV)*aNormal;vEye=(uMV*vec4(aPosition,1.0)).xyz;}"
        private const val FRAGMENT="precision mediump float;varying vec3 vNormal;varying vec3 vEye;uniform vec3 uColor;void main(){vec3 n=normalize(vNormal);vec3 l=normalize(vec3(0.4,0.8,1.0));float dif=abs(dot(n,l));float ambient=0.32;float spec=pow(max(dot(reflect(-l,n),normalize(-vEye)),0.0),22.0)*0.20;gl_FragColor=vec4(uColor*(ambient+0.68*dif)+vec3(spec),1.0);}"
        private const val FLAT_VERTEX="attribute vec3 aPosition;uniform mat4 uMVP;void main(){gl_Position=uMVP*vec4(aPosition,1.0);}"
        private const val FLAT_FRAGMENT="precision mediump float;uniform vec3 uColor;void main(){gl_FragColor=vec4(uColor,1.0);}"
    }
}
