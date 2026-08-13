package cn.safetyledger.app.sync

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.safetyledger.app.data.*
import cn.safetyledger.app.pdf.PdfExporter
import cn.safetyledger.app.pdf.PrintableInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class CloudSummary(val uploaded:Int=0,val downloaded:Int=0,val trashed:Int=0,val removed:Int=0,val conflicts:Int=0)
data class ArchiveSummary(val archived:Int,val releasedBytes:Long)
data class ConnectedCloud(val teamName:String,val role:String,val activeDeviceCount:Int=1)

private data class CloudConfig(
    val endpoint:String,val provider:String,val space:String,val deviceName:String,
    val teamCode:String,val role:String,val lastSyncAt:String="",val lastError:String="",val activeDeviceCount:Int=1,
)
private data class RecordSyncState(val version:Int,val syncedUpdatedAt:Long,val status:String)
private class CloudApiException(val status:Int,message:String):Exception(message)

object CloudSecrets {
    private fun prefs(context:Context)=EncryptedSharedPreferences.create(
        context,"cloud_secret",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    fun get(context:Context,key:String)=prefs(context).getString(key,null)
    fun put(context:Context,values:Map<String,String>)=prefs(context).edit().apply{values.forEach{(key,value)->putString(key,value)}}.apply()
    fun clearSession(context:Context)=prefs(context).edit().remove("device_token").remove("encryption_key").apply()
}

class CloudSyncEngine(private val context:Context,private val dao:LedgerDao=AppDatabase.get(context).dao()) {
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).writeTimeout(45,TimeUnit.SECONDS).build()

    suspend fun connect(provider:String,endpointInput:String,space:String,password:String,deviceName:String):ConnectedCloud=withContext(Dispatchers.IO){
        require(password.length>=8){"同步密码至少需要8位"}
        val endpoint=normalizeEndpoint(endpointInput)
        val health=publicRequest(endpoint,"/api/health")
        require(health.optBoolean("ok")&&health.optInt("version")>=3){"这不是兼容的安全检查台账云端地址"}
        val teamCode=workspaceCode(space)
        val deviceId=CloudSecrets.get(context,"device_id")?:UUID.randomUUID().toString().also{CloudSecrets.put(context,mapOf("device_id" to it))}
        val verifier=sha256Url("safety-ledger-auth-v1|$teamCode|$password".toByteArray())
        val common=JSONObject().put("teamCode",teamCode).put("authVerifier",verifier).put("deviceId",deviceId).put("deviceName",deviceName.ifBlank{"安卓手机"}).put("platform","android")
        val joined=try {
            publicRequest(endpoint,"/api/v1/teams/join","POST",common)
        } catch(error:CloudApiException) {
            if(error.status!=403) throw error
            val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
            val create=JSONObject(common.toString()).put("teamName",space).put("encryptionSalt",encodeUrl(salt))
            try{publicRequest(endpoint,"/api/v1/teams","POST",create)}
            catch(createError:CloudApiException){if(createError.status==409)throw IllegalArgumentException("同步空间已存在，请核对同步空间名称和密码");throw createError}
        }
        val salt=decodeUrl(joined.getString("encryptionSalt"))
        val key=deriveKey(password,salt)
        CloudSecrets.put(context,mapOf(
            "sync_password" to password,"device_token" to joined.getString("deviceToken"),
            "encryption_key" to encodeUrl(key),"device_id" to deviceId,
        ))
        val config=JSONObject().put("provider",provider).put("endpoint",endpoint).put("space",space)
            .put("device",deviceName.ifBlank{"安卓手机"}).put("teamCode",joined.getString("teamCode"))
            .put("role",joined.optString("role","member")).put("activeDeviceCount",1).put("lastSyncAt","").put("lastError","")
        dao.saveSetting(SettingEntity("cloud_config",config.toString()))
        CloudSyncScheduler.schedule(context)
        ConnectedCloud(joined.optString("teamName",space),joined.optString("role","member"))
    }

