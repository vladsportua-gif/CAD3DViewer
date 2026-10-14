package com.cadviewer.mobile

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max

/** Offline STL/OBJ mesh parser. Models are normalized into a viewing cube. */
object ModelLoader {
    const val MAX_BYTES=60 * 1024 * 1024
    const val MAX_TRIANGLES=400_000
    data class Model(val vertices:FloatArray,val normals:FloatArray,val triangles:Int,val name:String)
    fun load(bytes:ByteArray,name:String):Model {
        require(bytes.isNotEmpty() && bytes.size<=MAX_BYTES){"File size must be 1–60 MB"}
        val ext=name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val arrays=when(ext) {
            "obj" -> readObj(bytes)
            "stl" -> readStl(bytes)
            else -> error("Unsupported .$ext format. Export the CAD file to STL or OBJ.")
        }
        require(arrays.first.isNotEmpty()){"No triangles found in the file"}
        val v=arrays.first.toFloatArray()
        val n=arrays.second.toFloatArray()
        normalize(v)
        return Model(v,n,v.size/9,name)
    }
    private fun normalize(v:FloatArray) {
        var minX=Float.POSITIVE_INFINITY;var minY=minX;var minZ=minX
        var maxX=Float.NEGATIVE_INFINITY;var maxY=maxX;var maxZ=maxX
        for(i in v.indices step 3) {
            minX=kotlin.math.min(minX,v[i]);minY=kotlin.math.min(minY,v[i+1]);minZ=kotlin.math.min(minZ,v[i+2])
            maxX=max(maxX,v[i]);maxY=max(maxY,v[i+1]);maxZ=max(maxZ,v[i+2])
        }
        val size=max(maxX-minX,max(maxY-minY,maxZ-minZ))
        require(size.isFinite() && size>0f){"Degenerate or invalid geometry"}
        val cx=(minX+maxX)*.5f;val cy=(minY+maxY)*.5f;val cz=(minZ+maxZ)*.5f
        for(i in v.indices step 3){
            v[i]=(v[i]-cx)*2f/size
            v[i+1]=(v[i+1]-cy)*2f/size
            v[i+2]=(v[i+2]-cz)*2f/size
        }
    }
    private fun readObj(bytes:ByteArray):Pair<ArrayList<Float>,ArrayList<Float>> {
        val points=ArrayList<FloatArray>()
        val positions=ArrayList<Float>()
        val normals=ArrayList<Float>()
        ByteArrayInputStream(bytes).bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line=raw.trim()
                if(line.isEmpty() || line.startsWith("#")) return@forEach
                val t=line.split(Regex("\\s+"))
                when(t[0]) {
                    "v" -> if(t.size>=4) points.add(floatArrayOf(t[1].toFloat(),t[2].toFloat(),t[3].toFloat()))
                    "f" -> if(t.size>=4) {
                        val face=t.drop(1).takeWhile{ !it.startsWith("#") }.map { element ->
                            val id=element.substringBefore('/').toInt()
                            val index=if(id>0) id-1 else points.size+id
                            require(index in points.indices){"OBJ vertex index is invalid"}
                            points[index]
                        }
                        for(i in 1 until face.size-1) addTri(face[0],face[i],face[i+1],positions,normals)
                    }
                }
            }
        }
        return positions to normals
    }
    private fun readStl(bytes:ByteArray):Pair<ArrayList<Float>,ArrayList<Float>> {
        if(bytes.size>=84) {
            val bb=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count=bb.getInt(80).toLong() and 0xffffffffL
            if(count<=MAX_TRIANGLES && 84L+count*50L==bytes.size.toLong()) {
                val positions=ArrayList<Float>();val normals=ArrayList<Float>()
                bb.position(84)
                repeat(count.toInt()) {
                    bb.float;bb.float;bb.float
                    val a=FloatArray(3){bb.float}
                    val b=FloatArray(3){bb.float}
                    val c=FloatArray(3){bb.float}
                    bb.short
                    addTri(a,b,c,positions,normals)
                }
                return positions to normals
            }
        }
        val positions=ArrayList<Float>();val normals=ArrayList<Float>();val vertices=ArrayList<FloatArray>(3)
        ByteArrayInputStream(bytes).bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line=raw.trim()
                if(line.startsWith("vertex ",true)) {
                    val t=line.split(Regex("\\s+"))
                    if(t.size>=4) vertices.add(floatArrayOf(t[1].toFloat(),t[2].toFloat(),t[3].toFloat()))
                    if(vertices.size==3) {
                        addTri(vertices[0],vertices[1],vertices[2],positions,normals)
                        vertices.clear()
                    }
                }
            }
        }
        return positions to normals
    }
    private fun addTri(a:FloatArray,b:FloatArray,c:FloatArray,v:ArrayList<Float>,n:ArrayList<Float>) {
        require(v.size/9<MAX_TRIANGLES){"Model exceeds $MAX_TRIANGLES triangles"}
        val ux=b[0]-a[0];val uy=b[1]-a[1];val uz=b[2]-a[2]
        val vx=c[0]-a[0];val vy=c[1]-a[1];val vz=c[2]-a[2]
        var nx=uy*vz-uz*vy;var ny=uz*vx-ux*vz;var nz=ux*vy-uy*vx
        val length=kotlin.math.sqrt(nx*nx+ny*ny+nz*nz)
        if(length>0f){nx/=length;ny/=length;nz/=length}else{nx=0f;ny=0f;nz=1f}
        for(p in arrayOf(a,b,c)){
            v.add(p[0]);v.add(p[1]);v.add(p[2])
            n.add(nx);n.add(ny);n.add(nz)
        }
    }
    fun demo():Model {
        val p=arrayOf(
            floatArrayOf(-1f,-1f,-1f),floatArrayOf(1f,-1f,-1f),
            floatArrayOf(1f,1f,-1f),floatArrayOf(-1f,1f,-1f),
            floatArrayOf(-1f,-1f,1f),floatArrayOf(1f,-1f,1f),
            floatArrayOf(1f,1f,1f),floatArrayOf(-1f,1f,1f)
        )
        val faces=arrayOf(
            intArrayOf(0,2,1),intArrayOf(0,3,2),intArrayOf(4,5,6),intArrayOf(4,6,7),
            intArrayOf(0,1,5),intArrayOf(0,5,4),intArrayOf(3,7,6),intArrayOf(3,6,2),
            intArrayOf(1,2,6),intArrayOf(1,6,5),intArrayOf(0,4,7),intArrayOf(0,7,3)
        )
        val v=ArrayList<Float>();val n=ArrayList<Float>()
        faces.forEach { addTri(p[it[0]],p[it[1]],p[it[2]],v,n) }
        val a=v.toFloatArray();normalize(a)
        return Model(a,n.toFloatArray(),12,"Demo cube")
    }
}
