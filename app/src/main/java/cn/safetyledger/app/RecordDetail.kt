package cn.safetyledger.app
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.safetyledger.app.data.*
@Composable fun RecordDetail(record:InspectionEntity,dao:LedgerDao,back:()->Unit){var items by remember{mutableStateOf<List<InspectionItemEntity>>(emptyList())};LaunchedEffect(record.id){items=dao.inspectionItems(record.id)};Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(record.type,style=MaterialTheme.typography.headlineSmall);Text("${record.date} ${record.time}");Text(record.location);items.forEach{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text(it.category);Text(it.content);Text(it.result.name);if(it.problem.isNotBlank())Text(it.problem)}}};Button(onClick={}){Text("\u5bfc\u51fa\u6b63\u5f0f PDF")};TextButton(onClick=back){Text("\u8fd4\u56de")}}}
