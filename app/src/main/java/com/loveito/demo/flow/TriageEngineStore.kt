package com.loveito.demo.flow

import android.content.Context
import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

object TriageEngineStore {
    private const val TAG = "TriageEngineStore"
    private const val PREFS = "triage_engine_prefs"
    private const val KEY_VERSION = "version"
    private const val KEY_SHA256 = "sha256"

    // Storage path where you uploaded the engine
    private const val STORAGE_PATH = "triage/engine.json"

    fun localFile(context: Context): File = File(context.filesDir, "flow/engine.json")

    suspend fun ensureLatest(context: Context): EngineUpdateResult {
        return withContext(Dispatchers.IO) {
            try {
                val storage = FirebaseStorage.getInstance()
                val ref = storage.reference.child(STORAGE_PATH)
                val md = ref.metadata.await()
                val remoteVersion = md.getCustomMetadata("version") ?: md.updatedTimeMillis.toString()
                val remoteSha = md.getCustomMetadata("sha256")

                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val localVersion = prefs.getString(KEY_VERSION, null)
                val localSha = prefs.getString(KEY_SHA256, null)

                // If versions match and (if sha provided) hash matches, skip
                if (localVersion != null && localVersion == remoteVersion) {
                    if (remoteSha.isNullOrBlank()) return@withContext EngineUpdateResult(false, localVersion, localSha)
                    val lf = localFile(context)
                    if (lf.exists()) {
                        val current = sha256Hex(lf)
                        if (current.equals(remoteSha, ignoreCase = true)) return@withContext EngineUpdateResult(false, localVersion, localSha)
                    }
                }

                // Download to temp and validate
                val destDir = localFile(context).parentFile ?: context.filesDir
                if (!destDir.exists()) destDir.mkdirs()
                val tmp = File(destDir, "engine.json.tmp")
                ref.getFile(tmp).await()
                if (!remoteSha.isNullOrBlank()) {
                    val got = sha256Hex(tmp)
                    if (!got.equals(remoteSha, ignoreCase = true)) {
                        tmp.delete()
                        return@withContext EngineUpdateResult(false, localVersion, localSha, error = "SHA256 mismatch")
                    }
                }
                // Atomic replace
                val final = localFile(context)
                if (final.exists()) final.delete()
                if (!tmp.renameTo(final)) {
                    // Fallback copy
                    FileInputStream(tmp).use { input ->
                        FileOutputStream(final).use { out -> input.copyTo(out) }
                    }
                }
                tmp.delete()
                prefs.edit().putString(KEY_VERSION, remoteVersion).putString(KEY_SHA256, remoteSha).apply()
                EngineUpdateResult(true, remoteVersion, remoteSha)
            } catch (e: Exception) {
                Log.w(TAG, "ensureLatest failed: ${e.message}")
                EngineUpdateResult(false, null, null, error = e.message)
            }
        }
    }

    fun readLocalJson(context: Context): String? {
        return try {
            val f = localFile(context)
            if (!f.exists()) return null
            f.readText()
        } catch (_: Exception) { null }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    data class EngineUpdateResult(val updated: Boolean, val version: String?, val sha256: String?, val error: String? = null)
}