    suspend fun sync():CloudSummary=withContext(Dispatchers.IO){
        val config=loadConfig()
        try {
            val manifest=api(config,"/api/v1/manifest")
            val states=loadStates()
            val local=dao.allInspections().associateBy{it.id}.toMutableMap()
            val remoteArray=manifest.optJSONArray("records")?:JSONArray()
            val remoteIds=mutableSetOf<String>()
            var downloaded=0;var uploaded=0;var trashed=0;var removed=0;var conflicts=0
            for(index in 0 until remoteArray.length()){
                val meta=remoteArray.getJSONObject(index)
                val id=meta.getString("id");remoteIds+=id
                val remoteVersion=meta.optInt("version")
                val state=states[id]
                val record=local[id]
                when(meta.optString("status","active")){
                    "trash"->if(state==null||remoteVersion>state.version){
                        if(record!=null)dao.trash(id,parseTime(meta.opt("deletedAt"))?:System.currentTimeMillis())
                        states[id]=RecordSyncState(remoteVersion,record?.updatedAt?:0,"trash");trashed++
                    }
                    "archived"->if(state==null||remoteVersion>state.version||record?.archiveOnly!=true){
                        if(record!=null&&state!=null&&!record.archiveOnly&&record.updatedAt>state.syncedUpdatedAt){duplicateConflict(record);conflicts++}
                        val updated=parseTime(meta.opt("updatedAt"))?:record?.updatedAt?:System.currentTimeMillis()
                        val placeholder=(record?:InspectionEntity(id=id,templateId=meta.optString("typeId","default"),date=meta.optString("date"),time="00:00",type=meta.optString("typeName","检查记录"),unit="",location="",dutyOfficer="",inspector1="",inspector2="",inspectee="",conclusion="",rectificationAdvice="",responsiblePerson="",deadline=""))
                            .copy(updatedAt=updated,deletedAt=null,archiveOnly=true,archiveBlobId=meta.optString("archiveBlobId"),archivePageCount=meta.optInt("archivePageCount",1))
                        dao.saveInspection(placeholder);dao.deleteInspectionItems(id);dao.purgeMedia(id);File(context.filesDir,"media/$id").deleteRecursively();local[id]=placeholder
                        states[id]=RecordSyncState(remoteVersion,updated,"archived")
                    }
                    else->if(state==null||remoteVersion>state.version||record==null){
                        if(record!=null&&state!=null&&record.updatedAt>state.syncedUpdatedAt){
                            duplicateConflict(record);conflicts++
                        }
                        val bundle=downloadRecord(config,meta)
                        dao.replaceInspection(bundle.first,bundle.second,bundle.third)
                        local[id]=bundle.first
                        states[id]=RecordSyncState(remoteVersion,bundle.first.updatedAt,"active")
                        downloaded++
                    }
                }
            }
            for((id,state) in states.toMap()){
                if(id !in remoteIds){
                    if(local[id]!=null){dao.purge(id);File(context.filesDir,"media/$id").deleteRecursively();removed++}
                    states.remove(id)
                }
            }
            for(record in dao.allInspections()){
                val state=states[record.id]
                if(record.deletedAt!=null){
                    if(state!=null&&(state.status!="trash"||record.updatedAt>state.syncedUpdatedAt)){
                        val version=state.version+1
                        api(config,"/api/v1/records/${record.id}/trash","POST",JSONObject().put("version",version).put("updatedAt",Instant.ofEpochMilli(record.updatedAt).toString()))
                        states[record.id]=RecordSyncState(version,record.updatedAt,"trash");trashed++
                    }
                } else if(record.archiveOnly&&state?.status=="trash"&&record.updatedAt>state.syncedUpdatedAt){
                    val version=state.version+1
                    api(config,"/api/v1/records/${record.id}/restore","POST",JSONObject().put("version",version).put("updatedAt",Instant.ofEpochMilli(record.updatedAt).toString()))
                    states[record.id]=RecordSyncState(version,record.updatedAt,"archived")
                } else if(!record.archiveOnly&&(state==null||state.status!="active"||record.updatedAt>state.syncedUpdatedAt)){
                    val version=(state?.version?:0)+1
                    uploadRecord(config,record,version)
                    states[record.id]=RecordSyncState(version,record.updatedAt,"active");uploaded++
                }
            }
            val proof=CloudSecrets.get(context,"sync_password")?.let{sha256Url("safety-ledger-auth-v1|${config.teamCode}|$it".toByteArray())}
            if(config.role=="admin"&&proof!=null)dao.allTombstones().filter{it.entityType=="inspection"}.forEach{
                api(config,"/api/v1/records/${it.entityId}","DELETE",headers=mapOf("x-hard-delete-proof" to proof))
                states.remove(it.entityId)
            }
            api(config,"/api/v1/ack","POST",JSONObject())
            saveStates(states)
            val now=Instant.now().toString()
            saveConfig(config.copy(lastSyncAt=now,lastError="",activeDeviceCount=manifest.optInt("activeDeviceCount",1)))
            CloudSummary(uploaded,downloaded,trashed,removed,conflicts)
        } catch(error:Exception){
            saveConfig(config.copy(lastError=error.message?:"未知错误"))
            throw error
        }
    }

