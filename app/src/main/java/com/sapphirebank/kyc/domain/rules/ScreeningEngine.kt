package com.sapphirebank.kyc.domain.rules

import com.sapphirebank.kyc.domain.model.BankConfig
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.CountryRisk
import com.sapphirebank.kyc.domain.model.Countries
import com.sapphirebank.kyc.domain.model.CustomerApplication
import com.sapphirebank.kyc.domain.model.DocumentKind
import com.sapphirebank.kyc.domain.model.DocumentRequirements
import com.sapphirebank.kyc.domain.model.EligibilityAssessment
import com.sapphirebank.kyc.domain.model.EligibilityIndicator
import com.sapphirebank.kyc.domain.model.EligibilityOutcome
import com.sapphirebank.kyc.domain.model.FinancialProfile
import com.sapphirebank.kyc.domain.model.Inr
import com.sapphirebank.kyc.domain.model.KycThresholds
import com.sapphirebank.kyc.domain.model.ProductLine
import com.sapphirebank.kyc.domain.model.RiskCategory
import com.sapphirebank.kyc.domain.model.RuleOutcome
import com.sapphirebank.kyc.domain.model.ScreeningEvidence
import com.sapphirebank.kyc.domain.model.ScreeningResult
import com.sapphirebank.kyc.domain.model.ScreeningStatus
import com.sapphirebank.kyc.domain.model.Tone
import com.sapphirebank.kyc.domain.model.ValidationCheck
import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.validation.DocumentValidators

/**
 * Stage 4 AML / PEP screening. Weights are ILLUSTRATIVE (the knowledge base publishes no scoring model);
 * the watchlist and country list are configuration owned by the System Admin.
 */
object ScreeningEngine {
    fun screen(app: CustomerApplication, cfg: BankConfig, nowIso: String): ScreeningResult {
        val best = cfg.watchlist.map { it to DocumentValidators.nameMatchScore(app.customerName, it) }.maxByOrNull { it.second }
        val hit = best?.takeIf { it.second >= cfg.thresholds.nameMatchMin }
        val risk = cfg.countryRisk[app.countryCode] ?: CountryRisk.MEDIUM
        var score = 0
        if (hit != null) score += 50
        if (app.isPep) score += 30
        score += when (risk) { CountryRisk.HIGH -> 25; CountryRisk.MEDIUM -> 10; CountryRisk.LOW -> 0 }
        score = minOf(score, 100)

        val evidence = buildList {
            add(ScreeningEvidence("Watchlist report",
                if (hit != null) "Name matches \"${hit.first}\" (similarity %.2f)".format(hit.second) else "No watchlist match for ${app.customerName}"))
            add(ScreeningEvidence("PEP database", if (app.isPep) "Declared politically exposed person" else "Not a PEP"))
            add(ScreeningEvidence("Country risk", "${Countries.label(app.countryCode)}: ${risk.label} risk"))
        }
        val flagged = hit != null || app.isPep || risk == CountryRisk.HIGH
        return ScreeningResult(
            watchlistMatch = hit != null, matchedName = hit?.first, pepMatch = app.isPep,
            countryCode = app.countryCode, countryRisk = risk, riskScore = score, evidence = evidence,
            status = if (flagged) ScreeningStatus.FLAGGED else ScreeningStatus.CLEAR, screenedAt = nowIso,
        )
    }
}

/** Stage 5 product eligibility. Thresholds are the editable EligibilityPolicy in the bank configuration. */
object EligibilityEngine {
    /** Standard reducing-balance EMI, rounded to the nearest rupee. */
    fun emi(principal: Long, annualPct: Double, months: Int): Long {
        if (months <= 0 || principal <= 0) return 0
        val r = annualPct / 1200.0
        if (r == 0.0) return Math.round(principal.toDouble() / months)
        val f = Math.pow(1 + r, months.toDouble())
        return Math.round(principal * r * f / (f - 1))
    }

    fun bureauBand(score: Int): String = when {
        score >= 750 -> "Excellent"; score >= 700 -> "Good"; score >= 650 -> "Fair"; else -> "Poor"
    }

