package com.sapphirebank.kyc.domain.model

/** Lifecycle stages S1..S8 (SBK-KYC-00 overview / SBK-KYC-13 state machine). */
enum class LifecycleStage(
    val number: Int,
    val code: String,
    val title: String,
    val ownerRole: String,
    val slaHours: Int,
) {
    S1_APPLICATION(1, "S1_APPLICATION", "Customer Application", "RELATIONSHIP_MANAGER", 4),
    S2_DOCUMENTS(2, "S2_DOCUMENTS", "Document Upload & Validation", "KYC_OPERATIONS_OFFICER", 24),
    S3_KYC(3, "S3_KYC", "KYC Verification", "KYC_OPERATIONS_OFFICER", 24),
    S4_AML(4, "S4_AML", "AML / PEP Screening", "COMPLIANCE_OFFICER", 24),
    S5_ELIGIBILITY(5, "S5_ELIGIBILITY", "Product Eligibility", "CREDIT_OFFICER", 24),
    S6_REVIEW(6, "S6_REVIEW", "Officer Review & Dual Control", "BRANCH_MANAGER", 48),
    S7_FULFILMENT(7, "S7_FULFILMENT", "Fulfilment", "RELATIONSHIP_MANAGER", 24),
    S8_AUDIT(8, "S8_AUDIT", "Immutable Audit Trail", "SYSTEM_SERVICE", 0),
}

/** Application statuses (SBK-KYC-13). */
enum class ApplicationStatus(val stage: LifecycleStage, val terminal: Boolean = false) {
    DRAFT(LifecycleStage.S1_APPLICATION),
    SUBMITTED(LifecycleStage.S2_DOCUMENTS),
    DOCUMENTS_PENDING(LifecycleStage.S2_DOCUMENTS),
    DOCUMENTS_VALIDATED(LifecycleStage.S3_KYC),
    KYC_IN_PROGRESS(LifecycleStage.S3_KYC),
    KYC_VERIFIED(LifecycleStage.S4_AML),
    AML_SCREENING(LifecycleStage.S4_AML),
    EDD_PENDING(LifecycleStage.S4_AML),
    AML_CLEARED(LifecycleStage.S5_ELIGIBILITY),
    ELIGIBILITY_CHECK(LifecycleStage.S5_ELIGIBILITY),
    ELIGIBLE(LifecycleStage.S6_REVIEW),
    PENDING_REVIEW(LifecycleStage.S6_REVIEW),
    APPROVED(LifecycleStage.S7_FULFILMENT),
    FULFILLED(LifecycleStage.S7_FULFILMENT, true),
    REJECTED(LifecycleStage.S1_APPLICATION, true),
    WITHDRAWN(LifecycleStage.S1_APPLICATION, true),
    EXPIRED(LifecycleStage.S1_APPLICATION, true),
}

enum class ProductLine { SAVINGS_ACCOUNT, CURRENT_ACCOUNT, TERM_DEPOSIT, RETAIL_LOAN }

enum class DocType(
    val label: String,
    val isOvd: Boolean,
    val provesIdentity: Boolean,
    val provesAddress: Boolean,
) {
    PAN("PAN card", false, true, false),
    FORM60("Form 60", false, false, false),
    AADHAAR("Aadhaar", true, true, true),
    PASSPORT("Passport", true, true, true),
    VOTER_ID("Voter ID (EPIC)", true, true, true),
    DRIVING_LICENCE("Driving licence", true, true, true),
    UTILITY_BILL("Utility bill (deemed OVD)", false, false, true),
}

enum class KycMode(val label: String) {
    VCIP("Video CIP"),
    AADHAAR_OTP_EKYC("Aadhaar OTP e-KYC (limited)"),
    IN_PERSON("In-person OVD"),
}

enum class RuleSeverity { BLOCKING, REFER, ADVISORY }
enum class RuleOutcome { PASS, FAIL, REFER, NOT_APPLICABLE, OVERRIDDEN, PENDING }

data class RuleEvaluation(
    val ruleId: String,
    val name: String,
    val severity: RuleSeverity,
    val outcome: RuleOutcome,
    val detail: String,
    val onFail: String,
)

enum class RiskCategory(val reviewYears: Int) { LOW(10), MEDIUM(8), HIGH(2) }

data class RiskAssessment(val score: Int, val category: RiskCategory, val factors: List<String>)

data class CustomerApplication(
    val arn: String,
    val customerName: String,
    val dateOfBirth: String,
    val productLine: ProductLine,
    val sanctionAmountInr: Long,
    val panNumber: String,
    val form60Submitted: Boolean,
    val identityDoc: DocType,
    val identityDocNumber: String,
    val addressDoc: DocType,
    val addressDocNumber: String,
    val kycMode: KycMode,
    val aadhaarConsentGiven: Boolean,
    val isPep: Boolean,
    val isNonIndividual: Boolean,
    val beneficialOwnerRecorded: Boolean,
    val status: ApplicationStatus,
    /** Country of residence (ISO alpha-2); drives country-risk screening. */
    val countryCode: String = "IN",
    /** Aadhaar is vaulted at intake: only the masked form is stored (last 4 digits) plus this reference (DOC-003, KYC-004). */
    val aadhaarVaultRef: String = "",
)

data class VcipSession(
    val geoTagInIndia: Boolean,
    val ipCountryCode: String,
    val officialIsTrainedBankEmployee: Boolean,
    val livenessPassed: Boolean,
    val panCapturedLive: Boolean,
    val ovdVerifiedViaSource: Boolean,
    val randomQuestionsAsked: Int,
    val recordingHashed: Boolean,
    val faceMatchScore: Double,
    val concurrentAuditPassed: Boolean,
)

enum class ApprovalLevel(val label: String) {
    L1_CREDIT_OFFICER("L1 Credit Officer"),
    L2_BRANCH_MANAGER("L2 Branch Manager"),
    L3_SENIOR_MANAGEMENT("L3 Senior Management"),
}

enum class Decision { APPROVED, REJECTED }

data class Officer(
    val userId: String,
    val displayName: String,
    val role: String,
    val delegatedLimitInr: Long,
)

data class ApprovalDecision(
    val level: ApprovalLevel,
    val approver: Officer,
    val decision: Decision,
    val decidedAt: String,
    val signOffSha256: String,
    /** Mandatory for L2/L3 approvers (DCS justification rule). */
    val justification: String = "",
)

data class AuditEvent(
    val sequenceNo: Int,
    val occurredAt: String,
    val eventType: String,
    val entityId: String,
    val actor: String,
    val payloadSha256: String,
    val previousEventHash: String,
    val eventHash: String,
)
