package com.sapphirebank.kyc.domain.rules

import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.ApplicationStatus.*
import com.sapphirebank.kyc.domain.model.LifecycleStage

enum class StepState { DONE, ACTIVE, PENDING, FAILED }

data class StageProgress(val stage: LifecycleStage, val state: StepState, val detail: String)

/** Transition table from SBK-KYC-13 (Application state machine). */
object ApplicationStateMachine {

    private val transitions: Map<ApplicationStatus, Set<ApplicationStatus>> = mapOf(
        DRAFT to setOf(SUBMITTED, WITHDRAWN),
        SUBMITTED to setOf(DOCUMENTS_PENDING, DOCUMENTS_VALIDATED, REJECTED, WITHDRAWN),
        DOCUMENTS_PENDING to setOf(DOCUMENTS_VALIDATED, REJECTED, WITHDRAWN, EXPIRED),
        DOCUMENTS_VALIDATED to setOf(KYC_IN_PROGRESS, WITHDRAWN),
        KYC_IN_PROGRESS to setOf(KYC_VERIFIED, REJECTED, WITHDRAWN, EXPIRED),
        KYC_VERIFIED to setOf(AML_SCREENING, WITHDRAWN),
        AML_SCREENING to setOf(EDD_PENDING, AML_CLEARED, REJECTED),
        EDD_PENDING to setOf(AML_CLEARED, REJECTED),
        AML_CLEARED to setOf(ELIGIBILITY_CHECK, WITHDRAWN),
        ELIGIBILITY_CHECK to setOf(ELIGIBLE, REJECTED),
        ELIGIBLE to setOf(PENDING_REVIEW),
        PENDING_REVIEW to setOf(APPROVED, REJECTED),
        APPROVED to setOf(FULFILLED, EXPIRED),
    )

    fun canMove(from: ApplicationStatus, to: ApplicationStatus): Boolean = transitions[from]?.contains(to) == true

    /** Eight-stage stepper. S8 (audit) runs across all stages so it is always ACTIVE. */
    fun progress(status: ApplicationStatus, failedAt: LifecycleStage? = null): List<StageProgress> {
        val failedStage = when (status) {
            REJECTED, WITHDRAWN, EXPIRED -> failedAt ?: status.stage
            else -> null
        }
        return LifecycleStage.entries.map { stage ->
            val state = when {
                stage == LifecycleStage.S8_AUDIT -> StepState.ACTIVE
                status == FULFILLED -> StepState.DONE
                failedStage != null -> when {
                    stage.number < failedStage.number -> StepState.DONE
                    stage == failedStage -> StepState.FAILED
                    else -> StepState.PENDING
                }
                stage.number < status.stage.number -> StepState.DONE
                stage == status.stage -> StepState.ACTIVE
                else -> StepState.PENDING
            }
            val sla = if (stage.slaHours > 0) "SLA ${stage.slaHours}h" else "all stages"
            StageProgress(stage, state, "${stage.ownerRole.replace('_', ' ').lowercase()} · $sla")
        }
    }
}
