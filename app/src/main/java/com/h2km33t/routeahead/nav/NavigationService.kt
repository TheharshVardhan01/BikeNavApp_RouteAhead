package com.h2km33t.routeahead.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.h2km33t.routeahead.R
import com.h2km33t.routeahead.RouteAheadApplication
import com.h2km33t.routeahead.routing.Maneuver
import com.h2km33t.routeahead.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the navigation session alive with the screen off.
 *
 * Android aggressively suspends background processes, and a phone in a jacket pocket is
 * exactly that - without this the location updates stop within a minute or two and the
 * device screen freezes on whatever turn it last received. The service holds no state
 * itself; [NavigationController] does. Its whole job is the foreground notification.
 */
class NavigationService : Service() {

    companion object {
        private const val CHANNEL_ID = "routeahead_navigation"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.h2km33t.routeahead.START_NAV"
        const val ACTION_STOP = "com.h2km33t.routeahead.STOP_NAV"

        fun start(context: Context) {
            val intent = Intent(context, NavigationService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NavigationService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    private val controller: NavigationController
        get() = (application as RouteAheadApplication).navigationController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                controller.stopNavigation()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForegroundCompat(buildNotification(controller.state.value))
        observeState()
        // START_STICKY: if Android does kill us under memory pressure, come back - the
        // rider is mid-ride and would otherwise silently lose navigation.
        return START_STICKY
    }

    private fun observeState() {
        observeJob?.cancel()
        observeJob = scope.launch {
            controller.state.collectLatest { state ->
                if (!state.isNavigating && state.phase != NavPhase.ARRIVED) {
                    stopSelf()
                    return@collectLatest
                }
                notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: NavigationState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when {
            state.phase == NavPhase.ARRIVED -> "Arrived"
            state.offRoute -> "Rerouting..."
            state.maneuver == Maneuver.STRAIGHT && state.distanceToManeuverM == 0 ->
                "Navigating"
            else -> "${formatDistance(state.distanceToManeuverM)} - " +
                    instructionText(state.maneuver, state.maneuverStreet, state.roundaboutExit)
        }

        val body = buildString {
            append(formatDuration(state.remainingSeconds))
            append(" - ")
            append(formatDistance(state.remainingDistanceM))
            state.destination?.name?.let { append(" to ").append(it) }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Navigation",
            // LOW: the notification updates every second while riding, so anything
            // higher would buzz the phone continuously.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the current turn while navigation is running"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
