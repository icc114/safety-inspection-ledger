package cn.safetyledger.app.data

import java.util.UUID

object InitialData {
    suspend fun seedIfNeeded(dao: LedgerDao) {
        if (dao.templateCount() > 0) return
        val templateId = UUID.randomUUID().toString()
        dao.saveTemplate(TemplateEntity(templateId, "车棚检查", "车棚检查"))
        val rows = listOf(
            Triple("排水防汛", "雨篦子及周边", "是否在降雨前完成清理，无落叶、淤泥及杂物堵塞"),
            Triple("排水防汛", "车棚周边排水沟、排水口", "是否畅通，低洼处是否存在明显积水"),
            Triple("物资保障", "沙袋、挡水板、抽水泵、雨具、照明及警示设施", "是否配齐、完好可用"),
            Triple("人员值守", "值守人员是否到岗，职责是否明确", "联系电话及通信设备是否畅通"),
            Triple("应急预案", "防汛预案和应急处置措施是否落实", "现场人员是否熟悉处置及上报流程"),
            Triple("施工管理", "降雨期间车棚内施工是否全部停工", "人员、设备是否撤至安全位置（无施工填不适用）"),
            Triple("设施安全", "消防设施、用电线路及棚体结构", "是否安全完好，无松动、漏电等隐患"),
            Triple("停车秩序", "车辆是否分区有序停放", "不占压雨篦子，不堵塞出入口和疏散通道"),
            Triple("通道巡查", "出入口、疏散通道是否畅通", "是否按要求巡查并如实留存记录"),
            Triple("值守纪律", "是否在岗履职、保持通信畅通、服从统一指挥调度", "险情是否及时报告"),
        )
        dao.saveTemplateItems(rows.mapIndexed { index, (category, content, standard) ->
            TemplateItemEntity(UUID.randomUUID().toString(), templateId, category, content, standard, index)
        })
    }
}
