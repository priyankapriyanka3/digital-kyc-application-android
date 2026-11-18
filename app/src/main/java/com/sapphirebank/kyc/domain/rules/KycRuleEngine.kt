package com.sapphirebank.kyc.domain.rules

import com.sapphirebank.kyc.domain.model.CustomerApplication
import com.sapphirebank.kyc.domain.model.DocType
import com.sapphirebank.kyc.domain.model.KycEvidence
import com.sapphirebank.kyc.domain.model.KycMode
import com.sapphirebank.kyc.domain.model.ProductLine
import com.sapphirebank.kyc.domain.model.RuleEvaluation
import com.sapphirebank.kyc.domain.model.RuleOutcome
import com.sapphirebank.kyc.domain.model.RuleSeverity
import com.sapphirebank.kyc.domain.validation.DocumentValidators

/**
 * Evaluates rules KYC-001..KYC-009 (SBK-KYC-10 rule catalogue, stage S3_KYC).
 * Thresholds are the catalogue's configurable parameters.
 */
object KycRuleEngine {

    const val NAME_MATCH_MIN = 0.85          // KYC-008 nameMatchMinScore
    const val FACE_MATCH_MIN_KYC008 = 0.85   // KYC-008 faceMatchMinScore
    const val FACE_MATCH_MIN_VCIP = 0.90     // KYC-006 faceMatchMinScore
    const val MIN_RANDOM_QUESTIONS = 3       // KYC-006 minRandomQuestions
    const val LIMITED_KYC_DEPOSIT_CAP_INR = 100_000L   // KYC-007 balance cap
    const val LIMITED_KYC_LOAN_CAP_INR = 60_000L       // KYC-007 loans per year
    const val DEEMED_OVD_WINDOW_DAYS = 90    // KYC-003 deemedOvdUpdateWindowDays

    private val boApplicable = setOf(ProductLine.CURRENT_ACCOUNT, ProductLine.RETAIL_LOAN, ProductLine.TERM_DEPOSIT)

    fun evaluate(app: CustomerApplication, ev: KycEvidence): List<RuleEvaluation> = listOf(
        kyc001(app), kyc002(app), kyc003(app), kyc004(app), kyc005(ev),
        kyc006(app, ev), kyc007(app), kyc008(app, ev), kyc009(app),
    )

    /**
     * KYC_VERIFIED requires KYC-001..009 to be PASS or NOT_APPLICABLE (SBK-KYC-04). The one
     * ADVISORY rule (KYC-005 CKYCR search) never blocks: its on-fail action is "proceed with fresh KYC".
     */
    fun isVerifiable(evals: List<RuleEvaluation>): Boolean =
        evals.isNotEmpty() && evals.filter { it.severity != RuleSeverity.ADVISORY }
            .all { it.outcome == RuleOutcome.PASS || it.outcome == RuleOutcome.NOT_APPLICABLE }

    fun blockingFailures(evals: List<RuleEvaluation>): List<RuleEvaluation> =
        evals.filter { it.severity == RuleSeverity.BLOCKING && it.outcome == RuleOutcome.FAIL }

    private fun eval(
        id: String, name: String, sev: RuleSeverity, outcome: RuleOutcome, detail: String, onFail: String,
    ) = RuleEvaluation(id, name, sev, outcome, detail, onFail)

    // KYC-001  PAN or Form 60 captured: PAN (verified) XOR Form 60.
    private fun kyc001(a: CustomerApplication): RuleEvaluation {
        val hasPan = a.panNumber.isNotBlank()
        val panOk = DocumentValidators.isValidPan(a.panNumber)
        val (o, d) = when {
            hasPan && a.form60Submitted -> RuleOutcome.FAIL to "Both PAN and Form 60 supplied; exactly one is allowed"
            hasPan && panOk -> RuleOutcome.PASS to "PAN ${DocumentValidators.mask(DocType.PAN, a.panNumber)} format valid"
            hasPan -> RuleOutcome.FAIL to "PAN does not match AAAAA9999A"
            a.form60Submitted -> RuleOutcome.PASS to "Form 60 on file (no PAN)"
            else -> RuleOutcome.FAIL to "Neither PAN nor Form 60 captured"
        }
        return eval("KYC-001", "PAN or Form 60 captured", RuleSeverity.BLOCKING, o, d, "Block KYC verification")
    }

