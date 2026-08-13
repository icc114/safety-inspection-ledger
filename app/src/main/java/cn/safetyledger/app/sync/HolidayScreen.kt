package cn.safetyledger.app.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.safetyledger.app.data.LedgerDao
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Year

@Composable
fun HolidayScreen(dao:LedgerDao,onBack:()->Unit){
    val context=LocalContext.current;val repository=remember{HolidayRepository(context,dao)};val scope=rememberCoroutineScope()
    var year by remember{mutableIntStateOf(Year.now().value)};var cache by remember{mutableStateOf<JSONObject?>(null)}
    var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    suspend fun load(){cache=repository.cachedYear(year)}
    LaunchedEffect(year){load()}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text("法定节假日",style=MaterialTheme.typography.headlineSmall);Text("联网时从已设置的同步服务更新；断网时继续显示本机缓存。")}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={year--}){Text("上一年")};Text("${year}年",style=MaterialTheme.typography.titleLarge);TextButton(onClick={year++}){Text("下一年")}}}
        item{Button(enabled=!busy,onClick={busy=true;message="正在更新…";scope.launch{runCatching{repository.refresh(year)}.onSuccess{load();message="已更新并保存到本机"}.onFailure{message="更新失败：${it.message}；仍可使用已有缓存"};busy=false}},modifier=Modifier.fillMaxWidth()){Text("联网更新本年度节假日")}}
        cache?.let{value->item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text("本机已有缓存");Text("更新时间：${value.optString("fetchedAt").ifBlank{"未知"}}");Text("断网可用")}}}}
        if(cache==null)item{Text("本机还没有${year}年节假日缓存。")}
        if(message.isNotBlank())item{Text(message,color=if(message.startsWith("更新失败"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)}
        item{TextButton(onClick=onBack){Text("返回设置")}}
    }
}
