package cn.safetyledger.app.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable fun BackupScreen(onBack:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var password by remember{mutableStateOf("")};var message by remember{mutableStateOf("")}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->uri?.let{scope.launch{runCatching{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(it,"w")!!.use{out->BackupService(context).export(password.toCharArray(),out)}}}.onSuccess{message="备份已保存"}.onFailure{message="备份失败：${it.message}"}}}}
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{scope.launch{runCatching{withContext(Dispatchers.IO){context.contentResolver.openInputStream(it)!!.use{input->BackupService(context).restore(password.toCharArray(),input)}}}.onSuccess{message="恢复完成，请完全关闭并重新打开应用"}.onFailure{message="恢复失败：${it.message}"}}}}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("APP数据备份与恢复",style=MaterialTheme.typography.headlineSmall);Text("备份包含记录、模板、照片和签名；PDF导出文件不作为数据备份。文件使用AES-256-GCM加密，可复制到另一部安卓手机恢复。");OutlinedTextField(password,{password=it},label={Text("备份密码（至少8位）")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Button(enabled=password.length>=8,onClick={export.launch("安全检查台账备份-${LocalDate.now()}.csinspect")},modifier=Modifier.fillMaxWidth()){Text("导出 .csinspect 备份")};OutlinedButton(enabled=password.length>=8,onClick={restore.launch(arrayOf("application/octet-stream","application/zip","*/*"))},modifier=Modifier.fillMaxWidth()){Text("选择备份文件并恢复")};if(message.isNotBlank())Text(message,color=if(message.startsWith("恢复失败")||message.startsWith("备份失败"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary);TextButton(onClick=onBack){Text("返回设置")}}
}
