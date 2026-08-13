package cn.safetyledger.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import cn.safetyledger.app.data.*
import cn.safetyledger.app.pdf.PdfExporter
import cn.safetyledger.app.pdf.PrintableInspection
import cn.safetyledger.app.sync.CloudSettingsScreen
import cn.safetyledger.app.sync.CloudSyncScheduler
import cn.safetyledger.app.sync.HolidayMeta
import cn.safetyledger.app.sync.HolidayRepository
import cn.safetyledger.app.sync.HolidayScreen
import cn.safetyledger.app.backup.BackupScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.*
import java.util.UUID

class MainActivity : ComponentActivity() {
 override fun onCreate(state: Bundle?) { super.onCreate(state); val dao=AppDatabase.get(this).dao(); lifecycleScope.launch(Dispatchers.IO){InitialData.seedIfNeeded(dao);if(dao.setting("cloud_config")!=null)CloudSyncScheduler.schedule(this@MainActivity)}; setContent { MaterialTheme(colorScheme=lightColorScheme(primary=LedgerBlue,background=Color(0xFFF3F4F2),surface=LedgerPaper,onSurface=LedgerInk)) { Ledger(dao) } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Ledger(dao: LedgerDao) {
 val records by dao.inspections().collectAsState(emptyList()); var screen by remember { mutableStateOf("home") }; var editRecord by remember{mutableStateOf<InspectionEntity?>(null)};var editItems by remember{mutableStateOf<List<TemplateItemEntity>>(emptyList())};var editTemplate by remember{mutableStateOf<TemplateEntity?>(null)}
 Scaffold(topBar={TopAppBar(title={Text(if(screen=="home") "安全检查台账" else when(screen){"form"->"新建检查";"editor"->"逐项检查";"record"->"检查记录";"settings"->"设置";else->"安全检查台账"},fontWeight=FontWeight.Bold,color=LedgerInk)},navigationIcon={if(screen!="home")TextButton(onClick={screen=when(screen){"editor"->"form";"cloud","holidays","backup","templates","trash"->"settings";"templateEditor"->"templates";else->"home"}}){Text("返回")}},actions={if(screen=="home")TextButton(onClick={screen="settings"}){Text("设置")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=LedgerPaper))}) { padding ->
  Box(Modifier.padding(padding).fillMaxSize()) { when(screen) { "home"->FormalHome(dao,records,{screen=it}){record->editRecord=record;screen="record"}; "form"->FormalForm(dao){record,items->editRecord=record;editItems=items;screen="editor"}; "editor"->editRecord?.let{InspectionEditor(it,editItems,dao){screen="home"}};"record"->editRecord?.let{RecordDetail(it,dao){screen="home"}}; "settings"->Settings{screen=it};"cloud"->CloudSettingsScreen(dao){screen="settings"};"holidays"->HolidayScreen(dao){screen="settings"};"backup"->BackupScreen{screen="settings"}; "templates"->Templates(dao,{screen="settings"}){editTemplate=it;screen="templateEditor"};"templateEditor"->editTemplate?.let{TemplateItemEditor(it,dao){screen="templates"}}; "trash"->Trash(dao){screen="settings"}; else->FormalHome(dao,records,{screen=it}){record->editRecord=record;screen="record"} } }
 }
}

@Composable fun Home(dao:LedgerDao,records: List<InspectionEntity>, go:(String)->Unit,open:(InspectionEntity)->Unit) {
 val context=LocalContext.current;val scope=rememberCoroutineScope();val holidayRepository=remember{HolidayRepository(context,dao)};var holiday by remember{mutableStateOf<HolidayMeta?>(null)};var month by remember { mutableStateOf(YearMonth.now()) }; var selected by remember { mutableStateOf(LocalDate.now()) }; var size by remember { mutableIntStateOf(10) }; var page by remember { mutableIntStateOf(0) }; var range by remember { mutableStateOf("all") };var typeFilter by remember{mutableStateOf("全部")};var selectionMode by remember{mutableStateOf(false)};var selectedIds by remember{mutableStateOf(setOf<String>())};var exportMessage by remember{mutableStateOf<String?>(null)}
 LaunchedEffect(selected){holiday=holidayRepository.meta(selected)}
 val types=remember(records){listOf("全部")+records.map{it.type}.filter{it.isNotBlank()}.distinct().sorted()};val shown=records.filter { record->val date=runCatching{LocalDate.parse(record.date)}.getOrNull();val inRange=when(range){"day"->record.date==selected.toString();"month"->record.date.startsWith(YearMonth.from(selected).toString());"quarter"->date!=null&&date.year==selected.year&&((date.monthValue-1)/3)==((selected.monthValue-1)/3);"year"->record.date.startsWith("${selected.year}-");else->true};inRange&&(typeFilter=="全部"||record.type==typeFilter) }; val pages=((shown.size+size-1)/size).coerceAtLeast(1);val pageRows=shown.drop(page*size).take(size)
 LazyColumn(Modifier.padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
  item { Card { Column(Modifier.padding(12.dp)) { Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={month=month.minusMonths(1)}){Text("\u4e0a\u6708")};Spacer(Modifier.weight(1f));Text("${month.year}\u5e74${month.monthValue}\u6708",fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));TextButton(onClick={month=month.plusMonths(1)}){Text("\u4e0b\u6708")}}; CalendarGrid(month,selected,records.map{it.date}.toSet()){selected=it;range="day";page=0};holiday?.let{Text("${selected} · ${it.name}${if(it.isWork)"（调休上班）" else "（放假）"}",color=if(it.isWork)Color(0xFF9A5B00) else Color(0xFFB3261E))} } } }
  item { Row(verticalAlignment=Alignment.CenterVertically){Text("\u68c0\u67e5\u8bb0\u5f55",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));TextButton(onClick={selectionMode=!selectionMode;if(!selectionMode)selectedIds=emptySet()}){Text(if(selectionMode)"退出多选" else "\u591a\u9009\u5bfc\u51fa")}} }
  item { Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("day" to "\u5f53\u65e5","month" to "\u672c\u6708","quarter" to "\u672c\u5b63\u5ea6","year" to "\u672c\u5e74\u5ea6","all" to "\u5168\u90e8").forEach{(v,t)->FilterChip(range==v,{range=v;page=0},{Text(t)})}} }
  item{Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){types.forEach{type->FilterChip(typeFilter==type,{typeFilter=type;page=0},{Text(type)})}}}
  if(selectionMode)item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={val ids=shown.filterNot{it.archiveOnly}.map{it.id}.toSet();selectedIds=if(selectedIds.containsAll(ids))emptySet() else ids},modifier=Modifier.weight(1f)){Text(if(selectedIds.containsAll(shown.filterNot{it.archiveOnly}.map{it.id})&&shown.any{!it.archiveOnly})"取消全选" else "全选本机完整记录")};Button(onClick={val chosen=shown.filter{selectedIds.contains(it.id)&&!it.archiveOnly};scope.launch{runCatching{withContext(Dispatchers.IO){val printable=chosen.map{PrintableInspection(it,dao.inspectionItems(it.id),dao.media(it.id))};val dir=File(context.cacheDir,"exports").apply{mkdirs()};val file=File(dir,"安全检查台账-${System.currentTimeMillis()}.pdf");file.outputStream().use{out->PdfExporter().export(printable,out)};file}}.onSuccess{file->val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"导出检查记录"));exportMessage="已合并导出${chosen.size}条记录"}.onFailure{exportMessage="导出失败：${it.message}"}}},enabled=selectedIds.isNotEmpty(),modifier=Modifier.weight(1f)){Text("合并导出 PDF")}}}
  items(pageRows,key={it.id}) { r -> Card(Modifier.fillMaxWidth().clickable{if(selectionMode){if(r.archiveOnly)exportMessage="云端归档记录请进入详情下载PDF" else selectedIds=if(selectedIds.contains(r.id))selectedIds-r.id else selectedIds+r.id}else open(r)}) { Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){if(selectionMode&&!r.archiveOnly)Checkbox(checked=selectedIds.contains(r.id),onCheckedChange={checked->selectedIds=if(checked)selectedIds+r.id else selectedIds-r.id});Column{Text("${r.date}  ${r.type}",fontWeight=FontWeight.Bold);Text("${r.unit} - ${r.location}");Text(if(r.archiveOnly)"云端PDF归档 · 点击下载" else "\u8be6\u60c5 / \u6574\u6539 / \u590d\u67e5 / PDF") }} } }
  item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){TextButton(onClick={if(page>0)page--},enabled=page>0){Text("\u4e0a\u4e00\u9875")};Text("${page+1} / $pages");TextButton(onClick={if(page+1<pages)page++},enabled=page+1<pages){Text("\u4e0b\u4e00\u9875")}} }
  item { Row(verticalAlignment=Alignment.CenterVertically){Text("\u6bcf\u9875\uff1a");listOf(10,20,50,100,200).forEach{TextButton(onClick={size=it;page=0}){Text(if(size==it)"[$it]" else "$it")}} } }
  exportMessage?.let{item{Text(it,color=MaterialTheme.colorScheme.primary)}}
  item { Card(Modifier.fillMaxWidth().clickable{go("settings")},colors=CardDefaults.cardColors(containerColor=Color(0xFFE7F4EE))){Column(Modifier.padding(16.dp)){Text("\u4e91\u540c\u6b65",fontWeight=FontWeight.Bold);Text("Cloudflare / WebDAV / NAS / Drive / OneDrive / HTTP")}} }
 }
}