    suspend fun summary():JSONObject?=withContext(Dispatchers.IO){dao.setting("cloud_config")?.value?.let{runCatching{JSONObject(it)}.getOrNull()}}

    suspend fun archiveBefore(cutoff:LocalDate=LocalDate.now().minusMonths(6)):ArchiveSummary=withContext(Dispatchers.IO){
        sync()
        val config=loadConfig();val states=loadStates();var archived=0;var released=0L
        val candidates=dao.allInspections().filter{it.deletedAt==null&&!it.archiveOnly&&runCatching{LocalDate.parse(it.date).isBefore(cutoff)}.getOrDefault(false)}
        for(record in candidates){
            val state=states[record.id]?:continue
            val media=dao.media(record.id);val items=dao.inspectionItems(record.id)
            val file=File(context.cacheDir,"archive/${record.id}.pdf").apply{parentFile?.mkdirs()}
            file.outputStream().use{PdfExporter().export(listOf(PrintableInspection(record,items,media)),it)}
            val bytes=file.readBytes();val pages=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{descriptor->PdfRenderer(descriptor).use{it.pageCount}}
            val blobId="archive:${record.id}:${System.currentTimeMillis().toString(36)}:${UUID.randomUUID().toString().take(6)}"
            uploadBlob(config,blobId,"archive",record.id,"application/pdf",bytes)
            val now=System.currentTimeMillis();val version=state.version+1
            api(config,"/api/v1/records/${record.id}/archive","POST",JSONObject().put("version",version).put("updatedAt",Instant.ofEpochMilli(now).toString())
                .put("archiveBlobId",blobId).put("pageStart",0).put("pageCount",pages))
            released+=media.mapNotNull{it.localPath?.let(::File)?.takeIf(File::exists)?.length()}.sum()
            dao.saveInspection(record.copy(updatedAt=now,archiveOnly=true,archiveBlobId=blobId,archivePageCount=pages))
            dao.deleteInspectionItems(record.id);dao.purgeMedia(record.id);File(context.filesDir,"media/${record.id}").deleteRecursively()
            states[record.id]=RecordSyncState(version,now,"archived");file.delete();archived++
        }
        saveStates(states)
        ArchiveSummary(archived,released)
    }

    suspend fun downloadArchive(record:InspectionEntity):ByteArray=withContext(Dispatchers.IO){
        require(record.archiveOnly&&!record.archiveBlobId.isNullOrBlank()){"这条记录还没有云端PDF归档"}
        val blobId=requireNotNull(record.archiveBlobId)
        downloadBlob(loadConfig(),blobId)
    }

    private suspend fun uploadRecord(config:CloudConfig,record:InspectionEntity,version:Int){
        val payload=serializeRecord(record).toString().toByteArray(Charsets.UTF_8)
        require(payload.size<=32*1024*1024){"单条检查记录（含照片）超过32MB，请减少照片后重试"}
        val blobId="record:${record.id}:${System.currentTimeMillis().toString(36)}:${UUID.randomUUID().toString().take(6)}"
        uploadBlob(config,blobId,"record",record.id,"application/json",payload)
        api(config,"/api/v1/records/${record.id}","PUT",JSONObject()
            .put("date",record.date).put("typeId",record.templateId).put("typeName",record.type)
            .put("version",version).put("updatedAt",Instant.ofEpochMilli(record.updatedAt).toString()).put("payloadBlobId",blobId))
    }

