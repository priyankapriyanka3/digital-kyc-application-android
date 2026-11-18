package com.sapphirebank.kyc.presentation.tracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphirebank.kyc.AppContainer
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.VcipSession
import com.sapphirebank.kyc.domain.usecase.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrackerMessage(val text: String, val isError: Boolean)

class TrackerViewModel(private val c: AppContainer) : ViewModel() {
    val case: StateFlow<CaseFile?> = c.observeCase()

    /** Defaults describe a clean session; toggle any switch to see KYC-006 fail. */
    private val _vcip = MutableStateFlow(
        VcipSession(
            geoTagInIndia = true, ipCountryCode = "IN", officialIsTrainedBankEmployee = true,
            livenessPassed = false, panCapturedLive = true, ovdVerifiedViaSource = true,
            randomQuestionsAsked = 3, recordingHashed = true, faceMatchScore = 0.95, concurrentAuditPassed = true,
        ),
    )
    val vcip: StateFlow<VcipSession> = _vcip.asStateFlow()

    private val _message = MutableStateFlow<TrackerMessage?>(null)
    val message: StateFlow<TrackerMessage?> = _message.asStateFlow()

    fun editVcip(block: (VcipSession) -> VcipSession) = _vcip.update(block)

    private fun report(r: Step, ok: String) {
        _message.value = when (r) {
            is Step.Ok -> TrackerMessage(ok, false)
            is Step.Refused -> TrackerMessage(r.reason, true)
        }
    }

    fun startKyc() = viewModelScope.launch { report(c.startKyc(), "KYC started") }
    fun searchCkycr() = viewModelScope.launch { report(c.searchCkycr(), "CKYCR searched (KYC-005)") }
    fun recordVcip() = viewModelScope.launch { report(c.recordVcipSession(_vcip.value), "V-CIP session recorded and evaluated against KYC-006") }
    fun completeKyc() = viewModelScope.launch { report(c.completeKyc(), "KYC completed") }
    fun advance() = viewModelScope.launch { report(c.advanceStage(), "Moved to the next status") }
    fun withdraw() = viewModelScope.launch { report(c.withdraw(), "Application withdrawn") }
}
