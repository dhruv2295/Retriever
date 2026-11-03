/*
 * Copyright 2023 The Android Open Source Project
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
// Permission sets per Android version and the full / selected / denied check adapted from android/platform-samples
// samples/storage/src/main/java/com/example/platform/storage/mediastore/SelectedPhotosAccess.kt (Apache-2.0).
// Modified for Retriever: audio too, pure functions over the granted set, all-files access and picked folders.

package app.retriever.access

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import app.retriever.core.Kind
import app.retriever.core.Words
import app.retriever.core.android.SafSource
import java.net.URLDecoder

/**
 * Settings' all-files switch for this app; some builds lack that page, so the list of apps, then the app's own page.
 * [start]: how a page opens, so setup can launch it for a result.
 */
fun openAllFilesSettings(context: Context, start: (Intent) -> Unit = context::startActivity) {
    val app = Uri.fromParts("package", context.packageName, null)
    val pages = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, app),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app),
    )
    for (page in pages) {
        try {
            start(page)
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}

/**
 * After a media permission request. Once a person has said no twice, Android answers at once without asking, so the
 * button would seem dead: the app's page in Settings is the only way left.
 */
fun openSettingsIfBlocked(activity: Activity?, result: Map<String, Boolean>) {
    if (activity == null || result.isEmpty() || result.values.any { it }) return
    if (result.keys.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }) return
    try {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
    } catch (_: ActivityNotFoundException) {
    }
}

enum class Photos { ALL, SELECTED, NONE }

data class PickedFolder(val uri: String, val name: String)

/** What a status line offers to do. */
enum class Fix { CHOOSE_PHOTOS, ALLOW_MEDIA, ALL_FILES }

data class CoverageLine(val text: String, val fix: Fix? = null) {
    /** Something Retriever can't see, with a way to let it. */
    val gap: Boolean get() = fix == Fix.ALLOW_MEDIA || fix == Fix.ALL_FILES
}

internal const val ONLY_PICKED_PHOTOS = "Only the photos you picked"

