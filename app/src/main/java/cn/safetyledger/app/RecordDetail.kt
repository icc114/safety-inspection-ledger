package cn.safetyledger.app

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.safetyledger.app.data.*
import cn.safetyledger.app.pdf.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun RecordDetail(record:InspectionEntity,dao:LedgerDao,back:()->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope();var items by remember{mutableStateOf<List<InspectionItemEntity>>(emptyList())};var detail by remember{mutableStateOf(record.rectificationDetail)};var review by remember{mutableStateOf(record.reviewResult)};var status by remember{mutableStateOf(record.status)};var message by remember{mutableStateOf<String?>(null)};LaunchedEffect(record.id){items=dao.inspectionItems(record.id)}
 Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(record.type,style=MaterialTheme.typography.headlineSmall);Text("${record.date} ${record.time}");Text(record.location);items.forEach{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text(it.category);Text(it.content);Text(it.result.name);if(it.problem.isNotBlank())Text(it.problem)}}};OutlinedTextField(detail,{detail=it},label={Text("\u6574\u6539\u60c5\u51b5")},modifier=Modifier.fillMaxWidth());OutlinedTextField(review,{review=it},label={Text("\u590d\u67e5\u7ed3\u679c")},modifier=Modifier.fillMaxWidth());Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){RecordStatus.entries.forEach{s->FilterChip(status==s,{status=s},{Text(statusLabel(s))})}};Button(onClick={scope.launch{dao.saveInspection(record.copy(rectificationDetail=detail,reviewResult=review,status=status,updatedAt=System.currentTimeMillis()));message="\u5df2\u4fdd\u5b58"}},modifier=Modifier.fillMaxWidth()){Text("\u4fdd\u5b58\u6574\u6539/\u590d\u67e5")};Button(onClick={scope.launch{runCatching{withContext(Dispatchers.IO){val dir=File(context.cacheDir,"exports").apply{mkdirs()};val file=File(dir,"${record.date}-${record.type}.pdf");file.outputStream().use{PdfExporter().export(listOf(PrintableInspection(record.copy(rectificationDetail=detail,reviewResult=review,status=status),items)),it)};file}}.onSuccess{file->val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"\u5bfc\u51fa\u68c0\u67e5\u8bb0\u5f55"))}.onFailure{message=it.message}}},modifier=Modifier.fillMaxWidth()){Text("\u751f\u6210\u5e76\u5206\u4eab\u6b63\u5f0f PDF")};message?.let{Text(it)};TextButton(onClick=back){Text("\u8fd4\u56de")}}
}
private fun statusLabel(s:RecordStatus)=when(s){RecordStatus.PENDING->"\u5f85\u6574\u6539";RecordStatus.RECTIFYING->"\u6574\u6539\u4e2d";RecordStatus.RECTIFIED->"\u5df2\u6574\u6539";RecordStatus.COMPLETE->"\u5df2\u5b8c\u6210"}
