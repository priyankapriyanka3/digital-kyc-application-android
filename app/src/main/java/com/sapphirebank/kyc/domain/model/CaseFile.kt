package com.sapphirebank.kyc.domain.model

/** Evidence gathered during Stage 2/3 that the KYC rules (KYC-005/006/008) evaluate. */
data class KycEvidence(
    val ocrName: String? = null,
    val ocrDob: String? = null,
    val ckycrSearched: Boolean = false,
    val vcip: VcipSession? = null,
    val vcipOfficialId: String? = null,
    /** Set when an analyst compared name/DOB with the uploaded documents by eye (no OCR available). */
    val manualVerifiedBy: String? = null,
)

/** Aggregate for one application: everything the role workspaces render. */
data class CaseFile(
    val application: CustomerApplication,
    val sourcingRmId: String,
    val evidence: KycEvidence = KycEvidence(),
    val evaluations: List<RuleEvaluation> = emptyList(),
    val risk: RiskAssessment? = null,
    val approvals: List<ApprovalDecision> = emptyList(),
    val audit: List<AuditEvent> = emptyList(),
    val failedAt: LifecycleStage? = null,
    /** User who created the application (customer or staff). DCS-003: this user can never approve it. */
    val createdBy: String = sourcingRmId,
    val createdAt: String = "",
    val documents: List<DocumentRecord> = emptyList(),
    val infoRequests: List<InfoRequest> = emptyList(),
    val financials: FinancialProfile? = null,
    val screening: ScreeningResult? = null,
    val eligibility: EligibilityAssessment? = null,
    val kycDecision: KycDecision? = null,
    val hold: HoldInfo? = null,
    val assignedAnalystId: String? = null,
    val welcomeKit: WelcomeKit? = null,
) {
    val arn: String get() = application.arn
    val status: ApplicationStatus get() = application.status
    val openInfoRequests: List<InfoRequest> get() = infoRequests.filter { it.status == InfoRequestStatus.OPEN }
    val lastUpdated: String get() = audit.lastOrNull()?.occurredAt ?: createdAt
}
