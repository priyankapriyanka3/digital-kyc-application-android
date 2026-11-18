package com.sapphirebank.kyc.domain.usecase

import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.ApplicationStatus.*
import com.sapphirebank.kyc.domain.model.ApprovalDecision
import com.sapphirebank.kyc.domain.model.ApprovalLevel
import com.sapphirebank.kyc.domain.model.BankConfig
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.CustomerApplication
import com.sapphirebank.kyc.domain.model.Decision
import com.sapphirebank.kyc.domain.model.DocumentKind
import com.sapphirebank.kyc.domain.model.DocumentRecord
import com.sapphirebank.kyc.domain.model.EligibilityOutcome
import com.sapphirebank.kyc.domain.model.FinancialProfile
import com.sapphirebank.kyc.domain.model.HoldInfo
import com.sapphirebank.kyc.domain.model.InfoRequest
import com.sapphirebank.kyc.domain.model.InfoRequestStatus
import com.sapphirebank.kyc.domain.model.KycDecision
import com.sapphirebank.kyc.domain.model.KycEvidence
import com.sapphirebank.kyc.domain.model.KycOutcome
import com.sapphirebank.kyc.domain.model.LifecycleStage
import com.sapphirebank.kyc.domain.model.Officer
import com.sapphirebank.kyc.domain.model.ProductLine
import com.sapphirebank.kyc.domain.model.RiskCategory
import com.sapphirebank.kyc.domain.model.RuleOutcome
import com.sapphirebank.kyc.domain.model.ScreeningEvidence
import com.sapphirebank.kyc.domain.model.ScreeningStatus
import com.sapphirebank.kyc.domain.model.VcipSession
import com.sapphirebank.kyc.domain.model.WelcomeKit
import com.sapphirebank.kyc.domain.model.dicgcNote
import com.sapphirebank.kyc.domain.rules.ApplicationStateMachine
import com.sapphirebank.kyc.domain.rules.AuditChain
import com.sapphirebank.kyc.domain.rules.AutoApproval
import com.sapphirebank.kyc.domain.rules.DualControlEngine
import com.sapphirebank.kyc.domain.rules.EligibilityEngine
import com.sapphirebank.kyc.domain.rules.KycRuleEngine
import com.sapphirebank.kyc.domain.rules.RiskEngine
import com.sapphirebank.kyc.domain.rules.ScreeningEngine
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
 *
 * Role checks live in [CaseService]; the business rules that protect data integrity (open information
 * requests, justification, segregation of duties, screening clearance) live here so they hold no matter who calls.
 */
object CaseWorkflow {

    private const val SYSTEM = "U-SYS-01 (SYSTEM_SERVICE)"
    private const val KYC_ACTOR = "U-KYC-01 (KYC_OPERATIONS_OFFICER)"
    const val MIN_NOTE_CHARS = 10

    fun arnFor(nowIso: String, seq: Int): String = "SB" + nowIso.take(10).replace("-", "") + "%03d".format(seq)

    // ---- Stage 1 + 2 -------------------------------------------------------------------------
    fun submit(
        app: CustomerApplication, rmId: String, ocrName: String?, nowIso: String, today: LocalDate, seq: Int,
        createdBy: String = rmId, createdByRole: String = "RELATIONSHIP_MANAGER",
        documents: List<DocumentRecord> = emptyList(), financials: FinancialProfile? = null,
        ocrDob: String? = null, assignedAnalystId: String? = null, validated: Boolean = false,
        cfg: BankConfig = BankConfig(),
    ): Step {
        if (!validated) {
            val errors = ApplicationValidator.validate(app, today)
            if (errors.isNotEmpty()) return Step.Refused("Fix ${errors.size} field(s) before submitting", errors)
        }
        val arn = arnFor(nowIso, seq)
        val actor = "$createdBy ($createdByRole)"
        var c = CaseFile(
            application = app.copy(arn = arn, status = DRAFT),
            sourcingRmId = rmId,
            evidence = KycEvidence(ocrName = ocrName, ocrDob = ocrDob),
            createdBy = createdBy, createdAt = nowIso, documents = documents, financials = financials,
            assignedAnalystId = assignedAnalystId,
        )
        c = audit(c, nowIso, "ApplicationCreated", "Application:$arn", actor, "create:$arn")
        c = move(c, SUBMITTED, nowIso, "ApplicationSubmitted", actor)
        // DOC-001/004: formats were validated above, so documents are validated in the same step.
        c = move(c, DOCUMENTS_VALIDATED, nowIso, "DocumentValidated", SYSTEM)
        return Step.Ok(refresh(c, cfg))
    }

