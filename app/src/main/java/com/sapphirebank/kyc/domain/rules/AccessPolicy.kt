package com.sapphirebank.kyc.domain.rules

import com.sapphirebank.kyc.domain.model.Actor
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.Role

/**
 * Which applications a role may see. Enforced by [com.sapphirebank.kyc.domain.usecase.CaseService] on every call and
 * reused by the ViewModels so what the UI lists always equals what the service would allow.
 *
 *  - Customer: only applications they created.
 *  - KYC Analyst: only applications assigned to them (their queue; a Branch Manager can reassign).
 *  - Credit Officer / Branch Manager / Compliance Officer: all applications (their action buttons are permission-gated).
 *  - System Admin: none. Administration is configuration only; it never touches individual applications.
 */
object AccessPolicy {
    fun canSee(actor: Actor, c: CaseFile): Boolean = when (actor.role) {
        Role.CUSTOMER -> c.createdBy == actor.userId
        Role.KYC_ANALYST -> c.assignedAnalystId == actor.userId
        Role.CREDIT_OFFICER, Role.BRANCH_MANAGER, Role.COMPLIANCE_OFFICER -> true
        Role.SYSTEM_ADMIN -> false
    }

    fun visible(actor: Actor, cases: List<CaseFile>): List<CaseFile> = cases.filter { canSee(actor, it) }
}