@Composable fun CalendarGrid(month:YearMonth,selected:LocalDate,marked:Set<String>,choose:(LocalDate)->Unit){val offset=month.atDay(1).dayOfWeek.value-1;Column{Row{listOf("1","2","3","4","5","6","7").forEach{Text(it,Modifier.weight(1f))}};for(w in 0..5){Row{for(x in 0..6){val n=w*7+x-offset+1;if(n in 1..month.lengthOfMonth()){val d=month.atDay(n);Text(if(marked.contains(d.toString()))"$n*" else "$n",Modifier.weight(1f).clickable{choose(d)},color=if(d==selected)Color(0xFF006B4F)else Color.Black)}else Spacer(Modifier.weight(1f))}}}}}

@Composable fun Form(dao:LedgerDao,next:(InspectionEntity,List<TemplateItemEntity>)->Unit){val scope=rememberCoroutineScope();val templates by dao.templates().collectAsState(emptyList());var chosen by remember{mutableStateOf<TemplateEntity?>(null)};var unit by remember{mutableStateOf("")};var place by remember{mutableStateOf("")};var duty by remember{mutableStateOf("")};var inspector1 by remember{mutableStateOf("")};var inspector2 by remember{mutableStateOf("")};var inspectee by remember{mutableStateOf("")};LazyColumn(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){item{Text("\u5b8c\u6574\u68c0\u67e5\u8868",style=MaterialTheme.typography.headlineSmall);Text("${LocalDate.now()} ${LocalTime.now().withNano(0)}");Text("\u9009\u62e9\u68c0\u67e5\u6a21\u677f")};items(templates.filter{it.active},key={it.id}){template->FilterChip(chosen?.id==template.id,{chosen=template},{Text(template.name)})};item{OutlinedTextField(unit,{unit=it},label={Text("\u88ab\u68c0\u67e5\u5355\u4f4d")},modifier=Modifier.fillMaxWidth());OutlinedTextField(place,{place=it},label={Text("\u68c0\u67e5\u5730\u70b9")},modifier=Modifier.fillMaxWidth());OutlinedTextField(duty,{duty=it},label={Text("在岗人员")},modifier=Modifier.fillMaxWidth());OutlinedTextField(inspector1,{inspector1=it},label={Text("检查人1")},modifier=Modifier.fillMaxWidth());OutlinedTextField(inspector2,{inspector2=it},label={Text("检查人2")},modifier=Modifier.fillMaxWidth());OutlinedTextField(inspectee,{inspectee=it},label={Text("被检查人")},modifier=Modifier.fillMaxWidth());Text("\u9010\u9879\u68c0\u67e5 → \u73b0\u573a\u7167\u7247 → \u4e09\u65b9\u7b7e\u540d → \u6574\u6539 → \u590d\u67e5 → \u5b8c\u6210");Button(onClick={val t=chosen?:return@Button;scope.launch{val templateRows=dao.templateItems(t.id);next(InspectionEntity(UUID.randomUUID().toString(),t.id,LocalDate.now().toString(),LocalTime.now().withNano(0).toString(),t.name,unit,place,duty,inspector1,inspector2,inspectee,"","","",""),templateRows)}},enabled=chosen!=null&&place.isNotBlank()&&inspector1.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("\u8fdb\u5165\u9010\u9879\u68c0\u67e5")};Spacer(Modifier.height(32.dp))}}}

