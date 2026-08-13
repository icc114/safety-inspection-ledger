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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.get(this).dao()
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF006C4C))) { LedgerApp(dao) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerApp(dao: LedgerDao) {
    val scope = rememberCoroutineScope()
    val records by dao.inspections().collectAsState(initial = emptyList())
    var screen by remember { mutableStateOf("home") }
    Scaffold(topBar = { TopAppBar(title = { Text("\u5b89\u5168\u68c0\u67e5\u53f0\u8d26") }, actions = {
        TextButton(onClick = { screen = "templates" }) { Text("\u6a21\u677f\u7ba1\u7406") }
        TextButton(onClick = { screen = "trash" }) { Text("\u56de\u6536\u7ad9") }
    }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            when (screen) {
                "home" -> {
                    Button(onClick = { screen = "form" }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("\u68c0\u67e5\u586b\u62a5") }
                    Spacer(Modifier.height(12.dp))
                    Text("${LocalDate.now().year} \u5e74 ${LocalDate.now().monthValue} \u6708", style = MaterialTheme.typography.titleLarge)
                    Text("\u7b5b\u9009\uff1a\u5f53\u65e5 | \u672c\u6708 | \u672c\u5b63\u5ea6 | \u672c\u5e74\u5ea6 | \u5168\u90e8    \u6bcf\u9875\uff1a10 / 20 / 50 / 100 / 200")
                    LazyColumn { items(records) { record -> Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(12.dp)) { Text("${record.date}  ${record.type}"); Text("${record.unit} - ${record.location}"); Text(record.status.name) } } } }
                }
                "form" -> InspectionForm { record -> scope.launch { dao.saveInspection(record) }; screen = "home" }
                "templates" -> TemplateManager(dao) { screen = "home" }
                else -> TrashScreen(dao) { screen = "home" }
            }
        }
    }
}

@Composable fun InspectionForm(save: (InspectionEntity) -> Unit) {
    var unit by remember { mutableStateOf("") }; var location by remember { mutableStateOf("") }; var type by remember { mutableStateOf("") }
    Column { Text("\u65b0\u5efa\u68c0\u67e5", style = MaterialTheme.typography.headlineSmall)
        listOf("\u68c0\u67e5\u7c7b\u578b" to type, "\u88ab\u68c0\u67e5\u5355\u4f4d" to unit, "\u68c0\u67e5\u5730\u70b9" to location).forEachIndexed { i, pair -> OutlinedTextField(pair.second, { when(i) { 0 -> type=it; 1 -> unit=it; else -> location=it } }, label={Text(pair.first)}, modifier=Modifier.fillMaxWidth()) }
        Button(onClick = { save(InspectionEntity(UUID.randomUUID().toString(), "", LocalDate.now().toString(), java.time.LocalTime.now().withNano(0).toString(), type, unit, location, "", "", "", "", "", "", "", "")) }, enabled=unit.isNotBlank() && location.isNotBlank()) { Text("\u4fdd\u5b58\u68c0\u67e5\u8bb0\u5f55") }
    }
}

@Composable fun TemplateManager(dao: LedgerDao, back: () -> Unit) {
    val scope=rememberCoroutineScope(); val templates by dao.templates().collectAsState(emptyList()); var name by remember { mutableStateOf("") }
    Column { Text("\u68c0\u67e5\u6a21\u677f", style=MaterialTheme.typography.headlineSmall); OutlinedTextField(name,{name=it},label={Text("\u6a21\u677f\u540d\u79f0")}); Button(onClick={scope.launch { dao.saveTemplate(TemplateEntity(UUID.randomUUID().toString(),name,"\u81ea\u5b9a\u4e49")); name="" }},enabled=name.isNotBlank()){Text("\u65b0\u5efa\u6a21\u677f")}; templates.forEach { Text("${it.name} - ${it.category}") }; TextButton(onClick=back){Text("\u8fd4\u56de")} }
}

@Composable fun TrashScreen(dao: LedgerDao, back: () -> Unit) {
    val trash by dao.trash().collectAsState(emptyList()); Column { Text("\u56de\u6536\u7ad9", style=MaterialTheme.typography.headlineSmall); trash.forEach { Text("${it.date} ${it.unit}") }; TextButton(onClick=back){Text("\u8fd4\u56de")} }
}
