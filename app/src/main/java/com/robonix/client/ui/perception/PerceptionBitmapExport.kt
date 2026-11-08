package com.robonix.client.ui.perception

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.annotation.TargetApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class PerceptionExportChannel(val fileStem: String) {
    CAMERA("camera"),
    DEPTH("depth"),
    MAP("map"),
}

internal fun exportBitmapForChannel(state: PerceptionUiState, channel: PerceptionExportChannel): Bitmap? =
    when (channel) {
        PerceptionExportChannel.CAMERA -> state.cameraBitmap
        PerceptionExportChannel.DEPTH -> state.depthBitmap
        PerceptionExportChannel.MAP -> state.mapBitmap
    }?.takeUnless { it.isRecycled }

@TargetApi(Build.VERSION_CODES.Q)
internal suspend fun saveBitmapToGallery(
    resolver: ContentResolver,
    bitmap: Bitmap,
    channel: PerceptionExportChannel,
): Boolean = withContext(Dispatchers.IO) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "robonix_${channel.fileStem}_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Robonix")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    var uri: Uri? = null
    try {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        uri = resolver.insert(collection, values) ?: return@withContext false
        val wroteImage = resolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } ?: false
        if (!wroteImage) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext false
        }
        val published = resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null) > 0
        if (!published) runCatching { resolver.delete(uri, null, null) }
        published
    } catch (cancelled: CancellationException) {
        uri?.let { runCatching { resolver.delete(it, null, null) } }
        throw cancelled
    } catch (_: Exception) {
        uri?.let { runCatching { resolver.delete(it, null, null) } }
        false
    }
}

internal fun requiresDocumentPicker(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

internal suspend fun writeBitmapToDocument(resolver: ContentResolver, uri: Uri, bitmap: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val success = resolver.openOutputStream(uri, "w")?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
            if (!success) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            success
        } catch (cancelled: CancellationException) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw cancelled
        } catch (_: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            false
        }
    }

internal suspend fun deleteSnapshotDocument(resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    Unit
}
