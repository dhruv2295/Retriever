/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingWorker.kt (Apache-2.0). Modified for Retriever: ensureNotificationChannels and the createForegroundInfo builder moved into an object, plain strings, our icon, a Pause action and a tap-to-open intent.

package app.retriever.indexing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.retriever.MainActivity
import app.retriever.R
import app.retriever.core.DeviceWords
import app.retriever.core.ReadingPhase
import app.retriever.core.ReadingPolicy
import app.retriever.core.ReadingProgress
import app.retriever.core.ReadingStatus
import app.retriever.core.StatusAction
import app.retriever.core.StopReason
import app.retriever.core.Words

object Notifications {
    const val CHANNEL = "indexing"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, Words.PROGRESS_CHANNEL, NotificationManager.IMPORTANCE_LOW)
            .apply {
                description = Words.PROGRESS_CHANNEL_BODY
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** The session's notification. A pause for the battery, heat or Battery Saver says what it waits for and offers Keep paused, as Status does. */
    fun progress(context: Context, counts: ReadingProgress, status: ReadingStatus?): Notification {
        val held = when (status?.phase) {
            ReadingPhase.CHARGING -> StopReason.UNPLUGGED
            ReadingPhase.HOT -> StopReason.HOT
            ReadingPhase.POWER_SAVE -> StopReason.POWER_SAVE
            ReadingPhase.LOW_BATTERY -> StopReason.LOW_BATTERY
            ReadingPhase.CHARGING_UP -> StopReason.CHARGING_UP
            else -> null
        }
        val display = if (held != null && status != null) paused(status, held, status.detail) else status
        val text = Words.strings.notificationBody(display?.count, display?.detail, counts.contentRead)
        return NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(display?.title ?: Words.STARTING)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context, status = true))
            .addAction(0, if (held != null) Words.strings.processingKeepPaused else Words.PAUSE, pauseIntent(context))
            .apply {
                val done = display?.done
                val total = display?.total
                if (done != null && total != null && total > 0) setProgress(total.toInt(), done.toInt(), false)
            }
            .build()
    }

    /** What a pause for [stop] waits for, with a detail that matches it: only an unplugged phone needs the charger. */
    private fun paused(status: ReadingStatus, stop: StopReason, detail: String?) = status.copy(
        title = ReadingPolicy.stopCopy(stop, DeviceWords.ANDROID),
        detail = if (stop == StopReason.UNPLUGGED || stop == StopReason.LOW_BATTERY) Words.strings.processingPlugInDetail else detail,
    )

    /**
     * "Processing paused · Tap to continue": a person's request that no session serves, with no service or wake lock behind it.
     * Tapping it opens the app, which resumes. Separate from the session's, so Android's service teardown can't remove it.
     */
    internal fun recovery(context: Context, counts: ReadingProgress, status: ReadingStatus, blocker: StopReason?) {
        if (!processingNotificationsEnabled(context)) {
            cancelWaiting(context)
            return
        }
        val shown = paused(status, blocker ?: StopReason.PAUSED, Words.strings.processingTapToContinue)
        val text = Words.strings.notificationBody(shown.count, shown.detail, counts.contentRead)
        // Below the explicit floor only the charger starts it; a Resume there would do nothing.
        val needsCharger = blocker == StopReason.UNPLUGGED || blocker == StopReason.LOW_BATTERY
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(shown.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, active = false))
            .setContentIntent(resumeIntent(context))
            .setAutoCancel(true)
        if (!needsCharger) builder.addAction(0, StatusAction.RESUME.label, resumeIntent(context))
        context.getSystemService(NotificationManager::class.java).notify(WAITING_ID, builder.build())
    }

    fun cancelWaiting(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(WAITING_ID)

    fun waitingShown(context: Context) = context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == WAITING_ID }

    /** Posted only when notifications are allowed; tapping it opens the app where the first run left off. */
    fun demoDone(context: Context, done: Int, planned: Int) {
        if (planned > 0) notice(context, DEMO_DONE_ID, Words.DEMO_DONE_NOTICE_TITLE, Words.demoDoneNotice(done.toLong(), planned.toLong()))
    }

    /** The one notice Home's card asked for: everything is searchable. */
    fun caughtUp(context: Context, files: Long, textOnly: Boolean) =
        notice(context, CAUGHT_UP_ID, Words.CAUGHT_UP_NOTICE_TITLE, Words.caughtUpNotice(files, textOnly))

    private fun notice(context: Context, id: Int, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    private const val DEMO_DONE_ID = 2
    const val CAUGHT_UP_ID = 3

    /** Processing's one notification, shared by every run. */
    const val PROGRESS_ID = 1

    internal const val WAITING_ID = 4

    fun cancelSample(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(DEMO_DONE_ID)

    /** The progress notification's Pause action. */
    fun pauseIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, PauseReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)

    const val OPEN_STATUS = "app.retriever.OPEN_READING_STATUS"
    const val RESUME_PROCESSING = "app.retriever.RESUME_PROCESSING"

    private fun publicVersion(context: Context, active: Boolean = true) = NotificationCompat.Builder(context, CHANNEL)
        .setContentTitle("Retriever").setContentText(if (active) Words.PROCESSING_YOUR_FILES else Words.READING_STATUS)
        .setSmallIcon(R.drawable.ic_notification).setSilent(true).build()

    internal fun resumeIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        2,
        Intent(context, MainActivity::class.java).apply {
            action = RESUME_PROCESSING
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(context: Context, status: Boolean = false) = PendingIntent.getActivity(
        context,
        if (status) 1 else 0,
        // Reuse the open activity (closing a viewer above it) so onNewIntent runs instead of stacking another one.
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (status) action = OPEN_STATUS
        },
        PendingIntent.FLAG_IMMUTABLE,
    )
}
