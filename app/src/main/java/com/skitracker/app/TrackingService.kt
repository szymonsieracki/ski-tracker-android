package com.skitracker.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Usługa pierwszoplanowa: GPS + barometr działają także przy zablokowanym ekranie. */
class TrackingService : Service(), LocationListener, SensorEventListener {

    private lateinit var lm: LocationManager
    private lateinit var sm: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private var baroAlt: Double? = null
    private var baroTime = 0L
    private var offset: Double? = null

    private val draftSaver = object : Runnable {
        override fun run() {
            Store.saveDraft(this@TrackingService, TrackerState.snapshot())
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, n)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "skitracker:tracking").apply { acquire(12 * 60 * 60 * 1000L) }

        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        handler.postDelayed(draftSaver, 60_000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lm.removeUpdates(this)
        sm.unregisterListener(this)
        handler.removeCallbacks(draftSaver)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("tracking", "Śledzenie trasy", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, "tracking")
            .setContentTitle("Ski Tracker")
            .setContentText("Nagrywam trasę…")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    // --- GPS ---
    override fun onLocationChanged(loc: Location) {
        if (!loc.hasSpeed() || !loc.hasAccuracy() || loc.accuracy > 25f) return
        var alt = loc.altitude
        val b = baroAlt
        if (b != null && System.currentTimeMillis() - baroTime < 5000) {
            // barometr = gładsza wysokość, skalibrowana do GPS przy pierwszym pomiarze
            if (offset == null) offset = alt - b
            alt = b + (offset ?: 0.0)
        }
        TrackerState.onFix(Pt(loc.latitude, loc.longitude, alt, loc.speed.toDouble(), loc.time))
    }

    @Deprecated("Wymagane na starszych wersjach Androida")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // --- Barometr ---
    override fun onSensorChanged(e: SensorEvent) {
        baroAlt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, e.values[0]).toDouble()
        baroTime = System.currentTimeMillis()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
