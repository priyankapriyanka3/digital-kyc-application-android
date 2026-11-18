package com.sapphirebank.kyc.data

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sapphirebank.kyc.domain.repository.DocumentTextRecognizer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** On-device OCR via the bundled ML Kit Latin model: images never leave the phone. */
class MlKitTextRecognizer(private val context: Context) : DocumentTextRecognizer {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(imageUri: String): String = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromFilePath(context, Uri.parse(imageUri))
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resumeWithException(it) }
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }
}
