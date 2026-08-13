package cn.safetyledger.app

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.safetyledger.app.data.*
import kotlinx.coroutines.launch
import java.time.*
import java.util.UUID

class MainActivity : ComponentActivity() {
 override fun onCreate(state: Bundle?) { super.onCreate(state); val dao=AppDatabase.get(this).dao(); setContent { MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF006B4F))) { Ledger(dao) } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Ledger(dao: LedgerDao) {
 val records by dao.inspections().collectAsState(emptyList()); var screen by remember { mutableStateOf("home") }; var editRecord by remember{mutableStateOf<InspectionEntity?>(null)};var editItems by remember{mutableStateOf<List<TemplateItemEntity>>(emptyList())};var editTemplate by remember{mutableStateOf<TemplateEntity?>(null)}
 Scaffold(topBar={TopAppBar(title={Text("\u5b89\u5168\u68c0\u67e5\u53f0\u8d26",fontWeight=FontWeight.Bold)},actions={if(screen=="home"){TextButton(onClick={screen="form"}){Text("\u68c0\u67e5\u586b\u62a5")};TextButton(onClick={screen="settings"}){Text("\u8bbe\u7f6e")}}})}) { padding ->
  Box(Modifier.padding(padding).fillMaxSize()) { when(screen) { "home"->Home(records,{screen=it}){record->editRecord=record;screen="record"}; "form"->Form(dao){record,items->editRecord=record;editItems=items;screen="editor"}; "editor"->editRecord?.let{InspectionEditor(it,editItems,dao){screen="home"}};"record"->editRecord?.let{RecordDetail(it,dao){screen="home"}}; "settings"->Settings{screen=it}; "templates"->Templates(dao,{screen="settings"}){editTemplate=it;screen="templateEditor"};"templateEditor"->editTemplate?.let{TemplateItemEditor(it,dao){screen="templates"}}; "trash"->Trash(dao){screen="settings"}; else->Home(records,{screen=it}){record->editRecord=record;screen="record"} } }
 }
}

@Composable fun Home(records: List<InspectionEntity>, go:(String)->Unit,open:(InspectionEntity)->Unit) {
 var month by remember { mutableStateOf(YearMonth.now()) }; var selected by remember { mutableStateOf(LocalDate.now()) }; var size by remember { mutableIntStateOf(10) }; var page by remember { mutableIntStateOf(0) }; var range by remember { mutableStateOf("all") }
 val shown=records.filter { when(range){"day"->it.date==selected.toString();"month"->it.date.startsWith(YearMonth.from(selected).toString());"year"->it.date.startsWith("${selected.year}-");else->true} }; val pages=((shown.size+size-1)/size).coerceAtLeast(1)
 LazyColumn(Modifier.padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
  item { Card { Column(Modifier.padding(12.dp)) { Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={month=month.minusMonths(1)}){Text("\u4e0a\u6708")};Spacer(Modifier.weight(1f));Text("${month.year}\u5e74${month.monthValue}\u6708",fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));TextButton(onClick={month=month.plusMonths(1)}){Text("\u4e0b\u6708")}}; CalendarGrid(month,selected,records.map{it.date}.toSet()){selected=it;range="day";page=0} } } }
  item { Row(verticalAlignment=Alignment.CenterVertically){Text("\u68c0\u67e5\u8bb0\u5f55",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));TextButton(onClick={}){Text("\u591a\u9009\u5bfc\u51fa")}} }
  item { Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("day" to "\u5f53\u65e5","month" to "\u672c\u6708","quarter" to "\u672c\u5b63\u5ea6","year" to "\u672c\u5e74\u5ea6","all" to "\u5168\u90e8").forEach{(v,t)->FilterChip(range==v,{range=v;page=0},{Text(t)})}} }
  items(shown.drop(page*size).take(size),key={it.id}) { r -> Card(Modifier.fillMaxWidth().clickable{open(r)}) { Column(Modifier.padding(14.dp)){Text("${r.date}  ${r.type}",fontWeight=FontWeight.Bold);Text("${r.unit} - ${r.location}");Text("\u8be6\u60c5 / \u6574\u6539 / \u590d\u67e5 / PDF") } } }
  item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){TextButton(onClick={if(page>0)page--},enabled=page>0){Text("\u4e0a\u4e00\u9875")};Text("${page+1} / $pages");TextButton(onClick={if(page+1<pages)page++},enabled=page+1<pages){Text("\u4e0b\u4e00\u9875")}} }
  item { Row(verticalAlignment=Alignment.CenterVertically){Text("\u6bcf\u9875\uff1a");listOf(10,20,50,100,200).forEach{TextButton(onClick={size=it;page=0}){Text(if(size==it)"[$it]" else "$it")}} } }
  item { Card(Modifier.fillMaxWidth().clickable{go("settings")},colors=CardDefaults.cardColors(containerColor=Color(0xFFE7F4EE))){Column(Modifier.padding(16.dp)){Text("\u4e91\u540c\u6b65",fontWeight=FontWeight.Bold);Text("Cloudflare / WebDAV / NAS / Drive / OneDrive / HTTP")}} }
 }
}

