package cn.safetyledger.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class CloudSyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=runCatching{CloudSyncEngine(applicationContext).sync();Result.success()}.getOrElse{error->
        showSyncFailure(applicationContext,"云同步失败：${error.message}；本机记录已保留")
        if(runAttemptCount<5)Result.retry() else Result.failure()
    }
}

object CloudSyncScheduler {
    private fun constraints()=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(context:Context){
        val periodic=PeriodicWorkRequestBuilder<CloudSyncWorker>(15,TimeUnit.MINUTES).setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("cloud-auto-sync",ExistingPeriodicWorkPolicy.UPDATE,periodic)
        enqueue(context)
    }
    fun enqueue(context:Context){
        val request=OneTimeWorkRequestBuilder<CloudSyncWorker>().setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("cloud-sync-now",ExistingWorkPolicy.REPLACE,request)
    }
}

fun showSyncFailure(context:Context,message:String){
    val channel="cloud-sync";val manager=context.getSystemService(NotificationManager::class.java)
    if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel(channel,"云同步提醒",NotificationManager.IMPORTANCE_HIGH))
    if(Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
        NotificationManagerCompat.from(context).notify(3101,NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("安全检查台账同步失败").setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build())
}