    fun assess(app: CustomerApplication, fin: FinancialProfile?, bureau: Int?, docs: List<com.sapphirebank.kyc.domain.model.DocumentRecord>, cfg: BankConfig): EligibilityAssessment {
        val pol = cfg.eligibility
        val prod = cfg.product(app.productLine)
        val reasons = mutableListOf<String>()
        val ind = mutableListOf<EligibilityIndicator>()
        var worst = EligibilityOutcome.ELIGIBLE
        fun degrade(to: EligibilityOutcome, why: String) {
            reasons += why
            if (to.ordinal > worst.ordinal) worst = to
        }
        fun bureauIndicator() {
            if (bureau == null) { ind += EligibilityIndicator("Bureau score", "Not available", Tone.WATCH); return }
            val tone = when { bureau >= pol.minBureauScore -> Tone.GOOD; bureau >= pol.referBureauScore -> Tone.WATCH; else -> Tone.BAD }
            ind += EligibilityIndicator("Bureau score", "$bureau (${bureauBand(bureau)})", tone)
        }
        val have = docs.map { it.kind }.toSet()
        var proposed: Long? = null
        var dti: Int? = null

        when (app.productLine) {
            ProductLine.SAVINGS_ACCOUNT -> ind += EligibilityIndicator("Product", "Eligible for auto-approval after KYC", Tone.GOOD)
            ProductLine.TERM_DEPOSIT -> {
                val inRange = app.sanctionAmountInr in prod.minAmountInr..prod.maxAmountInr
                ind += EligibilityIndicator("Deposit amount", Inr.format(app.sanctionAmountInr), if (inRange) Tone.GOOD else Tone.BAD)
                ind += EligibilityIndicator("DICGC cover", Inr.format(cfg.dicgcCoverInr) + " per depositor", Tone.INFO)
                if (!inRange) degrade(EligibilityOutcome.INELIGIBLE, "Amount outside the product range ${Inr.format(prod.minAmountInr)} to ${Inr.format(prod.maxAmountInr)}")
            }
            ProductLine.CURRENT_ACCOUNT -> {
                val proof = DocumentKind.BUSINESS_PROOF in have
                ind += EligibilityIndicator("Business proof", if (proof) "Uploaded" else "Missing", if (proof) Tone.GOOD else Tone.BAD)
                bureauIndicator()
                if (!proof) degrade(EligibilityOutcome.REFER, "Business proof not uploaded")
                if (bureau != null && bureau < pol.minBureauScore) degrade(EligibilityOutcome.REFER, "Bureau score below ${pol.minBureauScore}")
            }
            ProductLine.RETAIL_LOAN -> {
                val income = fin?.monthlyIncomeInr ?: 0L
                val existing = fin?.existingEmiInr ?: 0L
                if (fin == null || income <= 0) {
                    ind += EligibilityIndicator("Monthly income", "Not provided", Tone.BAD)
                    degrade(EligibilityOutcome.REFER, "Financial profile missing")
                } else {
                    ind += EligibilityIndicator("Monthly income", Inr.format(income), Tone.INFO)
                    proposed = emi(app.sanctionAmountInr, prod.annualRatePct, fin.tenureMonths)
                    dti = (((existing + proposed) * 100.0) / income).let { Math.round(it).toInt() }
                    ind += EligibilityIndicator("Proposed EMI", Inr.format(proposed), Tone.INFO)
                    val tone = when { dti <= pol.maxDtiPct -> Tone.GOOD; dti <= pol.referDtiPct -> Tone.WATCH; else -> Tone.BAD }
                    ind += EligibilityIndicator("Debt-to-income", "$dti%", tone)
                    if (dti > pol.referDtiPct) degrade(EligibilityOutcome.INELIGIBLE, "Debt-to-income $dti% is above ${pol.referDtiPct}%")
                    else if (dti > pol.maxDtiPct) degrade(EligibilityOutcome.REFER, "Debt-to-income $dti% is above ${pol.maxDtiPct}%")
                }
                val proof = DocumentKind.INCOME_PROOF in have
                ind += EligibilityIndicator("Income proof", if (proof) "Uploaded" else "Missing", if (proof) Tone.GOOD else Tone.BAD)
                if (!proof) degrade(EligibilityOutcome.REFER, "Income proof not uploaded")
                bureauIndicator()
                if (bureau != null) {
                    if (bureau < pol.referBureauScore) degrade(EligibilityOutcome.INELIGIBLE, "Bureau score $bureau is below ${pol.referBureauScore}")
                    else if (bureau < pol.minBureauScore) degrade(EligibilityOutcome.REFER, "Bureau score $bureau is below ${pol.minBureauScore}")
                } else degrade(EligibilityOutcome.REFER, "No bureau score available")
            }
        }
        return EligibilityAssessment(
            outcome = worst, monthlyIncomeInr = fin?.monthlyIncomeInr, existingEmiInr = fin?.existingEmiInr,
            proposedEmiInr = proposed, dtiPct = dti, bureauScore = bureau, bureauBand = bureau?.let { bureauBand(it) },
            indicators = ind, reasons = reasons,
        )
    }
}

