package app.retriever

import android.app.backup.BackupAgentHelper
import android.app.backup.BackupDataOutput
import android.app.backup.FileBackupHelper
import android.os.ParcelFileDescriptor

/**
 * Settings and stars ([USER_STATE]) as a key-value backup. A full (Auto Backup) backup runs the app in restricted mode and
 * then kills its process, always on Android 15 and below, which ended a running processing session; a key-value backup
 * runs in the live process and leaves it running. As data_extraction_rules.xml says, a cloud backup goes only end-to-end
 * encrypted; a device-to-device transfer always goes.
 */
class StateBackupAgent : BackupAgentHelper() {
    override fun onCreate() = addHelper("state", FileBackupHelper(this, USER_STATE))

    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput, newState: ParcelFileDescriptor) {
        if (data.transportFlags and (FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED or FLAG_DEVICE_TO_DEVICE_TRANSFER) != 0) {
            super.onBackup(oldState, data, newState)
        }
    }

    companion object {
        /** In filesDir; the index and the model are rebuilt and downloaded again, so nothing else is backed up. */
        const val USER_STATE = "user-state.json"
    }
}
