package com.kabshah.delivra.attachments

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

/**
 * Attachment access helpers per §2.7 of wp_brain.md.
 *
 * Core contract:
 * - User picks file via SAF system picker (content:// URI returned).
 * - We immediately take a *persistent* read grant via takePersistableUriPermission,
 *   so we can re-open the file at send time, potentially days later, without copying it.
 * - We read only cheap metadata (name, size, MIME) at attach time — no file bytes in memory.
 * - Readability is validated twice: once here (fail-fast) and again in NodeBridge before send.
 */
object AttachmentAccess {

    private const val TAG = "AttachmentAccess"

    /**
     * Called immediately after the SAF file picker returns a URI.
     *
     * 1. Takes a persistent read-URI permission (survives app restarts/reboots).
     * 2. Validates the file is actually readable right now (fail-fast per §2.7 point 5).
     * 3. Reads metadata — display name, MIME type, and size — without reading file bytes.
     *
     * Returns [AttachmentMeta] on success, or null if the file can't be read.
     */
    fun takePersistentAccessAndReadMeta(context: Context, uri: Uri): AttachmentMeta? {
        // ── 1. Take persistent read permission ────────────────────────────────
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            Log.d(TAG, "Persistent read grant taken for $uri")
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not take persistent permission for $uri: ${e.message}")
            // Non-fatal at this stage — file may still be readable this session
            // But it will likely fail at send time, so we warn rather than block
        }

        // ── 2. Validate readability (fail-fast, §2.7 point 5) ─────────────────
        val readable = try {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "File not readable at attach time: $uri — ${e.message}")
            false
        }
        if (!readable) {
            Log.e(TAG, "Blocking attach: file at $uri is not readable")
            return null
        }

        // ── 3. Read metadata (no file bytes) ──────────────────────────────────
        var displayName: String? = null
        var sizeBytes: Long? = null
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                    if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read metadata for $uri: ${e.message}")
        }

        return AttachmentMeta(
            uri = uri.toString(),
            displayName = displayName ?: uri.lastPathSegment ?: "attachment",
            mimeType = mimeType,
            sizeBytes = sizeBytes
        )
    }

    /**
     * Re-validate that the URI is still readable at send time (§2.7 point 6).
     * Called from NodeBridge / ScheduleRepository before actually sending.
     */
    fun isReadable(context: Context, uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            if (uriString.startsWith("content://")) {
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            } else {
                java.io.File(uriString.removePrefix("file://")).let { it.exists() && it.length() > 0 }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Re-validation failed for $uriString: ${e.message}")
            false
        }
    }

    /**
     * Imports a file picked via the system chooser (GET_CONTENT — shows
     * "My Files", Photos, Drive, etc.) by COPYING it into app-private
     * storage at attach time.
     *
     * Why copy instead of persisting a SAF grant? GET_CONTENT URIs carry no
     * persistable permission, but a local copy is even stronger: the send —
     * potentially days later — can never lose access, and the source file
     * being moved/deleted in the meantime no longer breaks delivery.
     *
     * Returns [AttachmentMeta] whose `uri` is the LOCAL absolute file path,
     * or null if the pick is unreadable or the copy fails.
     */
    fun importAndStage(context: Context, uri: Uri): AttachmentMeta? {
        // ── 1. Fail-fast readability check ────────────────────────────────────
        val readable = try {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "File not readable at attach time: $uri — ${e.message}")
            false
        }
        if (!readable) {
            Log.e(TAG, "Blocking attach: file at $uri is not readable")
            return null
        }

        // ── 2. Metadata ───────────────────────────────────────────────────────
        var displayName: String? = null
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read metadata for $uri: ${e.message}")
        }
        val safeName = (displayName ?: "attachment")
            .replace(Regex("[^A-Za-z0-9._ \\-]"), "_")
            .take(80)
            .ifBlank { "attachment" }

        // ── 3. Copy into app-private storage ─────────────────────────────────
        return try {
            val dir = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
            val dest = java.io.File(dir, "${System.currentTimeMillis()}-$safeName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            AttachmentMeta(
                uri = dest.absolutePath,
                displayName = displayName ?: safeName,
                mimeType = mimeType,
                sizeBytes = dest.length()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Staging attachment failed: ${e.message}")
            null
        }
    }
}

data class AttachmentMeta(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?
)
