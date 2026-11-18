package com.sapphirebank.kyc.presentation.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.sapphirebank.kyc.domain.model.DocumentKind
import com.sapphirebank.kyc.domain.model.DocumentRecord

@Composable
fun DocumentPreviewDialog(record: DocumentRecord, onDismiss: () -> Unit) {
    val bmp = rememberBitmap(record.uri)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(record.kind.label) },
        text = {
            if (bmp != null) Image(bmp, contentDescription = "Preview of ${record.kind.label}", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            else Text(
                "${record.fileName} (v${record.version})\nNo image preview is available for this demo document." +
                    if (record.kind == DocumentKind.AADHAAR_MASKED) "\nAadhaar copies must show only the last 4 digits." else "",
            )
        },
    )
}
