package com.sapphirebank.kyc.data

import com.sapphirebank.kyc.domain.model.AdminEvent
import com.sapphirebank.kyc.domain.model.BankConfig
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.repository.AuditTamperException
import com.sapphirebank.kyc.domain.repository.CaseRepository
import com.sapphirebank.kyc.domain.rules.AuditChain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory store. Swap for Room / a REST client without touching the domain or presentation layers.
 *
 * Immutability (AUD-001/002): a save is rejected if it drops or rewrites an existing audit event, approval or
 * uploaded document, or if the resulting hash chain does not verify. The admin log is append-only by construction.
 */
class InMemoryCaseRepository(initial: BankConfig = BankConfig()) : CaseRepository {
    private val caseState = MutableStateFlow<List<CaseFile>>(emptyList())
    private val configState = MutableStateFlow(initial)
    private val adminState = MutableStateFlow<List<AdminEvent>>(emptyList())
    private val seq = AtomicInteger(0)

    override val cases: StateFlow<List<CaseFile>> = caseState.asStateFlow()
    override val config: StateFlow<BankConfig> = configState.asStateFlow()
    override val adminLog: StateFlow<List<AdminEvent>> = adminState.asStateFlow()

    override fun nextArnSequence(): Int = seq.incrementAndGet()
    override fun find(arn: String): CaseFile? = caseState.value.firstOrNull { it.arn == arn }

    override suspend fun save(case: CaseFile) {
        val existing = find(case.arn)
        if (existing != null) {
            if (case.audit.size < existing.audit.size || case.audit.take(existing.audit.size) != existing.audit)
                throw AuditTamperException("audit history of ${case.arn} cannot be altered or shortened")
            if (case.approvals.take(existing.approvals.size) != existing.approvals)
                throw AuditTamperException("recorded approvals of ${case.arn} cannot be altered")
            if (case.documents.take(existing.documents.size) != existing.documents)
                throw AuditTamperException("uploaded documents of ${case.arn} are versioned, not replaced")
            if (case.infoRequests.size < existing.infoRequests.size)
                throw AuditTamperException("information requests of ${case.arn} cannot be removed")
        }
        val v = AuditChain.verify(case.audit)
        if (!v.valid) throw AuditTamperException(v.message)
        caseState.update { list ->
            if (existing == null) list + case else list.map { if (it.arn == case.arn) case else it }
        }
    }

    override suspend fun updateConfig(config: BankConfig, event: AdminEvent) {
        configState.value = config
        adminState.update { it + event }
    }
}
