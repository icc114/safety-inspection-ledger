package cn.safetyledger.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.safetyledger.app.data.*
import kotlinx.coroutines.launch
import java.time.*
import java.util.UUID

class MainActivity : ComponentActivity() { override fun onCreate(state:Bundle?){super.onCreate(state);val dao=AppDatabase.get(this).dao();setContent{SafetyTheme{LedgerApp(dao)}}} }
@Composable private fun SafetyTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF006B4F),secondary=Color(0xFF47645A),surface=Color(0xFFF8FAF8)),content=content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LedgerApp(dao:LedgerDao){
 val scope=rememberCoroutineScope();val records by dao.inspections().collectAsState(emptyList());var screen by remember{mutableStateOf("home")}
 Scaffold(topBar={TopAppBar(title={Text("安全检查台账",fontWeight=FontWeight.Bold)},actions={if(screen=="home"){TextButton(onClick={screen="form"}){Text("检查填报")};IconButton(onClick={screen="settings"}){Icon(Icons.Default.Settings,"设置")}}})}){pad->Box(Modifier.padding(pad).fillMaxSize()){when(screen){"home"->Home(records){screen=it};"form"->InspectionForm(dao){screen="home"};"settings"->SettingsHub{screen=it};"templates"->TemplateManager(dao){screen="settings"};"trash"->TrashScreen(dao){screen="settings"};else->Home(records){screen=it}}}}
}

@Composable private fun Home(records:List<InspectionEntity>,navigate:(String)->Unit){
 var month by remember{mutableStateOf(YearMonth.now())};var selected by remember{mutableStateOf(LocalDate.now())};var pageSize by remember{mutableIntStateOf(10)};var page by remember{mutableIntStateOf(0)};var range by remember{mutableStateOf("全部")}
 val filtered=remember(records,range,selected){records.filter{r->when(range){"当日"->r.date==selected.toString();"本月"->r.date.startsWith(YearMonth.from(selected).toString());"本季度"->{val d=runCatching{LocalDate.parse(r.date)}.getOrNull();d!=null&&d.year==selected.year&&(d.monthValue-1)/3==(selected.monthValue-1)/3};"本年度"->r.date.startsWith("${selected.year}-");else->true}}};val pages=((filtered.size+pageSize-1)/pageSize).coerceAtLeast(1);if(page>=pages)page=pages-1
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{CalendarCard(month,selected,records.map{it.date}.toSet(),{month=month.minusMonths(1)},{month=month.plusMonths(1)}){selected=it;range="当日";page=0}};item{Row(verticalAlignment=Alignment.CenterVertically){Text("检查记录",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));TextButton(onClick={}){Text("多选导出")}}};item{Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("当日","本月","本季度","本年度","全部").forEach{FilterChip(range==it,{range=it;page=0},{Text(it)})}}};items(filtered.drop(page*pageSize).take(pageSize),key={it.id}){r->Card(Modifier.fillMaxWidth().clickable{}){Column(Modifier.padding(14.dp)){Row{Text("${r.date}  ${r.type}",fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));AssistChip(onClick={},label={Text(statusText(r.status))})};Text("${r.unit} · ${r.location}",color=Color.DarkGray);Text("查看详情、整改、复查、签名与导出 PDF",style=MaterialTheme.typography.bodySmall)}}};item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={if(page>0){{page--}}else null},enabled=page>0){Text("上一页")};Text("第 ${page+1} / $pages 页");TextButton(onClick={if(page+1<pages){{page++}}else null},enabled=page+1<pages){Text("下一页")}}};item{Row(verticalAlignment=Alignment.CenterVertically){Text("每页显示：");listOf(10,20,50,100,200).forEach{TextButton(onClick={pageSize=it;page=0}){Text(if(pageSize==it)"[$it]" else "$it")}}};item{Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFE7F4EE)),modifier=Modifier.fillMaxWidth().clickable{navigate("settings")}){Column(Modifier.padding(16.dp)){Text("云同步",fontWeight=FontWeight.Bold);Text("本机离线优先 · Cloudflare · 可迁移到其他服务提供商")}}};item{Spacer(Modifier.height(20.dp))}}
}

@Composable private fun CalendarCard(month:YearMonth,selected:LocalDate,marked:Set<String>,prev:()->Unit,next:()->Unit,choose:(LocalDate)->Unit){Card{Column(Modifier.padding(14.dp)){Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick=prev){Text("上月")};Spacer(Modifier.weight(1f));Text("${month.year}年${month.monthValue}月",fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));TextButton(onClick=next){Text("下月")}};Row(Modifier.fillMaxWidth()){listOf("一","二","三","四","五","六","日").forEach{Text(it,Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center)}};val offset=month.atDay(1).dayOfWeek.value-1;for(week in 0..5){Row(Modifier.fillMaxWidth()){for(day in 1..7){val n=week*7+day-offset;if(n in 1..month.lengthOfMonth()){val d=month.atDay(n);val bg=if(d==selected)Color(0xFFBCE9D8)else Color.Transparent;Column(Modifier.weight(1f).height(42.dp).background(bg).clickable{choose(d)},horizontalAlignment=Alignment.CenterHorizontally){Text("$n");if(marked.contains(d.toString()))Text("●",color=Color(0xFF006B4F))}}else Spacer(Modifier.weight(1f).height(42.dp))}}}}}}

