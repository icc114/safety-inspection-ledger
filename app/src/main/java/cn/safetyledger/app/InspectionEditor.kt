package cn.safetyledger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.safetyledger.app.data.*
import cn.safetyledger.app.media.MediaActions
import cn.safetyledger.app.media.SignatureDialog
import cn.safetyledger.app.sync.CloudSyncScheduler
import kotlinx.coroutines.launch
import java.util.UUID

data class ItemDraft(val source: TemplateItemEntity, val result: ItemResult = ItemResult.NA, val problem: String = "")

@Composable
fun InspectionEditor(record: InspectionEntity, templateItems: List<TemplateItemEntity>, dao: LedgerDao, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var drafts by remember(templateItems) { mutableStateOf(templateItems.map { ItemDraft(it) }) }
    var conclusionChoice by remember { mutableStateOf(if (record.conclusion.isBlank()) "合格" else record.conclusion) }
    var inspectionSummary by remember { mutableStateOf("") }
    var advice by remember { mutableStateOf(record.rectificationAdvice) }
    var responsible by remember { mutableStateOf(record.responsiblePerson) }
    var deadline by remember { mutableStateOf(record.deadline) }
    var message by remember { mutableStateOf("") }
    var signatureKind by remember { mutableStateOf<MediaKind?>(null) }
    val hasFailure = drafts.any { it.result == ItemResult.FAIL }
    val invalidFailure = drafts.any { it.result == ItemResult.FAIL && it.problem.isBlank() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF3F4F2)).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            LedgerSection {
                Text(recordTitle(record.type), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("逐项检查", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 12.sp, color = LedgerMuted)
                Spacer(Modifier.height(10.dp))
                MetaRow("检查时间", "${record.date}  ${record.time}")
                MetaRow("检查地点", record.location)
                MetaRow("被检查单位", record.unit.ifBlank { "—" })
                MetaRow("检查人员", listOf(record.inspector1, record.inspector2).filter { it.isNotBlank() }.joinToString("、"))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().background(LedgerHeader).border(1.dp, LedgerLine).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("检查项目", Modifier.weight(1f), fontWeight = FontWeight.Black)
                Text("${drafts.count { it.result == ItemResult.PASS }}/${drafts.size} 合格", fontSize = 12.sp, color = LedgerBlue)
            }
        }
        itemsIndexed(drafts, key = { _, value -> value.source.id }) { index, draft ->
            InspectionTableRow(index, draft) { changed -> drafts = drafts.toMutableList().also { it[index] = changed } }
        }
        item {
            LedgerSection {
                SectionTitle("检查结论与整改")
                Text("检查结论", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("合格", "不合格", "限期整改").forEach { choice ->
                        FilterChip(
                            selected = conclusionChoice == choice,
                            onClick = { conclusionChoice = choice },
                            label = { Text(choice) },
                            shape = RoundedCornerShape(3.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = LedgerHeader),
                        )
                    }
                }
                OutlinedTextField(inspectionSummary, { inspectionSummary = it }, label = { Text("检查情况") }, minLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(2.dp))
                OutlinedTextField(advice, { advice = it }, label = { Text("整改意见") }, minLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(responsible, { responsible = it }, label = { Text("整改责任人") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(2.dp))
                    OutlinedTextField(deadline, { deadline = it }, label = { Text("整改期限") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(2.dp))
                }
            }
        }
        item {
            LedgerSection {
                SectionTitle("现场照片")
                Text("照片自动保留拍摄时间和地点水印。", color = LedgerMuted, fontSize = 12.sp)
                MediaActions(record.id, MediaKind.SITE, record.location, dao) { message = it }
            }
        }
        item {
            LedgerSection {
                SectionTitle("签字确认")
                SignatureButton("检查人 1", record.inspector1) { signatureKind = MediaKind.SIGNATURE_INSPECTOR_1 }
                SignatureButton("检查人 2", record.inspector2.ifBlank { "未填写" }) { signatureKind = MediaKind.SIGNATURE_INSPECTOR_2 }
                SignatureButton("被检查人", record.inspectee.ifBlank { "未填写" }) { signatureKind = MediaKind.SIGNATURE_INSPECTEE }
                if (message.isNotBlank()) Text(message, color = LedgerBlue, fontSize = 12.sp)
            }
        }
        item {
            if (invalidFailure) Text("不合格项目必须填写现场情况/问题。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Button(
                onClick = {
                    scope.launch {
                        val finalConclusion = if (hasFailure && conclusionChoice == "合格") "限期整改" else conclusionChoice
                        val combinedSummary = listOf(inspectionSummary, advice).filter { it.isNotBlank() }.joinToString("\n")
                        dao.saveInspection(record.copy(conclusion = finalConclusion, rectificationAdvice = combinedSummary, responsiblePerson = responsible, deadline = deadline, status = if (hasFailure) RecordStatus.PENDING else RecordStatus.COMPLETE, updatedAt = System.currentTimeMillis()))
                        dao.saveInspectionItems(drafts.map {
                            InspectionItemEntity(UUID.randomUUID().toString(), record.id, it.source.id, it.source.category, it.source.content, it.source.standard, it.result, it.problem)
                        })
                        CloudSyncScheduler.enqueue(context)
                        onSaved()
                    }
                },
                enabled = drafts.isNotEmpty() && !invalidFailure,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(3.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LedgerBlue),
            ) { Text("保存检查记录", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(24.dp))
        }
    }

    signatureKind?.let { kind ->
        SignatureDialog(record.id, kind, when (kind) {
            MediaKind.SIGNATURE_INSPECTOR_1 -> "检查人 1 签名"
            MediaKind.SIGNATURE_INSPECTOR_2 -> "检查人 2 签名"
            else -> "被检查人签名"
        }, dao, { signatureKind = null }) { message = it }
    }
}

@Composable
private fun InspectionTableRow(index: Int, draft: ItemDraft, update: (ItemDraft) -> Unit) {
    Column(Modifier.fillMaxWidth().background(LedgerPaper).border(1.dp, LedgerLine)) {
        Row(Modifier.fillMaxWidth().background(LedgerLabel).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}", Modifier.width(30.dp), fontWeight = FontWeight.Black, color = LedgerBlue)
            Text(draft.source.category, fontWeight = FontWeight.Black)
        }
        Column(Modifier.padding(10.dp)) {
            Text("检查内容及标准", color = LedgerMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(listOf(draft.source.content, draft.source.standard).filter { it.isNotBlank() }.joinToString("："), fontSize = 14.sp, lineHeight = 21.sp)
            HorizontalDivider(Modifier.padding(vertical = 9.dp), color = LedgerLine.copy(alpha = .3f))
            Text("检查结果", color = LedgerMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ItemResult.entries.forEach { result ->
                    FilterChip(
                        selected = draft.result == result,
                        onClick = { update(draft.copy(result = result)) },
                        label = { Text(resultLabel(result), fontSize = 12.sp) },
                        shape = RoundedCornerShape(3.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = when (result) {
                            ItemResult.PASS -> Color(0xFFDCECDD)
                            ItemResult.FAIL -> Color(0xFFF5DDD7)
                            ItemResult.NA -> LedgerHeader
                        }),
                    )
                }
            }
            if (draft.result == ItemResult.FAIL) {
                OutlinedTextField(draft.problem, { update(draft.copy(problem = it)) }, label = { Text("现场情况/问题（必填）") }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(2.dp))
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().border(1.dp, LedgerLine), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(92.dp).background(LedgerLabel).padding(10.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(value.ifBlank { "—" }, Modifier.weight(1f).padding(10.dp), fontSize = 12.sp)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Black)
    HorizontalDivider(Modifier.padding(top = 8.dp), color = LedgerLine.copy(alpha = .45f))
}

@Composable
private fun SignatureButton(label: String, name: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(name, color = LedgerMuted, fontSize = 12.sp) }
        OutlinedButton(onClick = click, shape = RoundedCornerShape(3.dp)) { Text("签名") }
    }
}

private fun resultLabel(result: ItemResult) = when (result) {
    ItemResult.PASS -> "合格"
    ItemResult.FAIL -> "不合格"
    ItemResult.NA -> "不适用"
}

private fun recordTitle(type: String) = when {
    type.endsWith("检查") -> "${type}记录表"
    type.isNotBlank() -> "${type}检查记录表"
    else -> "安全检查记录表"
}