    private suspend fun uploadBlob(config:CloudConfig,blobId:String,kind:String,ownerId:String,mime:String,bytes:ByteArray){
        val chunkSize=320*1024
        val count=maxOf(1,(bytes.size+chunkSize-1)/chunkSize)
        api(config,"/api/v1/blobs","POST",JSONObject().put("blobId",blobId).put("kind",kind).put("ownerId",ownerId)
            .put("mimeType",mime).put("byteLength",bytes.size).put("chunkCount",count).put("sha256",sha256Url(bytes)))
        for(index in 0 until count){
            val start=index*chunkSize;val end=minOf(bytes.size,start+chunkSize)
            val plain=if(bytes.isEmpty())ByteArray(0) else bytes.copyOfRange(start,end)
            api(config,"/api/v1/blobs/${encodePath(blobId)}/chunks/$index","PUT",JSONObject().put("encryptedData",encrypt(plain)))
        }
        api(config,"/api/v1/blobs/${encodePath(blobId)}/complete","POST",JSONObject())
    }

    private suspend fun downloadRecord(config:CloudConfig,meta:JSONObject):Triple<InspectionEntity,List<InspectionItemEntity>,List<MediaEntity>>{
        val blobId=meta.optString("payloadBlobId")
        require(blobId.isNotBlank()){"${meta.optString("date")} 缺少云端记录数据"}
        return deserializeRecord(JSONObject(String(downloadBlob(config,blobId),Charsets.UTF_8)),meta)
    }

    private fun downloadBlob(config:CloudConfig,blobId:String):ByteArray{
        val meta=api(config,"/api/v1/blobs/${encodePath(blobId)}").getJSONObject("blob")
        val output=ArrayList<Byte>()
        for(index in 0 until meta.getInt("chunkCount")){
            val encoded=api(config,"/api/v1/blobs/${encodePath(blobId)}/chunks/$index").getString("encryptedData")
            decrypt(encoded).forEach{output+=it}
        }
        val bytes=output.toByteArray()
        require(bytes.size==meta.getInt("byteLength")&&sha256Url(bytes)==meta.optString("sha256")){"云端文件完整性校验失败"}
        return bytes
    }

    private fun serializeRecord(record:InspectionEntity):JSONObject{
        val items=runBlockingDao{dao.inspectionItems(record.id)}
        val media=runBlockingDao{dao.media(record.id)}
        val standardItems=JSONArray()
        items.forEachIndexed{index,item->standardItems.put(JSONObject().put("sequence",index+1).put("category",item.category).put("standard",item.standard)
            .put("result",when(item.result){ItemResult.FAIL->"no";ItemResult.NA->"na";ItemResult.PASS->"yes"}).put("androidResult",item.result.name).put("issue",item.problem))}
        fun photoJson(value:MediaEntity)=JSONObject().put("id",value.id).put("data",fileData(value.localPath)).put("capturedAt",Instant.ofEpochMilli(value.capturedAt).toString())
            .put("location",record.location).put("latitude",value.latitude).put("longitude",value.longitude)
        val inspectionPhotos=JSONArray();val rectificationPhotos=JSONArray();val signatures=JSONObject().put("inspector1","").put("inspector2","").put("inspected","")
        media.forEach{value->when(value.kind){
            MediaKind.SITE,MediaKind.PROBLEM->inspectionPhotos.put(photoJson(value))
            MediaKind.RECTIFICATION,MediaKind.REVIEW->rectificationPhotos.put(photoJson(value))
            MediaKind.SIGNATURE_INSPECTOR_1->signatures.put("inspector1",fileData(value.localPath))
            MediaKind.SIGNATURE_INSPECTOR_2->signatures.put("inspector2",fileData(value.localPath))
            MediaKind.SIGNATURE_INSPECTEE->signatures.put("inspected",fileData(value.localPath))
        }}
        val androidItems=JSONArray();items.forEach{androidItems.put(JSONObject().put("id",it.id).put("templateItemId",it.templateItemId).put("category",it.category)
            .put("content",it.content).put("standard",it.standard).put("result",it.result.name).put("problem",it.problem).put("updatedAt",it.updatedAt))}
        return JSONObject().put("id",record.id).put("inspectionTypeId",record.templateId).put("inspectionTypeName",record.type)
            .put("date",record.date).put("location",record.location).put("items",standardItems).put("inspectionPhotos",inspectionPhotos)
            .put("signatures",signatures).put("rectification",JSONObject().put("opinion",record.rectificationAdvice).put("photos",rectificationPhotos)
                .put("completed",record.status==RecordStatus.RECTIFIED||record.status==RecordStatus.COMPLETE).put("completedAt",if(record.status==RecordStatus.RECTIFIED||record.status==RecordStatus.COMPLETE)Instant.ofEpochMilli(record.updatedAt).toString() else ""))
            .put("createdAt",Instant.ofEpochMilli(record.updatedAt).toString()).put("updatedAt",Instant.ofEpochMilli(record.updatedAt).toString())
            .put("android",JSONObject().put("schema",1).put("time",record.time).put("unit",record.unit).put("dutyOfficer",record.dutyOfficer)
                .put("inspector1",record.inspector1).put("inspector2",record.inspector2).put("inspectee",record.inspectee).put("conclusion",record.conclusion)
                .put("rectificationAdvice",record.rectificationAdvice).put("responsiblePerson",record.responsiblePerson).put("deadline",record.deadline)
                .put("rectificationDetail",record.rectificationDetail).put("reviewResult",record.reviewResult).put("status",record.status.name).put("items",androidItems))
    }