    // KYC-002  Identity established through OVD / e-KYC.
    private fun kyc002(a: CustomerApplication): RuleEvaluation {
        val r = DocumentValidators.validateNumber(a.identityDoc, a.identityDocNumber)
        val ok = a.identityDoc.provesIdentity && r.valid
        val d = if (ok) "${a.identityDoc.label} ${DocumentValidators.mask(a.identityDoc, a.identityDocNumber)} accepted as identity proof"
        else if (!a.identityDoc.provesIdentity) "${a.identityDoc.label} does not prove identity"
        else r.message
        return eval("KYC-002", "Identity established through OVD / e-KYC", RuleSeverity.BLOCKING,
            if (ok) RuleOutcome.PASS else RuleOutcome.FAIL, d, "KYC FAILED; request alternate identity evidence")
    }

    // KYC-003  Current address established by OVD, or deemed OVD with undertaking.
    private fun kyc003(a: CustomerApplication): RuleEvaluation {
        val r = DocumentValidators.validateNumber(a.addressDoc, a.addressDocNumber)
        val (o, d) = when {
            !r.valid -> RuleOutcome.FAIL to r.message
            a.addressDoc.isOvd && a.addressDoc.provesAddress -> RuleOutcome.PASS to "Address proven by OVD (${a.addressDoc.label})"
            a.addressDoc == DocType.UTILITY_BILL ->
                RuleOutcome.REFER to "Deemed OVD; customer must submit an OVD within $DEEMED_OVD_WINDOW_DAYS days"
            else -> RuleOutcome.FAIL to "${a.addressDoc.label} does not prove address"
        }
        return eval("KYC-003", "Current address established", RuleSeverity.BLOCKING, o, d, "KYC REFERRED; request address proof")
    }

    // KYC-004  Aadhaar authentication with explicit consent (only for Aadhaar modes).
    private fun kyc004(a: CustomerApplication): RuleEvaluation {
        val (o, d) = when {
            a.kycMode != KycMode.AADHAAR_OTP_EKYC -> RuleOutcome.NOT_APPLICABLE to "KYC mode is ${a.kycMode.label}; no Aadhaar authentication"
            !a.aadhaarConsentGiven -> RuleOutcome.FAIL to "ConsentRecord(AADHAAR_AUTHENTICATION) not GIVEN before e-KYC"
            a.identityDoc != DocType.AADHAAR -> RuleOutcome.FAIL to "e-KYC mode needs an Aadhaar number"
            else -> RuleOutcome.PASS to "Consent GIVEN; Aadhaar kept as vault reference only"
        }
        return eval("KYC-004", "Aadhaar authentication with explicit consent", RuleSeverity.BLOCKING, o, d,
            "KYC FAILED; fall back to V-CIP or in-person OVD")
    }

    // KYC-005  CKYCR search and KIN reuse (ADVISORY).
    private fun kyc005(ev: KycEvidence): RuleEvaluation = eval(
        "KYC-005", "CKYCR search and KIN reuse", RuleSeverity.ADVISORY,
        if (ev.ckycrSearched) RuleOutcome.PASS else RuleOutcome.PENDING,
        if (ev.ckycrSearched) "CKYCR searched before fresh KYC" else "CKYCR search not performed yet",
        "Proceed with fresh KYC",
    )

    // KYC-006  V-CIP session standards.
    private fun kyc006(a: CustomerApplication, ev: KycEvidence): RuleEvaluation {
        val name = "V-CIP session standards"
        val onFail = "Reject session; schedule new V-CIP or in-person"
        if (a.kycMode != KycMode.VCIP) {
            return eval("KYC-006", name, RuleSeverity.BLOCKING, RuleOutcome.NOT_APPLICABLE, "KYC mode is ${a.kycMode.label}", onFail)
        }
        val s = ev.vcip ?: return eval("KYC-006", name, RuleSeverity.BLOCKING, RuleOutcome.PENDING, "V-CIP session not recorded yet", onFail)
        val failures = buildList {
            if (!s.geoTagInIndia) add("geo-tag outside India")
            if (s.ipCountryCode != "IN") add("IP country ${s.ipCountryCode} != IN")
            if (!s.officialIsTrainedBankEmployee) add("official not a trained bank employee")
            if (!s.livenessPassed) add("liveness not PASS")
            if (!s.panCapturedLive) add("PAN not captured live")
            if (!s.ovdVerifiedViaSource) add("OVD not verified at source")
            if (s.randomQuestionsAsked < MIN_RANDOM_QUESTIONS) add("only ${s.randomQuestionsAsked}/$MIN_RANDOM_QUESTIONS random questions")
            if (!s.recordingHashed) add("recording not hashed")
            if (s.faceMatchScore < FACE_MATCH_MIN_VCIP) add("face match %.2f < %.2f".format(s.faceMatchScore, FACE_MATCH_MIN_VCIP))
            if (!s.concurrentAuditPassed) add("concurrent audit not PASSED")
        }
        return if (failures.isEmpty())
            eval("KYC-006", name, RuleSeverity.BLOCKING, RuleOutcome.PASS, "All 10 V-CIP standards met", onFail)
        else eval("KYC-006", name, RuleSeverity.BLOCKING, RuleOutcome.FAIL, failures.joinToString("; "), onFail)
    }