    // ---- Stage 3 -----------------------------------------------------------------------------
    fun startKyc(c: CaseFile, nowIso: String, actor: String = KYC_ACTOR, cfg: BankConfig = BankConfig()): Step {
        if (c.application.status != DOCUMENTS_VALIDATED) return refused("KYC starts from DOCUMENTS_VALIDATED")
        return Step.Ok(refresh(move(c, KYC_IN_PROGRESS, nowIso, "KYCInitiated", actor), cfg))
    }

    fun searchCkycr(c: CaseFile, nowIso: String, cfg: BankConfig = BankConfig()): Step =
        Step.Ok(refresh(audit(c.copy(evidence = c.evidence.copy(ckycrSearched = true)), nowIso, "CKYCRSearched",
            "Application:${c.application.arn}", SYSTEM, "ckycr-search"), cfg))

    fun recordVcip(c: CaseFile, s: VcipSession, officialId: String, nowIso: String, cfg: BankConfig = BankConfig()): Step {
        if (c.application.status != KYC_IN_PROGRESS) return refused("V-CIP is recorded while KYC is in progress")
        val next = c.copy(evidence = c.evidence.copy(vcip = s, vcipOfficialId = officialId))
        return Step.Ok(refresh(audit(next, nowIso, "VCIPSessionRecorded", "Application:${c.application.arn}",
            "$officialId (VCIP_OFFICIAL)", "vcip:liveness=${s.livenessPassed}:face=${s.faceMatchScore}"), cfg))
    }

    /** KYC_VERIFIED needs KYC-001..009 PASS / NOT_APPLICABLE; blocking failures reject the application. */
    fun completeKyc(c: CaseFile, nowIso: String, actor: String = KYC_ACTOR, cfg: BankConfig = BankConfig()): Step {
        if (c.application.status != KYC_IN_PROGRESS) return refused("KYC is not in progress")
        val evals = KycRuleEngine.evaluate(c.application, c.evidence, cfg.thresholds)
        val failures = KycRuleEngine.blockingFailures(evals)
        return when {
            failures.isNotEmpty() -> {
                val rejected = move(c.copy(failedAt = LifecycleStage.S3_KYC), REJECTED, nowIso, "KYCFailed", actor)
                Step.Ok(refresh(rejected, cfg))
            }
            !KycRuleEngine.isVerifiable(evals) -> {
                val open = evals.filter { it.severity.name != "ADVISORY" && it.outcome !in setOf(RuleOutcome.PASS, RuleOutcome.NOT_APPLICABLE) }
                refused("Outstanding: " + open.joinToString { "${it.ruleId} ${it.outcome}" })
            }
            else -> Step.Ok(refresh(move(c, KYC_VERIFIED, nowIso, "KYCVerified", actor), cfg))
        }
    }

    /** Analyst "Approve KYC": records the decision, then runs the rule gate (which may still reject on a blocking failure). */
    fun kycApprove(c: CaseFile, analystId: String, comment: String, manualCompared: Boolean, nowIso: String, cfg: BankConfig = BankConfig()): Step {
        guardOpenRequests(c)?.let { return refused(it) }
        if (c.application.status != KYC_IN_PROGRESS) return refused("KYC is not in progress (application is ${c.application.status})")
        val ev = c.evidence
        if (ev.ocrName == null && ev.vcip == null && !manualCompared)
            return refused("Confirm that you compared the name and date of birth with the uploaded documents")
        val actor = "$analystId (KYC_OPERATIONS_OFFICER)"
        val prepared = c.copy(evidence = ev.copy(manualVerifiedBy = if (ev.ocrName == null && ev.vcip == null) analystId else ev.manualVerifiedBy))
        val done = completeKyc(prepared, nowIso, actor, cfg)
        if (done !is Step.Ok) return done
        val verified = done.case.application.status == KYC_VERIFIED
        val decision = KycDecision(if (verified) KycOutcome.APPROVED else KycOutcome.REJECTED, analystId,
            if (verified) comment.trim() else "Rejected by rule gate: " + KycRuleEngine.blockingFailures(done.case.evaluations).joinToString { it.ruleId }, nowIso)
        return Step.Ok(audit(done.case.copy(kycDecision = decision), nowIso, "KYCDecisionRecorded",
            "KycDecision:${c.application.arn}", actor, "${decision.outcome}:${decision.comment}"))
    }

