package cn.safetyledger.app.sync

import cn.safetyledger.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val KEY_ENDPOINT = "cloud_endpoint"
private const val KEY_TOKEN = "cloud_token"
private const val KEY_DEVICE_ID = "cloud_device_id"
private const val KEY_LAST_SYNC = "cloud_last_sync"

const val DEFAULT_CLOUDFLARE_ENDPOINT = "https://safety-inspection-ledger-cloud.icc2820.workers.dev"

data class SyncSummary(val uploaded:Int,val downloaded:Int,val serverTime:Long)

class CloudSyncClient(
    private val dao: LedgerDao,
    endpoint: String,
    private val token: String = ""
) {
    private val baseUrl = endpoint.trim().trimEnd('/')

    suspend fun test(): ConnectionResult = withContext(Dispatchers.IO) {
        runCatching {
            require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) { "服务器地址格式不正确" }
            val json = request("GET", "/health")
            if (json.optBoolean("ok", false)) ConnectionResult.Success
            else ConnectionResult.Failure(json.optString("error", "服务器未返回正常状态"))
        }.getOrElse { ConnectionResult.Failure(it.message ?: "连接失败") }
    }

    suspend fun sync(): SyncSummary = withContext(Dispatchers.IO) {
        require(baseUrl.isNotBlank()) { "请先填写云同步服务器地址" }
        val deviceId = deviceId()
        val local = localObjects(deviceId)
        val push = JSONObject()
            .put("deviceId", deviceId)
            .put("objects", JSONArray().apply { local.forEach { put(it) } })
        request("POST", "/v1/sync/push", push)

        val lastSync = dao.setting(KEY_LAST_SYNC)?.value?.toLongOrNull() ?: 0L
        val since = (lastSync - 1L).coerceAtLeast(0L)
        val pulled = request("GET", "/v1/sync/pull?since=$since")
        val objects = pulled.optJSONArray("objects") ?: JSONArray()
        var applied = 0
        for (i in 0 until objects.length()) {
            if (applyRemote(objects.getJSONObject(i))) applied++
        }
        val serverTime = pulled.optLong("serverTime", System.currentTimeMillis())
        dao.saveSetting(SettingEntity(KEY_LAST_SYNC, serverTime.toString()))
        SyncSummary(local.size, applied, serverTime)
    }

    private suspend fun deviceId(): String {
        dao.setting(KEY_DEVICE_ID)?.value?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        dao.saveSetting(SettingEntity(KEY_DEVICE_ID, id))
        return id
    }

    private suspend fun localObjects(deviceId:String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        dao.allTemplates().forEach { value ->
            out += objectEnvelope("template", value.id, value.updatedAt, value.deletedAt, deviceId, JSONObject()
                .put("id", value.id).put("name", value.name).put("category", value.category)
                .put("active", value.active).put("updatedAt", value.updatedAt).putNullable("deletedAt", value.deletedAt))
        }
        dao.allTemplateItems().forEach { value ->
            out += objectEnvelope("template_item", value.id, value.updatedAt, null, deviceId, JSONObject()
                .put("id", value.id).put("templateId", value.templateId).put("category", value.category)
                .put("content", value.content).put("standard", value.standard).put("position", value.position)
                .put("updatedAt", value.updatedAt))
        }
        dao.allInspections().forEach { value ->
            out += objectEnvelope("inspection", value.id, value.updatedAt, value.deletedAt, deviceId, inspectionJson(value))
        }
        dao.allInspectionItems().forEach { value ->
            out += objectEnvelope("inspection_item", value.id, value.updatedAt, null, deviceId, JSONObject()
                .put("id", value.id).put("inspectionId", value.inspectionId).put("templateItemId", value.templateItemId)
                .put("category", value.category).put("content", value.content).put("standard", value.standard)
                .put("result", value.result.name).put("problem", value.problem).put("updatedAt", value.updatedAt))
        }
        dao.allTombstones().forEach { value ->
            out += objectEnvelope("tombstone", value.id, value.deletedAt, value.deletedAt, deviceId, JSONObject()
                .put("id", value.id).put("entityType", value.entityType).put("entityId", value.entityId)
                .put("deletedAt", value.deletedAt).put("deviceId", value.deviceId))
        }
        return out
    }

    private suspend fun applyRemote(obj: JSONObject): Boolean {
        val type = obj.getString("type")
        val payload = obj.getJSONObject("payload")
        val remoteUpdatedAt = obj.getLong("updatedAt")
        return when(type) {
            "template" -> {
                val id = payload.getString("id")
                val local = dao.template(id)
                if (local != null && local.updatedAt > remoteUpdatedAt) false else {
                    dao.saveTemplate(TemplateEntity(
                        id=id,
                        name=payload.getString("name"),
                        category=payload.optString("category", ""),
                        active=payload.optBoolean("active", true),
                        updatedAt=payload.optLong("updatedAt", remoteUpdatedAt),
                        deletedAt=payload.optNullableLong("deletedAt")
                    )); true
                }
            }
            "template_item" -> {
                val id = payload.getString("id")
                val local = dao.templateItem(id)
                if (local != null && local.updatedAt > remoteUpdatedAt) false else {
                    dao.saveTemplateItem(TemplateItemEntity(
                        id=id,
                        templateId=payload.getString("templateId"),
                        category=payload.optString("category", ""),
                        content=payload.optString("content", ""),
                        standard=payload.optString("standard", ""),
                        position=payload.optInt("position", 0),
                        updatedAt=payload.optLong("updatedAt", remoteUpdatedAt)
                    )); true
                }
            }
            "inspection" -> {
                val id = payload.getString("id")
                val local = dao.inspection(id)
                if (local != null && local.updatedAt > remoteUpdatedAt) false else {
                    dao.saveInspection(inspectionFromJson(payload, remoteUpdatedAt)); true
                }
            }
            "inspection_item" -> {
                val id = payload.getString("id")
                val local = dao.inspectionItem(id)
                if (local != null && local.updatedAt > remoteUpdatedAt) false else {
                    dao.saveInspectionItems(listOf(InspectionItemEntity(
                        id=id,
                        inspectionId=payload.getString("inspectionId"),
                        templateItemId=payload.optString("templateItemId", ""),
                        category=payload.optString("category", ""),
                        content=payload.optString("content", ""),
                        standard=payload.optString("standard", ""),
                        result=runCatching { ItemResult.valueOf(payload.optString("result", ItemResult.NA.name)) }.getOrDefault(ItemResult.NA),
                        problem=payload.optString("problem", ""),
                        updatedAt=payload.optLong("updatedAt", remoteUpdatedAt)
                    ))); true
                }
            }
            "tombstone" -> {
                val tomb = TombstoneEntity(
                    id=payload.getString("id"),
                    entityType=payload.getString("entityType"),
                    entityId=payload.getString("entityId"),
                    deletedAt=payload.getLong("deletedAt"),
                    deviceId=payload.optString("deviceId", "remote")
                )
                val existing = dao.tombstoneFor(tomb.entityType, tomb.entityId)
                if (existing != null && existing.deletedAt > tomb.deletedAt) false else {
                    dao.tombstone(tomb)
                    if (tomb.entityType == "inspection") {
                        val local = dao.inspection(tomb.entityId)
                        if (local == null || local.updatedAt <= tomb.deletedAt) dao.purge(tomb.entityId)
                    }
                    true
                }
            }
            else -> false
        }
    }

    private fun request(method:String, path:String, body:JSONObject?=null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("云端返回 HTTP $code：${text.take(300)}")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}

