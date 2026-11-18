package com.sapphirebank.kyc.domain.usecase

import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.ApplicationStatus.*
import com.sapphirebank.kyc.domain.model.ApprovalDecision
import com.sapphirebank.kyc.domain.model.ApprovalLevel
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.CustomerApplication
import com.sapphirebank.kyc.domain.model.Decision
import com.sapphirebank.kyc.domain.model.KycEvidence
import com.sapphirebank.kyc.domain.model.LifecycleStage
import com.sapphirebank.kyc.domain.model.Officer
import com.sapphirebank.kyc.domain.model.RuleOutcome
import com.sapphirebank.kyc.domain.model.VcipSession
import com.sapphirebank.kyc.domain.rules.ApplicationStateMachine
import com.sapphirebank.kyc.domain.rules.AuditChain
import com.sapphirebank.kyc.domain.rules.DualControlEngine
import com.sapphirebank.kyc.domain.rules.KycRuleEngine
import com.sapphirebank.kyc.domain.rules.RiskEngine
import com.sapphirebank.kyc.domain.validation.ApplicationValidator
import com.sapphirebank.kyc.domain.validation.FormField
import java.time.LocalDate

/** Outcome of a workflow action: a new immutable CaseFile, or a reason the action was refused. */
sealed interface Step {
    data class Ok(val case: CaseFile) : Step
    data class Refused(val reason: String, val fieldErrors: Map<FormField, String> = emptyMap()) : Step
}

/**
 * Pure state transitions for the 8-stage journey. Every status change appends a hash-chained
 * AuditEvent (AUD-001 / AUD-002) so the audit log and the case can never drift apart.
 */
object CaseWorkflow {

    private const val SYSTEM = "U-SYS-01 (SYSTEM_SERVICE)"

    // ---- Stage 1 + 2 -------------------------------------------------------------------------
    fun submit(app: CustomerApplication, rmId: String, ocrName: String?, nowIso: String, today: LocalDate, seq: Int): Step {
        val errors = ApplicationValidator.validate(app, today)
        if (errors.isNotEmpty()) return Step.Refused("Fix ${errors.size} field(s) before submitting", errors)
        val arn = "ARN-%06d".format(seq)
        var c = CaseFile(
            application = app.copy(arn = arn, status = DRAFT),
            sourcingRmId = rmId,
            evidence = KycEvidence(ocrName = ocrName),
        )
        c = audit(c, nowIso, "ApplicationCreated", "Application:$arn", "$rmId (RELATIONSHIP_MANAGER)", "create:$arn")
        c = move(c, SUBMITTED, nowIso, "ApplicationSubmitted", "$rmId (RELATIONSHIP_MANAGER)")
        // DOC-001/004: formats were validated above, so documents are validated in the same step.
        c = move(c, DOCUMENTS_VALIDATED, nowIso, "DocumentValidated", SYSTEM)
        return Step.Ok(refresh(c))
    }

    // ---- Stage 3 -----------------------------------------------------------------------------
    fun startKyc(c: CaseFile, nowIso: String): Step {
        if (c.application.status != DOCUMENTS_VALIDATED) return refused(c, "KYC starts from DOCUMENTS_VALIDATED")
        return Step.Ok(refresh(move(c, KYC_IN_PROGRESS, nowIso, "KYCInitiated", "U-KYC-01 (KYC_OPERATIONS_OFFICER)")))
    }

    fun searchCkycr(c: CaseFile, nowIso: String): Step =
        Step.Ok(refresh(audit(c.copy(evidence = c.evidence.copy(ckycrSearched = true)), nowIso, "CKYCRSearched",
            "Application:${c.application.arn}", SYSTEM, "ckycr-search")))

    fun recordVcip(c: CaseFile, s: VcipSession, officialId: String, nowIso: String): Step {
        if (c.application.status != KYC_IN_PROGRESS) return refused(c, "V-CIP is recorded while KYC is in progress")
        val next = c.copy(evidence = c.evidence.copy(vcip = s, vcipOfficialId = officialId))
        return Step.Ok(refresh(audit(next, nowIso, "VCIPSessionRecorded", "Application:${c.application.arn}",
            "$officialId (VCIP_OFFICIAL)", "vcip:liveness=${s.livenessPassed}:face=${s.faceMatchScore}")))
    }

    /** KYC_VERIFIED needs KYC-001..009 PASS / NOT_APPLICABLE; blocking failures reject the application. */
    fun completeKyc(c: CaseFile, nowIso: String): Step {
        if (c.application.status != KYC_IN_PROGRESS) return refused(c, "KYC is not in progress")
        val evals = KycRuleEngine.evaluate(c.application, c.evidence)
        val failures = KycRuleEngine.blockingFailures(evals)
        return when {
            failures.isNotEmpty() -> {
                val rejected = move(c.copy(failedAt = LifecycleStage.S3_KYC), REJECTED, nowIso, "KYCFailed", "U-KYC-01 (KYC_OPERATIONS_OFFICER)")
                Step.Ok(refresh(rejected))
            }
            !KycRuleEngine.isVerifiable(evals) -> {
                val open = evals.filter { it.severity.name != "ADVISORY" && it.outcome !in setOf(RuleOutcome.PASS, RuleOutcome.NOT_APPLICABLE) }
                refused(c, "Outstanding: " + open.joinToString { "${it.ruleId} ${it.outcome}" })
            }
            else -> Step.Ok(refresh(move(c, KYC_VERIFIED, nowIso, "KYCVerified", "U-KYC-01 (KYC_OPERATIONS_OFFICER)")))
        }
    }