    fun kycReject(c: CaseFile, analystId: String, comment: String, nowIso: String, cfg: BankConfig = BankConfig()): Step {
        guardOpenRequests(c)?.let { return refused(it) }
        if (c.application.status != KYC_IN_PROGRESS) return refused("KYC is not in progress (application is ${c.application.status})")
        if (comment.trim().length < MIN_NOTE_CHARS) return refused("Give a reason of at least $MIN_NOTE_CHARS characters")
        val actor = "$analystId (KYC_OPERATIONS_OFFICER)"
        val decided = c.copy(kycDecision = KycDecision(KycOutcome.REJECTED, analystId, comment.trim(), nowIso), failedAt = LifecycleStage.S3_KYC)
        val next = move(audit(decided, nowIso, "KYCDecisionRecorded", "KycDecision:${c.application.arn}", actor, "REJECTED:${comment.trim()}"),
            REJECTED, nowIso, "KYCRejected", actor)
        return Step.Ok(refresh(next, cfg))
    }

    // ---- information requests ---------------------------------------------------------------
    fun requestInfo(c: CaseFile, byId: String, byRole: String, message: String, kinds: List<DocumentKind>, nowIso: String): Step {
        if (c.application.status.terminal) return refused("Application is ${c.application.status}; nothing can be requested")
        if (message.trim().length < 5) return refused("Describe what is needed")
        val req = InfoRequest("IR-%02d".format(c.infoRequests.size + 1), byId, byRole, message.trim(), kinds, nowIso)
        return Step.Ok(audit(c.copy(infoRequests = c.infoRequests + req), nowIso, "InformationRequested",
            "InfoRequest:${c.application.arn}:${req.id}", "$byId ($byRole)", "${req.id}:${kinds.joinToString { it.name }}:${req.message}"))
    }

    fun respondInfo(c: CaseFile, customerId: String, requestId: String, response: String, newDocs: List<DocumentRecord>, nowIso: String): Step {
        val req = c.infoRequests.firstOrNull { it.id == requestId && it.status == InfoRequestStatus.OPEN }
            ?: return refused("That request is no longer open")
        if (response.trim().isEmpty() && newDocs.isEmpty()) return refused("Add a reply or upload a document")
        val missing = req.kinds.filter { k -> newDocs.none { it.kind == k } }
        if (missing.isNotEmpty()) return refused("Upload the requested document(s): " + missing.joinToString { it.label })
        val versioned = newDocs.map { d -> d.copy(version = (c.documents.filter { it.kind == d.kind }.maxOfOrNull { it.version } ?: 0) + 1) }
        val updated = req.copy(status = InfoRequestStatus.RESPONDED, response = response.trim(), respondedAt = nowIso)
        val next = c.copy(infoRequests = c.infoRequests.map { if (it.id == requestId) updated else it }, documents = c.documents + versioned)
        return Step.Ok(audit(next, nowIso, "InformationProvided", "InfoRequest:${c.application.arn}:$requestId",
            "$customerId (CUSTOMER)", "$requestId:${versioned.joinToString { it.kind.name + "@v" + it.version }}"))
    }

    fun reassign(c: CaseFile, newAnalystId: String, byId: String, reason: String, nowIso: String): Step {
        if (c.application.status.terminal) return refused("Application is ${c.application.status}")
        if (c.assignedAnalystId == newAnalystId) return refused("Already assigned to that analyst")
        return Step.Ok(audit(c.copy(assignedAnalystId = newAnalystId), nowIso, "Reassigned", "Application:${c.application.arn}",
            "$byId (BRANCH_MANAGER)", "analyst:${c.assignedAnalystId}->$newAnalystId:${reason.trim()}"))
    }

