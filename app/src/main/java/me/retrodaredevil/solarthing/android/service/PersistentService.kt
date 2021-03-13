package me.retrodaredevil.solarthing.android.service

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.AsyncTask
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.widget.Toast
import me.retrodaredevil.solarthing.SolarThingConstants
import me.retrodaredevil.solarthing.android.*
import me.retrodaredevil.solarthing.android.activity.ConnectionSettingsActivity
import me.retrodaredevil.solarthing.android.notifications.NotificationChannels
import me.retrodaredevil.solarthing.android.notifications.PERSISTENT_NOTIFICATION_ID
import me.retrodaredevil.solarthing.android.notifications.getGroup
import me.retrodaredevil.solarthing.android.prefs.*
import me.retrodaredevil.solarthing.android.request.CouchDbDataRequester
import me.retrodaredevil.solarthing.android.request.DataRequest
import me.retrodaredevil.solarthing.android.request.DataRequester
import me.retrodaredevil.solarthing.android.request.DataRequesterMultiplexer
import me.retrodaredevil.solarthing.android.util.SSIDNotAvailable
import me.retrodaredevil.solarthing.android.util.SSIDPermissionException
import me.retrodaredevil.solarthing.android.util.createDefaultObjectMapper
import me.retrodaredevil.solarthing.android.util.getSSID
import me.retrodaredevil.solarthing.commands.packets.status.CommandStatusPacket
import me.retrodaredevil.solarthing.misc.device.DevicePacket
import me.retrodaredevil.solarthing.packets.collection.parsing.ObjectMapperPacketConverter
import me.retrodaredevil.solarthing.packets.collection.parsing.PacketGroupParser
import me.retrodaredevil.solarthing.packets.collection.parsing.PacketParserMultiplexer
import me.retrodaredevil.solarthing.packets.collection.parsing.SimplePacketGroupParser
import me.retrodaredevil.solarthing.packets.instance.InstancePacket
import me.retrodaredevil.solarthing.solar.SolarStatusPacket
import me.retrodaredevil.solarthing.solar.event.SolarEventPacket
import me.retrodaredevil.solarthing.solar.extra.SolarExtraPacket
import me.retrodaredevil.solarthing.solar.outback.command.packets.MateCommandFeedbackPacket
import me.retrodaredevil.solarthing.util.JacksonUtil
import java.util.*


fun restartService(context: Context){
    val serviceIntent = Intent(context, PersistentService::class.java)
    context.stopService(serviceIntent)
    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
    } else {
        context.startService(serviceIntent)
    }
}
fun startServiceIfNotRunning(context: Context){
    if(isServiceRunning(context, PersistentService::class.java)){
        return
    }
    val serviceIntent = Intent(context, PersistentService::class.java)
    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
    } else {
        context.startService(serviceIntent)
    }
}
fun stopService(context: Context){
    val serviceIntent = Intent(context, PersistentService::class.java)
    context.stopService(serviceIntent)
}
private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    return manager.getRunningServices(Int.MAX_VALUE).any { serviceClass.name == it.service.className }
}
private const val STOP_SERVICE_ACTION = "me.retrodaredevil.solarthing.android.service.action.stop_service"
private const val RELOAD_SERVICE_ACTION = "me.retrodaredevil.solarthing.android.service.action.reload_service"
private const val RESTART_SERVICE_ACTION = "me.retrodaredevil.solarthing.android.service.action.restart_service"

private class ServiceObject(
        val dataService: DataService,
        val databaseName: String,
        val packetGroupParser: PacketGroupParser
){
    var task: AsyncTask<*, *, *>? = null
}

class PersistentService : Service(), Runnable{
    companion object {
        private val MAPPER = createDefaultObjectMapper().apply {
            JacksonUtil.lenientMapper(this)
        }
    }
    private var initialized = false
    private lateinit var handler: Handler
    private lateinit var connectionProfileManager: ProfileManager<ConnectionProfile>
    private lateinit var solarProfileManager: ProfileManager<SolarProfile>
    private lateinit var miscProfileProvider: ProfileProvider<MiscProfile>

    private lateinit var services: List<ServiceObject>

