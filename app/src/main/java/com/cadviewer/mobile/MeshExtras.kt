package com.cadviewer.mobile

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

/** Offline mesh import. STEP and IGES are B-Rep CAD data and are NOT handled here. */
object MeshExtras {
    data class Parsed(
        val vertices:ArrayList<Float>,
        val normals:ArrayList<Float>,
        val units:String,
        val parts:List<ModelLoader.Part>
    )
    private val limit=ModelLoader.MAX_TRIANGLES
    private fun add(a:FloatArray,b:FloatArray,c:FloatArray,out:ArrayList<Float>,normal:ArrayList<Float>) {
        require(out.size / 9 < limit){"Модель надто велика: понад $limit трикутників"}
        val ux=b[0]-a[0];val uy=b[1]-a[1];val uz=b[2]-a[2]
        val vx=c[0]-a[0];val vy=c[1]-a[1];val vz=c[2]-a[2]
        var nx=uy*vz-uz*vy
        var ny=uz*vx-ux*vz
        var nz=ux*vy-uy*vx
        val len=sqrt(nx*nx+ny*ny+nz*nz)
        if(len>1.0e-12f){nx/=len;ny/=len;nz/=len}else{nx=0f;ny=0f;nz=1f}
        for(p in arrayOf(a,b,c)){
            require(p.all{it.isFinite()}){"Некоректні координати"}
            out.add(p[0]);out.add(p[1]);out.add(p[2])
            normal.add(nx);normal.add(ny);normal.add(nz)
        }
    }