    private fun deserializeRecord(json:JSONObject,meta:JSONObject):Triple<InspectionEntity,List<InspectionItemEntity>,List<MediaEntity>>{
        val id=meta.getString("id");val android=json.optJSONObject("android")
        val updated=parseTime(meta.opt("updatedAt"))?:System.currentTimeMillis()
        val typeId=json.optString("inspectionTypeId",meta.optString("typeId","default"))
        val typeName=json.optString("inspectionTypeName",meta.optString("typeName","检查记录"))
        val rectification=json.optJSONObject("rectification")?:JSONObject()
        val record=InspectionEntity(id,typeId,meta.optString("date",json.optString("date")),android?.optString("time","00:00")?:"00:00",typeName,
            android?.optString("unit","")?:"",json.optString("location"),android?.optString("dutyOfficer","")?:"",
            android?.optString("inspector1","")?:"",android?.optString("inspector2","")?:"",android?.optString("inspectee","")?:"",
            android?.optString("conclusion","")?:"",android?.optString("rectificationAdvice",rectification.optString("opinion"))?:rectification.optString("opinion"),
            android?.optString("responsiblePerson","")?:"",android?.optString("deadline","")?:"",android?.optString("rectificationDetail","")?:"",
            android?.optString("reviewResult","")?:"",runCatching{RecordStatus.valueOf(android?.optString("status")?:if(rectification.optBoolean("completed"))"COMPLETE" else "PENDING")}.getOrDefault(RecordStatus.PENDING),
            updated,null,false,null,0)
        val sourceItems=android?.optJSONArray("items")?:json.optJSONArray("items")?:JSONArray()
        val items=mutableListOf<InspectionItemEntity>()
        for(index in 0 until sourceItems.length()){
            val value=sourceItems.getJSONObject(index);val resultText=value.optString("result",value.optString("androidResult"))
            val result=runCatching{ItemResult.valueOf(value.optString("androidResult",resultText))}.getOrElse{if(resultText=="no")ItemResult.FAIL else if(resultText=="yes")ItemResult.PASS else ItemResult.NA}
            items+=InspectionItemEntity(value.optString("id",UUID.randomUUID().toString()),id,value.optString("templateItemId","remote-$index"),
                value.optString("category","检查项目${index+1}"),value.optString("content",value.optString("standard")),value.optString("standard"),result,
                value.optString("problem",value.optString("issue")),value.optLong("updatedAt",updated))
        }
        val media=mutableListOf<MediaEntity>()
        decodePhotoArray(id,json.optJSONArray("inspectionPhotos"),MediaKind.SITE,media)
        decodePhotoArray(id,rectification.optJSONArray("photos"),MediaKind.RECTIFICATION,media)
        val signatures=json.optJSONObject("signatures")
        listOf("inspector1" to MediaKind.SIGNATURE_INSPECTOR_1,"inspector2" to MediaKind.SIGNATURE_INSPECTOR_2,"inspected" to MediaKind.SIGNATURE_INSPECTEE).forEach{(key,kind)->
            signatures?.optString(key)?.takeIf{it.startsWith("data:")}?.let{data->decodeMedia(id,UUID.randomUUID().toString(),kind,data,updated,null,null)?.let(media::add)}
        }
        return Triple(record,items,media)
    }

