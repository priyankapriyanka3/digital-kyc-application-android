package com.sapphirebank.kyc.domain.model

/** Indian-grouped rupee formatting, e.g. 750000 -> "₹7,50,000". */
object Inr {
    fun format(amount: Long): String {
        val neg = amount < 0
        val s = Math.abs(amount).toString()
        val grouped = if (s.length <= 3) s else {
            val head = s.dropLast(3)
            val tail = s.takeLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
        }
        return (if (neg) "-" else "") + "₹" + grouped
    }
}

fun ProductLine.displayName(): String = when (this) {
    ProductLine.SAVINGS_ACCOUNT -> "Savings Account"
    ProductLine.CURRENT_ACCOUNT -> "Current Account"
    ProductLine.TERM_DEPOSIT -> "Fixed / Recurring Deposit"
    ProductLine.RETAIL_LOAN -> "Retail Loan"
}

fun ProductLine.shortName(): String = when (this) {
    ProductLine.SAVINGS_ACCOUNT -> "Savings"
    ProductLine.CURRENT_ACCOUNT -> "Current"
    ProductLine.TERM_DEPOSIT -> "FD / RD"
    ProductLine.RETAIL_LOAN -> "Retail Loan"
}

/** DICGC insurance information that FD / RD screens must show. The cover amount is configuration. */
fun dicgcNote(product: ProductLine, coverInr: Long): String? =
    if (product == ProductLine.TERM_DEPOSIT)
        "Deposits are insured by DICGC up to ${Inr.format(coverInr)} per depositor per bank (principal and interest combined)."
    else null

enum class DocumentKind(val label: String, val hint: String) {
    PAN("PAN card", "Clear photo of the PAN card"),
    AADHAAR_MASKED("Aadhaar (masked)", "Upload the masked copy: only the last 4 digits may be visible"),
    IDENTITY_OTHER("Identity proof", "Passport, voter ID or driving licence"),
    ADDRESS_PROOF("Address proof", "Aadhaar, passport, voter ID, driving licence or a recent utility bill"),
    PHOTOGRAPH("Photograph", "Recent passport-size photograph, face clearly visible"),
    INCOME_PROOF("Income proof", "Latest salary slips or income-tax return"),
    BUSINESS_PROOF("Business proof", "GST registration, shop & establishment or incorporation certificate"),
    BANK_STATEMENT("Bank statement", "Last six months"),
}

data class DocumentRecord(
    val kind: DocumentKind,
    val fileName: String,
    val uri: String? = null,
    val uploadedAt: String,
    val uploadedBy: String,
    val version: Int = 1,
)

object DocumentRequirements {
    fun required(app: CustomerApplication): List<DocumentKind> = buildList {
        if (app.panNumber.isNotBlank()) add(DocumentKind.PAN)
        add(if (app.identityDoc == DocType.AADHAAR) DocumentKind.AADHAAR_MASKED else DocumentKind.IDENTITY_OTHER)
        add(DocumentKind.ADDRESS_PROOF)
        add(DocumentKind.PHOTOGRAPH)
        when (app.productLine) {
            ProductLine.CURRENT_ACCOUNT -> { add(DocumentKind.BUSINESS_PROOF); add(DocumentKind.BANK_STATEMENT) }
            ProductLine.RETAIL_LOAN -> { add(DocumentKind.INCOME_PROOF); add(DocumentKind.BANK_STATEMENT) }
            else -> Unit
        }
    }

    /** Latest upload per kind. */
    fun latest(docs: List<DocumentRecord>): Map<DocumentKind, DocumentRecord> =
        docs.groupBy { it.kind }.mapValues { (_, v) -> v.maxBy { it.version } }

    fun missing(app: CustomerApplication, docs: List<DocumentRecord>): List<DocumentKind> {
        val have = docs.map { it.kind }.toSet()
        return required(app).filter { it !in have }
    }
}

enum class InfoRequestStatus { OPEN, RESPONDED }

data class InfoRequest(
    val id: String,
    val raisedBy: String,
    val raisedByRole: String,
    val message: String,
    val kinds: List<DocumentKind>,
    val raisedAt: String,
    val status: InfoRequestStatus = InfoRequestStatus.OPEN,
    val response: String = "",
    val respondedAt: String? = null,
)

data class FinancialProfile(
    val monthlyIncomeInr: Long,
    val existingEmiInr: Long,
    val employmentType: String,
    val tenureMonths: Int,
)

enum class ScreeningStatus { CLEAR, FLAGGED, CLEARED_BY_COMPLIANCE, ON_HOLD }

data class ScreeningEvidence(val title: String, val detail: String)

data class ScreeningResult(
    val watchlistMatch: Boolean,
    val matchedName: String?,
    val pepMatch: Boolean,
    val countryCode: String,
    val countryRisk: CountryRisk,
    val riskScore: Int,
    val evidence: List<ScreeningEvidence>,
    val status: ScreeningStatus,
    val screenedAt: String,
    val decidedBy: String? = null,
    val decisionNote: String = "",
    val decidedAt: String? = null,
)

enum class EligibilityOutcome(val label: String) { ELIGIBLE("Eligible"), REFER("Refer"), INELIGIBLE("Not eligible") }
enum class Tone { GOOD, WATCH, BAD, INFO }

data class EligibilityIndicator(val label: String, val value: String, val tone: Tone)

data class EligibilityAssessment(
    val outcome: EligibilityOutcome,
    val monthlyIncomeInr: Long?,
    val existingEmiInr: Long?,
    val proposedEmiInr: Long?,
    val dtiPct: Int?,
    val bureauScore: Int?,
    val bureauBand: String?,
    val indicators: List<EligibilityIndicator>,
    val reasons: List<String>,
)

enum class KycOutcome { APPROVED, REJECTED }
data class KycDecision(val outcome: KycOutcome, val by: String, val comment: String, val at: String)
data class HoldInfo(val by: String, val note: String, val at: String)

data class WelcomeKit(
    val accountNumber: String,
    val ifsc: String,
    val branch: String,
    val items: List<String>,
    val dicgcNote: String?,
)

data class ValidationCheck(val label: String, val outcome: RuleOutcome, val detail: String)
