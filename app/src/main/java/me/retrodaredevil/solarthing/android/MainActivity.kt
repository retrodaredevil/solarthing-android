package me.retrodaredevil.solarthing.android

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.text.InputType
import android.view.View
import android.widget.*
import me.retrodaredevil.solarthing.android.notifications.NotificationChannelGroups
import me.retrodaredevil.solarthing.android.notifications.NotificationChannels
import me.retrodaredevil.solarthing.android.prefs.*
import me.retrodaredevil.solarthing.android.service.restartService
import me.retrodaredevil.solarthing.android.service.startServiceIfNotRunning
import me.retrodaredevil.solarthing.android.service.stopService
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var connectionProfileManager: ProfileManager<ConnectionProfile>
    private val connectionProfileUUIDMap = mutableMapOf<UUID, Pair<Long, String>>()
    private lateinit var solarProfileManager: ProfileManager<SolarProfile>
    private lateinit var miscProfileProvider: ProfileProvider<MiscProfile>

    private lateinit var connectionProfileSpinner: Spinner
    private lateinit var connectionProfileName: EditText
    private lateinit var protocol: EditText
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var useAuth: CheckBox

    private lateinit var initialRequestTimeout: EditText
    private lateinit var subsequentRequestTimeout: EditText


    private lateinit var generatorFloatHours: EditText
    private lateinit var virtualFloatModeMinimumBatteryVoltage: EditText
    private lateinit var lowBatteryVoltage: EditText
    private lateinit var criticalBatteryVoltage: EditText

    private lateinit var maxFragmentTime: EditText
    private lateinit var startOnBoot: CheckBox

    // region Initialization
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionProfileManager = createConnectionProfileManager(this)
        solarProfileManager = createSolarProfileManager(this)
        miscProfileProvider = createMiscProfileProvider(this)

        connectionProfileSpinner = findViewById(R.id.connection_profile_spinner)
        connectionProfileName = findViewById(R.id.connection_profile_name)
        protocol = findViewById(R.id.protocol)
        host = findViewById(R.id.hostname)
        port = findViewById(R.id.port)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        useAuth = findViewById(R.id.use_auth)
        generatorFloatHours = findViewById(R.id.generator_float_hours)
        initialRequestTimeout = findViewById(R.id.initial_request_timeout)
        subsequentRequestTimeout = findViewById(R.id.subsequent_request_timeout)
        maxFragmentTime = findViewById(R.id.max_fragment_time)
        lowBatteryVoltage = findViewById(R.id.low_battery_voltage)
        criticalBatteryVoltage = findViewById(R.id.critical_battery_voltage)
        virtualFloatModeMinimumBatteryVoltage = findViewById(R.id.virtual_float_mode_minimum_battery_voltage)
        startOnBoot = findViewById(R.id.start_on_boot)

        useAuth.setOnCheckedChangeListener{ _, _ ->
            onUseAuthUpdate()
        }

        connectionProfileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = throw error("Cannot have nothing selected!")
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onConnectionProfileChange(id)
        }

        loadSettings()


        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            for(channel in NotificationChannels.OLD_CHANNELS) {
                notificationManager.deleteNotificationChannel(channel)
            }

            for(channelGroup in NotificationChannelGroups.values()){
                notificationManager.createNotificationChannelGroup(NotificationChannelGroup(
                    channelGroup.id, channelGroup.getName(this)
                ).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        description = channelGroup.getDescription(this@MainActivity)
                    }
                })
            }
            for(notificationChannel in NotificationChannels.values()){
                notificationManager.createNotificationChannel(NotificationChannel(
                    notificationChannel.id,
                    notificationChannel.getName(this),
                    notificationChannel.importance
                ).apply {
                    description = notificationChannel.getDescription(this@MainActivity)
                    enableLights(notificationChannel.enableLights)
                    if (notificationChannel.lightColor != null) {
                        lightColor = notificationChannel.lightColor
                    }
                    enableVibration(notificationChannel.enableVibration)
                    if (notificationChannel.vibrationPattern != null) {
                        vibrationPattern = notificationChannel.vibrationPattern
                    }
                    setShowBadge(notificationChannel.showBadge)
                    if(notificationChannel.notificationChannelGroups != null){
                        group = notificationChannel.notificationChannelGroups.id
                    }
                    if(notificationChannel.sound != null){
                        val soundId = notificationChannel.sound
                        val uri = Uri.parse("android.resource://$packageName/raw/$soundId")
                        setSound(
                            uri,
                            AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
                        )
                    }
                })
            }
        }
        startServiceIfNotRunning(this)
    }
    // endregion

    // region Buttons
    @Suppress("UNUSED_PARAMETER")
    fun saveSettings(view: View){
        saveSettings()
    }
    @Suppress("UNUSED_PARAMETER")
    fun restartService(view: View){
        restartService(this)
    }
    @Suppress("UNUSED_PARAMETER")
    fun stopService(view: View){
        stopService(this)
    }
    @Suppress("UNUSED_PARAMETER")
    fun openCommands(view: View){
        startActivity(Intent(this, CommandActivity::class.java))
    }
    @Suppress("UNUSED_PARAMETER")
    fun newConnectionProfile(view: View){
        createPromptAlert("New Profile Name") {
            val (uuid, profile) = connectionProfileManager.addAndCreateProfile(it)
            connectionProfileManager.activeUUID = uuid
            loadSettings()
//            loadSpinner(connectionProfileSpinner, connectionProfileManager, connectionProfileUUIDMap, uuid)
//            loadConnectionSettings(connectionProfileManager.activeProfileName, profile)
        }.show()
    }
    @Suppress("UNUSED_PARAMETER")
    fun deleteCurrentConnectionProfile(view: View){
        val size = connectionProfileManager.profileUUIDs.size
        if(size <= 1){
            Toast.makeText(this, "You cannot remove the last profile!", Toast.LENGTH_SHORT).show()
            return
        }
        val success = connectionProfileManager.removeProfile(connectionProfileManager.activeUUID)
        if (success) {
            loadSettings()
            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Error. Unable to remove...", Toast.LENGTH_SHORT).show()
        }
    }
    // endregion
    private fun createPromptAlert(title: String, onSubmit: (String) -> Unit): AlertDialog {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        builder.setView(input)
        builder.setPositiveButton("OK"){ _, _ ->
            onSubmit(input.text.toString())
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }

        return builder.create()
    }

    private fun onUseAuthUpdate(){
        if(useAuth.isChecked){
            username.alpha = 1f
            password.alpha = 1f
        } else {
            username.alpha = .5f
            password.alpha = .5f
        }
    }
    private fun onConnectionProfileChange(rowId: Long){
        println("Connection Profile Changed to ID: $rowId")
        var activeUUID: UUID? = null
        var activeName: String? = null
        for(entry in connectionProfileUUIDMap.entries){
            val uuid = entry.key
            val (id, name) = entry.value
            if(id == rowId){
                activeUUID = uuid
                activeName = name
                break
            }
        }
        activeUUID ?: error("activeUUID is null from rowId: $rowId")
        activeName!!
        val currentUUID = connectionProfileManager.activeUUID
        if(currentUUID == activeUUID){ // they're the same
            return
        }
        saveSettings(reloadSettings = false, showToast = false)
        connectionProfileManager.activeUUID = activeUUID
        loadConnectionSettings(activeName, connectionProfileManager.getProfile(activeUUID))
    }
    // region Saving and Loading
    private fun saveSettings(reloadSettings: Boolean = true, showToast: Boolean = true){
        connectionProfileManager.setProfileName(connectionProfileManager.activeUUID, connectionProfileName.text.toString())
        connectionProfileManager.activeProfile.apply {
            (databaseConnectionProfile as CouchDbDatabaseConnectionProfile).let { // TODO don't cast
                it.protocol = protocol.text.toString()
                it.host = host.text.toString()
                it.port = port.text.toString().toIntOrNull() ?: DefaultOptions.CouchDb.port
                it.username = username.text.toString()
                it.password = password.text.toString()
                it.useAuth = useAuth.isChecked
            }
            initialRequestTimeSeconds = initialRequestTimeout.text.toString().toIntOrNull() ?: DefaultOptions.initialRequestTimeSeconds
            subsequentRequestTimeSeconds = subsequentRequestTimeout.text.toString().toIntOrNull() ?: DefaultOptions.subsequentRequestTimeSeconds
        }

        solarProfileManager.activeProfile.let {
            it.generatorFloatTimeHours = generatorFloatHours.text.toString().toFloatOrNull() ?: DefaultOptions.generatorFloatTimeHours
            it.virtualFloatMinimumBatteryVoltage = virtualFloatModeMinimumBatteryVoltage.text.toString().toFloatOrNull() ?: DefaultOptions.virtualFloatModeMinimumBatteryVoltage
            it.lowBatteryVoltage = lowBatteryVoltage.text.toString().toFloatOrNull() ?: DefaultOptions.lowBatteryVoltage
            it.criticalBatteryVoltage = criticalBatteryVoltage.text.toString().toFloatOrNull() ?: DefaultOptions.criticalBatteryVoltage
        }
        miscProfileProvider.activeProfile.let {
            it.maxFragmentTimeMinutes = maxFragmentTime.text.toString().toFloatOrNull() ?: DefaultOptions.maxFragmentTimeMinutes
            it.startOnBoot = startOnBoot.isChecked
        }

        if(reloadSettings) loadSettings()
        if(showToast) Toast.makeText(this, "Saved settings!", Toast.LENGTH_SHORT).show()
    }
    private fun loadSpinner(spinner: Spinner, profileManager: ProfileManager<*>, map: MutableMap<UUID, Pair<Long, String>>, activeUUID: UUID){
        val uuids = profileManager.profileUUIDs
        val profileNameList = uuids.map { profileManager.getProfileName(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, profileNameList)
        var selectedPosition: Int? = null
        for((position, uuid) in uuids.withIndex()){
            val id = adapter.getItemId(position)
            map[uuid] = Pair(id, profileNameList[position])
            if(uuid == activeUUID){
                selectedPosition = position
            }
        }
        selectedPosition ?: error("No active uuid: $activeUUID in uuids: $uuids")
        spinner.adapter = adapter
        spinner.setSelection(selectedPosition)
    }
    private fun loadSettings(){
        val activeConnectionUUID = connectionProfileManager.activeUUID
        val activeConnectionProfile = connectionProfileManager.getProfile(activeConnectionUUID)
        loadConnectionSettings(connectionProfileManager.activeProfileName, activeConnectionProfile)
        loadSpinner(connectionProfileSpinner, connectionProfileManager, connectionProfileUUIDMap, activeConnectionUUID)

        solarProfileManager.activeProfile.let {
            generatorFloatHours.setText(it.generatorFloatTimeHours.toString())
            virtualFloatModeMinimumBatteryVoltage.setText(it.virtualFloatMinimumBatteryVoltage?.toString() ?: "")
            lowBatteryVoltage.setText(it.lowBatteryVoltage?.toString() ?: "")
            criticalBatteryVoltage.setText(it.criticalBatteryVoltage?.toString() ?: "")
        }

        miscProfileProvider.activeProfile.let {
            maxFragmentTime.setText(it.maxFragmentTimeMinutes.toString())
            startOnBoot.isChecked = it.startOnBoot
        }
    }
    private fun loadConnectionSettings(name: String, connectionProfile: ConnectionProfile){
        connectionProfileName.setText(name)
        (connectionProfile.databaseConnectionProfile as CouchDbDatabaseConnectionProfile).let {
            protocol.setText(it.protocol)
            host.setText(it.host)
            port.setText(it.port.toString())
            username.setText(it.username)
            password.setText(it.password)
            useAuth.isChecked = it.useAuth
            onUseAuthUpdate()
        }
        connectionProfile.let {
            initialRequestTimeout.setText(it.initialRequestTimeSeconds.toString())
            subsequentRequestTimeout.setText(it.subsequentRequestTimeSeconds.toString())
        }

    }
    // endregion
}