    private data class Item(val id:String,val transform:FloatArray)
    private data class Definition(
        val id:String,val name:String,
        val points:ArrayList<FloatArray> = ArrayList(),
        val faces:ArrayList<IntArray> = ArrayList(),
        val children:ArrayList<Item> = ArrayList()
    )
    private val identity=floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f,0f,0f,0f)
    private fun transform(raw:String?):FloatArray {
        if(raw.isNullOrBlank())return identity.copyOf()
        val numbers=raw.trim().split(Regex("\\s+")).map{it.toFloat()}
        require(numbers.size==12 && numbers.all{it.isFinite()}){"Неправильний 3MF transform"}
        return numbers.toFloatArray()
    }
    private fun apply(p:FloatArray,m:FloatArray):FloatArray=floatArrayOf(
        p[0]*m[0]+p[1]*m[3]+p[2]*m[6]+m[9],
        p[0]*m[1]+p[1]*m[4]+p[2]*m[7]+m[10],
        p[0]*m[2]+p[1]*m[5]+p[2]*m[8]+m[11]
    )
    private fun compose(outer:FloatArray,inner:FloatArray):FloatArray {
        val out=FloatArray(12)
        for(col in 0..2) {
            for(row in 0..2){
                out[col*3+row]=outer[row]*inner[col*3]+outer[3+row]*inner[col*3+1]+outer[6+row]*inner[col*3+2]
            }
        }
        for(row in 0..2)out[9+row]=outer[row]*inner[9]+outer[3+row]*inner[10]+outer[6+row]*inner[11]+outer[9+row]
        return out
    }
    fun read3mf(bytes:ByteArray):Parsed {
        val zip=ZipInputStream(ByteArrayInputStream(bytes))
        var modelXml:ByteArray?=null
        var total=0
        zip.use { z ->
            while(true){
                val entry=z.nextEntry?:break
                if(!entry.isDirectory && entry.name.replace('\\','/').lowercase().endsWith(".model")){
                    val stream=ByteArrayOutputStream()
                    val buf=ByteArray(8192)
                    while(true){
                        val n=z.read(buf)
                        if(n<0)break
                        total+=n
                        require(total<=30*1024*1024){"3MF XML exceeds 30 MB"}
                        stream.write(buf,0,n)
                    }
                    if(modelXml==null)modelXml=stream.toByteArray()
                }
                z.closeEntry()
            }
        }
        val xml=modelXml?:error("3MF does not contain a model XML part")
        val parser=Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        val definitions=LinkedHashMap<String,Definition>()
        val build=ArrayList<Item>()
        var current:Definition?=null
        var isBuild=false
        var mm=1f
        var event=parser.eventType
        while(event!=XmlPullParser.END_DOCUMENT){
            val tag=parser.name ?: ""
            if(event==XmlPullParser.START_TAG){
                when(tag){
                    "model" -> {
                        mm=when(parser.getAttributeValue(null,"unit")?:"millimeter"){
                            "millimeter" -> 1f
                            "micron" -> .001f
                            "centimeter" -> 10f
                            "inch" -> 25.4f
                            "foot" -> 304.8f
                            "meter" -> 1000f
                            else -> 1f
                        }
                    }
                    "object" -> {
                        val id=parser.getAttributeValue(null,"id")?: ""
                        current=Definition(id,parser.getAttributeValue(null,"name")?:"Object $id")
                        definitions[id]=current!!
                    }
                    "vertex" -> current?.let{ o ->
                        require(o.points.size <= 2_000_000){"Too many 3MF vertices"}
                        o.points.add(floatArrayOf(
                            (parser.getAttributeValue(null,"x")?:"0").toFloat()*mm,
                            (parser.getAttributeValue(null,"y")?:"0").toFloat()*mm,
                            (parser.getAttributeValue(null,"z")?:"0").toFloat()*mm
                        ))
                    }
                    "triangle" -> current?.let{o->
                        o.faces.add(intArrayOf(
                            parser.getAttributeValue(null,"v1")!!.toInt(),
                            parser.getAttributeValue(null,"v2")!!.toInt(),
                            parser.getAttributeValue(null,"v3")!!.toInt()))
                    }
                    "component" -> current?.let{o->
                        val id=parser.getAttributeValue(null,"objectid")?:return@let
                        o.children.add(Item(id,transform(parser.getAttributeValue(null,"transform"))))
                    }
                    "build" -> isBuild=true
                    "item" -> if(isBuild) {
                        val id=parser.getAttributeValue(null,"objectid")?:""
                        build.add(Item(id,transform(parser.getAttributeValue(null,"transform"))))
                    }
                }
            }else if(event==XmlPullParser.END_TAG){
                when(tag){
                    "object" -> current=null
                    "build" -> isBuild=false
                }
            }
            event=parser.next()
        }
        require(definitions.isNotEmpty()){"No 3MF model objects"}
        val vertex=ArrayList<Float>()
        val normals=ArrayList<Float>()
        val parts=ArrayList<ModelLoader.Part>()
        fun emitObject(id:String, matrix:FloatArray, depth:Int){
            require(depth<12){"Cyclic 3MF components"}
            val obj=definitions[id] ?: error("Missing 3MF object $id")
            val start=vertex.size/3
            for(face in obj.faces){
                require(face.all{it in obj.points.indices}){"Invalid 3MF triangle index"}
                add(apply(obj.points[face[0]],matrix),apply(obj.points[face[1]],matrix),
                    apply(obj.points[face[2]],matrix),vertex,normals)
            }
            val count=vertex.size/3-start
            if(count>0)parts.add(ModelLoader.Part(obj.name,start,count))
            obj.children.forEach { emitObject(it.id,compose(matrix,it.transform),depth+1) }
        }
        if(build.isNotEmpty())build.forEach{emitObject(it.id,it.transform,0)}
        else definitions.values.filter{it.faces.isNotEmpty()}.forEach{emitObject(it.id,identity,0)}
        require(vertex.isNotEmpty()){"3MF model contains no mesh triangles"}
        return Parsed(vertex,normals,"mm",parts)
    }

    private data class Property(val type:String,val name:String,val listCount:String?=null)
    private fun size(type:String):Int=when(type){
        "char","int8","uchar","uint8" -> 1
        "short","int16","ushort","uint16" -> 2
        "int","int32","uint","uint32","float","float32" -> 4
        "double","float64" -> 8
        else -> error("Unsupported PLY data type: $type")
    }
    private fun number(bb:ByteBuffer,type:String):Number=when(type){
        "char","int8" -> bb.get().toInt()
        "uchar","uint8" -> bb.get().toInt() and 255
        "short","int16" -> bb.short.toInt()
        "ushort","uint16" -> bb.short.toInt() and 65535
        "int","int32" -> bb.int
        "uint","uint32" -> bb.int.toLong() and 0xffffffffL
        "float","float32" -> bb.float
        "double","float64" -> bb.double
        else -> error("Unsupported PLY data type: $type")
    }
    fun readPly(bytes:ByteArray):Parsed {
        val prefix=bytes.copyOfRange(0,minOf(bytes.size,64)).toString(Charsets.US_ASCII)
        require(prefix.startsWith("ply\n")||prefix.startsWith("ply\r\n")){"Invalid PLY header"}
        val endMarker="end_header"
        val headEnd=bytes.indexOfSequence(endMarker.toByteArray(Charsets.US_ASCII))
        require(headEnd in 0..16_384){"PLY header missing or too large"}
        var start=headEnd+endMarker.length
        while(start<bytes.size && (bytes[start]==13.toByte()||bytes[start]==10.toByte()))start++
        val header=bytes.copyOfRange(0,start).toString(Charsets.US_ASCII)
        val lines=header.lines()
        val binary=lines.any{it.trim().startsWith("format binary_little_endian")}
        require(binary || lines.any{it.trim().startsWith("format ascii")}){"Only ASCII and binary little-endian PLY supported"}
        var element=""
        var countVertices=0
        var countFaces=0
        val vp=ArrayList<Property>()
        val fp=ArrayList<Property>()
        for(line in lines){
            val t=line.trim().split(Regex("\\s+"))
            when(t.firstOrNull()){
                "element" -> {
                    element=t.getOrNull(1)?:""
                    if(element=="vertex")countVertices=t.getOrNull(2)?.toIntOrNull()?:0
                    if(element=="face")countFaces=t.getOrNull(2)?.toIntOrNull()?:0
                }
                "property" -> {
                    val target=when(element){"vertex"->vp;"face"->fp;else->null}?:continue
                    if(t.getOrNull(1)=="list" && t.size>=5)target.add(Property(t[3],t[4],t[2]))
                    else if(t.size>=3)target.add(Property(t[1],t[2]))
                }
            }
        }
        require(countVertices in 3..2_000_000 && countFaces in 1..limit){"Invalid PLY vertex/face count"}
        require(vp.any{it.name=="x"} && vp.any{it.name=="y"} && vp.any{it.name=="z"}){"PLY has no XYZ coordinates"}
        val points=ArrayList<FloatArray>(countVertices)
        val faces=ArrayList<IntArray>(countFaces)
        if(binary){
            val bb=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(start)
            repeat(countVertices){
                var x=0f;var y=0f;var z=0f
                for(p in vp){
                    require(p.listCount==null){"Vertex list property not supported"}
                    val n=number(bb,p.type).toFloat()
                    when(p.name){"x"->x=n;"y"->y=n;"z"->z=n}
                }
                points.add(floatArrayOf(x,y,z))
            }
            repeat(countFaces){
                var indices:IntArray?=null
                for(p in fp){
                    if(p.listCount!=null){
                        val n=number(bb,p.listCount).toInt()
                        require(n in 0..4096){"Invalid PLY face size"}
                        val arr=IntArray(n){number(bb,p.type).toInt()}
                        if(p.name=="vertex_indices"||p.name=="vertex_index")indices=arr
                    }else number(bb,p.type)
                }
                if(indices!=null && indices!!.size>=3)faces.add(indices!!)
            }
        }else{
            val body=bytes.copyOfRange(start,bytes.size).toString(Charsets.US_ASCII)
            val rows=body.lineSequence().filter{it.isNotBlank()}.iterator()
            repeat(countVertices){
                require(rows.hasNext()){"Incomplete PLY vertices"}
                val t=rows.next().trim().split(Regex("\\s+"))
                fun coord(name:String):Float=t[vp.indexOfFirst{it.name==name}].toFloat()
                points.add(floatArrayOf(coord("x"),coord("y"),coord("z")))
            }
            repeat(countFaces){
                require(rows.hasNext()){"Incomplete PLY faces"}
                val t=rows.next().trim().split(Regex("\\s+"))
                val n=t[0].toInt()
                require(n in 0..4096 && n+1<=t.size){"Invalid PLY face"}
                if(n>=3)faces.add(IntArray(n){t[it+1].toInt()})
            }
        }
        val vertex=ArrayList<Float>();val normal=ArrayList<Float>()
        for(face in faces){
            require(face.all{it in points.indices}){"Invalid PLY face vertex index"}
            for(i in 1 until face.size-1)add(points[face[0]],points[face[i]],points[face[i+1]],vertex,normal)
        }
        return Parsed(vertex,normal,"од. моделі",listOf(ModelLoader.Part("PLY mesh",0,vertex.size/3)))
    }
    private fun ByteArray.indexOfSequence(needle:ByteArray):Int {
        outer@ for(i in 0..(size-needle.size)){
            for(j in needle.indices)if(this[i+j]!=needle[j])continue@outer
            return i
        }
        return -1
    }
}
