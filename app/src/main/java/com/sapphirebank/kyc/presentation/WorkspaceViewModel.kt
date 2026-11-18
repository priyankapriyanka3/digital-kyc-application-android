package com.sapphirebank.kyc.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphirebank.kyc.AppContainer
import com.sapphirebank.kyc.domain.model.Actor
import com.sapphirebank.kyc.domain.model.AdminEvent
import com.sapphirebank.kyc.domain.model.BankConfig
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.CountryRisk
import com.sapphirebank.kyc.domain.model.Decision
import com.sapphirebank.kyc.domain.model.DocumentKind
import com.sapphirebank.kyc.domain.model.DocumentRecord
import com.sapphirebank.kyc.domain.model.EligibilityPolicy
import com.sapphirebank.kyc.domain.model.KycThresholds
import com.sapphirebank.kyc.domain.model.ProductConfig
import com.sapphirebank.kyc.domain.model.VcipSession
import com.sapphirebank.kyc.domain.rules.AuditChain
import com.sapphirebank.kyc.domain.usecase.ActionResult
import com.sapphirebank.kyc.domain.usecase.CaseService
import com.sapphirebank.kyc.domain.usecase.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiMessage(val text: String, val isError: Boolean, val id: Long = System.nanoTime())

/**
 * One instance per signed-in user. It only forwards intents to [CaseService] (which enforces roles) and exposes
 * state. Lists come straight from the service's role-filtered flow, so the UI can never show more than the role may see.
 */
class WorkspaceViewModel(private val c: AppContainer, val actor: Actor) : ViewModel() {
    private val svc: CaseService = c.service

    /** null while the first emission is loading. */
    val cases: StateFlow<List<CaseFile>?> =
        svc.observe(actor).map<List<CaseFile>, List<CaseFile>?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val config: StateFlow<BankConfig> = c.repository.config
    val adminLog: StateFlow<List<AdminEvent>> =
        c.repository.adminLog.map { svc.adminLog(actor) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _verified = MutableStateFlow<Map<String, AuditChain.Verification>>(emptyMap())
    val verified: StateFlow<Map<String, AuditChain.Verification>> = _verified.asStateFlow()

    fun clearMessage() { _message.value = null }

    fun analysts() = config.value.activeUsers("KYC_OPERATIONS_OFFICER")

    private fun act(success: String, block: suspend () -> Step, onDone: (Step) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            val r = try { block() } catch (e: Exception) { Step.Refused(e.message ?: "Unexpected error") }
            _busy.value = false
            _message.value = when (r) {
                is Step.Ok -> UiMessage(success, false)
                is Step.Refused -> UiMessage(r.reason, true)
            }
            onDone(r)
        }
    }

    // ---- KYC analyst ----
    fun startKyc(arn: String) = act("KYC review started", { svc.startKyc(actor, arn) })
    fun searchCkycr(arn: String) = act("CKYCR searched", { svc.searchCkycr(actor, arn) })
    fun recordVcip(arn: String, s: VcipSession) = act("V-CIP session recorded", { svc.recordVcip(actor, arn, s) })
    fun kycApprove(arn: String, comment: String, manual: Boolean, done: (Step) -> Unit) =
        act("KYC decision recorded", { svc.kycApprove(actor, arn, comment, manual) }, done)
    fun kycReject(arn: String, comment: String, done: (Step) -> Unit) =
        act("KYC rejected", { svc.kycReject(actor, arn, comment) }, done)

    // ---- shared ----
    fun requestInfo(arn: String, message: String, kinds: List<DocumentKind>, done: (Step) -> Unit) =
        act("Information request sent to the customer", { svc.requestInfo(actor, arn, message, kinds) }, done)

    fun verify(arn: String) {
        svc.verifyAudit(actor, arn)?.let { v -> _verified.update { it + (arn to v) } }
    }

    // ---- compliance ----
    fun screeningDecision(arn: String, clear: Boolean, note: String, done: (Step) -> Unit) =
        act(if (clear) "Application cleared" else "Application placed on hold", { svc.screeningDecision(actor, arn, clear, note) }, done)

    // ---- credit / branch ----
    fun signOff(arn: String, decision: Decision, justification: String, done: (Step) -> Unit) =
        act(if (decision == Decision.APPROVED) "Approval recorded" else "Rejection recorded", { svc.signOff(actor, arn, decision, justification) }, done)

    fun reassign(arn: String, analystId: String, reason: String, done: (Step) -> Unit) =
        act("Application reassigned", { svc.reassign(actor, arn, analystId, reason) }, done)

    // ---- customer ----
    fun respondInfo(arn: String, requestId: String, reply: String, docs: List<DocumentRecord>, done: (Step) -> Unit) =
        act("Thanks, your response was sent", { svc.respondInfo(actor, arn, requestId, reply, docs) }, done)
    fun withdraw(arn: String) = act("Application withdrawn", { svc.withdraw(actor, arn) })

    // ---- system admin ----
    private fun admin(block: suspend () -> ActionResult, onDone: (ActionResult) -> Unit = {}) {
        viewModelScope.launch {
            val r = block()
            _message.value = UiMessage(r.message, !r.ok)
            onDone(r)
        }
    }
    fun setUserActive(id: String, active: Boolean) = admin({ svc.setUserActive(actor, id, active) })
    fun addUser(name: String, role: String, limit: Long, done: (ActionResult) -> Unit) = admin({ svc.addUser(actor, name, role, limit) }, done)
    fun saveThresholds(t: KycThresholds) = admin({ svc.updateThresholds(actor, t) })
    fun saveProduct(p: ProductConfig) = admin({ svc.updateProduct(actor, p) })
    fun saveEligibility(p: EligibilityPolicy) = admin({ svc.updateEligibilityPolicy(actor, p) })
    fun setCountryRisk(code: String, risk: CountryRisk, done: (ActionResult) -> Unit = {}) = admin({ svc.setCountryRisk(actor, code, risk) }, done)
    fun addReference(kind: CaseService.ReferenceKind, v: String, done: (ActionResult) -> Unit) = admin({ svc.addReference(actor, kind, v) }, done)
    fun removeReference(kind: CaseService.ReferenceKind, v: String) = admin({ svc.removeReference(actor, kind, v) })
}
