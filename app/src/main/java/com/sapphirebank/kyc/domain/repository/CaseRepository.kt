package com.sapphirebank.kyc.domain.repository

import com.sapphirebank.kyc.domain.model.AdminEvent
import com.sapphirebank.kyc.domain.model.BankConfig
import com.sapphirebank.kyc.domain.model.CaseFile
import kotlinx.coroutines.flow.StateFlow

/** Raised when a save would rewrite or drop existing audit history (the trail is immutable, AUD-001/002). */
class AuditTamperException(message: String) : IllegalStateException(message)

/**
 * Persistence boundary. The in-memory implementation lives in the data layer; a Room or REST implementation
 * can replace it without touching the domain or presentation layers.
 */
interface CaseRepository {
    val cases: StateFlow<List<CaseFile>>
    val config: StateFlow<BankConfig>
    val adminLog: StateFlow<List<AdminEvent>>
    fun nextArnSequence(): Int
    fun find(arn: String): CaseFile?

    /** Append-only: the stored audit chain and approvals must remain a prefix of the saved case, and the chain must verify. */
    suspend fun save(case: CaseFile)

    suspend fun updateConfig(config: BankConfig, event: AdminEvent)
}

/** On-device OCR boundary (ML Kit in the data layer). Returns raw recognised text. */
interface DocumentTextRecognizer {
    suspend fun recognize(imageUri: String): String
}

/** Credit-bureau boundary. The bundled implementation is a SIMULATION; swap in a real bureau client. */
fun interface CreditBureau {
    fun score(pan: String): Int?
}
