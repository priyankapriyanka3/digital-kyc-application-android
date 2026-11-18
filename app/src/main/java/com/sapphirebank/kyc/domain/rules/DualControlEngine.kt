package com.sapphirebank.kyc.domain.rules

import com.sapphirebank.kyc.domain.model.ApprovalLevel
import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.Decision
import com.sapphirebank.kyc.domain.model.Officer
import com.sapphirebank.kyc.domain.model.RiskCategory
import com.sapphirebank.kyc.domain.model.RuleEvaluation
import com.sapphirebank.kyc.domain.model.RuleOutcome
import com.sapphirebank.kyc.domain.model.RuleSeverity
import com.sapphirebank.kyc.domain.model.ScreeningStatus

/** Stage 6 maker-checker-approver rules DCS-001..DCS-006 (SBK-KYC-14) plus the bank's justification and hold rules. */
object DualControlEngine {

    const val MIN_JUSTIFICATION_CHARS = 10

    /** L1 Credit Officer -> L2 Branch Manager (-> L3 Senior Management for PEP / high risk, DCS-004). */
    fun requiredLevels(c: CaseFile): List<ApprovalLevel> = buildList {
        add(ApprovalLevel.L1_CREDIT_OFFICER)
        add(ApprovalLevel.L2_BRANCH_MANAGER)
        if (c.application.isPep || c.risk?.category == RiskCategory.HIGH) add(ApprovalLevel.L3_SENIOR_MANAGEMENT)
    }

    /** The approval level an officer role signs at, or null for roles with no sign-off authority. */
    fun levelFor(officerRole: String): ApprovalLevel? = when (officerRole) {
        "CREDIT_OFFICER" -> ApprovalLevel.L1_CREDIT_OFFICER
        "BRANCH_MANAGER" -> ApprovalLevel.L2_BRANCH_MANAGER
        "SENIOR_MANAGEMENT" -> ApprovalLevel.L3_SENIOR_MANAGEMENT
        else -> null
    }

    fun requiredRole(level: ApprovalLevel): String = when (level) {
        ApprovalLevel.L1_CREDIT_OFFICER -> "CREDIT_OFFICER"
        ApprovalLevel.L2_BRANCH_MANAGER -> "BRANCH_MANAGER"
        ApprovalLevel.L3_SENIOR_MANAGEMENT -> "SENIOR_MANAGEMENT"
    }

    fun signOffHash(
        applicationId: String, level: ApprovalLevel, decision: Decision, decidedAt: String, approverId: String,
        justification: String = "",
    ): String = Hashing.sha256Hex(
        "$applicationId|$level|$decision|$decidedAt|$approverId" + if (justification.isBlank()) "" else "|${justification.trim()}",
    )

    fun isComplete(c: CaseFile): Boolean =
        requiredLevels(c).all { l -> c.approvals.any { it.level == l && it.decision == Decision.APPROVED } }

    /** The next level still awaiting a decision, or null when every required level has approved. */
    fun nextLevel(c: CaseFile): ApprovalLevel? =
        requiredLevels(c).firstOrNull { l -> c.approvals.none { it.level == l } }

    /** Justification is mandatory for L2 / L3 decisions and for every rejection. */
    fun justificationRequired(level: ApprovalLevel, decision: Decision): Boolean =
        level != ApprovalLevel.L1_CREDIT_OFFICER || decision == Decision.REJECTED

