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

/** Stage 6 maker-checker-approver rules DCS-001..DCS-006 (SBK-KYC-14). */
object DualControlEngine {

    /** L1 Credit Officer -> L2 Branch Manager (-> L3 Senior Management for PEP / HIGH risk, DCS-004). */
    fun requiredLevels(c: CaseFile): List<ApprovalLevel> = buildList {
        add(ApprovalLevel.L1_CREDIT_OFFICER)
        add(ApprovalLevel.L2_BRANCH_MANAGER)
        if (c.application.isPep || c.risk?.category == RiskCategory.HIGH) add(ApprovalLevel.L3_SENIOR_MANAGEMENT)
    }

    fun requiredRole(level: ApprovalLevel): String = when (level) {
        ApprovalLevel.L1_CREDIT_OFFICER -> "CREDIT_OFFICER"
        ApprovalLevel.L2_BRANCH_MANAGER -> "BRANCH_MANAGER"
        ApprovalLevel.L3_SENIOR_MANAGEMENT -> "SENIOR_MANAGEMENT"
    }

    fun signOffHash(applicationId: String, level: ApprovalLevel, decision: Decision, decidedAt: String, approverId: String): String =
        Hashing.sha256Hex("$applicationId|$level|$decision|$decidedAt|$approverId")

    fun isComplete(c: CaseFile): Boolean =
        requiredLevels(c).all { l -> c.approvals.any { it.level == l && it.decision == Decision.APPROVED } }

    /** Returns a human-readable violation, or null when [officer] may record [decision] at [level]. */
    fun validate(c: CaseFile, level: ApprovalLevel, officer: Officer, decision: Decision): String? {
        val st = c.application.status
        if (st != ApplicationStatus.PENDING_REVIEW) return "Sign-off opens at PENDING_REVIEW (application is $st)"
        val req = requiredLevels(c)
        if (level !in req) return "${level.label} is not required for this application"
        if (c.approvals.any { it.level == level }) return "${level.label} has already been decided"
        if (officer.role != requiredRole(level))
            return "${officer.displayName} is ${officer.role}; ${level.label} needs ${requiredRole(level)}"
        if (officer.userId == c.sourcingRmId || officer.userId == c.evidence.vcipOfficialId)
            return "DCS-003: approver cannot be the sourcing RM or the V-CIP official"
        if (c.approvals.any { it.approver.userId == officer.userId })
            return "DCS-001: approvals must come from distinct users"
        val blocker = req.take(req.indexOf(level))
            .firstOrNull { l -> c.approvals.none { it.level == l && it.decision == Decision.APPROVED } }
        if (blocker != null) return "DCS-002: ${blocker.label} must approve before ${level.label}"
        if (decision == Decision.APPROVED && c.application.sanctionAmountInr > officer.delegatedLimitInr)
            return "DCS-002: sanction INR ${c.application.sanctionAmountInr} exceeds ${officer.displayName}'s limit " +
                "INR ${officer.delegatedLimitInr}; escalate to the next level"
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
        val violators = c.approvals.filter { it.approver.userId == c.sourcingRmId || it.approver.userId == c.evidence.vcipOfficialId }
        val d3 = ev("DCS-003", "Segregation of maker and checker",
            when { none -> RuleOutcome.PENDING; violators.isEmpty() -> RuleOutcome.PASS; else -> RuleOutcome.FAIL },
            "Approvers are distinct from sourcing RM and V-CIP official", "Reassign task")
        val seniorReq = ApprovalLevel.L3_SENIOR_MANAGEMENT in requiredLevels(c)
        val d4 = ev("DCS-004", "Senior-management approval for PEP / high risk",
            when {
                !seniorReq -> RuleOutcome.NOT_APPLICABLE
                ok.any { it.level == ApprovalLevel.L3_SENIOR_MANAGEMENT } -> RuleOutcome.PASS
                else -> RuleOutcome.PENDING
            }, if (seniorReq) "PEP / HIGH risk: L3 sign-off required" else "Not PEP / not HIGH risk", "Hold at PENDING_REVIEW")
        val tampered = c.approvals.filter {
            it.signOffSha256 != signOffHash(c.application.arn, it.level, it.decision, it.decidedAt, it.approver.userId)
        }
        val d6 = ev("DCS-006", "Tamper-evident sign-off",
            when { none -> RuleOutcome.PENDING; tampered.isEmpty() -> RuleOutcome.PASS; else -> RuleOutcome.FAIL },
            if (tampered.isEmpty()) "signOffSha256 verified on ${c.approvals.size} decision(s)" else "${tampered.size} decision(s) altered", "Raise integrity alert")
        return listOf(d1, d2, d3, d4, d6)
    }
}
