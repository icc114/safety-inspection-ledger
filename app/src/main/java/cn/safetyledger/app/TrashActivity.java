package cn.safetyledger.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import cn.safetyledger.app.data.Entities.Inspection;
import cn.safetyledger.app.data.LedgerRepository;
import cn.safetyledger.app.media.MediaService;
import cn.safetyledger.app.security.PasswordHash;
import cn.safetyledger.app.sync.CloudSyncService;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public final class TrashActivity extends Activity {
    private LedgerRepository repo;
    private LinearLayout list;
    private CloudSyncService cloud;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);Ui.setupWindow(this);repo=new LedgerRepository(this);cloud=new CloudSyncService(this);
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);root.addView(Ui.appBar(this,"回收站"));
        ScrollView scroll=new ScrollView(this);list=Ui.column(this);list.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,24));
        scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);load();
    }

    private void load(){
        list.removeAllViews();TextView note=Ui.text(this,
                "普通删除只进入本机回收站，可直接恢复。启用云同步后，永久删除必须再次输入云同步空间密码；系统会先在云端建立管理员恢复副本，再向普通设备同步删除标记。",
                13,false);note.setTextColor(Ui.MUTED);list.addView(note);list.addView(Ui.gap(this,8));
        if(isAdmin()&&cloud.hasConfiguredSync()){
            Button recovery=Ui.secondaryButton(this,"管理员恢复库 · 找回已彻底删除记录");
            recovery.setOnClickListener(v->loadAdminRecovery());list.addView(recovery);list.addView(Ui.gap(this,10));
        }
        int count=0;for(Inspection inspection:repo.list(null,null,null,true,1,100000).rows){
            count++;LinearLayout card=Ui.card(this);card.setPadding(Ui.dp(this,12),Ui.dp(this,9),Ui.dp(this,12),Ui.dp(this,9));
            card.addView(Ui.text(this,inspection.date+" · "+inspection.templateName+"\n"+inspection.location,15,true));
            LinearLayout actions=Ui.row(this);Button restore=Ui.compactButton(this,"恢复记录",true);Button delete=Ui.dangerButton(this,"永久删除");
            restore.setOnClickListener(v->{repo.restore(inspection.id);Ui.toast(this,"记录已恢复");load();});
            delete.setOnClickListener(v->confirm(inspection));actions.addView(restore,Ui.weight(1));actions.addView(Ui.horizontalGap(this,6));actions.addView(delete,Ui.weight(1));
            card.addView(actions);list.addView(card);list.addView(Ui.gap(this,8));
        }
        if(count==0){TextView empty=Ui.text(this,"本机回收站为空",16,true);empty.setTextColor(Ui.MUTED);empty.setGravity(android.view.Gravity.CENTER);list.addView(empty);}
    }

    private boolean isAdmin(){String r=repo.setting("cloud_role","");return "OWNER".equals(r)||"ADMIN".equals(r)||"PRIMARY".equals(repo.setting("device_role",""));}

    private void confirm(Inspection inspection){
        boolean cloudEnabled=cloud.hasConfiguredSync();String hash=repo.setting("delete_password_hash","");
        if(!cloudEnabled&&hash.isBlank()){Ui.toast(this,"未启用云同步；请先在基础设置中设置本机永久删除密码");return;}
        EditText password=Ui.input(this,cloudEnabled?"请输入云同步空间密码":"请输入本机永久删除密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        new AlertDialog.Builder(this).setTitle(cloudEnabled?"云端永久删除确认":"本机永久删除确认")
                .setMessage(cloudEnabled?"系统会先保存一份仅管理员可恢复的加密云端副本，然后删除本机记录和照片，并把删除标记同步到其他普通设备。":"仅删除本机数据，不会产生云端恢复副本。")
                .setView(password).setPositiveButton("永久删除",(dialog,which)->{
                    char[] entered=password.getText().toString().toCharArray();
                    if(cloudEnabled)deleteWithCloudRecovery(inspection,entered);
                    else{
                        try{if(!PasswordHash.verify(entered,hash)){Ui.toast(this,"密码错误");return;}
                            new MediaService(this).deleteInspectionMedia(inspection.id);repo.permanentDelete(inspection.id);Ui.toast(this,"本机记录已永久删除");load();
                        }catch(Exception error){Ui.toast(this,"删除失败："+error.getMessage());}
                    }
                }).setNegativeButton("取消",null).show();
    }

    private void deleteWithCloudRecovery(Inspection inspection,char[] password){
        Ui.toast(this,"正在建立管理员恢复副本…");new Thread(()->{
            try{cloud.archiveAndPermanentlyDelete(inspection.id,password,null);runOnUiThread(()->{Ui.toast(this,"已永久删除；管理员恢复副本已保存");load();});}
            catch(Exception error){runOnUiThread(()->Ui.toast(this,"删除失败："+error.getMessage()));}
        },"trash-cloud-delete").start();
    }

    private void loadAdminRecovery(){
        Ui.toast(this,"正在读取管理员恢复库…");new Thread(()->{
            try{List<CloudSyncService.RecoveryEntry> entries=cloud.listAdminRecovery();runOnUiThread(()->showRecovery(entries));}
            catch(Exception error){runOnUiThread(()->Ui.toast(this,"读取失败："+error.getMessage()));}
        },"admin-recovery-list").start();
    }

    private void showRecovery(List<CloudSyncService.RecoveryEntry> entries){
        if(entries.isEmpty()){Ui.toast(this,"管理员恢复库为空");return;}
        String[] labels=new String[entries.size()];for(int i=0;i<entries.size();i++){
            CloudSyncService.RecoveryEntry e=entries.get(i);String when=e.deletedAt()>0?DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(e.deletedAt())):"未知时间";
            labels[i]=when+" · 记录 "+shortId(e.inspectionId());
        }
        new AlertDialog.Builder(this).setTitle("管理员恢复库").setItems(labels,(d,which)->confirmRecovery(entries.get(which))).setNegativeButton("关闭",null).show();
    }

    private void confirmRecovery(CloudSyncService.RecoveryEntry entry){
        new AlertDialog.Builder(this).setTitle("恢复这条检查记录？").setMessage("恢复后会生成一个比删除标记更新的记录版本，并在下一次检查内容同步时重新发送到其他设备。")
                .setPositiveButton("恢复",(d,w)->new Thread(()->{
                    try{cloud.restoreAdminRecovery(entry.name());runOnUiThread(()->{Ui.toast(this,"检查记录已从管理员恢复库找回");load();});}
                    catch(Exception error){runOnUiThread(()->Ui.toast(this,"恢复失败："+error.getMessage()));}
                },"admin-recovery-restore").start()).setNegativeButton("取消",null).show();
    }

    private String shortId(String id){return id==null?"未知":id.substring(0,Math.min(8,id.length()));}
}
