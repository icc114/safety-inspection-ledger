package cn.safetyledger.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import cn.safetyledger.app.data.InspectionEntity
import cn.safetyledger.app.data.LedgerDao
import cn.safetyledger.app.data.RecordStatus
import cn.safetyledger.app.data.TemplateEntity
import cn.safetyledger.app.data.TemplateItemEntity
import cn.safetyledger.app.pdf.PdfExporter
import cn.safetyledger.app.pdf.PrintableInspection
import cn.safetyledger.app.sync.HolidayMeta
import cn.safetyledger.app.sync.HolidayRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.UUID

internal val LedgerInk = Color(0xFF1F2933)
internal val LedgerBlue = Color(0xFF315B8A)
internal val LedgerHeader = Color(0xFFD9E2F3)
internal val LedgerLabel = Color(0xFFE7E6E6)
internal val LedgerPaper = Color(0xFFFCFCFA)
internal val LedgerLine = Color(0xFF4A4F55)
internal val LedgerMuted = Color(0xFF66717D)
private val Success = Color(0xFF287A4D)
private val Warning = Color(0xFFB45A35)

@Composable
fun FormalHome(
    dao: LedgerDao,
    records: List<InspectionEntity>,
    go: (String) -> Unit,
    open: (InspectionEntity) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holidayRepository = remember { HolidayRepository(context, dao) }
    var holiday by remember { mutableStateOf<HolidayMeta?>(null) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var pageSize by remember { mutableIntStateOf(10) }
    var page by remember { mutableIntStateOf(0) }
    var range by remember { mutableStateOf("month") }
    var typeFilter by remember { mutableStateOf("全部") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected) { holiday = holidayRepository.meta(selected) }
    val types = remember(records) {
        listOf("全部") + records.map { it.type }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val shown = records.filter { record ->
        val date = runCatching { LocalDate.parse(record.date) }.getOrNull()
        val inRange = when (range) {
            "day" -> record.date == selected.toString()
            "month" -> record.date.startsWith(YearMonth.from(selected).toString())
            "quarter" -> date != null && date.year == selected.year &&
                (date.monthValue - 1) / 3 == (selected.monthValue - 1) / 3
            "year" -> record.date.startsWith("${selected.year}-")
            else -> true
        }
        inRange && (typeFilter == "全部" || record.type == typeFilter)
    }
    val totalPages = ((shown.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val safePage = page.coerceAtMost(totalPages - 1)
    val pageRows = shown.drop(safePage * pageSize).take(pageSize)
    val pending = shown.count { it.status == RecordStatus.PENDING }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF3F4F2)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().background(LedgerPaper).padding(horizontal = 16.dp, vertical = 18.dp),
            ) {
                Text("安全检查台账", fontSize = 26.sp, fontWeight = FontWeight.Black, color = LedgerInk)
                Text("检查记录中心", fontSize = 13.sp, color = LedgerMuted)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { go("form") },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(3.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LedgerBlue),
                ) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("新建检查记录", fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            LedgerSection(Modifier.padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = LedgerBlue, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("检查日历", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { month = month.minusMonths(1); selected = month.atDay(1); page = 0 }) {
                        Icon(Icons.Outlined.KeyboardArrowLeft, "上月")
                    }
                    Text("${month.year}年${month.monthValue}月", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { month = month.plusMonths(1); selected = month.atDay(1); page = 0 }) {
                        Icon(Icons.Outlined.KeyboardArrowRight, "下月")
                    }
                }
                HorizontalDivider(color = LedgerLine.copy(alpha = .35f))
                FormalCalendar(month, selected, records.map { it.date }.toSet()) {
                    selected = it
                    range = "day"
                    page = 0
                }
                holiday?.let {
                    Text(
                        "${selected} · ${it.name}${if (it.isWork) "（调休上班）" else "（放假）"}",
                        modifier = Modifier.fillMaxWidth().background(LedgerLabel).padding(8.dp),
                        color = if (it.isWork) Color(0xFF8B5A14) else Color(0xFF9A2D28),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryCell("筛选记录", "${shown.size}", Icons.Outlined.Description, Modifier.weight(1f))
                SummaryCell("已完成", "${shown.size - pending}", Icons.Outlined.CheckCircle, Modifier.weight(1f))
                SummaryCell("待整改", "$pending", Icons.Outlined.CloudDone, Modifier.weight(1f), pending > 0)
            }
        }

        item {
            LedgerSection(Modifier.padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("检查记录", fontSize = 19.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        selectionMode = !selectionMode
                        if (!selectionMode) selectedIds = emptySet()
                    }) { Text(if (selectionMode) "退出多选" else "多选导出") }
                }
                FilterStrip(
                    values = listOf("day" to "当日", "month" to "本月", "quarter" to "本季度", "year" to "本年度", "all" to "全部"),
                    selected = range,
                ) { range = it; page = 0 }
                Spacer(Modifier.height(6.dp))
                FilterStrip(types.map { it to it }, typeFilter) { typeFilter = it; page = 0 }

                if (selectionMode) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val ids = shown.filterNot { it.archiveOnly }.map { it.id }.toSet()
                                selectedIds = if (selectedIds.containsAll(ids)) emptySet() else ids
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(3.dp),
                        ) { Text("全选") }
                        Button(
                            onClick = {
                                val chosen = shown.filter { selectedIds.contains(it.id) && !it.archiveOnly }
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            val printable = chosen.map {
                                                PrintableInspection(it, dao.inspectionItems(it.id), dao.media(it.id))
                                            }
                                            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                                            val file = File(dir, "安全检查台账-${System.currentTimeMillis()}.pdf")
                                            file.outputStream().use { PdfExporter().export(printable, it) }
                                            file
                                        }
                                    }.onSuccess { file ->
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "application/pdf"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "导出检查记录"))
                                        exportMessage = "已合并导出 ${chosen.size} 条记录"
                                    }.onFailure { exportMessage = "导出失败：${it.message}" }
                                }
                            },
                            enabled = selectedIds.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(3.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = LedgerBlue),
                        ) { Text("合并导出 PDF") }
                    }
                }
                Spacer(Modifier.height(10.dp))
                RecordHeader()
                if (pageRows.isEmpty()) {
                    Text("当前筛选范围内暂无检查记录", Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, color = LedgerMuted)
                }
                pageRows.forEach { record ->
                    RecordRow(record, selectionMode, selectedIds.contains(record.id)) { checked ->
                        if (selectionMode) {
                            if (record.archiveOnly) exportMessage = "云端归档记录请进入详情下载 PDF"
                            else selectedIds = if (checked) selectedIds + record.id else selectedIds - record.id
                        } else open(record)
                    }
                }
                exportMessage?.let { Text(it, color = LedgerBlue, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (page > 0) page-- }, enabled = safePage > 0) { Text("上一页") }
                    Spacer(Modifier.weight(1f))
                    Text("第 ${safePage + 1} / $totalPages 页", fontSize = 12.sp, color = LedgerMuted)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { if (safePage + 1 < totalPages) page++ }, enabled = safePage + 1 < totalPages) { Text("下一页") }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("每页：", fontSize = 12.sp)
                    listOf(10, 20, 50, 100, 200).forEach { value ->
                        TextButton(onClick = { pageSize = value; page = 0 }) {
                            Text(if (pageSize == value) "[$value]" else "$value", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun FormalForm(dao: LedgerDao, next: (InspectionEntity, List<TemplateItemEntity>) -> Unit) {
    val scope = rememberCoroutineScope()
    val templates by dao.templates().collectAsState(emptyList())
    var chosen by remember { mutableStateOf<TemplateEntity?>(null) }
    var unit by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var duty by remember { mutableStateOf("") }
    var inspector1 by remember { mutableStateOf("") }
    var inspector2 by remember { mutableStateOf("") }
    var inspectee by remember { mutableStateOf("") }
    val now = remember { LocalTime.now().withNano(0) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF3F4F2)).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(10.dp))
            LedgerSection {
                Text("车棚检查记录表", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("新建检查 · 基本信息", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = LedgerMuted, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                FormalStaticRow("检查日期", LocalDate.now().toString())
                FormalStaticRow("检查时间", now.toString())
                FormalChoiceRow("检查类型") {
                    templates.filter { it.active }.forEach { template ->
                        FilterChip(
                            selected = chosen?.id == template.id,
                            onClick = { chosen = template },
                            label = { Text(template.name) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = LedgerHeader),
                        )
                    }
                }
                FormalInputRow("被检查单位", unit, { unit = it }, "请输入单位名称")
                FormalInputRow("检查地点", place, { place = it }, "请输入具体地点")
                FormalInputRow("在岗人员", duty, { duty = it }, "姓名，可多人")
                FormalInputRow("检查人 1", inspector1, { inspector1 = it }, "必填")
                FormalInputRow("检查人 2", inspector2, { inspector2 = it }, "可选")
                FormalInputRow("被检查人", inspectee, { inspectee = it }, "请输入姓名")
            }
        }
        item {
            Button(
                onClick = {
                    val template = chosen ?: return@Button
                    scope.launch {
                        next(
                            InspectionEntity(
                                UUID.randomUUID().toString(), template.id, LocalDate.now().toString(), now.toString(),
                                template.name, unit, place, duty, inspector1, inspector2, inspectee, "", "", "", "",
                            ),
                            dao.templateItems(template.id),
                        )
                    }
                },
                enabled = chosen != null && place.isNotBlank() && inspector1.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(3.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LedgerBlue),
            ) { Text("进入检查项目", fontWeight = FontWeight.Bold) }
        }
        item { Text("下一步：逐项检查 → 现场照片 → 检查结论 → 三方签名", color = LedgerMuted, fontSize = 12.sp); Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun LedgerSection(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().border(1.dp, LedgerLine, RoundedCornerShape(3.dp)),
        shape = RoundedCornerShape(3.dp),
        color = LedgerPaper,
        shadowElevation = 0.dp,
    ) { Column(Modifier.padding(12.dp), content = content) }
}

@Composable
private fun FormalCalendar(month: YearMonth, selected: LocalDate, marked: Set<String>, choose: (LocalDate) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f).padding(5.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = LedgerMuted, fontSize = 12.sp) } }
        val offset = month.atDay(1).dayOfWeek.value - 1
        repeat(6) { week ->
            Row {
                repeat(7) { dayIndex ->
                    val dayNumber = week * 7 + dayIndex - offset + 1
                    if (dayNumber in 1..month.lengthOfMonth()) {
                        val date = month.atDay(dayNumber)
                        val isSelected = date == selected
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                                .background(if (isSelected) LedgerBlue else Color.Transparent, RoundedCornerShape(3.dp))
                                .border(if (marked.contains(date.toString())) 1.dp else 0.dp, LedgerBlue, RoundedCornerShape(3.dp))
                                .clickable { choose(date) },
                            contentAlignment = Alignment.Center,
                        ) { Text("$dayNumber", color = if (isSelected) Color.White else LedgerInk, fontSize = 13.sp, fontWeight = if (marked.contains(date.toString())) FontWeight.Bold else FontWeight.Normal) }
                    } else Spacer(Modifier.weight(1f).aspectRatio(1f))
                }
            }
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, warn: Boolean = false) {
    Column(modifier.border(1.dp, LedgerLine, RoundedCornerShape(3.dp)).background(LedgerPaper).padding(10.dp)) {
        Icon(icon, null, tint = if (warn) Warning else LedgerBlue, modifier = Modifier.size(20.dp))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Black, color = if (warn) Warning else LedgerInk)
        Text(label, fontSize = 11.sp, color = LedgerMuted)
    }
}

