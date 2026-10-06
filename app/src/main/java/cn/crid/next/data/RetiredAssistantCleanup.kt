package cn.crid.next.data

import android.content.Context
import java.io.File
import java.security.KeyStore

/** Remove only the retired feature's storage, without opening or decrypting its contents. */
internal fun removeRetiredAssistantData(context: Context) {
    runCatching { context.noBackupFilesDir }.getOrNull()?.let { directory ->
        listOf("assistant.secure", "assistant.secure.bak", "assistant.secure.new").forEach { name ->
            runCatching { File(directory, name).delete() }
        }
    }
    // Storage and keystore failures are independent and must not block timetable loading.
    runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("crid-next-ai")
    }
}
