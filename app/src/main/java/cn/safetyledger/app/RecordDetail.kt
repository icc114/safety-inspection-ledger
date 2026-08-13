package cn.safetyledger.app

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.safetyledger.app.data.*
import cn.safetyledger.app.media.MediaActions
import cn.safetyledger.app.pdf.PdfExporter
import cn.safetyledger.app.pdf.PrintableInspection
import cn.safetyledger.app.sync.CloudSyncScheduler
import cn.safetyledger.app.sync.CloudSyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun RecordDetail(record:InspectionEntity,dao:LedgerDao,back:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    if(record.archiveOnly){
        var message by remember{mutableStateOf("")};val engine=remember{CloudSyncEngine(context,dao)}
        Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(record.type,style=MaterialTheme.typography.headlineSmall);Text("${record.date} · 云端PDF归档")
            Text("详细检查数据、照片和签名已合并保存在云端PDF中，本机原始照片空间已释放。")
            Button(onClick={scope.launch{runCatching{withContext(Dispatchers.IO){
                val file=File(context.cacheDir,"exports/${record.date}-${record.type}-归档.pdf").apply{parentFile?.mkdirs();writeBytes(engine.downloadArchive(record))};file
            }}.onSuccess{file->val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"查看云端归档PDF"))}.onFailure{message="下载失败：${it.message}"}}},modifier=Modifier.fillMaxWidth()){Text("下载并分享归档 PDF")}
            if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error)
            TextButton(onClick=back){Text("返回")}
        }
        return
    }
    var rows by remember{mutableStateOf<List<InspectionItemEntity>>(emptyList())}
    var media by remember{mutableStateOf<List<MediaEntity>>(emptyList())}
    var detail by remember{mutableStateOf(record.rectificationDetail)};var review by remember{mutableStateOf(record.reviewResult)}
    var status by remember{mutableStateOf(record.status)};var message by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(record.id){rows=dao.inspectionItems(record.id);media=dao.media(record.id)}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text(record.type,style=MaterialTheme.typography.headlineSmall);Text("${record.date} ${record.time}");Text(record.location)}
        items(rows,key={it.id}){row->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text(row.category);Text(row.content);Text(resultText(row.result));if(row.problem.isNotBlank())Text(row.problem)}}}
        item{
            Text("已保存照片及签名：${media.size}个")
            Text("补录整改后照片",style=MaterialTheme.typography.titleMedium)
            MediaActions(record.id,MediaKind.RECTIFICATION,record.location,dao){message=it;scope.launch{media=dao.media(record.id)}}
            OutlinedTextField(detail,{detail=it},label={Text("整改情况")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(review,{review=it},label={Text("复查结果")},modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){RecordStatus.entries.forEach{value->FilterChip(status==value,{status=value},{Text(statusLabel(value))})}}
            Button(onClick={scope.launch{
                dao.saveInspection(record.copy(rectificationDetail=detail,reviewResult=review,status=status,updatedAt=System.currentTimeMillis()))
                CloudSyncScheduler.enqueue(context);message="已保存"
            }},modifier=Modifier.fillMaxWidth()){Text("保存整改/复查")}
            Button(onClick={scope.launch{
                runCatching{withContext(Dispatchers.IO){
                    val dir=File(context.cacheDir,"exports").apply{mkdirs()};val file=File(dir,"${record.date}-${record.type}.pdf")
                    file.outputStream().use{PdfExporter().export(listOf(PrintableInspection(record.copy(rectificationDetail=detail,reviewResult=review,status=status),rows,dao.media(record.id))),it)}
                    file
                }}.onSuccess{file->
                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"导出检查记录"))
                }.onFailure{message=it.message}
            }},modifier=Modifier.fillMaxWidth()){Text("生成并分享正式 PDF")}
            OutlinedButton(onClick={scope.launch{dao.trash(record.id,System.currentTimeMillis());CloudSyncScheduler.enqueue(context);back()}},modifier=Modifier.fillMaxWidth()){
                Text("移入回收站",color=MaterialTheme.colorScheme.error)
            }
            message?.let{Text(it)}
            TextButton(onClick=back){Text("返回")}
        }
    }
}

private fun resultText(value:ItemResult)=when(value){ItemResult.PASS->"合格";ItemResult.FAIL->"不合格";ItemResult.NA->"不适用"}
private fun statusLabel(value:RecordStatus)=when(value){RecordStatus.PENDING->"待整改";RecordStatus.RECTIFYING->"整改中";RecordStatus.RECTIFIED->"已整改";RecordStatus.COMPLETE->"已完成"}
