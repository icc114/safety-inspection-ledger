package cn.safetyledger.app.data

import java.util.UUID

object InitialData {
    suspend fun seedIfNeeded(dao: LedgerDao) {
        if (dao.templateCount() > 0) return
        val templateId = UUID.randomUUID().toString()
        dao.saveTemplate(TemplateEntity(templateId, "车棚安全检查", "车棚检查"))
        val rows = listOf(
            Triple("排水防汛", "雨篦子及排水口", "周边无杂物、无明显积水，排水通畅"),
            Triple("物资保障", "防汛物资配备", "沙袋、挡水板、照明及应急工具齐备有效"),
            Triple("人员值守", "值守人员在岗", "人员在岗，熟悉职责和联系方式"),
            Triple("应急预案", "应急预案落实", "预案可执行，紧急联系人和处置流程明确"),
            Triple("施工管理", "降雨期间施工管理", "强降雨期间停止施工并落实现场防护"),
            Triple("设施安全", "棚体、照明和消防设施", "结构稳固、照明正常、消防器材有效"),
            Triple("停车秩序", "车辆停放秩序", "车辆码放整齐，不占压消防和疏散区域"),
            Triple("通道巡查", "消防及人员通道", "通道保持畅通，无堆物和违规充电"),
            Triple("值守纪律", "工作和值守纪律", "服从调度，巡查、交接和问题上报记录完整"),
        )
        dao.saveTemplateItems(rows.mapIndexed { index, (category, content, standard) ->
            TemplateItemEntity(UUID.randomUUID().toString(), templateId, category, content, standard, index)
        })
    }
}
