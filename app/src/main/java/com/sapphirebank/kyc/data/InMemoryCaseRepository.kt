package com.sapphirebank.kyc.data

import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.Officer
import com.sapphirebank.kyc.domain.repository.CaseRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single in-memory case. Swap for Room / a REST client without touching domain or presentation. */
class InMemoryCaseRepository : CaseRepository {
    private val state = MutableStateFlow<CaseFile?>(null)
    private var seq = 0

    override val current: StateFlow<CaseFile?> = state.asStateFlow()
    override val officers: List<Officer> = SeedData.officers
    override fun nextArnSequence(): Int = ++seq
    override suspend fun save(case: CaseFile) { state.value = case }
    override suspend fun clear() { state.value = null }
}

/** Officer roster from SBK-KYC-14 (roles and dual control). User ids follow the sample-journey pattern. */
object SeedData {
    val officers = listOf(
        Officer("U-RM-01", "Ananya Iyer", "RELATIONSHIP_MANAGER", 0),
        Officer("U-KYC-01", "Farhan Qureshi", "KYC_OPERATIONS_OFFICER", 0),
        Officer("U-VCP-01", "Sandeep Rao", "VCIP_OFFICIAL", 0),
        Officer("U-CMP-01", "Kavita Menon", "COMPLIANCE_OFFICER", 0),
        Officer("U-CRD-01", "Vikram Desai", "CREDIT_OFFICER", 2_500_000),
        Officer("U-BM-01", "Lakshmi Narayanan", "BRANCH_MANAGER", 10_000_000),
        Officer("U-SM-01", "Dr. Nandini Rao", "SENIOR_MANAGEMENT", 100_000_000),
        Officer("U-AUD-01", "Tanvi Shah", "INTERNAL_AUDITOR", 0),
    )
}