@Composable
private fun FilterStrip(values: List<Pair<String, String>>, selected: String, select: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { select(value) },
                label = { Text(label, fontSize = 12.sp) },
                shape = RoundedCornerShape(3.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = LedgerHeader, selectedLabelColor = LedgerInk),
            )
        }
    }
}

@Composable
private fun RecordHeader() {
    Row(Modifier.fillMaxWidth().background(LedgerHeader).border(1.dp, LedgerLine).padding(vertical = 8.dp, horizontal = 6.dp)) {
        Text("日期", Modifier.width(82.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("检查类别 / 地点", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("状态", Modifier.width(56.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RecordRow(record: InspectionEntity, selectionMode: Boolean, selected: Boolean, select: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().border(1.dp, LedgerLine.copy(alpha = .75f)).clickable { select(!selected) }.padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode && !record.archiveOnly) Checkbox(selected, onCheckedChange = select, modifier = Modifier.size(30.dp))
        Text(record.date.takeLast(5), Modifier.width(if (selectionMode) 54.dp else 82.dp), fontSize = 12.sp)
        Column(Modifier.weight(1f)) {
            Text(record.type, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(record.unit, record.location).filter { it.isNotBlank() }.joinToString(" · "), color = LedgerMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val label = when { record.archiveOnly -> "已归档"; record.status == RecordStatus.PENDING -> "待整改"; else -> "已完成" }
        Text(label, Modifier.width(56.dp), color = if (record.status == RecordStatus.PENDING) Warning else Success, fontSize = 11.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FormalStaticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().border(1.dp, LedgerLine), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp).background(LedgerLabel).padding(12.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontSize = 13.sp)
        Text(value, Modifier.weight(1f).padding(12.dp), fontSize = 13.sp)
    }
}

@Composable
private fun FormalChoiceRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, LedgerLine), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp).background(LedgerLabel).padding(12.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontSize = 13.sp)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun FormalInputRow(label: String, value: String, update: (String) -> Unit, hint: String) {
    Row(Modifier.fillMaxWidth().border(1.dp, LedgerLine), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp).background(LedgerLabel).padding(vertical = 18.dp, horizontal = 5.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontSize = 13.sp)
        OutlinedTextField(value, update, placeholder = { Text(hint, fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f).padding(6.dp), shape = RoundedCornerShape(2.dp))
    }
}
