package com.sapphirebank.kyc.presentation.application

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphirebank.kyc.AppContainer
import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.CustomerApplication
import com.sapphirebank.kyc.domain.model.DocType
import com.sapphirebank.kyc.domain.model.KycEvidence
import com.sapphirebank.kyc.domain.model.KycMode
import com.sapphirebank.kyc.domain.model.ProductLine
import com.sapphirebank.kyc.domain.model.RuleEvaluation
import com.sapphirebank.kyc.domain.rules.KycRuleEngine
import com.sapphirebank.kyc.domain.usecase.Step
import com.sapphirebank.kyc.domain.validation.ApplicationValidator
import com.sapphirebank.kyc.domain.validation.FormField
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FormState(
    val name: String = "",
    val dob: String = "",
    val amount: String = "",
    val product: ProductLine = ProductLine.SAVINGS_ACCOUNT,
    val pan: String = "",
    val form60: Boolean = false,
    val identityDoc: DocType = DocType.AADHAAR,
    val identityNo: String = "",
    val addressDoc: DocType = DocType.AADHAAR,
    val addressNo: String = "",
    val kycMode: KycMode = KycMode.VCIP,
    val consent: Boolean = false,
    val pep: Boolean = false,
    val nonIndividual: Boolean = false,
    val beneficialOwner: Boolean = false,
    val ocrName: String? = null,
    val showAllErrors: Boolean = false,
    val scanning: Boolean = false,
    val scanSummary: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class ApplicationViewModel(private val c: AppContainer) : ViewModel() {

    private val _form = MutableStateFlow(FormState())
    val form: StateFlow<FormState> = _form.asStateFlow()
    val case: StateFlow<CaseFile?> = c.observeCase()

    fun update(block: (FormState) -> FormState) = _form.update(block)

    private fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Kolkata"))

    fun buildApplication(f: FormState): CustomerApplication = CustomerApplication(
        arn = "",
        customerName = f.name.trim(),
        dateOfBirth = f.dob.trim(),
        productLine = f.product,
        sanctionAmountInr = f.amount.filter { it.isDigit() }.toLongOrNull() ?: 0L,
        panNumber = f.pan.trim().uppercase(),
        form60Submitted = f.form60,
        identityDoc = f.identityDoc,
        identityDocNumber = f.identityNo.trim(),
        addressDoc = f.addressDoc,
        addressDocNumber = f.addressNo.trim(),
        kycMode = f.kycMode,
        aadhaarConsentGiven = f.consent,
        isPep = f.pep,
        isNonIndividual = f.nonIndividual,
        beneficialOwnerRecorded = f.beneficialOwner,
        status = ApplicationStatus.DRAFT,
    )

    /** Field errors the user should see now: everything after a submit attempt, otherwise only touched fields. */
    fun visibleErrors(f: FormState): Map<FormField, String> {
        val all = ApplicationValidator.validate(buildApplication(f), today())
        return all.filter { (field, _) ->
            f.showAllErrors || when (field) {
                FormField.NAME -> f.name.isNotBlank()
                FormField.DOB -> f.dob.isNotBlank()
                FormField.AMOUNT -> f.amount.isNotBlank()
                FormField.PAN -> f.pan.isNotBlank()
                FormField.IDENTITY -> f.identityNo.isNotBlank()
                FormField.ADDRESS -> f.addressNo.isNotBlank()
                FormField.CONSENT -> f.kycMode == KycMode.AADHAAR_OTP_EKYC
            }
        }
    }

    /** Live pre-check of KYC-001..004 against the form (the rules that depend only on the application). */
    fun preCheck(f: FormState): List<RuleEvaluation> =
        KycRuleEngine.evaluate(buildApplication(f), KycEvidence())
            .filter { it.ruleId in setOf("KYC-001", "KYC-002", "KYC-003", "KYC-004") }

    fun scan(imageUri: String) {
        _form.update { it.copy(scanning = true, scanSummary = null, message = null) }
        viewModelScope.launch {
            runCatching { c.scanDocument(imageUri) }
                .onSuccess { r ->
                    _form.update { f ->
                        var n = f.copy(scanning = false)
                        when (r.docType) {
                            DocType.PAN -> n = n.copy(pan = r.number.orEmpty(), form60 = false)
                            DocType.AADHAAR, DocType.PASSPORT -> n = n.copy(
                                identityDoc = r.docType, identityNo = r.number.orEmpty(),
                                addressDoc = r.docType, addressNo = r.number.orEmpty(),
                            )
                            else -> Unit
                        }
                        if (r.name != null) n = n.copy(ocrName = r.name, name = n.name.ifBlank { r.name })
                        if (r.dateOfBirth != null && n.dob.isBlank()) n = n.copy(dob = r.dateOfBirth)
                        n.copy(
                            scanSummary = if (r.docType == null && r.name == null) "No PAN / Aadhaar / passport number recognised. Enter it manually."
                            else "Recognised ${r.docType?.label ?: "text"}${r.name?.let { " · name \"$it\"" }.orEmpty()}. Review the filled fields.",
                        )
                    }
                }
                .onFailure { e ->
                    _form.update { it.copy(scanning = false, message = "OCR failed: ${e.message}", messageIsError = true) }
                }
        }
    }

    fun submit() {
        val f = _form.value
        viewModelScope.launch {
            when (val r = c.submitApplication(buildApplication(f), f.ocrName)) {
                is Step.Ok -> _form.update {
                    it.copy(showAllErrors = false, message = "Submitted as ${r.case.application.arn}; documents validated.", messageIsError = false)
                }
                is Step.Refused -> _form.update { it.copy(showAllErrors = true, message = r.reason, messageIsError = true) }
            }
        }
    }

    fun fillSample() = _form.update {
        it.copy(
            name = "Asha Rao", dob = "15/04/1990", amount = "500000", pan = "ABCDE1234F", form60 = false,
            identityDoc = DocType.AADHAAR, identityNo = "234567890124",
            addressDoc = DocType.AADHAAR, addressNo = "234567890124",
            kycMode = KycMode.VCIP, message = null,
        )
    }

    fun newApplication() {
        viewModelScope.launch {
            c.resetCase()
            _form.value = FormState()
        }
    }
}
