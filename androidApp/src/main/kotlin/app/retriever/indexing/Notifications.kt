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

object Notifications {
    const val CHANNEL = "indexing"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Reading progress", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Shows while Retriever reads your files" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun progress(context: Context, processed: Int, total: Long, current: String?, cancel: PendingIntent): Notification {
        val text = buildString {
            if (total > 0) append("%,d of %,d".format(processed, total)) else append("Getting ready")
            if (current != null) append(" · ").append(current)
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("Reading your files")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), processed, total == 0L)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))
            .addAction(0, "Pause", cancel)
            .build()
    }

    /** Posted only when notifications are allowed; tapping it opens the app where the first run left off. */
    fun readyToTry(context: Context, files: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("Ready to try")
            .setContentText("Search the $files files Retriever just read.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        manager.notify(READY_TO_TRY_ID, notification)
    }

    private const val READY_TO_TRY_ID = 2

    private fun openApp(context: Context) = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
}
