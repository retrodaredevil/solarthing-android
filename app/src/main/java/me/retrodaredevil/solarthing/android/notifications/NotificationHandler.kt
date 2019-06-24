package me.retrodaredevil.solarthing.android.notifications

import android.app.Notification
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.Html
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import me.retrodaredevil.solarthing.android.SolarPacketInfo
import me.retrodaredevil.solarthing.android.R
import java.text.DateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

const val SEPARATOR = "|"

object NotificationHandler {


    fun createFloatGeneratorAlert(context: Context, floatModeActivatedInfo: SolarPacketInfo, currentInfo: SolarPacketInfo, generatorFloatTimeMillis: Long): Notification {
        val shouldHaveTurnedOffAt = floatModeActivatedInfo.dateMillis + generatorFloatTimeMillis
        val now = System.currentTimeMillis()
        if(now < shouldHaveTurnedOffAt){
            throw IllegalArgumentException("The generator alert should be to alert someone to turn it off! " +
                    "Not to alert them when in the future they should turn it off.")
        }
        val turnedOnAtString = DateFormat.getTimeInstance(DateFormat.SHORT)
            .format(GregorianCalendar().apply { timeInMillis = floatModeActivatedInfo.dateMillis }.time)
        val turnOffAtString = DateFormat.getTimeInstance(DateFormat.SHORT)
            .format(GregorianCalendar().apply { timeInMillis = shouldHaveTurnedOffAt }.time)

        return createNotificationBuilder(context, NotificationChannels.GENERATOR_FLOAT_NOTIFICATION.id, GENERATOR_FLOAT_NOTIFICATION_ID)
            .setSmallIcon(R.drawable.power_button)
            .setContentTitle("Generator")
            .setContentText("Should have turned off at $turnOffAtString!")
            .setSubText("Float started at $turnedOnAtString")
            .setWhen(shouldHaveTurnedOffAt)
            .setUsesChronometer(true) // stopwatch from when the generator should have been turned off
            .build()
    }
    fun createDoneGeneratorAlert(context: Context, doneChargingInfo: SolarPacketInfo): Notification {
        val stoppedChargingAt = doneChargingInfo.dateMillis
        val stoppedChargingAtString = DateFormat.getTimeInstance(DateFormat.SHORT)
            .format(GregorianCalendar().apply { timeInMillis = stoppedChargingAt }.time)

        return createNotificationBuilder(context, NotificationChannels.GENERATOR_FLOAT_NOTIFICATION.id, GENERATOR_FLOAT_NOTIFICATION_ID)
            .setSmallIcon(R.drawable.power_button)
            .setContentTitle("Generator")
            .setContentText("The generator stopped charging the batteries. Time to turn it off")
            .setSubText("Stopped charging at $stoppedChargingAtString")
            .setWhen(stoppedChargingAt)
            .setUsesChronometer(true) // stopwatch from when the generator should have been turned off
            .build()
    }
    fun createBatteryNotification(context: Context, currentInfo: SolarPacketInfo, critical: Boolean): Notification {
        val voltageString = currentInfo.batteryVoltageString
        val builder = createNotificationBuilder(context, NotificationChannels.BATTERY_NOTIFICATION.id, BATTERY_NOTIFICATION_ID)
            .setSmallIcon(R.drawable.power_button)
            .setContentText("The battery voltage is low! $voltageString!!!")
            .setWhen(currentInfo.dateMillis)
        if(critical) {
            builder.setContentTitle("CRITICAL BATTERY $voltageString V")
        } else {
            builder.setContentTitle("Low Battery $voltageString V")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_ERROR)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setColor(Color.RED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if(critical){
                builder.setColorized(true)
//                builder.style = Notification.BigPictureStyle()
            }
        }

        val r = builder.build()
        if(critical) {
            r.flags = r.flags or Notification.FLAG_INSISTENT
        }
        return r
    }

