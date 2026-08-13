package cn.safetyledger.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.safetyledger.app.data.LedgerDao
import cn.safetyledger.app.data.SettingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private data class ProviderOption(val id:String,val label:String,val help:String)
private val providers=listOf(
    ProviderOption("cloudflare","配套免费云服务（Cloudflare）","适合多手机和电脑通过互联网同步"),
    ProviderOption("leancloud","LeanCloud","使用兼容同步网关；先测试再保存"),
    ProviderOption("webdav","飞牛 NAS / WebDAV","建议使用 HTTPS；局域网也可填写 NAS 地址"),
    ProviderOption("gdrive","Google Drive 同步网关","不能填写普通网盘分享链接"),
    ProviderOption("http","自定义 HTTP","兼容安全检查台账同步协议 v3"),
)

@Composable
fun CloudSettingsScreen(dao:LedgerDao,onBack:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var provider by remember{mutableStateOf("cloudflare")};var endpoint by remember{mutableStateOf("")};var space by remember{mutableStateOf("安全检查台账")};var password by remember{mutableStateOf("")};var device by remember{mutableStateOf(Build.MODEL)};var result by remember{mutableStateOf("")};var testing by remember{mutableStateOf(false)}
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    LaunchedEffect(Unit){if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);dao.setting("cloud_config")?.value?.let{raw->runCatching{JSONObject(raw)}.getOrNull()?.let{json->provider=json.optString("provider","cloudflare");endpoint=json.optString("endpoint");space=json.optString("space","安全检查台账");device=json.optString("device",Build.MODEL)}};password=secretPrefs(context).getString("sync_password","").orEmpty()}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text("云同步",style=MaterialTheme.typography.headlineSmall);Text("先保存本机，联网后补同步。更换服务商不会自动删除旧云数据。")}
        items(providers,key={it.id}){option->Column{FilterChip(provider==option.id,{provider=option.id},{Text(option.label)});if(provider==option.id)Text(option.help,style=MaterialTheme.typography.bodySmall)}}
        item{OutlinedTextField(endpoint,{endpoint=it.trim()},label={Text("同步服务地址")},placeholder={Text("https://…")},modifier=Modifier.fillMaxWidth());OutlinedTextField(space,{space=it},label={Text("同步空间名称")},modifier=Modifier.fillMaxWidth());OutlinedTextField(device,{device=it},label={Text("本机名称")},modifier=Modifier.fillMaxWidth());OutlinedTextField(password,{password=it},label={Text("同步密码（至少8位）")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Button(enabled=!testing&&endpoint.isNotBlank()&&password.length>=8,onClick={testing=true;result="正在测试连接…";scope.launch{val tested=withContext(Dispatchers.IO){testEndpoint(endpoint)};testing=false;if(tested==null){val json=JSONObject().put("provider",provider).put("endpoint",endpoint.trimEnd('/')).put("space",space).put("device",device);dao.saveSetting(SettingEntity("cloud_config",json.toString()));secretPrefs(context).edit().putString("sync_password",password).apply();result="连接成功，配置已保存"}else{result="连接失败：$tested";notifyFailure(context,result)}}},modifier=Modifier.fillMaxWidth()){Text("测试连接并保存")};if(result.isNotBlank())Text(result,color=if(result.startsWith("连接失败"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary);TextButton(onClick=onBack){Text("返回设置")}}
    }
}

private fun secretPrefs(context:Context)=EncryptedSharedPreferences.create(context,"cloud_secret",MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
private fun testEndpoint(raw:String):String?=runCatching{val endpoint=raw.trim().trimEnd('/');require(endpoint.startsWith("https://")||endpoint.startsWith("http://")){"地址必须以 http:// 或 https:// 开头"};val request=Request.Builder().url("$endpoint/health").get().build();val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(18,TimeUnit.SECONDS).build();client.newCall(request).execute().use{response->if(!response.isSuccessful)error("服务器返回 ${response.code}")}}.exceptionOrNull()?.message
private fun notifyFailure(context:Context,message:String){val channel="cloud-sync";val manager=context.getSystemService(NotificationManager::class.java);if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel(channel,"云同步提醒",NotificationManager.IMPORTANCE_HIGH));if(Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)NotificationManagerCompat.from(context).notify(3101,NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("安全检查台账同步失败").setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build())}
