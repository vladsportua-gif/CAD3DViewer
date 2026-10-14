package com.cadviewer.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** IPT view is an embedded 2D reference image only, NOT a rotatable 3D body. */
class IptPreviewActivity : Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var image:ImageView
    private lateinit var info:TextView
    private val transform=Matrix()
    private var zoom=1f
    private var lastX=0f
    private var lastY=0f
    private fun dp(v:Int)=(v*resources.displayMetrics.density+0.5f).toInt()
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.rgb(9,18,30)
        window.navigationBarColor=Color.rgb(9,18,30)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13,21,34))
        }
        val top=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val back=Button(this).apply {text="‹ НАЗАД";setOnClickListener{finish()}}
        top.addView(back,LinearLayout.LayoutParams(dp(115),dp(56)))
        val title=TextView(this).apply {
            text="Inventor IPT · 2D перегляд";textSize=17f;setTextColor(Color.WHITE)
            gravity=Gravity.CENTER_VERTICAL
        }
        top.addView(title,LinearLayout.LayoutParams(0,dp(56),1f))
        root.addView(top)
        info=TextView(this).apply {
            text="Зчитую вбудоване зображення..."
            textSize=13f
            setTextColor(Color.rgb(191,210,226))
            setPadding(dp(16),dp(8),dp(16),dp(8))
        }
        root.addView(info)
        image=ImageView(this).apply {
            setBackgroundColor(Color.rgb(27,35,48))
            scaleType=ImageView.ScaleType.MATRIX
        }
        root.addView(image,LinearLayout.LayoutParams(-1,0,1f))
        val notice=TextView(this).apply {
            text="Це зображення з IPT, а не 3D-геометрія. Для повного 3D потрібен Autodesk Viewer."
            textSize=12f
            setTextColor(Color.rgb(209,217,230))
            setPadding(dp(14),dp(10),dp(14),dp(10))
        }
        root.addView(notice)
        val online=Button(this).apply {
            text="ВІДКРИТИ 3D В AUTODESK VIEWER"
            isAllCaps=false
            setOnClickListener{openOnline()}
        }
        root.addView(online,LinearLayout.LayoutParams(-1,dp(58)))
        setContentView(root)
        val detector=ScaleGestureDetector(this,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
            override fun onScale(d:ScaleGestureDetector):Boolean{
                val factor=d.scaleFactor.coerceIn(.8f,1.25f)
                val candidate=zoom*factor
                if(candidate in .65f..16f){
                    zoom=candidate
                    transform.postScale(factor,factor,d.focusX,d.focusY)
                    image.imageMatrix=transform
                }
                return true
            }
        })
        image.setOnTouchListener { _,event ->
            detector.onTouchEvent(event)
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN -> {lastX=event.x;lastY=event.y;true}
                MotionEvent.ACTION_MOVE -> {
                    if(event.pointerCount==1 && !detector.isInProgress){
                        transform.postTranslate(event.x-lastX,event.y-lastY)
                        image.imageMatrix=transform
                    }
                    lastX=event.x;lastY=event.y;true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {lastX=event.x;lastY=event.y;true}
                else -> true
            }
        }
        val raw=intent.getStringExtra("uri")
        if(raw.isNullOrBlank()){info.text="Файл не знайдено";return}
        worker.execute {
            try{
                val data=contentResolver.openInputStream(Uri.parse(raw))?.use { input ->
                    val output=ByteArrayOutputStream()
                    val chunk=ByteArray(8192)
                    while(true){
                        val count=input.read(chunk)
                        if(count<0)break
                        require(output.size()+count <= 60*1024*1024){"Файл більший за 60 МБ"}
                        output.write(chunk,0,count)
                    }
                    output.toByteArray()
                }?:error("Не вдалося прочитати файл")
                val preview=CfbPreview.extract(data)
                if(preview==null){
                    runOnUiThread{info.text="Цей IPT не має доступного вбудованого зображення. Для 3D скористайся Autodesk Viewer."}
                }else{
                    val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(preview,0,preview.size,options)
                    var sample=1
                    while(options.outWidth/sample>2500 || options.outHeight/sample>2500)sample*=2
                    val settings=BitmapFactory.Options().apply {inSampleSize=sample}
                    val bitmap=BitmapFactory.decodeByteArray(preview,0,preview.size,settings)
                    if(bitmap==null)error("Не вдалося прочитати зображення")
                    runOnUiThread{showImage(bitmap)}
                }
            }catch(ex:Exception){
                runOnUiThread{info.text="Не вдалося відкрити попередній перегляд: ${ex.message}"}
            }
        }
    }
    private fun showImage(bitmap:Bitmap){
        info.text="${intent.getStringExtra("name")?:"Деталь.ipt"} · зображення ${bitmap.width} × ${bitmap.height}"
        image.setImageBitmap(bitmap)
        image.post {
            val w=image.width.toFloat();val h=image.height.toFloat()
            if(w<=0f||h<=0f)return@post
            val factor=minOf(w/bitmap.width,h/bitmap.height)*.94f
            zoom=1f
            transform.reset()
            transform.postScale(factor,factor)
            transform.postTranslate((w-bitmap.width*factor)/2,(h-bitmap.height*factor)/2)
            image.imageMatrix=transform
        }
    }
    private fun openOnline(){
        AlertDialog.Builder(this).setTitle("Перегляд 3D онлайн")
            .setMessage("Autodesk Viewer підтримує файли IPT та SolidWorks. На сайті потрібно увійти в Autodesk Account і самостійно завантажити файл. Файл буде передано сервісу Autodesk, а не нашій програмі. Відкрити сайт?")
            .setPositiveButton("Відкрити"){_,_->startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://viewer.autodesk.com/")))}
            .setNegativeButton("Скасувати",null).show()
    }
    override fun onDestroy(){worker.shutdownNow();super.onDestroy()}
}
