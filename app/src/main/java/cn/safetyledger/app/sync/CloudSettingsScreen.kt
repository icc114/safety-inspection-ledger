package cn.safetyledger.app.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.safetyledger.app.data.LedgerDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class ProviderOption(val id:String,val label:String,val help:String)
private val providers=listOf(
    ProviderOption("cloudflare","配套免费云服务（Cloudflare）","推荐：适合多台手机和电脑通过互联网自动同步"),
    ProviderOption("leancloud","LeanCloud 兼容网关","需填写已部署的安全检查台账兼容同步网关"),
    ProviderOption("fnos","飞牛 NAS","可使用配套 Docker 服务；局域网私有地址允许 http://"),
    ProviderOption("google_drive","Google Drive 同步网关","填写配套网关地址，不是网盘分享链接"),
    ProviderOption("compatible","其他兼容 HTTP 服务","需支持安全检查台账同步协议 v3"),
)

@Composable
fun CloudSettingsScreen(dao:LedgerDao,onBack:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();val engine=remember{CloudSyncEngine(context,dao)}
    var provider by remember{mutableStateOf("cloudflare")};var endpoint by remember{mutableStateOf("")}
    var space by remember{mutableStateOf("安全检查台账")};var password by remember{mutableStateOf("")}
    var device by remember{mutableStateOf(Build.MODEL)};var result by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)};var current by remember{mutableStateOf<JSONObject?>(null)}
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    suspend fun refresh(){current=engine.summary()}
    LaunchedEffect(Unit){
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        refresh()
        current?.let{json->provider=json.optString("provider","cloudflare");endpoint=json.optString("endpoint");space=json.optString("space","安全检查台账");device=json.optString("device",Build.MODEL)}
        password=CloudSecrets.get(context,"sync_password").orEmpty()
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text("云同步",style=MaterialTheme.typography.headlineMedium);Text("离线时先保存到本机，联网后自动补同步。同步密码同时用于端到端加密，请在所有设备填写相同内容。") }
        items(providers){option->FilterChip(selected=provider==option.id,onClick={provider=option.id},label={Column{Text(option.label);if(provider==option.id)Text(option.help,style=MaterialTheme.typography.bodySmall)}})}
        item{
            OutlinedTextField(endpoint,{endpoint=it.trim()},label={Text("同步服务地址")},placeholder={Text("https://…")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(space,{space=it},label={Text("同步空间名称")},supportingText={Text("手机和电脑填写同一个名称即可自动进入同一空间")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(device,{device=it},label={Text("本机名称")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(password,{password=it},label={Text("同步密码（至少8位）")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        }
        current?.let{json->item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text("已连接",style=MaterialTheme.typography.titleMedium);Text("设备数：${json.optInt("activeDeviceCount",1)}")
            Text("上次同步：${json.optString("lastSyncAt").ifBlank{"尚未完成首次同步"}}")
            json.optString("lastError").takeIf{it.isNotBlank()}?.let{Text("最近错误：$it",color=MaterialTheme.colorScheme.error)}
        }}}}
        item{
            Button(enabled=!busy&&endpoint.isNotBlank()&&space.isNotBlank()&&password.length>=8,onClick={
                busy=true;result="正在连接并验证同步空间…";scope.launch{
                    runCatching{withContext(Dispatchers.IO){engine.connect(provider,endpoint,space,password,device);engine.sync()}}
                        .onSuccess{summary->result="连接并同步成功：上传${summary.uploaded}条，下载${summary.downloaded}条";refresh()}
                        .onFailure{result="连接失败：${it.message}";showSyncFailure(context,result)}
                    busy=false
                }
            },modifier=Modifier.fillMaxWidth()){Text(if(busy)"正在处理…" else "测试连接、保存并立即同步")}
            if(current!=null)OutlinedButton(enabled=!busy,onClick={
                busy=true;result="正在双向同步…";scope.launch{
                    runCatching{withContext(Dispatchers.IO){engine.sync()}}
                        .onSuccess{summary->result="同步完成：上传${summary.uploaded}条，下载${summary.downloaded}条，回收站${summary.trashed}条";refresh()}
                        .onFailure{result="同步失败：${it.message}；本机记录已保留";showSyncFailure(context,result)}
                    busy=false
                }
            },modifier=Modifier.fillMaxWidth()){Text("立即双向同步")}
            if(current!=null)OutlinedButton(enabled=!busy,onClick={
                busy=true;result="正在把六个月前记录生成PDF并上传云端…";scope.launch{
                    runCatching{withContext(Dispatchers.IO){engine.archiveBefore()}}
                        .onSuccess{summary->result="归档完成：${summary.archived}条，释放本机照片空间 ${"%.1f".format(summary.releasedBytes/1024.0/1024.0)} MB";refresh()}
                        .onFailure{result="归档失败：${it.message}；未成功的本机资料不会删除";showSyncFailure(context,result)}
                    busy=false
                }
            },modifier=Modifier.fillMaxWidth()){Text("归档六个月前记录并释放本机空间")}
            if(result.isNotBlank())Text(result,color=if(result.contains("失败"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            TextButton(onClick=onBack){Text("返回设置")}
        }
    }
}
