package cn.safetyledger.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface LedgerDao {
 @Query("SELECT COUNT(*) FROM templates WHERE deletedAt IS NULL") suspend fun templateCount():Int
 @Query("SELECT * FROM templates WHERE deletedAt IS NULL ORDER BY active DESC,name") fun templates():Flow<List<TemplateEntity>>
 @Query("SELECT * FROM template_items WHERE templateId=:id ORDER BY position") suspend fun templateItems(id:String):List<TemplateItemEntity>
 @Query("SELECT * FROM template_items ORDER BY templateId,position") suspend fun allTemplateItems():List<TemplateItemEntity>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveTemplate(value:TemplateEntity)
 @Query("UPDATE templates SET deletedAt=:now,active=0,updatedAt=:now WHERE id=:id") suspend fun deleteTemplate(id:String,now:Long=System.currentTimeMillis())
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveTemplateItems(value:List<TemplateItemEntity>)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveTemplateItem(value:TemplateItemEntity)
 @Query("DELETE FROM template_items WHERE id=:id") suspend fun deleteTemplateItem(id:String)
 @Query("UPDATE template_items SET position=:position,updatedAt=:now WHERE id=:id") suspend fun moveTemplateItem(id:String,position:Int,now:Long=System.currentTimeMillis())
 @Query("SELECT * FROM inspections WHERE deletedAt IS NULL ORDER BY date DESC,time DESC") fun inspections():Flow<List<InspectionEntity>>
 @Query("SELECT * FROM inspections ORDER BY date DESC,time DESC") suspend fun allInspections():List<InspectionEntity>
 @Query("SELECT * FROM inspection_items WHERE inspectionId=:id ORDER BY rowid") suspend fun inspectionItems(id:String):List<InspectionItemEntity>
 @Query("SELECT * FROM inspections WHERE id=:id LIMIT 1") suspend fun inspection(id:String):InspectionEntity?
 @Query("SELECT * FROM media WHERE inspectionId=:id AND deletedAt IS NULL ORDER BY capturedAt") suspend fun media(id:String):List<MediaEntity>
 @Query("SELECT * FROM media ORDER BY capturedAt") suspend fun allMedia():List<MediaEntity>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveInspection(value:InspectionEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveInspectionItems(value:List<InspectionItemEntity>)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveMedia(value:MediaEntity)
 @Query("UPDATE inspections SET deletedAt=:now,updatedAt=:now WHERE id=:id") suspend fun trash(id:String,now:Long)
 @Query("UPDATE inspections SET deletedAt=NULL,updatedAt=:now WHERE id=:id") suspend fun restore(id:String,now:Long)
 @Query("SELECT * FROM inspections WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC") fun trash():Flow<List<InspectionEntity>>
 @Insert suspend fun tombstone(value:TombstoneEntity)
 @Query("SELECT * FROM tombstones ORDER BY deletedAt") suspend fun allTombstones():List<TombstoneEntity>
 @Query("DELETE FROM inspection_items WHERE inspectionId=:id") suspend fun deleteInspectionItems(id:String)
 @Query("DELETE FROM inspections WHERE id=:id") suspend fun purgeRecord(id:String)
 @Query("DELETE FROM inspection_items WHERE inspectionId=:id") suspend fun purgeItems(id:String)
 @Query("DELETE FROM media WHERE inspectionId=:id") suspend fun purgeMedia(id:String)
 @Transaction suspend fun purge(id:String){purgeItems(id);purgeMedia(id);purgeRecord(id)}
 @Transaction suspend fun replaceInspection(record:InspectionEntity,items:List<InspectionItemEntity>,media:List<MediaEntity>){saveInspection(record);deleteInspectionItems(record.id);purgeMedia(record.id);if(items.isNotEmpty())saveInspectionItems(items);media.forEach{saveMedia(it)}}
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveSetting(value:SettingEntity)
 @Query("SELECT * FROM settings WHERE `key`=:key LIMIT 1") suspend fun setting(key:String):SettingEntity?
}

@Database(entities=[TemplateEntity::class,TemplateItemEntity::class,InspectionEntity::class,InspectionItemEntity::class,MediaEntity::class,TombstoneEntity::class,SyncQueueEntity::class,SettingEntity::class],version=1,exportSchema=true)
@TypeConverters(Converters::class) abstract class AppDatabase:RoomDatabase(){ abstract fun dao():LedgerDao
 companion object { @Volatile private var instance:AppDatabase?=null; fun get(c:Context)=instance?:synchronized(this){instance?:Room.databaseBuilder(c,AppDatabase::class.java,"safety-ledger-v1.db").build().also{instance=it}};fun closeForRestore(){synchronized(this){instance?.close();instance=null}} }
}
class Converters { @TypeConverter fun result(v:String)=ItemResult.valueOf(v); @TypeConverter fun result(v:ItemResult)=v.name; @TypeConverter fun status(v:String)=RecordStatus.valueOf(v); @TypeConverter fun status(v:RecordStatus)=v.name; @TypeConverter fun media(v:String)=MediaKind.valueOf(v); @TypeConverter fun media(v:MediaKind)=v.name }
