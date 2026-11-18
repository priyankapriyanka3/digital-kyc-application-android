package com.sapphirebank.kyc.domain.model

/** Evidence gathered during Stage 2/3 that the KYC rules (KYC-005/006/008) evaluate. */
data class KycEvidence(
    val ocrName: String? = null,
    val ckycrSearched: Boolean = false,
    val vcip: VcipSession? = null,
    val vcipOfficialId: String? = null,
)

/** Aggregate for one application: everything the three screens render. */
data class CaseFile(
    val application: CustomerApplication,
    val sourcingRmId: String,
    val evidence: KycEvidence = KycEvidence(),
    val evaluations: List<RuleEvaluation> = emptyList(),
    val risk: RiskAssessment? = null,
    val approvals: List<ApprovalDecision> = emptyList(),
    val audit: List<AuditEvent> = emptyList(),
    val failedAt: LifecycleStage? = null,
)