    // KYC-007  Limited-KYC (OTP e-KYC) caps.
    private fun kyc007(a: CustomerApplication): RuleEvaluation {
        val name = "Limited-KYC (OTP e-KYC) caps and one-year conversion"
        val onFail = "Debit-freeze on expiry; reject application if product disallows"
        if (a.kycMode != KycMode.AADHAAR_OTP_EKYC) {
            return eval("KYC-007", name, RuleSeverity.BLOCKING, RuleOutcome.NOT_APPLICABLE, "Not a limited-KYC application", onFail)
        }
        val cap = if (a.productLine == ProductLine.RETAIL_LOAN) LIMITED_KYC_LOAN_CAP_INR else LIMITED_KYC_DEPOSIT_CAP_INR
        return if (a.sanctionAmountInr <= cap)
            eval("KYC-007", name, RuleSeverity.BLOCKING, RuleOutcome.PASS,
                "Amount INR ${a.sanctionAmountInr} within limited-KYC cap INR $cap; convert to full CDD within 1 year", onFail)
        else eval("KYC-007", name, RuleSeverity.BLOCKING, RuleOutcome.FAIL,
            "Amount INR ${a.sanctionAmountInr} exceeds limited-KYC cap INR $cap; use V-CIP or in-person", onFail)
    }

    // KYC-008  Cross-document consistency and photograph match (REFER).
    private fun kyc008(a: CustomerApplication, ev: KycEvidence): RuleEvaluation {
        val name = "Cross-document consistency and photograph match"
        val onFail = "Refer to KYC operations officer"
        val ocr = ev.ocrName ?: return eval("KYC-008", name, RuleSeverity.REFER, RuleOutcome.PENDING,
            "No document name captured (scan PAN/ID to compare)", onFail)
        val nameScore = DocumentValidators.nameMatchScore(a.customerName, ocr)
        val face = ev.vcip?.faceMatchScore
        val problems = buildList {
            if (nameScore < NAME_MATCH_MIN) add("name match %.2f < %.2f".format(nameScore, NAME_MATCH_MIN))
            if (face != null && face < FACE_MATCH_MIN_KYC008) add("face match %.2f < %.2f".format(face, FACE_MATCH_MIN_KYC008))
        }
        return if (problems.isEmpty())
            eval("KYC-008", name, RuleSeverity.REFER, RuleOutcome.PASS,
                "Name match %.2f%s".format(nameScore, if (face != null) ", face match %.2f".format(face) else ""), onFail)
        else eval("KYC-008", name, RuleSeverity.REFER, RuleOutcome.REFER, problems.joinToString("; "), onFail)
    }

    // KYC-009  Beneficial owner identified (non-individuals; CA / loan / TD).
    private fun kyc009(a: CustomerApplication): RuleEvaluation {
        val name = "Beneficial owner and controllers identified"
        val onFail = "KYC FAILED; request ownership declaration"
        val (o, d) = when {
            !a.isNonIndividual || a.productLine !in boApplicable ->
                RuleOutcome.NOT_APPLICABLE to "Individual customer or product without BO requirement"
            a.beneficialOwnerRecorded -> RuleOutcome.PASS to "Beneficial owner identified and KYC-verified"
            else -> RuleOutcome.FAIL to "No beneficial owner / senior managing official recorded"
        }
        return eval("KYC-009", name, RuleSeverity.BLOCKING, o, d, onFail)
    }
}
