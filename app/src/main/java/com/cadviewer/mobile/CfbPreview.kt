package com.cadviewer.mobile

/** Reads PNG/JPEG reference images embedded inside OLE2 Inventor IPT files.
 * This is only a 2D preview and never decodes a CAD body.
 */
object CfbPreview {
    private val signature=byteArrayOf(0xd0.toByte(),0xcf.toByte(),0x11,0xe0.toByte(),0xa1.toByte(),0xb1.toByte(),0x1a,0xe1.toByte())
    private fun int(b:ByteArray,p:Int):Int {
        require(p>=0 && p+4<=b.size){"Corrupted compound file"}
        return (b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8) or
            ((b[p+2].toInt() and 255) shl 16) or (b[p+3].toInt() shl 24)
    }
    private fun short(b:ByteArray,p:Int):Int{
        require(p>=0 && p+2<=b.size)
        return (b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)
    }
    fun extract(b:ByteArray):ByteArray?{
        if(b.size<512 || signature.indices.any{b[it]!=signature[it]})return null
        val size=1 shl short(b,30)
        if(size!=512 && size!=4096)return null
        val total=(b.size-512)/size
        val fatCount=int(b,44)
        require(fatCount in 1..(total+1)){"Bad FAT count"}
        fun pos(id:Int):Int {
            require(id>=0 && id<total){"Bad OLE sector"}
            return 512+id*size
        }
        val fatIds=ArrayList<Int>()
        for(i in 0..108){
            val id=int(b,76+i*4)
            if(id>=0)fatIds.add(id)
        }
        var diff=int(b,68)
        repeat(int(b,72).coerceIn(0,2048)){
            if(diff>=0){
                val off=pos(diff)
                for(j in 0 until size/4-1){
                    val id=int(b,off+j*4)
                    if(id>=0)fatIds.add(id)
                }
                diff=int(b,off+size-4)
            }
        }
        require(fatIds.size>=fatCount){"FAT allocation missing"}
        val fat=IntArray(fatCount*size/4)
        for(i in 0 until fatCount){
            val off=pos(fatIds[i])
            for(j in 0 until size/4)fat[i*(size/4)+j]=int(b,off+j*4)
        }
        fun readStream(start:Int,length:Int):ByteArray{
            require(length in 0..60*1024*1024){"Stream too large"}
            val out=ByteArray(length)
            var copied=0
            var sid=start
            val seen=HashSet<Int>()
            while(copied<length){
                require(sid in fat.indices && seen.add(sid)){"Invalid FAT chain"}
                val n=minOf(size,length-copied)
                System.arraycopy(b,pos(sid),out,copied,n)
                copied+=n
                sid=fat[sid]
            }
            return out
        }
        fun readDirectory(start:Int):ByteArray{
            val out=java.io.ByteArrayOutputStream()
            var sid=start
            val seen=HashSet<Int>()
            while(sid>=0 && sid in fat.indices && seen.add(sid) && out.size()<256*size){
                out.write(b,pos(sid),size)
                sid=fat[sid]
            }
            return out.toByteArray()
        }
        data class Entry(val name:String,val type:Int,val start:Int,val length:Int)
        val directory=readDirectory(int(b,48))
        val entries=ArrayList<Entry>()
        for(p in directory.indices step 128){
            if(p+128>directory.size)break
            val t=directory[p+66].toInt() and 255
            if(t!=2 && t!=5)continue
            val n=short(directory,p+64)
            if(n<2 || n>64 || n%2!=0)continue
            val name=String(directory,p,n-2,Charsets.UTF_16LE)
            val first=int(directory,p+116)
            val length=int(directory,p+120)
            if(length>=0) entries.add(Entry(name,t,first,length))
        }
        val root=entries.firstOrNull{it.type==5}
        val miniSize=1 shl short(b,32)
        val cutoff=int(b,56)
        val miniBytes=if(root!=null && root.length in 1..(60*1024*1024))readStream(root.start,root.length) else ByteArray(0)
        val miniFat=ArrayList<Int>()
        val miniCount=int(b,64)
        if(miniCount>0 && miniSize==64){
            var sid=int(b,60)
            val seen=HashSet<Int>()
            repeat(miniCount.coerceAtMost(total)){
                if(sid in fat.indices && seen.add(sid)){
                    val off=pos(sid)
                    for(i in 0 until size/4)miniFat.add(int(b,off+i*4))
                    sid=fat[sid]
                }
            }
        }
        fun readMini(first:Int,length:Int):ByteArray{
            require(length in 1..(8*1024*1024))
            val result=ByteArray(length)
            var sid=first
            var copied=0
            val seen=HashSet<Int>()
            while(copied<length){
                require(sid in miniFat.indices && seen.add(sid)){"Invalid mini FAT"}
                val offset=sid*miniSize
                val n=minOf(miniSize,length-copied)
                require(offset>=0 && offset+n<=miniBytes.size)
                System.arraycopy(miniBytes,offset,result,copied,n)
                copied+=n
                sid=miniFat[sid]
            }
            return result
        }
        for(item in entries.filter{it.type==2 && it.name.startsWith("RefdFile_",true)}.sortedBy{it.name}){
            if(item.length !in 1..(12*1024*1024))continue
            try{
                val image=if(item.length<cutoff)readMini(item.start,item.length) else readStream(item.start,item.length)
                val png=image.size>=8 && image.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
                val jpg=image.size>=3 && (image[0].toInt() and 255)==255 && (image[1].toInt() and 255)==216 && (image[2].toInt() and 255)==255
                if(png||jpg)return image
            }catch(_:Exception){}
        }
        return null
    }
}
