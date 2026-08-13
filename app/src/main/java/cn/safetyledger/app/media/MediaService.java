package cn.safetyledger.app.media;

import android.content.*;
import android.graphics.*;
import android.location.Location;
import android.net.Uri;
import cn.safetyledger.app.data.Entities.Media;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class MediaService {
    private final Context context;
    public MediaService(Context c){context=c.getApplicationContext();}
    public File mediaDir(String inspectionId){File d=new File(context.getFilesDir(),"business_media/"+inspectionId);if(!d.exists()&&!d.mkdirs())throw new IllegalStateException("无法创建媒体目录");return d;}
    public Media importAndWatermark(Uri source,String iid,String itemId,String category,String place,Location location) throws IOException {
        Bitmap original;try(InputStream input=context.getContentResolver().openInputStream(source)){original=BitmapFactory.decodeStream(input);}if(original==null)throw new IOException("无法读取照片");
        int max=2400;float scale=Math.min(1f,max/(float)Math.max(original.getWidth(),original.getHeight()));Bitmap bitmap=scale<1?Bitmap.createScaledBitmap(original,Math.round(original.getWidth()*scale),Math.round(original.getHeight()*scale),true):original.copy(Bitmap.Config.ARGB_8888,true);if(bitmap!=original)original.recycle();
        LocalDateTime now=LocalDateTime.now();String locationText=place==null?"":place;String line1="拍摄时间："+now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));String line2="检查地点："+(locationText.isBlank()?"未填写":locationText);String line3=location==null?"":"经纬度："+String.format(Locale.US,"%.6f, %.6f",location.getLatitude(),location.getLongitude());
        Canvas canvas=new Canvas(bitmap);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));p.setTextSize(Math.max(28,bitmap.getWidth()/35f));p.setColor(Color.WHITE);p.setShadowLayer(3,0,1,Color.BLACK);float pad=24,line=p.getTextSize()*1.4f;int lines=line3.isBlank()?2:3;Paint bg=new Paint();bg.setColor(0x99000000);canvas.drawRect(0,bitmap.getHeight()-pad*2-line*lines,bitmap.getWidth(),bitmap.getHeight(),bg);float y=bitmap.getHeight()-pad-line*(lines-1);canvas.drawText(line1,pad,y,p);canvas.drawText(line2,pad,y+line,p);if(lines==3)canvas.drawText(line3,pad,y+line*2,p);
        String id=UUID.randomUUID().toString();File originalFile=new File(mediaDir(iid),id+"-original.bin");try(InputStream rawIn=context.getContentResolver().openInputStream(source);OutputStream rawOut=new FileOutputStream(originalFile)){if(rawIn==null)throw new IOException("原始照片读取失败");byte[]buf=new byte[65536];for(int n;(n=rawIn.read(buf))>=0;)if(n>0)rawOut.write(buf,0,n);}File out=new File(mediaDir(iid),id+".jpg");try(OutputStream os=new FileOutputStream(out)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,92,os))throw new IOException("照片压缩失败");}bitmap.recycle();
        Media m=new Media();m.id=id;m.inspectionId=iid;m.itemId=itemId;m.category=category;m.localPath=out.getAbsolutePath();m.capturedAt=System.currentTimeMillis();m.location=locationText;m.latitude=location==null?null:location.getLatitude();m.longitude=location==null?null:location.getLongitude();m.sha256=sha256(out);m.mime="image/jpeg";m.size=out.length();return m;
    }
    public static String sha256(File file) throws IOException {try{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file.toPath())){byte[]b=new byte[65536];for(int n;(n=in.read(b))>0;)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format("%02x",x));return s.toString();}catch(Exception e){throw new IOException(e);}}
    public void deleteInspectionMedia(String iid){File d=mediaDir(iid);File[]fs=d.listFiles();if(fs!=null)for(File f:fs)if(!f.delete())f.deleteOnExit();d.delete();}
}