/** Savings accounts are auto-approved after successful KYC; everything else needs human sign-off. */
object AutoApproval {
    /**
     * A PEP is never auto-approved. A HIGH risk category blocks auto-approval unless a Compliance Officer has
     * explicitly cleared the screening flag (a cleared false positive, e.g. a watchlist name collision).
     */
    fun allowed(c: CaseFile): Boolean {
        val sc = c.screening?.status
        val clearedByCompliance = sc == ScreeningStatus.CLEARED_BY_COMPLIANCE
        return c.application.productLine == ProductLine.SAVINGS_ACCOUNT &&
            c.application.status == ApplicationStatus.PENDING_REVIEW &&
            !c.application.isPep &&
            (c.risk?.category != RiskCategory.HIGH || clearedByCompliance) &&
            (sc == ScreeningStatus.CLEAR || clearedByCompliance) &&
            c.openInfoRequests.isEmpty() &&
            c.hold == null &&
            c.eligibility?.outcome != EligibilityOutcome.INELIGIBLE
    }
}

/** The "Validation Results" checklist an analyst sees: name, DOB, address, PAN and identity. */
object ValidationChecks {
    fun build(c: CaseFile, t: KycThresholds = KycThresholds()): List<ValidationCheck> {
        val a = c.application
        val ev = c.evidence
        val evals = c.evaluations.ifEmpty { KycRuleEngine.evaluate(a, ev, t) }
        fun rule(id: String) = evals.first { it.ruleId == id }
        val manual = ev.manualVerifiedBy

        val name = when {
            ev.ocrName != null -> {
                val s = DocumentValidators.nameMatchScore(a.customerName, ev.ocrName)
                ValidationCheck("Name match", if (s >= t.nameMatchMin) RuleOutcome.PASS else RuleOutcome.REFER, "Document name \"${ev.ocrName}\" vs application: %.2f".format(s))
            }
            manual != null -> ValidationCheck("Name match", RuleOutcome.PASS, "Compared with documents by $manual")
            else -> ValidationCheck("Name match", RuleOutcome.PENDING, "No scanned name: compare manually")
        }
        val dob = when {
            ev.ocrDob != null -> if (ev.ocrDob == a.dateOfBirth) ValidationCheck("DOB match", RuleOutcome.PASS, "Document DOB ${ev.ocrDob}")
            else ValidationCheck("DOB match", RuleOutcome.FAIL, "Document DOB ${ev.ocrDob} differs from ${a.dateOfBirth}")
            manual != null -> ValidationCheck("DOB match", RuleOutcome.PASS, "Compared with documents by $manual")
            else -> ValidationCheck("DOB match", RuleOutcome.PENDING, "No scanned DOB: compare manually")
        }
        val k3 = rule("KYC-003")
        val addr = ValidationCheck("Address valid", k3.outcome, k3.detail)
        val k1 = rule("KYC-001")
        val k2 = rule("KYC-002")
        return listOf(name, dob, addr, ValidationCheck("PAN / Form 60", k1.outcome, k1.detail), ValidationCheck("Identity proof", k2.outcome, k2.detail))
    }

    fun missingDocuments(c: CaseFile) = DocumentRequirements.missing(c.application, c.documents)
}