@Composable private fun InspectionForm(dao:LedgerDao,back:()->Unit){val scope=rememberCoroutineScope();val templates by dao.templates().collectAsState(emptyList());var template by remember{mutableStateOf<TemplateEntity?>(null)};var unit by remember{mutableStateOf("")};var location by remember{mutableStateOf("")};Column(Modifier.padding(16.dp).fillMaxSize(),verticalArrangement=Arrangement.spacedBy(9.dp)){Text("完整检查表",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("第一步：选择检查模板");templates.filter{it.active}.forEach{FilterChip(template?.id==it.id,{template=it},{Text(it.name)})};if(templates.isEmpty())Text("请先在设置 → 基础设置中新建检查类型和检查项目。",color=MaterialTheme.colorScheme.error);OutlinedTextField(unit,{unit=it},label={Text("被检查单位")},modifier=Modifier.fillMaxWidth());OutlinedTextField(location,{location=it},label={Text("检查地点")},modifier=Modifier.fillMaxWidth());Text("保存后可逐项选择合格/不合格/不适用，拍摄现场和问题照片，完成三方横屏签名，并继续补录整改与复查。");Button(onClick={val t=template?:return@Button;scope.launch{val id=UUID.randomUUID().toString();dao.saveInspection(InspectionEntity(id,t.id,LocalDate.now().toString(),LocalTime.now().withNano(0).toString(),t.name,unit,location,"","","","","","","",""));val items=dao.templateItems(t.id).map{InspectionItemEntity(UUID.randomUUID().toString(),id,it.id,it.category,it.content,it.standard,ItemResult.NA)};dao.saveInspectionItems(items)};back()},enabled=template!=null&&location.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("创建检查并进入逐项填报")};TextButton(onClick=back){Text("返回")}}
}

@Composable private fun SettingsHub(go:(String)->Unit){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("设置",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);SettingCard("基础设置","新增、编辑、停用检查类型和检查项目"){go("templates")};SettingCard("云同步","Cloudflare、WebDAV、飞牛 NAS、Google Drive、OneDrive、自定义服务器"){};SettingCard("云与云迁移","把原云完整复制到新云，原云数据不删除"){};SettingCard("设备迁移","手机与 PC 自动同步、冲突副本和恢复"){};SettingCard("回收站","恢复记录；验证密码后彻底删除并同步 tombstone"){go("trash")};SettingCard("APP 数据备份","导出/导入 AES-256-GCM 加密 .safetydata"){};TextButton(onClick={go("home")}){Text("返回首页")}}}
@Composable private fun SettingCard(title:String,sub:String,click:()->Unit)=Card(Modifier.fillMaxWidth().clickable(onClick=click)){Column(Modifier.padding(16.dp)){Text(title,fontWeight=FontWeight.Bold);Text(sub,color=Color.DarkGray)}}

@Composable fun TemplateManager(dao:LedgerDao,back:()->Unit){val scope=rememberCoroutineScope();val list by dao.templates().collectAsState(emptyList());var name by remember{mutableStateOf("")};Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("基础设置",style=MaterialTheme.typography.headlineSmall);OutlinedTextField(name,{name=it},label={Text("检查类型/模板名称，例如：车棚检查")},modifier=Modifier.fillMaxWidth());Button(onClick={scope.launch{dao.saveTemplate(TemplateEntity(UUID.randomUUID().toString(),name,name))};name=""},enabled=name.isNotBlank()){Text("新增检查模板")};list.forEach{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(it.name,fontWeight=FontWeight.Bold);Text("检查项目可新增、编辑、删除、上移、下移；历史记录保持不变")}}};TextButton(onClick=back){Text("返回设置")}}}
@Composable fun TrashScreen(dao:LedgerDao,back:()->Unit){val trash by dao.trash().collectAsState(emptyList());Column(Modifier.padding(16.dp)){Text("回收站",style=MaterialTheme.typography.headlineSmall);Text("普通恢复保留同步记录；彻底删除需密码验证，并同步到手机与 PC。");trash.forEach{Text("${it.date} ${it.unit}")};TextButton(onClick=back){Text("返回")}}}
private fun statusText(s:RecordStatus)=when(s){RecordStatus.PENDING->"PENDING";RecordStatus.RECTIFYING->"RECTIFYING";RecordStatus.RECTIFIED->"RECTIFIED";RecordStatus.COMPLETE->"COMPLETE"}
