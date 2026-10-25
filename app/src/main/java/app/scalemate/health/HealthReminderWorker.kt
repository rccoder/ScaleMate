package app.scalemate.health

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.*
import app.scalemate.BuildConfig
import app.scalemate.MainActivity
import app.scalemate.R
import app.scalemate.data.Store
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object HealthReminders {
    private const val WORK="health-coach-local-rules"
    fun schedule(context:Context,enabled:Boolean) {
        val wm=WorkManager.getInstance(context)
        if(BuildConfig.HEALTH_AI_ENABLED&&enabled)wm.enqueueUniquePeriodicWork(WORK,ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<HealthReminderWorker>(1,TimeUnit.HOURS).build())
        else {wm.cancelUniqueWork(WORK);(context.getSystemService(NotificationManager::class.java)).cancel(7401)}
    }
    fun notify(context:Context,r:HealthReminder,showDetails:Boolean) {
        if(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("health-coach",context.getString(R.string.health_notification_channel),NotificationManager.IMPORTANCE_DEFAULT).apply {lockscreenVisibility=Notification.VISIBILITY_PRIVATE})
        val pending=PendingIntent.getActivity(context,7401,Intent(context,MainActivity::class.java).putExtra("healthReminderId",r.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public=Notification.Builder(context,"health-coach").setSmallIcon(R.drawable.ic_scale).setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.health_notification_private)).build()
        val body=if(showDetails)r.topics.joinToString(" · "){topic->context.getString(topicResource(topic))} else context.getString(R.string.health_notification_private)
        manager.notify(7401,Notification.Builder(context,"health-coach").setSmallIcon(R.drawable.ic_scale).setContentTitle(context.getString(R.string.app_name)).setContentText(body).setContentIntent(pending).setAutoCancel(true).setVisibility(if(showDetails)Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE).setPublicVersion(public).build())
    }
    fun topicResource(topic:String)=when(topic){"sleep"->R.string.health_reminder_sleep;"recovery"->R.string.health_reminder_recovery;"activity"->R.string.health_reminder_activity;else->R.string.health_reminder_bedtime}
}

class HealthReminderWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        if(!BuildConfig.HEALTH_AI_ENABLED)return Result.success()
        val db=Store(applicationContext)
        try {
            val store=HealthStore(db);val settings=store.reminderSettings()
            if(!settings.enabled)return Result.success()
            val now=System.currentTimeMillis();val zone=ZoneId.systemDefault();val local=Instant.ofEpochMilli(now).atZone(zone)
            val history=store.reminders()
            if(history.any {Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate()==local.toLocalDate()})return Result.success()
            val reader=HealthReader(applicationContext)
            var snapshot:HealthSnapshot?=null
            val minute=local.hour*60+local.minute
            if(minute>=settings.checkMinute&&reader.granted(HealthCatalog.BACKGROUND)&&!HealthReminderPolicy.quiet(minute,settings.quietStart,settings.quietEnd)) {
                val needed=buildSet {if(settings.sleep||settings.recovery)add("SleepSession");if(settings.recovery){add("HeartRate");add("HeartRateVariabilityRmssd")};if(settings.activity)add("Steps")}.intersect(store.categories())
                val last=store.setting("lastBackgroundCheck").toLongOrNull() ?: 0L
                if(needed.isNotEmpty()&&now-last>=45*60000) {
                    snapshot=reader.read(HealthWindow.recent(7),needed,store.sources());store.snapshot(snapshot)
                    store.setting("lastBackgroundCheck",now.toString())
                }
            }
            val event=HealthReminderPolicy.evaluate(snapshot,settings,store.goals(),history,now,zone)
            // The unique event row makes repeated workers and process restarts idempotent.
            if(event!=null&&store.reminderSettings().enabled&&store.reminder(event))HealthReminders.notify(applicationContext,event,settings.showDetails)
            store.prune(now)
            return Result.success()
        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){return Result.retry()}
        finally {db.close()}
    }
}
