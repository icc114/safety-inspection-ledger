package cn.safetyledger.app.data

import androidx.room.*

enum class ItemResult { PASS, FAIL, NA }
enum class RecordStatus { PENDING, RECTIFYING, RECTIFIED, COMPLETE }
enum class MediaKind { SITE, PROBLEM, RECTIFICATION, REVIEW, SIGNATURE_INSPECTOR_1, SIGNATURE_INSPECTOR_2, SIGNATURE_INSPECTEE }

@Entity(tableName="templates") data class TemplateEntity(@PrimaryKey val id:String, val name:String, val category:String, val active:Boolean=true, val updatedAt:Long=System.currentTimeMillis(), val deletedAt:Long?=null)
@Entity(tableName="template_items", foreignKeys=[ForeignKey(entity=TemplateEntity::class,parentColumns=["id"],childColumns=["templateId"],onDelete=ForeignKey.CASCADE)], indices=[Index("templateId")]) data class TemplateItemEntity(@PrimaryKey val id:String,val templateId:String,val category:String,val content:String,val standard:String,val position:Int,val updatedAt:Long=System.currentTimeMillis())
@Entity(tableName="inspections", indices=[Index("date"),Index("templateId")]) data class InspectionEntity(@PrimaryKey val id:String,val templateId:String,val date:String,val time:String,val type:String,val unit:String,val location:String,val dutyOfficer:String,val inspector1:String,val inspector2:String,val inspectee:String,val conclusion:String,val rectificationAdvice:String,val responsiblePerson:String,val deadline:String,val rectificationDetail:String="",val reviewResult:String="",val status:RecordStatus=RecordStatus.COMPLETE,val updatedAt:Long=System.currentTimeMillis(),val deletedAt:Long?=null,val archiveOnly:Boolean=false,val archiveBlobId:String?=null,val archivePageCount:Int=0)
@Entity(tableName="inspection_items",indices=[Index("inspectionId")]) data class InspectionItemEntity(@PrimaryKey val id:String,val inspectionId:String,val templateItemId:String,val category:String,val content:String,val standard:String,val result:ItemResult,val problem:String="",val updatedAt:Long=System.currentTimeMillis())
@Entity(tableName="media",indices=[Index("inspectionId")]) data class MediaEntity(@PrimaryKey val id:String,val inspectionId:String,val itemId:String?=null,val kind:MediaKind,val localPath:String?,val remoteKey:String?=null,val sha256:String,val capturedAt:Long,val latitude:Double?=null,val longitude:Double?=null,val updatedAt:Long=System.currentTimeMillis(),val deletedAt:Long?=null)
@Entity(tableName="tombstones",indices=[Index(value=["entityType","entityId"],unique=true)]) data class TombstoneEntity(@PrimaryKey val id:String,val entityType:String,val entityId:String,val deletedAt:Long,val deviceId:String)
@Entity(tableName="sync_queue",indices=[Index("state")]) data class SyncQueueEntity(@PrimaryKey val id:String,val entityType:String,val entityId:String,val operation:String,val state:String="PENDING",val attempts:Int=0,val nextAttemptAt:Long=0,val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="settings") data class SettingEntity(@PrimaryKey val key:String,val value:String,val updatedAt:Long=System.currentTimeMillis())