/** What Retriever may read: all files, or the media permissions plus the folders the person picked. */
data class Access(
    val allFiles: Boolean,
    val photos: Photos = Photos.NONE,
    val videos: Boolean = false,
    val audio: Boolean = false,
    val folders: List<PickedFolder> = emptyList(),
) {
    val ready: Boolean get() = allFiles || photos != Photos.NONE || videos || audio || folders.isNotEmpty()

    /** Changes whenever MediaStore would show other rows; folders are not in it, they never reach MediaStore. */
    val fingerprint: String get() = if (allFiles) "all" else "p${photos.ordinal}v${if (videos) 1 else 0}a${if (audio) 1 else 0}"

    /** Kinds MediaStore already lists in full: a picked folder skips them, so one file is never indexed twice. */
    val mediaKinds: Set<Kind>
        get() = buildSet {
            if (photos == Photos.ALL) addAll(listOf(Kind.PHOTO, Kind.SCREENSHOT))
            if (videos) add(Kind.VIDEO)
            if (audio) add(Kind.AUDIO)
        }

    /** The trees SafSource walks: none with all-files access, which covers them through MediaStore. */
    val trees: List<String> get() = if (allFiles) emptyList() else folders.map { it.uri }

    /** Status's plain lines, folders aside (they get their own rows). */
    fun coverage(): List<CoverageLine> {
        if (allFiles) return listOf(CoverageLine(Words.SHARED_FILES_AND_FOLDERS))
        return buildList {
            when {
                photos == Photos.ALL && videos -> add(CoverageLine("Photos and videos"))
                photos == Photos.ALL -> add(CoverageLine("Photos"))
                photos == Photos.SELECTED -> add(CoverageLine(ONLY_PICKED_PHOTOS, Fix.CHOOSE_PHOTOS))
            }
            if (videos && photos != Photos.ALL) add(CoverageLine("Videos"))
            if (audio) add(CoverageLine("Music and recordings"))
            val missing = listOfNotNull("photos".takeIf { photos == Photos.NONE }, "videos".takeIf { !videos }, "music".takeIf { !audio })
            if (missing.isNotEmpty()) {
                val list = if (missing.size == 1) missing[0] else missing.dropLast(1).joinToString(", ") + " and " + missing.last()
                add(CoverageLine("Not covered: $list", Fix.ALLOW_MEDIA))
            }
            add(
                CoverageLine(
                    if (folders.isEmpty()) {
                        "Not covered: documents and downloads"
                    } else {
                        "Not covered: documents and downloads outside the folders you added"
                    },
                    Fix.ALL_FILES,
                ),
            )
        }
    }

    companion object {
        val NONE = Access(allFiles = false)
        val ALL = Access(allFiles = true)

        // InlinedApi: permission names are plain strings, and each is asked for or checked only on its own Android version.

        /** What to ask for on the narrower path. Android 14 may answer with only the photos the person picks. */
        @SuppressLint("InlinedApi")
        fun mediaPermissions(sdk: Int): List<String> = when {
            sdk >= 34 -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )

            sdk >= 33 -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )

            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        /** [describe] names a memory card by its volume id (SafSource.volumeDescriber); null while it is out. */
        @SuppressLint("InlinedApi")
        fun of(sdk: Int, allFiles: Boolean, granted: (String) -> Boolean, persisted: List<String>, describe: (String) -> String? = { null }): Access {
            val folders = foldersFrom(persisted, describe)
            if (sdk < 33) {
                // Up to Android 12L one permission covers every kind of media.
                val media = granted(Manifest.permission.READ_EXTERNAL_STORAGE)
                return Access(allFiles, if (media) Photos.ALL else Photos.NONE, media, media, folders)
            }
            val photos = when {
                granted(Manifest.permission.READ_MEDIA_IMAGES) -> Photos.ALL
                sdk >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Photos.SELECTED
                else -> Photos.NONE
            }
            return Access(
                allFiles,
                photos,
                granted(Manifest.permission.READ_MEDIA_VIDEO),
                granted(Manifest.permission.READ_MEDIA_AUDIO),
                folders,
            )
        }

        /** Picked trees on this phone's storage, from the persisted URI grants. */
        fun foldersFrom(persisted: List<String>, describe: (String) -> String? = { null }): List<PickedFolder> = persisted
            .filter { it.startsWith(TREE_PREFIX) }
            .map { PickedFolder(it, folderName(it, describe)) }
            .sortedBy { it.name.lowercase() }

        /** "Documents/Work" for content://…/tree/primary%3ADocuments%2FWork. */
        fun folderName(treeUri: String, describe: (String) -> String? = { null }): String = SafSource.treeName(treeId(treeUri), describe)

        /** Why a picked tree can't be added, or null when it can: it is, or is inside, a folder already added. */
        fun refusePick(uri: String, existing: List<PickedFolder>): String? {
            if (!uri.startsWith(TREE_PREFIX)) return "Pick a folder on this phone's storage."
            val id = treeId(uri)
            return if (existing.any { within(id, treeId(it.uri)) }) "Retriever already searches that folder." else null
        }

        /** Added folders inside [uri]: picking their parent takes their place, so no file is listed twice. */
        fun inside(uri: String, existing: List<PickedFolder>): List<PickedFolder> {
            val id = treeId(uri)
            return existing.filter { within(treeId(it.uri), id) }
        }

        private const val TREE_PREFIX = SafSource.PREFIX + "tree/"

        /** "primary:Documents/Work"; the URI encodes it as one path segment. */
        private fun treeId(uri: String) = URLDecoder.decode(uri.removePrefix(TREE_PREFIX).substringBefore('/'), "UTF-8")

        /** A volume root's id ends in ':' ("primary:"), a folder's doesn't. */
        private fun within(id: String, parent: String) = id == parent || id.startsWith(if (parent.endsWith(':')) parent else "$parent/")
    }
}