    private var metaUpdaterTask: AsyncTask<*, *, *>? = null
    private val metaHandler = MetaHandler()

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("ShowToast")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler = Handler()
        connectionProfileManager = createConnectionProfileManager(this)
        solarProfileManager = createSolarProfileManager(this)
        miscProfileProvider = createMiscProfileProvider(this)
        val solarStatusData = PacketGroupData()
        val solarEventData = PacketGroupData()
        val application = application as SolarThingApplication
        application.solarStatusData = solarStatusData
        application.solarEventData = solarEventData
        application.metaHandler = metaHandler
        services = listOf(
                ServiceObject(
                        SolarStatusService(this, connectionProfileManager, solarProfileManager, createMiscProfileProvider(this), solarStatusData, solarEventData, metaHandler), SolarThingConstants.SOLAR_STATUS_UNIQUE_NAME,
                        SimplePacketGroupParser(PacketParserMultiplexer(listOf(
                                ObjectMapperPacketConverter(MAPPER, SolarStatusPacket::class.java),
                                ObjectMapperPacketConverter(MAPPER, SolarExtraPacket::class.java),
                                ObjectMapperPacketConverter(MAPPER, InstancePacket::class.java),
                                ObjectMapperPacketConverter(MAPPER, DevicePacket::class.java),
                                ObjectMapperPacketConverter(MAPPER, CommandStatusPacket::class.java),
                        ), PacketParserMultiplexer.LenientType.FULLY_LENIENT))
                ),
                ServiceObject(SolarEventService(this, solarEventData), SolarThingConstants.SOLAR_EVENT_UNIQUE_NAME, SimplePacketGroupParser(PacketParserMultiplexer(listOf(
                        ObjectMapperPacketConverter(MAPPER, MateCommandFeedbackPacket::class.java),
                        ObjectMapperPacketConverter(MAPPER, SolarEventPacket::class.java),
                        ObjectMapperPacketConverter(MAPPER, InstancePacket::class.java),
                ), PacketParserMultiplexer.LenientType.FULLY_LENIENT))),
        )
        for(service in services){
            service.dataService.onInit()
        }
        handler.postDelayed(this, 300)
        Toast.makeText(this, "SolarThing Notification Service started", Toast.LENGTH_LONG).show()
        println("Starting service")
        updateNotification(System.currentTimeMillis() + 300)
        initialized = true
        return START_STICKY
    }
    private fun updateNotification(countDownWhen: Long){
        // unanswered question with problem we're having here: https://stackoverflow.com/questions/47703216/android-clicking-grouped-notifications-restarts-app
        val mainActivityIntent = Intent(this, ConnectionSettingsActivity::class.java) // TODO go to different main activity
        val builder = getBuilder()
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.sun)
                .setContentTitle("SolarThing service is running")
                .setContentText("${services.count { it.dataService.shouldUpdate }} service(s) are running")
                .setContentIntent(PendingIntent.getActivity(this, 0, mainActivityIntent, 0))
                .setWhen(1) // make it the lowest priority
                .setShowWhen(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            builder.setGroup(getGroup(PERSISTENT_NOTIFICATION_ID))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_SERVICE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // countdown
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(true)
            builder.setWhen(countDownWhen)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // buttons
            builder.addAction(
                    Notification.Action.Builder(
                            Icon.createWithResource(this, R.drawable.sun),
                             "Stop",
                            PendingIntent.getBroadcast(
                                     this, 0,
                                    Intent(STOP_SERVICE_ACTION),
                                    PendingIntent.FLAG_CANCEL_CURRENT
                            )
                    ).build()
            )
            builder.addAction(
                    Notification.Action.Builder(
                            Icon.createWithResource(this, R.drawable.sun),
                            "Reload",
                            PendingIntent.getBroadcast(
                                    this, 0,
                                    Intent(RELOAD_SERVICE_ACTION),
                                    PendingIntent.FLAG_CANCEL_CURRENT
                            )
                    ).build()
            )
            builder.addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.sun),
                        "Restart",
                        PendingIntent.getBroadcast(
                                this, 0,
                                Intent(RESTART_SERVICE_ACTION),
                                PendingIntent.FLAG_CANCEL_CURRENT
                        )
                    ).build()
            )
            val intentFilter = IntentFilter()
            intentFilter.addAction(STOP_SERVICE_ACTION)
            intentFilter.addAction(RELOAD_SERVICE_ACTION)
            intentFilter.addAction(RESTART_SERVICE_ACTION)
            registerReceiver(receiver, intentFilter)
        }
        val notification = builder.build()
        getManager().notify(PERSISTENT_NOTIFICATION_ID, notification)
        startForeground(PERSISTENT_NOTIFICATION_ID, notification)
    }
    @SuppressWarnings("deprecated")
    private fun getBuilder(): Notification.Builder {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            return Notification.Builder(this, NotificationChannels.PERSISTENT.id)
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
    }
    private fun getManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun <T> getProfileToSwitchTo(ssid: String?, manager: ProfileManager<T>, networkSwitchingProfileGetter: (T) -> NetworkSwitchingProfile): UUID? {
        var r: UUID? = null
        for(uuid in manager.profileUUIDs){
            val networkSwitchingProfile = networkSwitchingProfileGetter(manager.getProfile(uuid).profile)
            if(networkSwitchingProfile.isEnabled){
                if(networkSwitchingProfile.isBackup){
                    if(r == null){
                        r = uuid
                    }
                } else if(networkSwitchingProfile.ssid == ssid){
                    return uuid
                }
            }
        }
        return r
    }

    /*
    This is called periodically whenever we need to update
     */
    override fun run() {
        if(miscProfileProvider.activeProfile.profile.networkSwitchingEnabled) {
            try {
                val id = getSSID(this)
                println("Current ssid: $id")
                val switchUUID = getProfileToSwitchTo(id, connectionProfileManager, ConnectionProfile::networkSwitchingProfile)
                if (switchUUID != null && connectionProfileManager.activeUUID != switchUUID) {
                    connectionProfileManager.activeUUID = switchUUID
                    Toast.makeText(this, "Changed to profile: ${connectionProfileManager.getProfileName(switchUUID)}", Toast.LENGTH_SHORT).show()
                }
            } catch(ex: SSIDPermissionException){
                ex.printStackTrace()
            } catch(ex: SSIDNotAvailable){
                ex.printStackTrace()
            }
        }

        val activeConnectionProfile = connectionProfileManager.activeProfile.profile

        val couchDbDatabaseConnectionProfile = (activeConnectionProfile.databaseConnectionProfile as CouchDbDatabaseConnectionProfile)
        val properties = couchDbDatabaseConnectionProfile.createCouchProperties()
        var needsLargeData = false
        for(service in services){
            val task = service.task
            if(task != null){
                service.task = null
                if(task.cancel(true)) { // if the task was still running then...
                    service.dataService.onTimeout()
                }
            }
            if(!service.dataService.shouldUpdate){
                service.dataService.onCancel()
                continue
            }

            val dataRequesters = listOf(
                    CouchDbDataRequester(
                            { properties }, // this can be constant because we change this frequently enough for it to always be accurate
                            service.databaseName,
                            service.packetGroupParser,
                            service.dataService::startKey
                    )
            )
            if(service.dataService.updatePeriodType == UpdatePeriodType.LARGE_DATA){
                needsLargeData = true
            }
            val dataRequester = DataRequesterMultiplexer(dataRequesters)
            service.task = DataUpdaterTask(dataRequester, service.dataService::onDataRequest).execute()
        }
        metaUpdaterTask?.cancel(true)
        metaUpdaterTask = MetaUpdaterTask(properties, metaHandler).execute()

        val delay = if(needsLargeData){ activeConnectionProfile.initialRequestTimeSeconds * 1000L } else { activeConnectionProfile.subsequentRequestTimeSeconds * 1000L }
        handler.postDelayed(this, delay)
        updateNotification(System.currentTimeMillis() + delay)
    }
    private fun reload(){
        handler.removeCallbacks(this)
        handler.postDelayed(this, 100)
    }

    override fun onDestroy() {
        if(!initialized){
            println("This PersistentService wasn't initialized for some reason... Not to worry, we prepared for this!")
            return
        }
        println("Stopping persistent service")
        handler.removeCallbacks(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            unregisterReceiver(receiver)
        }
        for(service in services){
            service.task?.cancel(true)
            service.dataService.onCancel()
            service.dataService.onEnd()
        }
        metaUpdaterTask?.cancel(true)
        metaUpdaterTask = null
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if(context != null && intent != null){
                when(intent.action){
                    STOP_SERVICE_ACTION -> stopService(context)
                    RELOAD_SERVICE_ACTION -> reload()
                    RESTART_SERVICE_ACTION -> restartService(context)
                    else -> println("unknown action: ${intent.action}")
                }
            }
        }

    }
}
private class DataUpdaterTask(
        private val dataRequester: DataRequester,
        private val updateNotification: (dataRequest: DataRequest) -> Unit
) : AsyncTask<Void, Void, DataRequest>() {
    override fun doInBackground(vararg params: Void?): DataRequest {
        return dataRequester.requestData()
    }

    override fun onPostExecute(result: DataRequest?) {
        if(result == null){
            throw NullPointerException("result is null!")
        }
//        println("Received result: $result")
        updateNotification(result)
    }
}


