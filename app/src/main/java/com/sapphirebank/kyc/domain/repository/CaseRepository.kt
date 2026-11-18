package com.sapphirebank.kyc.domain.repository

import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.Officer
import kotlinx.coroutines.flow.StateFlow

/** Persistence boundary. The in-memory implementation lives in the data layer. */
interface CaseRepository {
    val current: StateFlow<CaseFile?>
    val officers: List<Officer>
    fun nextArnSequence(): Int
    suspend fun save(case: CaseFile)
    suspend fun clear()
}

/** On-device OCR boundary (ML Kit in the data layer). Returns raw recognised text. */
interface DocumentTextRecognizer {
    suspend fun recognize(imageUri: String): String
}