@Composable fun CalendarGrid(month:YearMonth,selected:LocalDate,marked:Set<String>,choose:(LocalDate)->Unit){val offset=month.atDay(1).dayOfWeek.value-1;Column{Row{listOf("1","2","3","4","5","6","7").forEach{Text(it,Modifier.weight(1f))}};for(w in 0..5){Row{for(x in 0..6){val n=w*7+x-offset+1;if(n in 1..month.lengthOfMonth()){val d=month.atDay(n);Text(if(marked.contains(d.toString()))"$n*" else "$n",Modifier.weight(1f).clickable{choose(d)},color=if(d==selected)Color(0xFF006B4F)else Color.Black)}else Spacer(Modifier.weight(1f))}}}}}

@Composable fun Form(dao:LedgerDao,next:(InspectionEntity,List<TemplateItemEntity>)->Unit){val scope=rememberCoroutineScope();val templates by dao.templates().collectAsState(emptyList());var chosen by remember{mutableStateOf<TemplateEntity?>(null)};var unit by remember{mutableStateOf("")};var place by remember{mutableStateOf("")};Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("\u5b8c\u6574\u68c0\u67e5\u8868",style=MaterialTheme.typography.headlineSmall);Text("\u9009\u62e9\u68c0\u67e5\u6a21\u677f");templates.filter{it.active}.forEach{FilterChip(chosen?.id==it.id,{chosen=it},{Text(it.name)})};OutlinedTextField(unit,{unit=it},label={Text("\u88ab\u68c0\u67e5\u5355\u4f4d")});OutlinedTextField(place,{place=it},label={Text("\u68c0\u67e5\u5730\u70b9")});Text("\u9010\u9879\u68c0\u67e5 -> \u73b0\u573a\u7167\u7247 -> \u4e09\u65b9\u7b7e\u540d -> \u6574\u6539 -> \u590d\u67e5 -> \u5b8c\u6210");Button(onClick={val t=chosen?:return@Button;scope.launch{val items=dao.templateItems(t.id);next(InspectionEntity(UUID.randomUUID().toString(),t.id,LocalDate.now().toString(),LocalTime.now().withNano(0).toString(),t.name,unit,place,"","","","","","","",""),items)}},enabled=chosen!=null&&place.isNotBlank()){Text("\u8fdb\u5165\u9010\u9879\u68c0\u67e5")}}}

@Composable fun Settings(go:(String)->Unit){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("\u8bbe\u7f6e",style=MaterialTheme.typography.headlineSmall);Entry("\u57fa\u7840\u8bbe\u7f6e","\u68c0\u67e5\u7c7b\u578b\u548c\u6a21\u677f"){go("templates")};Entry("\u4e91\u540c\u6b65","\u670d\u52a1\u63d0\u4f9b\u5546\u53ef\u66ff\u6362"){};Entry("\u4e91\u4e0e\u4e91\u8fc1\u79fb","\u539f\u4e91\u590d\u5236\u5230\u65b0\u4e91"){};Entry("\u8bbe\u5907\u8fc1\u79fb","Android / PC"){};Entry("\u56de\u6536\u7ad9","\u6062\u590d\u6216\u5bc6\u7801\u5f7b\u5e95\u5220\u9664"){go("trash")};Entry("APP \u6570\u636e\u5907\u4efd","AES-256-GCM .safetydata"){};TextButton(onClick={go("home")}){Text("\u8fd4\u56de\u9996\u9875")}}}
@Composable fun Entry(a:String,b:String,click:()->Unit)=Card(Modifier.fillMaxWidth().clickable(onClick=click)){Column(Modifier.padding(14.dp)){Text(a,fontWeight=FontWeight.Bold);Text(b)}}
@Composable fun Templates(dao:LedgerDao,back:()->Unit,open:(TemplateEntity)->Unit){val scope=rememberCoroutineScope();val list by dao.templates().collectAsState(emptyList());var name by remember{mutableStateOf("")};Column(Modifier.padding(16.dp)){Text("\u57fa\u7840\u8bbe\u7f6e",style=MaterialTheme.typography.headlineSmall);OutlinedTextField(name,{name=it},label={Text("\u68c0\u67e5\u6a21\u677f\u540d\u79f0")});Button(onClick={scope.launch{dao.saveTemplate(TemplateEntity(UUID.randomUUID().toString(),name,name))};name=""},enabled=name.isNotBlank()){Text("\u65b0\u589e")};list.forEach{Card(Modifier.fillMaxWidth().padding(vertical=4.dp).clickable{open(it)}){Text(it.name,Modifier.padding(14.dp))}};TextButton(onClick=back){Text("\u8fd4\u56de")}}}
@Composable fun Trash(dao: LedgerDao, back: () -> Unit) {
 val list by dao.trash().collectAsState(emptyList())
 Column(Modifier.padding(16.dp)) {
  Text("\u56de\u6536\u7ad9", style=MaterialTheme.typography.headlineSmall)
  Text("\u5f7b\u5e95\u5220\u9664\u9700\u8f93\u5165\u5bc6\u7801\uff0c\u5e76\u540c\u6b65 tombstone")
  list.forEach { Text("${it.date} ${it.unit}") }
  TextButton(onClick=back) { Text("\u8fd4\u56de") }
 }
}