    // ---- Stages 4-6 (system-driven until review) --------------------------------------------
    /**
     * Moves the application one status forward through AML, eligibility and review creation. A flagged case stops at
     * EDD_PENDING until a Compliance Officer clears it ([screeningDecision]); advance cannot bypass that.
     */
    fun advance(c: CaseFile, nowIso: String, cfg: BankConfig = BankConfig(), bureauScore: Int? = null): Step {
        val s = c.application.status
        val actor = when (s) {
            KYC_VERIFIED, AML_SCREENING -> "U-CMP-01 (COMPLIANCE_OFFICER)"
            else -> SYSTEM
        }
        return when (s) {
            KYC_VERIFIED -> Step.Ok(move(c, AML_SCREENING, nowIso, "ScreeningStarted", actor))
            AML_SCREENING -> {
                val raw = ScreeningEngine.screen(c.application, cfg, nowIso)
                val risk = RiskEngine.assess(c.application, c.evaluations, raw)
                val flagged = raw.status == ScreeningStatus.FLAGGED || c.application.isPep || risk.category == RiskCategory.HIGH
                val screening = if (flagged && raw.status == ScreeningStatus.CLEAR)
                    raw.copy(status = ScreeningStatus.FLAGGED, evidence = raw.evidence + ScreeningEvidence("Risk score", "Overall risk ${risk.score}/100 is HIGH"))
                else raw
                val withRisk = audit(c.copy(risk = risk, screening = screening), nowIso, "CustomerRiskCategorised",
                    "CustomerRiskAssessment:${c.application.arn}", actor, "risk:${risk.score}:${risk.category}:screening:${screening.status}")
                if (flagged) Step.Ok(move(withRisk, EDD_PENDING, nowIso, "EDDRequired", actor))
                else Step.Ok(move(withRisk, AML_CLEARED, nowIso, "ComplianceCleared", actor))
            }
            EDD_PENDING -> refused("Waiting for a Compliance Officer to clear or hold this flagged application")
            AML_CLEARED -> Step.Ok(move(c, ELIGIBILITY_CHECK, nowIso, "EligibilityStarted", actor))
            ELIGIBILITY_CHECK -> {
                val a = EligibilityEngine.assess(c.application, c.financials, bureauScore, c.documents, cfg)
                Step.Ok(move(audit(c.copy(eligibility = a), nowIso, "EligibilityAssessed", "Eligibility:${c.application.arn}", actor,
                    "outcome:${a.outcome}:dti:${a.dtiPct}:bureau:${a.bureauScore}"), ELIGIBLE, nowIso, "EligibilityCompleted", actor))
            }
            ELIGIBLE -> {
                // Savings accounts are auto-approved after successful KYC when nothing is flagged or open.
                Step.Ok(tryAutoApprove(move(c, PENDING_REVIEW, nowIso, "ReviewTasksCreated", actor), nowIso))
            }
            PENDING_REVIEW -> refused("Waiting for dual-control sign-off")
            APPROVED -> {
                val opened = move(c, FULFILLED, nowIso, "AccountOpened", SYSTEM)
                Step.Ok(opened.copy(welcomeKit = welcomeKit(opened, cfg)))
            }
            DRAFT, SUBMITTED, DOCUMENTS_PENDING, DOCUMENTS_VALIDATED, KYC_IN_PROGRESS ->
                refused("Complete Stage ${s.stage.number} first")
            FULFILLED, REJECTED, WITHDRAWN, EXPIRED -> refused("Application is $s (terminal)")
        }
    }

    /** Savings only: moves PENDING_REVIEW to APPROVED when no flag, hold or open information request remains. */
    fun tryAutoApprove(c: CaseFile, nowIso: String): CaseFile =
        if (AutoApproval.allowed(c)) move(c, APPROVED, nowIso, "AutoApproved", SYSTEM) else c

    /** Compliance clears a flagged application, or places it on hold. A written note is mandatory. */
    fun screeningDecision(c: CaseFile, officerId: String, clear: Boolean, note: String, nowIso: String): Step {
        val sc = c.screening ?: return refused("This application has not been screened yet")
        if (c.application.status.terminal) return refused("Application is ${c.application.status}")
        if (note.trim().length < MIN_NOTE_CHARS) return refused("A note of at least $MIN_NOTE_CHARS characters is mandatory")
        val actor = "$officerId (COMPLIANCE_OFFICER)"
        return if (clear) {
            if (sc.status != ScreeningStatus.FLAGGED && sc.status != ScreeningStatus.ON_HOLD) return refused("Nothing to clear: screening is ${sc.status}")
            val cleared = audit(c.copy(screening = sc.copy(status = ScreeningStatus.CLEARED_BY_COMPLIANCE, decidedBy = officerId, decisionNote = note.trim(), decidedAt = nowIso), hold = null),
                nowIso, "ScreeningCleared", "Screening:${c.application.arn}", actor, "cleared:${note.trim()}")
            Step.Ok(if (cleared.application.status == EDD_PENDING) move(cleared, AML_CLEARED, nowIso, "ComplianceCleared", actor) else cleared)
        } else {
            if (sc.status != ScreeningStatus.FLAGGED) return refused("Only a flagged application can be placed on hold (screening is ${sc.status})")
            Step.Ok(audit(c.copy(screening = sc.copy(status = ScreeningStatus.ON_HOLD, decidedBy = officerId, decisionNote = note.trim(), decidedAt = nowIso), hold = HoldInfo(officerId, note.trim(), nowIso)),
                nowIso, "PlacedOnHold", "Screening:${c.application.arn}", actor, "hold:${note.trim()}"))
        }
    }

