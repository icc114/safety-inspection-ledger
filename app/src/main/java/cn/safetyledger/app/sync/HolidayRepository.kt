package cn.safetyledger.app.sync

import android.content.Context
import cn.safetyledger.app.data.AppDatabase
import cn.safetyledger.app.data.LedgerDao
import cn.safetyledger.app.data.SettingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.Year
import java.util.concurrent.TimeUnit

data class HolidayMeta(val name:String,val isWork:Boolean)

class HolidayRepository(context:Context,private val dao:LedgerDao=AppDatabase.get(context).dao()){
    private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).build()
    suspend fun refresh(year:Int)=withContext(Dispatchers.IO){
        require(year in 2004..2100){"年份超出范围"}
        val config=dao.setting("cloud_config")?.value?.let(::JSONObject)?:error("请先设置云同步服务")
        val endpoint=config.getString("endpoint").trimEnd('/')
        client.newCall(Request.Builder().url("$endpoint/api/v1/holidays/$year").get().build()).execute().use{response->
            val json=runCatching{JSONObject(response.body?.string().orEmpty())}.getOrElse{JSONObject()}
            if(!response.isSuccessful||!json.optBoolean("ok"))error(json.optString("error","节假日服务返回 HTTP ${response.code}"))
            dao.saveSetting(SettingEntity("holiday_$year",JSONObject().put("data",json.optJSONObject("data")?:JSONObject())
                .put("fetchedAt",json.optString("fetchedAt")).put("source",json.optString("source")).toString()))
        }
    }
    suspend fun meta(date:LocalDate):HolidayMeta?=withContext(Dispatchers.IO){
        val cached=dao.setting("holiday_${date.year}")?.value?.let{runCatching{JSONObject(it)}.getOrNull()}?:return@withContext null
        val data=cached.optJSONObject("data")?:return@withContext null;val key=date.toString()
        val work=data.optJSONObject("workdays")?.optString(key)?.takeIf{it.isNotBlank()}
        val holiday=data.optJSONObject("holidays")?.optString(key)?.takeIf{it.isNotBlank()}
        val raw=work?:holiday?:return@withContext null;val parts=raw.split(',')
        HolidayMeta(parts.getOrNull(1)?.ifBlank{parts[0]}?:parts[0],work!=null)
    }
    suspend fun cachedYear(year:Int)=withContext(Dispatchers.IO){dao.setting("holiday_$year")?.value?.let{runCatching{JSONObject(it)}.getOrNull()}}
}
