package com.sapphirebank.kyc.presentation.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphirebank.kyc.AppContainer
import com.sapphirebank.kyc.domain.model.ApprovalLevel
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.Decision
import com.sapphirebank.kyc.domain.model.Officer
import com.sapphirebank.kyc.domain.rules.AuditChain
import com.sapphirebank.kyc.domain.usecase.Step
import com.sapphirebank.kyc.presentation.tracker.TrackerMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReviewViewModel(private val c: AppContainer) : ViewModel() {
    val case: StateFlow<CaseFile?> = c.observeCase()
    val officers: List<Officer> = c.repository.officers

    private val _message = MutableStateFlow<TrackerMessage?>(null)
    val message: StateFlow<TrackerMessage?> = _message.asStateFlow()

    private val _verification = MutableStateFlow<AuditChain.Verification?>(null)
    val verification: StateFlow<AuditChain.Verification?> = _verification.asStateFlow()

    fun officersFor(level: ApprovalLevel): List<Officer> = when (level) {
        ApprovalLevel.L1_CREDIT_OFFICER -> officers.filter { it.role == "CREDIT_OFFICER" }
        ApprovalLevel.L2_BRANCH_MANAGER -> officers.filter { it.role == "BRANCH_MANAGER" }
        ApprovalLevel.L3_SENIOR_MANAGEMENT -> officers.filter { it.role == "SENIOR_MANAGEMENT" }
    }

    fun signOff(level: ApprovalLevel, officer: Officer, decision: Decision) {
        viewModelScope.launch {
            _message.value = when (val r = c.signOff(level, officer, decision)) {
                is Step.Ok -> TrackerMessage("${level.label}: ${decision.name} by ${officer.displayName}", false)
                is Step.Refused -> TrackerMessage(r.reason, true)
            }
            _verification.value = null
        }
    }

    fun fulfil() {
        viewModelScope.launch {
            _message.value = when (val r = c.advanceStage()) {
                is Step.Ok -> TrackerMessage("Account opened (FULFILLED)", false)
                is Step.Refused -> TrackerMessage(r.reason, true)
            }
        }
    }

    fun verify() {
        val cur = case.value ?: return
        _verification.value = c.verifyAuditChain(cur)
    }

    fun tamper() {
        viewModelScope.launch {
            c.simulateAuditTamper()
            _verification.value = null
            _message.value = TrackerMessage("Demo: audit event #1 was altered. Run Verify chain.", true)
        }
    }
}
