package cn.safetyledger.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.safetyledger.app.data.*
import kotlinx.coroutines.launch

data class ItemDraft(val source:TemplateItemEntity,val result:ItemResult=ItemResult.NA,val problem:String="")

@Composable
fun InspectionEditor(record:InspectionEntity,templateItems:List<TemplateItemEntity>,dao:LedgerDao,onSaved:()->Unit){
 val scope=rememberCoroutineScope();var drafts by remember(templateItems){mutableStateOf(templateItems.map{ItemDraft(it)})};var conclusion by remember{mutableStateOf(record.conclusion)};var advice by remember{mutableStateOf(record.rectificationAdvice)};var responsible by remember{mutableStateOf(record.responsiblePerson)};var deadline by remember{mutableStateOf(record.deadline)}
 LazyColumn(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  item{Text("\u9010\u9879\u68c0\u67e5",style=MaterialTheme.typography.headlineSmall);Text("\u6a21\u677f\u5df2\u590d\u5236\u5230\u672c\u6b21\u8bb0\u5f55\uff0c\u4ee5\u540e\u4fee\u6539\u6a21\u677f\u4e0d\u4f1a\u6539\u53d8\u5386\u53f2\u8bb0\u5f55\u3002")}
  itemsIndexed(drafts,key={_,v->v.source.id}){index,draft->Card{Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("${index+1}. ${draft.source.category}");Text(draft.source.content);Text(draft.source.standard,style=MaterialTheme.typography.bodySmall);Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){ItemResult.entries.forEach{result->FilterChip(draft.result==result,{drafts=drafts.toMutableList().also{it[index]=draft.copy(result=result)}},{Text(resultLabel(result))})}};if(draft.result==ItemResult.FAIL)OutlinedTextField(draft.problem,{v->drafts=drafts.toMutableList().also{it[index]=draft.copy(problem=v)}},label={Text("\u73b0\u573a\u60c5\u51b5/\u95ee\u9898")},modifier=Modifier.fillMaxWidth())}}}
  item{OutlinedTextField(conclusion,{conclusion=it},label={Text("\u68c0\u67e5\u60c5\u51b5")},modifier=Modifier.fillMaxWidth());OutlinedTextField(advice,{advice=it},label={Text("\u6574\u6539\u610f\u89c1")},modifier=Modifier.fillMaxWidth());OutlinedTextField(responsible,{responsible=it},label={Text("\u6574\u6539\u8d23\u4efb\u4eba")},modifier=Modifier.fillMaxWidth());OutlinedTextField(deadline,{deadline=it},label={Text("\u6574\u6539\u671f\u9650")},modifier=Modifier.fillMaxWidth())}
  item{Button(onClick={scope.launch{val failed=drafts.any{it.result==ItemResult.FAIL};dao.saveInspection(record.copy(conclusion=conclusion,rectificationAdvice=advice,responsiblePerson=responsible,deadline=deadline,status=if(failed)RecordStatus.PENDING else RecordStatus.COMPLETE,updatedAt=System.currentTimeMillis()));dao.saveInspectionItems(drafts.map{InspectionItemEntity(java.util.UUID.randomUUID().toString(),record.id,it.source.id,it.source.category,it.source.content,it.source.standard,it.result,it.problem)});onSaved()}},enabled=drafts.isNotEmpty()&&drafts.none{it.result==ItemResult.FAIL&&it.problem.isBlank()},modifier=Modifier.fillMaxWidth()){Text("\u4fdd\u5b58\u68c0\u67e5\u8bb0\u5f55")};Spacer(Modifier.height(24.dp))}
 }
}
private fun resultLabel(r:ItemResult)=when(r){ItemResult.PASS->"\u5408\u683c";ItemResult.FAIL->"\u4e0d\u5408\u683c";ItemResult.NA->"\u4e0d\u9002\u7528"}
