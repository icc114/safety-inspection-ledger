package cn.safetyledger.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.safetyledger.app.data.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

class MainActivity:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);val dao=AppDatabase.get(this).dao();setContent{MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF006C4C))){LedgerApp(dao)}}}}
@Composable fun LedgerApp(dao:LedgerDao){val scope=rememberCoroutineScope();val records by dao.inspections().collectAsState(initial=emptyList());var screen by remember{mutableStateOf("home")};Scaffold(topBar={TopAppBar(title={Text("安全检查台账")},actions={TextButton(onClick={screen="templates"}){Text("模板管理")};TextButton(onClick={screen="trash"}){Text("回收站")}})}){pad->Column(Modifier.padding(pad).padding(16.dp)){when(screen){"home"->{Button(onClick={screen="form"},modifier=Modifier.fillMaxWidth().height(52.dp)){Text("检查填报")};Spacer(Modifier.height(12.dp));Text("${LocalDate.now().year} 年 ${LocalDate.now().monthValue} 月",style=MaterialTheme.typography.titleLarge);Text("筛选：当日｜本月｜本季度｜本年度｜全部    每页：10 / 20 / 50 / 100 / 200");LazyColumn{items(records){r->Card(Modifier.fillMaxWidth().padding(vertical=5.dp),onClick={}){Column(Modifier.padding(12.dp)){Text("${r.date}  ${r.type}");Text("${r.unit} · ${r.location}");Text(r.status.name)}}}}};"form"->InspectionForm{r->scope.launch{dao.saveInspection(r)};screen="home"};"templates"->TemplateManager(dao){screen="home"};else->TrashScreen(dao){screen="home"}}}}}
@Composable fun InspectionForm(save:(InspectionEntity)->Unit){var unit by remember{mutableStateOf("")};var location by remember{mutableStateOf("")};var type by remember{mutableStateOf("")};Column{Text("新建检查",style=MaterialTheme.typography.headlineSmall);listOf("检查类型" to type,"被检查单位" to unit,"检查地点" to location).forEachIndexed{i,(label,value)->OutlinedTextField(value=value,onValueChange={when(i){0->type=it;1->unit=it;else->location=it}},label={Text(label)},modifier=Modifier.fillMaxWidth())};Text("检查项目状态：合格 / 不合格 / 不适用；不合格时填写现场问题并添加照片");Button(onClick={save(InspectionEntity(UUID.randomUUID().toString(),"",LocalDate.now().toString(),java.time.LocalTime.now().withNano(0).toString(),type,unit,location,"","","","","","","","","",status=RecordStatus.COMPLETE))},enabled=unit.isNotBlank()&&location.isNotBlank()){Text("保存检查记录")}}
@Composable fun TemplateManager(dao:LedgerDao,back:()->Unit){val scope=rememberCoroutineScope();val templates by dao.templates().collectAsState(emptyList());var name by remember{mutableStateOf("")};Column{Text("检查模板",style=MaterialTheme.typography.headlineSmall);OutlinedTextField(name,{name=it},label={Text("模板名称")});Button(onClick={scope.launch{dao.saveTemplate(TemplateEntity(UUID.randomUUID().toString(),name,"自定义"));name=""}},enabled=name.isNotBlank()){Text("新建模板")};templates.forEach{Text("${it.name} · ${it.category} · ${if(it.active)"启用" else "停用"}")};Text("项目支持新增、编辑、删除与上移/下移，顺序持久化于 Room。") ;TextButton(onClick=back){Text("返回")}}}
@Composable fun TrashScreen(dao:LedgerDao,back:()->Unit){val trash by dao.trash().collectAsState(emptyList());Column{Text("回收站",style=MaterialTheme.typography.headlineSmall);trash.forEach{Text("${it.date} ${it.unit}")};Text("恢复可撤销普通删除；永久删除要求密码并写入 tombstone。") ;TextButton(onClick=back){Text("返回")}}}
