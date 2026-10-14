package com.cadviewer.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import java.util.concurrent.Executors

class MainActivity: Activity() {
    private lateinit var viewer: ViewerSurface
    private lateinit var status: TextView
    private val worker=Executors.newSingleThreadExecutor()
    private val ink=Color.rgb(224,235,250)
    private val surface=Color.rgb(24,34,49)
    private fun button(title:String, click:()->Unit):Button=Button(this).apply {
        text=title; isAllCaps=false; textSize=12f
        setTextColor(ink)
        backgroundTintList=android.content.res.ColorStateList.valueOf(surface)
        setPadding(6,0,6,0); setOnClickListener{click()}
    }
    private fun label(value:String,size:Float=13f)=TextView(this).apply {
        text=value; textSize=size; setTextColor(ink); gravity=Gravity.CENTER_VERTICAL
        setPadding(12,6,12,6)
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.rgb(11,18,29)
        window.navigationBarColor=Color.rgb(11,18,29)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(12,19,31)) }
        val header=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        header.addView(label("CAD 3D VIEWER",19f),LinearLayout.LayoutParams(0,dp(54),1f))
        header.addView(button("OPEN"){openFile()},LinearLayout.LayoutParams(dp(86),dp(48)))
        header.addView(button("SETTINGS"){settings()},LinearLayout.LayoutParams(dp(99),dp(48)))
        root.addView(header)
        val views=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false }
        val viewRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        for((name,code) in listOf("ISO" to "Iso","FRONT" to "Front","BACK" to "Back","TOP" to "Top","BOTTOM" to "Bottom","LEFT" to "Left","RIGHT" to "Right")) {
            viewRow.addView(button(name){viewer.view(code)},LinearLayout.LayoutParams(dp(80),dp(44)))
        }
        views.addView(viewRow); root.addView(views)
        viewer=ViewerSurface(this)
        val saved=getPreferences(MODE_PRIVATE)
        viewer.setDark(saved.getBoolean("dark",true))
        viewer.setAxes(saved.getBoolean("axes",true))
        viewer.setPerspective(saved.getBoolean("persp",true))
        root.addView(viewer,LinearLayout.LayoutParams(-1,0,1f))
        val toolbar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        toolbar.addView(button("FIT / RESET"){viewer.reset()},LinearLayout.LayoutParams(0,dp(46),1f))
        toolbar.addView(button("DISPLAY"){displayMode()},LinearLayout.LayoutParams(0,dp(46),1f))
        toolbar.addView(button("COLOR"){colorMode()},LinearLayout.LayoutParams(0,dp(46),1f))
        root.addView(toolbar)
        status=label("Demo cube  •  One finger: rotate  •  Two fingers: zoom/pan",11f)
        status.setBackgroundColor(Color.rgb(18,27,42)); root.addView(status)
        setContentView(root)
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun openFile() {
        val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="*/*"
        }
        startActivityForResult(i,100)
    }
    @Deprecated("Used for Android 7+ compatibility without AndroidX")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=100 || resultCode!=RESULT_OK) return
        val uri=data?.data?:return
        val name=filename(uri)
        val ext=name.substringAfterLast(".", "").lowercase()
        if(ext=="ipt" || ext=="iam") {
            status.text="$name  •  2D reference preview (3D available online)"
            startActivity(Intent(this,IptPreviewActivity::class.java)
                .putExtra("uri",uri.toString())
                .putExtra("name",name))
            return
        }
        if(ext=="sldprt" || ext=="sldasm") {
            AlertDialog.Builder(this)
                .setTitle("SolidWorks 3D")
                .setMessage("Ця версія не декодує SLDPRT/SLDASM локально. Autodesk Viewer відкриває ці формати через інтернет після завантаження файла на сайт.")
                .setPositiveButton("Autodesk Viewer"){_,_->
                    startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://viewer.autodesk.com/")))
                }.setNegativeButton("Cancel",null).show()
            return
        }
        status.text="Loading $name..."
        worker.execute {
            try {
                val bytes=contentResolver.openInputStream(uri)?.use { input ->
                    val out=java.io.ByteArrayOutputStream()
                    val chunk=ByteArray(8192);var total=0
                    while(true){
                        val n=input.read(chunk)
                        if(n<0) break
                        total+=n
                        require(total<=ModelLoader.MAX_BYTES){"File exceeds 60 MB limit"}
                        out.write(chunk,0,n)
                    }
                    out.toByteArray()
                }?:error("Cannot read file")
                val model=ModelLoader.load(bytes,name)
                runOnUiThread {
                    viewer.updateModel(model)
                    status.text="${model.name}  •  ${model.triangles} triangles"
                }
            }catch(t:Exception){
                runOnUiThread{
                    status.text="Could not open $name"
                    AlertDialog.Builder(this).setTitle("Import error").setMessage(t.message?:"Invalid model").setPositiveButton("OK",null).show()
                }
            }
        }
    }
    private fun filename(uri:Uri):String {
        contentResolver.query(uri,null,null,null,null)?.use { c->
            val idx=c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if(idx>=0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment?:"model.stl"
    }
    private fun displayMode(){
        val labels=arrayOf("Solid shaded","Solid + mesh edges","Wireframe")
        AlertDialog.Builder(this).setTitle("Display mode").setItems(labels){_,which->
            viewer.setWire(which==2);viewer.setEdges(which==1)
        }.show()
    }
    private fun colorMode(){
        AlertDialog.Builder(this).setTitle("Model color").setItems(arrayOf("CAD blue","Metal gray","Copper","Mint green")){_,n->
            viewer.setColor(n)
        }.show()
    }
    private fun settings(){
        val prefs=getPreferences(MODE_PRIVATE)
        val names=arrayOf("Dark background","Show XYZ axes","Perspective projection")
        val values=booleanArrayOf(prefs.getBoolean("dark",true),prefs.getBoolean("axes",true),prefs.getBoolean("persp",true))
        AlertDialog.Builder(this).setTitle("Viewer settings")
            .setMultiChoiceItems(names,values){_,i,v->values[i]=v}
            .setPositiveButton("Apply"){_,_->
                viewer.setDark(values[0]); viewer.setAxes(values[1]); viewer.setPerspective(values[2])
                prefs.edit().putBoolean("dark",values[0]).putBoolean("axes",values[1]).putBoolean("persp",values[2]).apply()
            }
            .setNeutralButton("About"){_,_->
                AlertDialog.Builder(this).setTitle("Supported files")
                    .setMessage("Open STL (ASCII/binary) or OBJ models. Native SolidWorks and Inventor parts require export to STL or OBJ. Offline. Maximum 60 MB or 400,000 triangles.")
                    .setPositiveButton("OK",null).show()
            }
            .setNegativeButton("Cancel",null).show()
    }
    override fun onPause(){ super.onPause(); if(::viewer.isInitialized) viewer.onPause() }
    override fun onResume(){ super.onResume(); if(::viewer.isInitialized) viewer.onResume() }
    override fun onDestroy(){worker.shutdownNow();super.onDestroy()}
}
