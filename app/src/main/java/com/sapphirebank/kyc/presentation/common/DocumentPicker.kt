package com.sapphirebank.kyc.presentation.common

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sapphirebank.kyc.domain.model.DocumentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

inline fun <reified T : ViewModel> vmFactory(crossinline build: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = build() as V
    }

/** Opens the system picker for a document image and reports (kind, uri, display name). */
@Composable
fun rememberDocumentPicker(onPicked: (DocumentKind, String, String) -> Unit): (DocumentKind) -> Unit {
    val ctx = LocalContext.current
    val callback by rememberUpdatedState(onPicked)
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val kind = pending?.let { runCatching { DocumentKind.valueOf(it) }.getOrNull() }
        pending = null
        if (uri != null && kind != null) callback(kind, uri.toString(), displayName(ctx, uri))
    }
    return { kind -> pending = kind.name; launcher.launch("image/*") }
}

private fun displayName(ctx: Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "document.jpg"

/** Decodes a small preview of an image URI off the main thread. Returns null while loading or if it cannot be read. */
@Composable
fun rememberBitmap(uri: String?): ImageBitmap? {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        if (uri != null) bmp = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.openInputStream(Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}