    // ---- Stages 4-6 (system-driven until review) --------------------------------------------
    /** Moves the application one status forward through AML, eligibility and review creation. */
    fun advance(c: CaseFile, nowIso: String): Step {
        val s = c.application.status
        val actor = when (s) {
            KYC_VERIFIED, AML_SCREENING, EDD_PENDING -> "U-CMP-01 (COMPLIANCE_OFFICER)"
            else -> SYSTEM
        }
        return when (s) {
            KYC_VERIFIED -> Step.Ok(move(c, AML_SCREENING, nowIso, "ScreeningStarted", actor))
            AML_SCREENING -> {
                val risk = RiskEngine.assess(c.application, c.evaluations)
                val withRisk = audit(c.copy(risk = risk), nowIso, "CustomerRiskCategorised", "CustomerRiskAssessment:${c.application.arn}",
                    actor, "risk:${risk.score}:${risk.category}")
                if (c.application.isPep || risk.category.name == "HIGH")
                    Step.Ok(move(withRisk, EDD_PENDING, nowIso, "EDDRequired", actor))
                else Step.Ok(move(withRisk, AML_CLEARED, nowIso, "ComplianceCleared", actor))
            }
            EDD_PENDING -> Step.Ok(move(c, AML_CLEARED, nowIso, "ComplianceCleared", actor))
            AML_CLEARED -> Step.Ok(move(c, ELIGIBILITY_CHECK, nowIso, "EligibilityStarted", actor))
            ELIGIBILITY_CHECK -> Step.Ok(move(c, ELIGIBLE, nowIso, "EligibilityAssessed", actor))
            ELIGIBLE -> Step.Ok(move(c, PENDING_REVIEW, nowIso, "ReviewTasksCreated", actor))
            PENDING_REVIEW -> refused(c, "Waiting for dual-control sign-off")
            APPROVED -> Step.Ok(move(c, FULFILLED, nowIso, "AccountOpened", "$SYSTEM"))
            DRAFT, SUBMITTED, DOCUMENTS_PENDING, DOCUMENTS_VALIDATED, KYC_IN_PROGRESS ->
                refused(c, "Complete Stage ${s.stage.number} first")
            FULFILLED, REJECTED, WITHDRAWN, EXPIRED -> refused(c, "Application is $s (terminal)")
        }
    }

    // ---- Stage 6 dual control ----------------------------------------------------------------
    fun signOff(c: CaseFile, level: ApprovalLevel, officer: Officer, decision: Decision, nowIso: String): Step {
        DualControlEngine.validate(c, level, officer, decision)?.let { return refused(c, it) }
        val hash = DualControlEngine.signOffHash(c.application.arn, level, decision, nowIso, officer.userId)
        val d = ApprovalDecision(level, officer, decision, nowIso, hash)
        var next = audit(c.copy(approvals = c.approvals + d), nowIso, "ApprovalRecorded",
            "ApprovalDecision:${c.application.arn}:${level.name}", "${officer.userId} (${officer.role})", "$level:$decision:$hash")
        next = when {
            decision == Decision.REJECTED -> move(next.copy(failedAt = LifecycleStage.S6_REVIEW), REJECTED, nowIso, "ApplicationRejected", "${officer.userId} (${officer.role})")
            DualControlEngine.isComplete(next) -> move(next, APPROVED, nowIso, "DualControlApproved", SYSTEM)
            else -> next
        }
        return Step.Ok(refresh(next))
    }

    fun withdraw(c: CaseFile, nowIso: String): Step {
        if (c.application.status.terminal) return refused(c, "Application is already ${c.application.status}")
        return Step.Ok(move(c.copy(failedAt = c.application.status.stage), WITHDRAWN, nowIso, "ApplicationWithdrawn", "customer"))
    }

    // ---- helpers -----------------------------------------------------------------------------
    /** Re-derives rule evaluations and the risk score from current evidence. */
    private fun refresh(c: CaseFile): CaseFile {
        val st = c.application.status
        if (st.ordinal < KYC_IN_PROGRESS.ordinal && st != REJECTED) return c
        val evals = KycRuleEngine.evaluate(c.application, c.evidence)
        val risk = if (c.risk != null && st.ordinal >= AML_SCREENING.ordinal) c.risk else RiskEngine.assess(c.application, evals)
        return c.copy(evaluations = evals, risk = risk)
    }

    private fun move(c: CaseFile, to: ApplicationStatus, nowIso: String, event: String, actor: String): CaseFile {
        val from = c.application.status
        check(ApplicationStateMachine.canMove(from, to) || from == to) { "Illegal transition $from -> $to" }
        return audit(c.copy(application = c.application.copy(status = to)), nowIso, event,
            "Application:${c.application.arn}", actor, "status:$from->$to")
    }

    private fun audit(c: CaseFile, nowIso: String, type: String, entity: String, actor: String, payload: String): CaseFile =
        c.copy(audit = AuditChain.append(c.audit, nowIso, type, entity, actor, payload))

    private fun refused(c: CaseFile, reason: String) = Step.Refused(reason)
}