@Composable fun Settings(go:(String)->Unit){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("\u8bbe\u7f6e",style=MaterialTheme.typography.headlineSmall);Entry("\u57fa\u7840\u8bbe\u7f6e","\u68c0\u67e5\u7c7b\u578b\u548c\u6a21\u677f"){go("templates")};Entry("\u4e91\u540c\u6b65","选择服务提供商、测试连接和失败通知"){go("cloud")};Entry("法定节假日","联网更新，断网使用本机缓存"){go("holidays")};Entry("\u4e91\u4e0e\u4e91\u8fc1\u79fb","\u539f\u4e91\u590d\u5236\u5230\u65b0\u4e91"){};Entry("\u8bbe\u5907\u8fc1\u79fb","Android / PC"){};Entry("\u56de\u6536\u7ad9","\u6062\u590d\u6216\u5bc6\u7801\u5f7b\u5e95\u5220\u9664"){go("trash")};Entry("APP \u6570\u636e\u5907\u4efd","AES-256-GCM .csinspect"){go("backup")};TextButton(onClick={go("home")}){Text("\u8fd4\u56de\u9996\u9875")}}}
@Composable fun Entry(a:String,b:String,click:()->Unit)=Card(Modifier.fillMaxWidth().clickable(onClick=click)){Column(Modifier.padding(14.dp)){Text(a,fontWeight=FontWeight.Bold);Text(b)}}
@Composable fun Templates(dao:LedgerDao,back:()->Unit,open:(TemplateEntity)->Unit){val scope=rememberCoroutineScope();val list by dao.templates().collectAsState(emptyList());var name by remember{mutableStateOf("")};var pendingDelete by remember{mutableStateOf<TemplateEntity?>(null)};Column(Modifier.padding(16.dp)){Text("\u57fa\u7840\u8bbe\u7f6e",style=MaterialTheme.typography.headlineSmall);Text("模板修改仅影响以后新建的检查，历史记录保持不变。",style=MaterialTheme.typography.bodySmall);OutlinedTextField(name,{name=it},label={Text("\u68c0\u67e5\u6a21\u677f\u540d\u79f0")});Button(onClick={scope.launch{dao.saveTemplate(TemplateEntity(UUID.randomUUID().toString(),name,name))};name=""},enabled=name.isNotBlank()){Text("\u65b0\u589e")};list.forEach{template->Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){Text(template.name,Modifier.weight(1f).clickable{open(template)});TextButton(onClick={open(template)}){Text("项目")};TextButton(onClick={pendingDelete=template}){Text("删除",color=MaterialTheme.colorScheme.error)}}}};TextButton(onClick=back){Text("\u8fd4\u56de")}}
 pendingDelete?.let{target->AlertDialog(onDismissRequest={pendingDelete=null},title={Text("删除检查模板")},text={Text("确定删除“${target.name}”吗？历史检查记录不会受到影响。")},confirmButton={TextButton(onClick={scope.launch{dao.deleteTemplate(target.id)};pendingDelete=null}){Text("删除")}},dismissButton={TextButton(onClick={pendingDelete=null}){Text("取消")}})}
}
@Composable fun Trash(dao: LedgerDao, back: () -> Unit) {
 val context=LocalContext.current;val scope=rememberCoroutineScope();val list by dao.trash().collectAsState(emptyList());var configuredHash by remember{mutableStateOf<String?>(null)};var password by remember{mutableStateOf("")};var target by remember{mutableStateOf<InspectionEntity?>(null)};var message by remember{mutableStateOf("")};LaunchedEffect(Unit){configuredHash=dao.setting("delete_password_hash")?.value}
 Column(Modifier.padding(16.dp)) {
  Text("\u56de\u6536\u7ad9", style=MaterialTheme.typography.headlineSmall)
  Text("普通删除可恢复；彻底删除会生成同步墓碑，并删除本机原始照片。")
  if(configuredHash==null){OutlinedTextField(password,{password=it},label={Text("设置彻底删除密码（至少8位）")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Button(enabled=password.length>=8,onClick={scope.launch{val hash=hashText(password);dao.saveSetting(SettingEntity("delete_password_hash",hash));configuredHash=hash;password="";message="彻底删除密码已设置"}}){Text("保存密码")}}
  list.forEach { record->Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("${record.date} ${record.type}",fontWeight=FontWeight.Bold);Text(record.unit)};TextButton(onClick={scope.launch{dao.restore(record.id,System.currentTimeMillis());CloudSyncScheduler.enqueue(context);message="已恢复"}}){Text("恢复")};TextButton(enabled=configuredHash!=null,onClick={target=record;password=""}){Text("彻底删除",color=MaterialTheme.colorScheme.error)}}} }
  if(list.isEmpty())Text("回收站为空")
  if(message.isNotBlank())Text(message)
  TextButton(onClick=back) { Text("\u8fd4\u56de") }
 }
 target?.let{record->AlertDialog(onDismissRequest={target=null},title={Text("彻底删除所有设备数据")},text={Column{Text("此操作不可恢复。请输入彻底删除密码确认删除：");OutlinedTextField(password,{password=it},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())}},confirmButton={TextButton(onClick={if(hashText(password)==configuredHash){scope.launch{dao.tombstone(TombstoneEntity(UUID.randomUUID().toString(),"inspection",record.id,System.currentTimeMillis(),"android"));dao.purge(record.id);File(context.filesDir,"media/${record.id}").deleteRecursively();CloudSyncScheduler.enqueue(context);message="已彻底删除"};target=null;password=""}else message="密码错误"}){Text("确认彻底删除")}},dismissButton={TextButton(onClick={target=null;password=""}){Text("取消")}})}
}
private fun hashText(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
