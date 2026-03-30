package com.aria.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.*

class ProactiveService : Service() {
    
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: android.content.SharedPreferences
    private var lastCheckTime = 0L
    
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_LOW) {
                checkBatteryAndNotify()
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
        createNotificationChannel()
        
        // Register battery receiver
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_LOW))
        
        // Start periodic checks
        startPeriodicChecks()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ARIA_PROACTIVE",
                "ARIA Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "ARIA proactive notifications"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun startPeriodicChecks() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                performChecks()
                handler.postDelayed(this, 15 * 60 * 1000) // Every 15 minutes
            }
        }, 1000)
    }
    
    private fun performChecks() {
        val personality = prefs.getString("personality", "girlfriend") ?: "girlfriend"
        if (personality != "girlfriend") return // Only girlfriend mode is proactive
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCheckTime < 30 * 60 * 1000) return // Max 1 check per 30 min
        
        lastCheckTime = currentTime
        
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        
        when (hour) {
            in 6..8 -> checkMorningGreeting()
            in 12..14 -> checkLunchReminder()
            in 20..22 -> checkDinnerReminder()
            in 23..24, in 0..5 -> checkSleepReminder()
        }
        
        checkBatteryAndNotify()
    }
    
    private fun checkMorningGreeting() {
        val lastMorningGreet = prefs.getLong("last_morning_greet", 0)
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val lastDay = Calendar.getInstance().apply { timeInMillis = lastMorningGreet }.get(Calendar.DAY_OF_YEAR)
        
        if (today != lastDay) {
            val name = prefs.getString("nickname", prefs.getString("user_name", "জান")) ?: "জান"
            sendNotification(
                "Good Morning! 💕",
                "Good morning $name! Kemon ghumiyecho? Aj ki plan? 😊"
            )
            prefs.edit().putLong("last_morning_greet", System.currentTimeMillis()).apply()
        }
    }
    
    private fun checkLunchReminder() {
        val lastLunch = prefs.getLong("last_lunch_remind", 0)
        if (System.currentTimeMillis() - lastLunch > 24 * 60 * 60 * 1000) {
            val name = prefs.getString("nickname", "জান") ?: "জান"
            sendNotification(
                "Lunch Time! 🍽️",
                "$name, lunch korecho? Khali pete thakbe na please! 💕"
            )
            prefs.edit().putLong("last_lunch_remind", System.currentTimeMillis()).apply()
        }
    }
    
    private fun checkDinnerReminder() {
        val lastDinner = prefs.getLong("last_dinner_remind", 0)
        if (System.currentTimeMillis() - lastDinner > 24 * 60 * 60 * 1000) {
            val name = prefs.getString("nickname", "জান") ?: "জান"
            sendNotification(
                "Dinner Time! 🍽️",
                "$name, raat er khawa kheyecho? Properly khao, skip koro na 😊"
            )
            prefs.edit().putLong("last_dinner_remind", System.currentTimeMillis()).apply()
        }
    }
    
    private fun checkSleepReminder() {
        val lastSleep = prefs.getLong("last_sleep_remind", 0)
        if (System.currentTimeMillis() - lastSleep > 24 * 60 * 60 * 1000) {
            val name = prefs.getString("nickname", "সোনা") ?: "সোনা"
            sendNotification(
                "Sleep Time! 🌙",
                "$name, ekhono jagcho? Ghumao ekhon, raat onek hoye geche. Sweet dreams 💕😘"
            )
            prefs.edit().putLong("last_sleep_remind", System.currentTimeMillis()).apply()
        }
    }
    
    private fun checkBatteryAndNotify() {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        
        if (batteryLevel < 20) {
            val lastBatteryWarn = prefs.getLong("last_battery_warn", 0)
            if (System.currentTimeMillis() - lastBatteryWarn > 60 * 60 * 1000) { // Once per hour
                val name = prefs.getString("nickname", "জান") ?: "জান"
                sendNotification(
                    "Battery Low! 🔋",
                    "$name, battery $batteryLevel% ache! Charge dao please 💕"
                )
                prefs.edit().putLong("last_battery_warn", System.currentTimeMillis()).apply()
            }
        }
    }
    
    private fun sendNotification(title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, "ARIA_PROACTIVE")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        handler.removeCallbacksAndMessages(null)
    }
}