    private fun decodePhotoArray(inspectionId:String,array:JSONArray?,kind:MediaKind,target:MutableList<MediaEntity>){
        if(array==null)return
        for(index in 0 until array.length()){val value=array.optJSONObject(index)?:continue;decodeMedia(inspectionId,value.optString("id",UUID.randomUUID().toString()),kind,value.optString("data"),
            parseTime(value.opt("capturedAt"))?:System.currentTimeMillis(),value.optDoubleOrNull("latitude"),value.optDoubleOrNull("longitude"))?.let(target::add)}
    }

    private fun decodeMedia(inspectionId:String,id:String,kind:MediaKind,data:String,capturedAt:Long,latitude:Double?,longitude:Double?):MediaEntity?{
        val comma=data.indexOf(',');if(!data.startsWith("data:")||comma<0)return null
        val bytes=runCatching{Base64.decode(data.substring(comma+1),Base64.DEFAULT)}.getOrNull()?:return null
        val ext=if(data.substring(0,comma).contains("png"))"png" else "jpg"
        val dir=File(context.filesDir,"media/$inspectionId").apply{mkdirs()};val file=File(dir,"remote-$id.$ext");file.writeBytes(bytes)
        return MediaEntity(id,inspectionId,kind=kind,localPath=file.absolutePath,sha256=sha256Hex(bytes),capturedAt=capturedAt,latitude=latitude,longitude=longitude)
    }

    private suspend fun duplicateConflict(record:InspectionEntity){
        val newId=UUID.randomUUID().toString();val now=System.currentTimeMillis()
        val newRecord=record.copy(id=newId,conclusion=(record.conclusion+"（云同步冲突副本）").trim(),updatedAt=now,deletedAt=null)
        val items=dao.inspectionItems(record.id).map{it.copy(id=UUID.randomUUID().toString(),inspectionId=newId,updatedAt=now)}
        val media=dao.media(record.id).map{source->
            val old=source.localPath?.let(::File);val target=old?.takeIf{it.exists()}?.let{File(context.filesDir,"media/$newId/${UUID.randomUUID()}.${it.extension.ifBlank{"jpg"}}").apply{parentFile?.mkdirs();it.copyTo(this,true)}}
            source.copy(id=UUID.randomUUID().toString(),inspectionId=newId,localPath=target?.absolutePath,remoteKey=null,updatedAt=now)
        }
        dao.replaceInspection(newRecord,items,media)
    }

    private fun fileData(path:String?):String{
        val file=path?.let(::File)?.takeIf{it.exists()}?:return ""
        val mime=if(file.extension.equals("png",true))"image/png" else "image/jpeg"
        return "data:$mime;base64,${Base64.encodeToString(file.readBytes(),Base64.NO_WRAP)}"
    }

