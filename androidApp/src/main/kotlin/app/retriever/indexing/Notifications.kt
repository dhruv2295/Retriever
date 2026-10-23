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
import app.retriever.core.ReadingProgress
import app.retriever.core.ReadingStatus
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

    fun progress(context: Context, counts: ReadingProgress, status: ReadingStatus?, cancel: PendingIntent): Notification {
        // The status line's own words: its count, then its detail; else what is processed so far.
        val text = listOfNotNull(status?.count, status?.detail).joinToString(". ").ifEmpty { Words.readCount(counts.contentRead) }
        return NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(status?.title ?: Words.STARTING)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context, status = true))
            .addAction(0, "Pause", cancel)
            .build()
    }

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

    fun cancelSample(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(DEMO_DONE_ID)

    const val OPEN_STATUS = "app.retriever.OPEN_READING_STATUS"

    private fun publicVersion(context: Context) = NotificationCompat.Builder(context, CHANNEL)
        .setContentTitle("Retriever").setContentText(Words.PROCESSING_YOUR_FILES).setSmallIcon(R.drawable.ic_notification).setSilent(true).build()

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