    /** Returns a human-readable violation, or null when [officer] may record [decision] at [level]. */
    fun validate(c: CaseFile, level: ApprovalLevel, officer: Officer, decision: Decision, justification: String = ""): String? {
        val st = c.application.status
        if (st != ApplicationStatus.PENDING_REVIEW) return "Sign-off opens at PENDING_REVIEW (application is $st)"
        val req = requiredLevels(c)
        if (level !in req) return "${level.label} is not required for this application"
        if (c.approvals.any { it.level == level }) return "${level.label} has already been decided"
        if (officer.role != requiredRole(level))
            return "${officer.displayName} is ${officer.role}; ${level.label} needs ${requiredRole(level)}"
        if (officer.userId == c.sourcingRmId || officer.userId == c.evidence.vcipOfficialId || officer.userId == c.createdBy)
            return "DCS-003: approver cannot be the sourcing RM, the V-CIP official or the creator of the application"
        if (c.approvals.any { it.approver.userId == officer.userId })
            return "DCS-001: approvals must come from distinct users"
        val blocker = req.take(req.indexOf(level))
            .firstOrNull { l -> c.approvals.none { it.level == l && it.decision == Decision.APPROVED } }
        if (blocker != null) return "DCS-002: ${blocker.label} must approve before ${level.label}"
        if (decision == Decision.APPROVED && c.application.sanctionAmountInr > officer.delegatedLimitInr)
            return "DCS-002: sanction INR ${c.application.sanctionAmountInr} exceeds ${officer.displayName}'s limit " +
                "INR ${officer.delegatedLimitInr}; escalate to the next level"
        if (c.openInfoRequests.isNotEmpty())
            return "${c.openInfoRequests.size} information request(s) are still open; a decision cannot be recorded until the customer responds"
        if (c.hold != null) return "The application is on compliance hold"
        val sc = c.screening
        if (sc != null && (sc.status == ScreeningStatus.FLAGGED || sc.status == ScreeningStatus.ON_HOLD))
            return "AML/PEP screening has not been cleared by a Compliance Officer"
        if (justificationRequired(level, decision) && justification.trim().length < MIN_JUSTIFICATION_CHARS)
            return "A justification of at least $MIN_JUSTIFICATION_CHARS characters is mandatory for this decision"
        return null
    }

    /** Live evaluation of the Stage 6 rules for the dashboard. */
    fun evaluate(c: CaseFile): List<RuleEvaluation> {
        val ok = c.approvals.filter { it.decision == Decision.APPROVED }
        val none = c.approvals.isEmpty()
        fun ev(id: String, n: String, o: RuleOutcome, d: String, f: String) =
            RuleEvaluation(id, n, RuleSeverity.BLOCKING, o, d, f)

        val d1 = ev("DCS-001", "Two-person approval (four-eyes)",
            if (ok.map { it.approver.userId }.distinct().size >= 2 && ok.map { it.level }.distinct().size >= 2) RuleOutcome.PASS else RuleOutcome.PENDING,
            "${ok.size} approval(s) from ${ok.map { it.approver.userId }.distinct().size} distinct user(s)", "Application cannot reach APPROVED")
        val d2 = ev("DCS-002", "Sequential levels and delegated limit",
            if (none) RuleOutcome.PENDING else RuleOutcome.PASS,
            if (none) "Waiting for L1" else "Levels signed in order within delegated limits", "Escalate")
        val violators = c.approvals.filter {
            it.approver.userId == c.sourcingRmId || it.approver.userId == c.evidence.vcipOfficialId || it.approver.userId == c.createdBy
        }
        val d3 = ev("DCS-003", "Segregation of maker and checker",
            when { none -> RuleOutcome.PENDING; violators.isEmpty() -> RuleOutcome.PASS; else -> RuleOutcome.FAIL },
            "Approvers are distinct from the creator, sourcing RM and V-CIP official", "Reassign task")
        val seniorReq = ApprovalLevel.L3_SENIOR_MANAGEMENT in requiredLevels(c)
        val d4 = ev("DCS-004", "Senior-management approval for PEP / high risk",
            when {
                !seniorReq -> RuleOutcome.NOT_APPLICABLE
                ok.any { it.level == ApprovalLevel.L3_SENIOR_MANAGEMENT } -> RuleOutcome.PASS
                else -> RuleOutcome.PENDING
            }, if (seniorReq) "PEP / HIGH risk: L3 sign-off required" else "Not PEP / not HIGH risk", "Hold at PENDING_REVIEW")
        val tampered = c.approvals.filter {
            it.signOffSha256 != signOffHash(c.application.arn, it.level, it.decision, it.decidedAt, it.approver.userId, it.justification)
        }
        val d6 = ev("DCS-006", "Tamper-evident sign-off",
            when { none -> RuleOutcome.PENDING; tampered.isEmpty() -> RuleOutcome.PASS; else -> RuleOutcome.FAIL },
            if (tampered.isEmpty()) "signOffSha256 verified on ${c.approvals.size} decision(s)" else "${tampered.size} decision(s) altered", "Raise integrity alert")
        return listOf(d1, d2, d3, d4, d6)
    }
}
