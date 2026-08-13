package cn.safetyledger.app.media

import android.graphics.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
object Watermarker { fun apply(source:Bitmap,location:String,lat:Double?,lon:Double?):Bitmap { val b=source.copy(Bitmap.Config.ARGB_8888,true);val c=Canvas(b);val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=(b.width/32f).coerceAtLeast(28f);setShadowLayer(4f,1f,1f,Color.BLACK)};val stamp=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(Date());val geo=if(lat!=null&&lon!=null)"  %.6f, %.6f".format(lat,lon) else "";c.drawText("$stamp  $location$geo",20f,b.height-30f,p);return b } }
class MediaRetention { fun candidates(root:File,now:Long=System.currentTimeMillis())=root.walkTopDown().filter{it.isFile&&now-it.lastModified()>180L*24*60*60*1000}.toList() }
