package cn.safetyledger.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import cn.safetyledger.app.data.Entities.Signature;
import cn.safetyledger.app.data.LedgerRepository;
import cn.safetyledger.app.media.MediaService;
import java.io.*;
import java.util.UUID;

public final class SignatureActivity extends Activity {
    private SignaturePad pad; private String iid,role;
    @Override protected void onCreate(Bundle b){super.onCreate(b);Ui.setupWindow(this);iid=getIntent().getStringExtra("inspection_id");role=getIntent().getStringExtra("role");
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);TextView title=Ui.text(this,"请横屏签名 · "+roleName(role),22,true);root.addView(title,new LinearLayout.LayoutParams(-1,Ui.dp(this,58)));pad=new SignaturePad(this);root.addView(pad,new LinearLayout.LayoutParams(-1,0,1));LinearLayout actions=Ui.row(this);Button clear=Ui.button(this,"清空");Button save=Ui.button(this,"保存签名");clear.setOnClickListener(v->pad.clear());save.setOnClickListener(v->save());actions.addView(clear,Ui.weight(1));actions.addView(save,Ui.weight(1));root.addView(actions);setContentView(root);}
    private String roleName(String r){return "INSPECTOR1".equals(r)?"检查人员1":"INSPECTOR2".equals(r)?"检查人员2":"被检查人";}
    private void save(){if(pad.empty){Ui.toast(this,"请先签名");return;}try{File dir=new File(getFilesDir(),"business_media/"+iid);dir.mkdirs();File f=new File(dir,"signature-"+role+".png");Bitmap bm=pad.bitmap();try(OutputStream o=new FileOutputStream(f)){bm.compress(Bitmap.CompressFormat.PNG,100,o);}bm.recycle();Signature s=new Signature();s.id=UUID.nameUUIDFromBytes((iid+role).getBytes()).toString();s.inspectionId=iid;s.role=role;s.path=f.getAbsolutePath();s.sha256=MediaService.sha256(f);new LedgerRepository(this).saveSignature(s);setResult(RESULT_OK,new Intent().putExtra("role",role));finish();}catch(Exception e){Ui.toast(this,"签名保存失败："+e.getMessage());}}
    private static final class SignaturePad extends View {Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);Path path=new Path();boolean empty=true;float lx,ly;SignaturePad(android.content.Context c){super(c);p.setColor(Color.rgb(15,23,42));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Ui.dp(c,4));p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);setBackgroundColor(Color.WHITE);}protected void onDraw(Canvas c){c.drawPath(path,p);}public boolean onTouchEvent(android.view.MotionEvent e){float x=e.getX(),y=e.getY();if(e.getAction()==0){path.moveTo(x,y);lx=x;ly=y;empty=false;}else if(e.getAction()==2){path.quadTo(lx,ly,(x+lx)/2,(y+ly)/2);lx=x;ly=y;}else if(e.getAction()==1)performClick();invalidate();return true;}@Override public boolean performClick(){super.performClick();return true;}void clear(){path.reset();empty=true;invalidate();}Bitmap bitmap(){Bitmap b=Bitmap.createBitmap(Math.max(1,getWidth()),Math.max(1,getHeight()),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.WHITE);c.drawPath(path,p);return b;}}
}

