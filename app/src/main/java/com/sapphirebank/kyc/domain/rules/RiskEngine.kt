package com.sapphirebank.kyc.domain.rules

import com.sapphirebank.kyc.domain.model.CountryRisk
import com.sapphirebank.kyc.domain.model.CustomerApplication
import com.sapphirebank.kyc.domain.model.ScreeningResult
import com.sapphirebank.kyc.domain.model.KycMode
import com.sapphirebank.kyc.domain.model.ProductLine
import com.sapphirebank.kyc.domain.model.RiskAssessment
import com.sapphirebank.kyc.domain.model.RiskCategory
import com.sapphirebank.kyc.domain.model.RuleEvaluation
import com.sapphirebank.kyc.domain.model.RuleOutcome
import com.sapphirebank.kyc.domain.model.RuleSeverity

/**
 * Customer risk score 0..100 (AML-005 requires a category; the KB does not publish the scoring
 * weights, so these are ILLUSTRATIVE and should be replaced by the bank's approved methodology).
 */
object RiskEngine {
    const val METHODOLOGY_VERSION = "illustrative-1"

    fun assess(app: CustomerApplication, evals: List<RuleEvaluation>, screening: ScreeningResult? = null): RiskAssessment {
        var score = 0
        val factors = mutableListOf<String>()
        fun add(points: Int, label: String) { score += points; factors += "$label (+$points)" }

        if (app.isPep) add(45, "Politically exposed person")
        if (app.isNonIndividual) add(10, "Non-individual customer")
        if (app.productLine == ProductLine.RETAIL_LOAN) add(10, "Credit exposure (retail loan)")
        if (app.sanctionAmountInr >= 5_000_000L) add(15, "High value (>= INR 50 lakh)")
        if (app.kycMode == KycMode.AADHAAR_OTP_EKYC) add(10, "Non-face-to-face limited KYC")
        if (screening != null) {
            if (screening.watchlistMatch) add(35, "Watchlist name match")
            when (screening.countryRisk) {
                CountryRisk.HIGH -> add(20, "High-risk country")
                CountryRisk.MEDIUM -> add(8, "Medium-risk country")
                CountryRisk.LOW -> Unit
            }
        }
        val open = evals.count {
            it.severity != RuleSeverity.ADVISORY && (it.outcome == RuleOutcome.FAIL || it.outcome == RuleOutcome.REFER)
        }
        if (open > 0) add(minOf(open * 8, 24), "$open KYC rule(s) failed/referred")

        val capped = minOf(score, 100)
        val cat = when {
            app.isPep || screening?.watchlistMatch == true -> RiskCategory.HIGH // PEP / watchlist hit: enhanced due diligence + DCS-004
            capped < 30 -> RiskCategory.LOW
            capped < 60 -> RiskCategory.MEDIUM
            else -> RiskCategory.HIGH
        }
        return RiskAssessment(capped, cat, factors)
    }
}
