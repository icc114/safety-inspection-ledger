package cn.safetyledger.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.safetyledger.app.data.*
import kotlinx.coroutines.launch
import java.util.UUID

@Composable fun TemplateItemEditor(template:TemplateEntity,dao:LedgerDao,onBack:()->Unit){
 val scope=rememberCoroutineScope();var rows by remember{mutableStateOf<List<TemplateItemEntity>>(emptyList())};var category by remember{mutableStateOf("")};var content by remember{mutableStateOf("")};var standard by remember{mutableStateOf("")};LaunchedEffect(template.id){rows=dao.templateItems(template.id)}
 Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Text(template.name,style=MaterialTheme.typography.headlineSmall);Text("\u68c0\u67e5\u9879\u76ee\u6a21\u677f")
  rows.forEachIndexed{i,row->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(9.dp)){Text("${i+1}. ${row.category}");Text(row.content);Text(row.standard,style=MaterialTheme.typography.bodySmall);Row{TextButton(onClick={if(i>0){{scope.launch{dao.moveTemplateItem(row.id,i-1);dao.moveTemplateItem(rows[i-1].id,i);rows=dao.templateItems(template.id)}}}else null},enabled=i>0){Text("\u4e0a\u79fb")};TextButton(onClick={if(i+1<rows.size){{scope.launch{dao.moveTemplateItem(row.id,i+1);dao.moveTemplateItem(rows[i+1].id,i);rows=dao.templateItems(template.id)}}}else null},enabled=i+1<rows.size){Text("\u4e0b\u79fb")};TextButton(onClick={scope.launch{dao.deleteTemplateItem(row.id);rows=dao.templateItems(template.id)}}){Text("\u5220\u9664")}}}}}
  OutlinedTextField(category,{category=it},label={Text("\u68c0\u67e5\u7c7b\u522b")});OutlinedTextField(content,{content=it},label={Text("\u68c0\u67e5\u5185\u5bb9")});OutlinedTextField(standard,{standard=it},label={Text("\u68c0\u67e5\u6807\u51c6")});Button(onClick={scope.launch{dao.saveTemplateItem(TemplateItemEntity(UUID.randomUUID().toString(),template.id,category,content,standard,rows.size));rows=dao.templateItems(template.id);category="";content="";standard=""}},enabled=category.isNotBlank()&&content.isNotBlank()){Text("\u65b0\u589e\u68c0\u67e5\u9879\u76ee")};TextButton(onClick=onBack){Text("\u8fd4\u56de")}
 }
}