    // ---- Stage 6 dual control ----------------------------------------------------------------
    fun signOff(
        c: CaseFile, level: ApprovalLevel, officer: Officer, decision: Decision, nowIso: String,
        justification: String = "", cfg: BankConfig = BankConfig(),
    ): Step {
        DualControlEngine.validate(c, level, officer, decision, justification)?.let { return refused(it) }
        if (level == ApprovalLevel.L1_CREDIT_OFFICER && decision == Decision.APPROVED && c.eligibility?.outcome == EligibilityOutcome.INELIGIBLE)
            return refused("Eligibility check failed (${c.eligibility.reasons.joinToString("; ")}); decline, or request more documents")
        val just = justification.trim()
        val hash = DualControlEngine.signOffHash(c.application.arn, level, decision, nowIso, officer.userId, just)
        val d = ApprovalDecision(level, officer, decision, nowIso, hash, just)
        var next = audit(c.copy(approvals = c.approvals + d), nowIso, "ApprovalRecorded",
            "ApprovalDecision:${c.application.arn}:${level.name}", "${officer.userId} (${officer.role})", "$level:$decision:$hash")
        next = when {
            decision == Decision.REJECTED -> move(next.copy(failedAt = LifecycleStage.S6_REVIEW), REJECTED, nowIso, "ApplicationRejected", "${officer.userId} (${officer.role})")
            DualControlEngine.isComplete(next) -> move(next, APPROVED, nowIso, "DualControlApproved", SYSTEM)
            else -> next
        }
        return Step.Ok(refresh(next, cfg))
    }

    fun withdraw(c: CaseFile, nowIso: String, actor: String = "customer"): Step {
        if (c.application.status.terminal) return refused("Application is already ${c.application.status}")
        return Step.Ok(move(c.copy(failedAt = c.application.status.stage), WITHDRAWN, nowIso, "ApplicationWithdrawn", actor))
    }

    // ---- helpers -----------------------------------------------------------------------------
    private fun guardOpenRequests(c: CaseFile): String? =
        if (c.openInfoRequests.isEmpty()) null
        else "${c.openInfoRequests.size} information request(s) are open; the application cannot be approved or rejected until the customer responds"

    private fun welcomeKit(c: CaseFile, cfg: BankConfig): WelcomeKit {
        val acct = "%010d".format(Math.abs(c.application.arn.hashCode().toLong()) % 10_000_000_000L)
        val branch = cfg.branches.first()
        val product = c.application.productLine
        val items = buildList {
            add("Account confirmation letter")
            if (product != ProductLine.RETAIL_LOAN && product != ProductLine.TERM_DEPOSIT) add("Debit card (dispatched in 5 working days)")
            if (product == ProductLine.TERM_DEPOSIT) add("Deposit advice and DICGC insurance notice")
            if (product == ProductLine.RETAIL_LOAN) add("Loan sanction letter and repayment schedule")
            add("Mobile and net-banking registration guide")
        }
        return WelcomeKit(acct, "SPHR0000101", branch, items, dicgcNote(product, cfg.dicgcCoverInr))
    }

    /** Re-derives rule evaluations and the risk score from current evidence. */
    private fun refresh(c: CaseFile, cfg: BankConfig = BankConfig()): CaseFile {
        val st = c.application.status
        if (st.ordinal < KYC_IN_PROGRESS.ordinal && st != REJECTED) return c
        val evals = KycRuleEngine.evaluate(c.application, c.evidence, cfg.thresholds)
        val risk = if (c.risk != null && st.ordinal >= AML_SCREENING.ordinal) c.risk else RiskEngine.assess(c.application, evals, c.screening)
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

    private fun refused(reason: String) = Step.Refused(reason)
}