private fun objectEnvelope(type:String,id:String,updatedAt:Long,deletedAt:Long?,deviceId:String,payload:JSONObject)=JSONObject()
    .put("type",type).put("id",id).put("updatedAt",updatedAt).putNullable("deletedAt",deletedAt)
    .put("deviceId",deviceId).put("payload",payload)

private fun inspectionJson(v:InspectionEntity)=JSONObject()
    .put("id",v.id).put("templateId",v.templateId).put("date",v.date).put("time",v.time).put("type",v.type)
    .put("unit",v.unit).put("location",v.location).put("dutyOfficer",v.dutyOfficer).put("inspector1",v.inspector1)
    .put("inspector2",v.inspector2).put("inspectee",v.inspectee).put("conclusion",v.conclusion)
    .put("rectificationAdvice",v.rectificationAdvice).put("responsiblePerson",v.responsiblePerson).put("deadline",v.deadline)
    .put("rectificationDetail",v.rectificationDetail).put("reviewResult",v.reviewResult).put("status",v.status.name)
    .put("updatedAt",v.updatedAt).putNullable("deletedAt",v.deletedAt)

private fun inspectionFromJson(p:JSONObject, fallbackUpdatedAt:Long)=InspectionEntity(
    id=p.getString("id"), templateId=p.optString("templateId", ""), date=p.optString("date", ""), time=p.optString("time", ""),
    type=p.optString("type", ""), unit=p.optString("unit", ""), location=p.optString("location", ""), dutyOfficer=p.optString("dutyOfficer", ""),
    inspector1=p.optString("inspector1", ""), inspector2=p.optString("inspector2", ""), inspectee=p.optString("inspectee", ""), conclusion=p.optString("conclusion", ""),
    rectificationAdvice=p.optString("rectificationAdvice", ""), responsiblePerson=p.optString("responsiblePerson", ""), deadline=p.optString("deadline", ""),
    rectificationDetail=p.optString("rectificationDetail", ""), reviewResult=p.optString("reviewResult", ""),
    status=runCatching { RecordStatus.valueOf(p.optString("status", RecordStatus.COMPLETE.name)) }.getOrDefault(RecordStatus.COMPLETE),
    updatedAt=p.optLong("updatedAt", fallbackUpdatedAt), deletedAt=p.optNullableLong("deletedAt")
)

private fun JSONObject.putNullable(key:String,value:Any?):JSONObject=put(key,value ?: JSONObject.NULL)
private fun JSONObject.optNullableLong(key:String):Long? = if (!has(key) || isNull(key)) null else getLong(key)