    private fun encrypt(bytes:ByteArray):String{
        val key=decodeUrl(requireNotNull(CloudSecrets.get(context,"encryption_key")){"云同步密钥不存在，请重新连接"})
        val iv=ByteArray(12).also{SecureRandom().nextBytes(it)};val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv))
        return encodeUrl(iv+cipher.doFinal(bytes))
    }
    private fun decrypt(encoded:String):ByteArray{
        val combined=decodeUrl(encoded);require(combined.size>=29){"加密分片损坏"}
        val key=decodeUrl(requireNotNull(CloudSecrets.get(context,"encryption_key")){"云同步密钥不存在，请重新连接"})
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,combined.copyOfRange(0,12)))
        return cipher.doFinal(combined.copyOfRange(12,combined.size))
    }

    private fun loadConfig():CloudConfig{
        val json=daoBlocking{dao.setting("cloud_config")}?.value?.let(::JSONObject)?:error("尚未设置云同步")
        require(!CloudSecrets.get(context,"device_token").isNullOrBlank()&&!CloudSecrets.get(context,"encryption_key").isNullOrBlank()){ "云同步授权已失效，请重新连接" }
        return CloudConfig(json.getString("endpoint"),json.optString("provider","cloudflare"),json.optString("space","安全检查台账"),json.optString("device","安卓手机"),
            json.getString("teamCode"),json.optString("role","member"),json.optString("lastSyncAt"),json.optString("lastError"),json.optInt("activeDeviceCount",1))
    }
    private fun saveConfig(config:CloudConfig){daoBlocking{dao.saveSetting(SettingEntity("cloud_config",JSONObject().put("provider",config.provider).put("endpoint",config.endpoint).put("space",config.space)
        .put("device",config.deviceName).put("teamCode",config.teamCode).put("role",config.role).put("lastSyncAt",config.lastSyncAt).put("lastError",config.lastError).put("activeDeviceCount",config.activeDeviceCount).toString()))}}
    private fun loadStates():MutableMap<String,RecordSyncState>{
        val raw=daoBlocking{dao.setting("cloud_sync_state")}?.value?:return mutableMapOf();val records=runCatching{JSONObject(raw).optJSONObject("records")}.getOrNull()?:return mutableMapOf()
        return records.keys().asSequence().associateWith{key->records.getJSONObject(key).let{RecordSyncState(it.optInt("version"),it.optLong("syncedUpdatedAt"),it.optString("status","active"))}}.toMutableMap()
    }
    private fun saveStates(states:Map<String,RecordSyncState>){val records=JSONObject();states.forEach{(id,state)->records.put(id,JSONObject().put("version",state.version).put("syncedUpdatedAt",state.syncedUpdatedAt).put("status",state.status))}
        daoBlocking{dao.saveSetting(SettingEntity("cloud_sync_state",JSONObject().put("records",records).toString()))}}

    private fun api(config:CloudConfig,path:String,method:String="GET",body:JSONObject?=null,headers:Map<String,String> = emptyMap()):JSONObject{
        val token=requireNotNull(CloudSecrets.get(context,"device_token")){"设备授权不存在"};val device=requireNotNull(CloudSecrets.get(context,"device_id")){"设备编号不存在"}
        return request(config.endpoint,path,method,body,mapOf("authorization" to "Bearer $token","x-team-code" to config.teamCode,"x-device-id" to device)+headers)
    }
    private fun publicRequest(endpoint:String,path:String,method:String="GET",body:JSONObject?=null)=request(endpoint,path,method,body)
    private fun request(endpoint:String,path:String,method:String,body:JSONObject?,headers:Map<String,String> = emptyMap()):JSONObject{
        val builder=Request.Builder().url(endpoint.trimEnd('/')+path);headers.forEach{(key,value)->builder.header(key,value)}
        if(body!=null)builder.header("content-type","application/json")
        val requestBody=body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        when(method){"POST"->builder.post(requestBody?:ByteArray(0).toRequestBody());"PUT"->builder.put(requestBody?:ByteArray(0).toRequestBody());"DELETE"->builder.delete(requestBody);else->builder.get()}
        client.newCall(builder.build()).execute().use{response->val text=response.body?.string().orEmpty();val json=runCatching{JSONObject(text)}.getOrElse{JSONObject()}
            if(!response.isSuccessful||json.optBoolean("ok",true).not())throw CloudApiException(response.code,json.optString("error","云服务返回 HTTP ${response.code}"));return json}
    }

    private fun normalizeEndpoint(value:String):String{val endpoint=value.trim().trimEnd('/');require(endpoint.startsWith("https://")||endpoint.startsWith("http://")){"地址必须以 http:// 或 https:// 开头"};return endpoint}
    private fun workspaceCode(name:String):String{val alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";val digest=MessageDigest.getInstance("SHA-256").digest("safety-ledger-workspace-v1|${name.trim().lowercase()}".toByteArray());return (0 until 8).joinToString(""){alphabet[(digest[it].toInt() and 255)%alphabet.length].toString()}}
    private fun deriveKey(password:String,salt:ByteArray)=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(),salt,180000,256)).encoded
    private fun sha256Url(bytes:ByteArray)=encodeUrl(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun sha256Hex(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun encodeUrl(bytes:ByteArray)=Base64.encodeToString(bytes,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decodeUrl(value:String)=Base64.decode(value,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun encodePath(value:String)=java.net.URLEncoder.encode(value,"UTF-8").replace("+","%20")
    private fun parseTime(value:Any?):Long?=when(value){is Number->value.toLong();is String->runCatching{Instant.parse(value).toEpochMilli()}.getOrNull();else->null}
    private fun JSONObject.optDoubleOrNull(key:String)=if(has(key)&&!isNull(key))optDouble(key) else null

    private fun <T> daoBlocking(block:suspend()->T):T=kotlinx.coroutines.runBlocking{block()}
    private fun <T> runBlockingDao(block:suspend()->T):T=kotlinx.coroutines.runBlocking{block()}
}