    /**
     *
     * @param info The SolarPacketInfo representing a simpler view of a PacketCollection
     * @param summary The sub text (or summary) of the notification.
     */
    fun createStatusNotification(context: Context, info: SolarPacketInfo, summary: String = "",
                                 floatModeActivatedInfo: SolarPacketInfo?, generatorFloatTimeMillis: Long): Notification {
        val devicesStringList = ArrayList<String>()
        devicesStringList.addAll(info.fxMap.values.map { "[<strong>${it.address}</strong> <span style=\"color:#770000\">FX</span>]" })
        devicesStringList.addAll(info.mxMap.values.map { "[<strong>${it.address}</strong> <span style=\"color:#000077\">MX</span>]" })

        val devicesString = devicesStringList.joinToString("")


        val fxACModesString = info.fxMap.values.joinToString(SEPARATOR) { "(${it.address})${it.acModeName}" }
        val fxOperationalModeString = info.fxMap.values.joinToString(SEPARATOR) { "(${it.address})${it.operatingModeName}" }
        val mxDailyKWHString = info.mxMap.values.joinToString(SEPARATOR) { "(${it.address})${SolarPacketInfo.FORMAT.format(it.dailyKWH)}" }
        val mxChargerModesString = info.mxMap.values.joinToString(SEPARATOR) { "(${it.address})${it.chargerModeName}" }
        val mxAuxModesString = info.mxMap.values.joinToString(SEPARATOR) { "(${it.address})${it.auxModeName}" }
        val mxPVWattagesString = info.mxMap.values.joinToString(SEPARATOR) {
            "(${it.address})${it.pvCurrent * it.inputVoltage}"
        }
        val mxChargerWattagesString = info.mxMap.values.joinToString(SEPARATOR) {
            "(${it.address})${(it.chargerCurrent * it.batteryVoltage).toInt()}"
        }

        val fxWarningsString = info.fxMap.values.joinToString(SEPARATOR) { "(${it.address})${it.warningsString}" }
        val fxErrorsString = info.fxMap.values.joinToString(SEPARATOR) { "(${it.address})${it.errorsString}" }
        val mxErrorsString = info.mxMap.values.joinToString(SEPARATOR) { "(${it.address})${it.errorsString}" }

        val timeLeftText = if (floatModeActivatedInfo != null) {
            val timeLeft = (floatModeActivatedInfo.dateMillis + generatorFloatTimeMillis) - System.currentTimeMillis()
            val absTimeLeft = abs(timeLeft)
            val hours = TimeUnit.MILLISECONDS.toHours(absTimeLeft)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(absTimeLeft) - TimeUnit.HOURS.toMinutes(hours)
            val minutesString = minutes.toString()

            " $hours" +
                    ":" +
                    (if(minutesString.length == 1) "0$minutesString" else minutesString) +
                    " " +
                    (if(timeLeft < 0) "PAST" else "left") +
                    "\n"
        } else {
            ""
        }
        val timeTurnedOnText = if(floatModeActivatedInfo != null){
            val timeTurnedOnString = DateFormat.getTimeInstance(DateFormat.SHORT).format(
                GregorianCalendar().apply { timeInMillis = floatModeActivatedInfo.dateMillis }.time
            )
            "Generator entered float mode at $timeTurnedOnString"
        } else {
            ""
        }
        val generatorWattageText = if(info.generatorOn || info.generatorToBatteryWattage > 0 || info.generatorTotalWattage > 0){
            " to battery: ${info.generatorToBatteryWattageString} W | total: ${info.generatorTotalWattageString} W"
        } else {
            ""
        }
        val text = "" +
                "PV: $mxPVWattagesString | Total: <strong>${info.pvWattageString}</strong> W\n" +
                "Charger: $mxChargerWattagesString | Total: <strong>${info.pvChargerWattageString}</strong> W\n" +
                "Daily kWH: $mxDailyKWHString | Total: <strong>${info.dailyKWHoursString}</strong>\n" +
                (if(info.generatorOn) "Generator <strong>ON</strong>$timeLeftText$generatorWattageText\n" else "") +
                (if(timeTurnedOnText.isNotEmpty()) timeTurnedOnText + "\n" else "") +
                "Devices: $devicesString" + (if(info.generatorOn) "" else " Generator OFF") + "\n" +
                (if(info.fxMap.values.any { it.errorMode != 0 }) "FX Errors: $fxErrorsString\n" else "") +
                (if(info.mxMap.values.any { it.errorMode != 0 }) "MX Errors: $mxErrorsString\n" else "") +
                (if(info.fxMap.values.any { it.warningMode != 0 }) "FX Warn: $fxWarningsString\n" else "") +
                "FX AC Mode: $fxACModesString\n" +
                "FX Operational Mode: $fxOperationalModeString\n" +
                "MX Charger Mode: $mxChargerModesString\n" +
                "MX Aux Mode: $mxAuxModesString"
        if(text.length > 5 * 1024){
            System.err.println("bigText.length: ${text.length}! Some text may be cut off")
        }

        val html = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(text.replace("\n", "<br/>"), 0)
        } else {
            Html.fromHtml(text.replace("\n", "<br/>"))
        }
        val style = Notification.BigTextStyle().bigText(html)

        val builder = createNotificationBuilder(context, NotificationChannels.SOLAR_STATUS.id, SOLAR_NOTIFICATION_ID)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.solar_panel)
            .setSubText(summary)
            .setContentTitle("Battery Voltage: ${info.batteryVoltageString} V Load: ${info.loadString} W")
            .setContentText("pv:${info.pvWattageString} err:${info.errorsCount} warn:${info.warningsCount} generator:" + if(info.generatorOn) "ON $timeLeftText" else "OFF")
            .setStyle(style)
            .setOnlyAlertOnce(true)
            .setWhen(info.dateMillis)
            .setShowWhen(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setColor(Color.YELLOW)
            builder.setCategory(Notification.CATEGORY_STATUS)
        }

        return builder.build()
    }
    private fun createNotificationBuilder(context: Context, channelId: String, notificationId: Int): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            Notification.Builder(context)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            builder.setGroup(getGroup(notificationId))
        }
        return builder
    }
}