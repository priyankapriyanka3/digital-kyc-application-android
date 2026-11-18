package com.sapphirebank.kyc.presentation.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.sapphirebank.kyc.AppContainer
import com.sapphirebank.kyc.domain.model.Actor
import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.CustomerApplication
import com.sapphirebank.kyc.domain.model.DocType
import com.sapphirebank.kyc.domain.model.DocumentKind
import com.sapphirebank.kyc.domain.model.DocumentRecord
import com.sapphirebank.kyc.domain.model.DocumentRequirements
import com.sapphirebank.kyc.domain.model.FinancialProfile
import com.sapphirebank.kyc.domain.model.Inr
import com.sapphirebank.kyc.domain.model.KycMode
import com.sapphirebank.kyc.domain.model.ProductLine
import com.sapphirebank.kyc.domain.usecase.NewApplication
import com.sapphirebank.kyc.domain.usecase.Step
import com.sapphirebank.kyc.domain.validation.ApplicationValidator
import com.sapphirebank.kyc.domain.validation.FormField
import com.sapphirebank.kyc.domain.validation.OcrExtractor
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shows only the last four characters; everything before them is replaced by a dot (same length, so cursor mapping is identity). */
object LastFourVisible : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val masked = s.mapIndexed { i, ch -> if (i >= s.length - 4) ch else '•' }.joinToString("")
        return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
    }
}

data class AppForm(
    val product: ProductLine = ProductLine.SAVINGS_ACCOUNT,
    val amount: String = "",
    val name: String = "",
    val dob: String = "",
    val country: String = "IN",
    val pan: String = "",
    val form60: Boolean = false,
    val pep: Boolean = false,
    val nonIndividual: Boolean = false,
    val beneficialOwner: Boolean = false,
    val identityDoc: DocType = DocType.AADHAAR,
    val identityNo: String = "",
    val addressSame: Boolean = true,
    val addressDoc: DocType = DocType.UTILITY_BILL,
    val addressNo: String = "",
    val kycMode: KycMode = KycMode.IN_PERSON,
    val consent: Boolean = false,
    val income: String = "",
    val existingEmi: String = "",
    val employment: String = "Salaried",
    val tenure: String = "",
    val docs: Map<DocumentKind, DocumentRecord> = emptyMap(),
    val ocrName: String? = null,
    val ocrDob: String? = null,
)

sealed interface SubmitState {
    data object Idle : SubmitState
    data object Submitting : SubmitState
    data class Done(val arn: String) : SubmitState
    data class Failed(val message: String) : SubmitState
}

class NewApplicationViewModel(private val c: AppContainer, private val actor: Actor, preselect: ProductLine?) : ViewModel() {
    private val _form = MutableStateFlow(AppForm(product = preselect ?: ProductLine.SAVINGS_ACCOUNT))
    val form: StateFlow<AppForm> = _form.asStateFlow()
    private val _step = MutableStateFlow(if (preselect != null) 1 else 0)
    val step: StateFlow<Int> = _step.asStateFlow()
    private val _errors = MutableStateFlow<Map<FormField, String>>(emptyMap())
    val errors: StateFlow<Map<FormField, String>> = _errors.asStateFlow()
    private val _submit = MutableStateFlow<SubmitState>(SubmitState.Idle)
    val submit: StateFlow<SubmitState> = _submit.asStateFlow()
    private val _scan = MutableStateFlow<String?>(null)
    val scanNote: StateFlow<String?> = _scan.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    val config get() = c.repository.config.value

    fun update(block: (AppForm) -> AppForm) { _form.update(block); _errors.value = emptyMap() }

    fun app(f: AppForm = _form.value): CustomerApplication {
        val addrDoc = if (f.addressSame) f.identityDoc else f.addressDoc
        val addrNo = if (f.addressSame) f.identityNo else f.addressNo
        return CustomerApplication(
            arn = "", customerName = f.name.trim(), dateOfBirth = f.dob.trim(), productLine = f.product,
            sanctionAmountInr = f.amount.filter { it.isDigit() }.toLongOrNull() ?: 0L,
            panNumber = if (f.form60) "" else f.pan.trim().uppercase(), form60Submitted = f.form60,
            identityDoc = f.identityDoc, identityDocNumber = f.identityNo.trim(),
            addressDoc = addrDoc, addressDocNumber = addrNo.trim(), kycMode = f.kycMode,
            aadhaarConsentGiven = f.consent, isPep = f.pep, isNonIndividual = f.nonIndividual,
            beneficialOwnerRecorded = f.beneficialOwner, status = ApplicationStatus.DRAFT, countryCode = f.country,
        )
    }

    fun financials(f: AppForm = _form.value) = FinancialProfile(
        monthlyIncomeInr = f.income.filter { it.isDigit() }.toLongOrNull() ?: 0L,
        existingEmiInr = f.existingEmi.filter { it.isDigit() }.toLongOrNull() ?: 0L,
        employmentType = f.employment, tenureMonths = f.tenure.filter { it.isDigit() }.toIntOrNull() ?: 0,
    )

    fun requiredDocs() = DocumentRequirements.required(app())

    /** Field errors that belong to a given wizard step (0 product, 1 you, 2 identity, 3 financials, 4 documents). */
    private fun stepErrors(step: Int): Map<FormField, String> {
        val f = _form.value
        val all = ApplicationValidator.validate(app(f), java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")))
        val out = linkedMapOf<FormField, String>()
        when (step) {
            0 -> {
                val p = config.product(f.product)
                val amt = f.amount.filter { it.isDigit() }.toLongOrNull() ?: 0L
                // The product range is the most useful message, including for an empty amount.
                if (amt !in p.minAmountInr..p.maxAmountInr)
                    out[FormField.AMOUNT] = "Enter between ${Inr.format(p.minAmountInr)} and ${Inr.format(p.maxAmountInr)}"
                else all[FormField.AMOUNT]?.let { out[FormField.AMOUNT] = it }
            }
            1 -> listOf(FormField.NAME, FormField.DOB, FormField.PAN).forEach { k -> all[k]?.let { out[k] = it } }
            2 -> listOf(FormField.IDENTITY, FormField.ADDRESS, FormField.CONSENT).forEach { k -> all[k]?.let { out[k] = it } }
            3 -> if (f.product == ProductLine.RETAIL_LOAN) {
                val fin = financials(f)
                val prod = config.product(f.product)
                if (fin.monthlyIncomeInr <= 0) out[FormField.INCOME] = "Enter your monthly income"
                if (fin.tenureMonths <= 0 || (prod.maxTenureMonths > 0 && fin.tenureMonths > prod.maxTenureMonths))
                    out[FormField.TENURE] = "Tenure must be 1 to ${prod.maxTenureMonths} months"
            }
            4 -> {
                val missing = DocumentRequirements.missing(app(f), f.docs.values.toList())
                if (missing.isNotEmpty()) out[FormField.DOCUMENTS] = "Upload: " + missing.joinToString { it.label }
            }
        }
        return out
    }

    /** Wizard steps shown to this customer. Financial details only apply to loans. */
    fun steps(): List<Int> = if (_form.value.product == ProductLine.RETAIL_LOAN) listOf(0, 1, 2, 3, 4, 5) else listOf(0, 1, 2, 4, 5)

    fun next(): Boolean {
        val cur = _step.value
        val e = if (cur < 5) stepErrors(cur) else emptyMap()
        _errors.value = e
        if (e.isNotEmpty()) return false
        val seq = steps()
        _step.value = seq.getOrElse(seq.indexOf(cur) + 1) { cur }
        return true
    }

    fun back() {
        val seq = steps()
        val i = seq.indexOf(_step.value)
        if (i > 0) { _step.value = seq[i - 1]; _errors.value = emptyMap() }
    }

    fun onDocumentPicked(kind: DocumentKind, uri: String, fileName: String) {
        val rec = DocumentRecord(kind, fileName, uri, Instant.now().toString(), actor.userId)
        _form.update { it.copy(docs = it.docs + (kind to rec)) }
        _errors.value = emptyMap()
        if (kind == DocumentKind.PAN || kind == DocumentKind.IDENTITY_OTHER) scan(uri)
    }

    private fun scan(uri: String) {
        viewModelScope.launch {
            _scanning.value = true
            _scan.value = null
            try {
                val r = OcrExtractor.parse(c.recognizer.recognize(uri))
                _form.update { f ->
                    var n = f.copy(ocrName = r.name ?: f.ocrName, ocrDob = r.dateOfBirth ?: f.ocrDob)
                    if (r.docType == DocType.PAN && n.pan.isBlank() && !n.form60) n = n.copy(pan = r.number.orEmpty())
                    if (n.name.isBlank() && r.name != null) n = n.copy(name = r.name)
                    if (n.dob.isBlank() && r.dateOfBirth != null) n = n.copy(dob = r.dateOfBirth)
                    n
                }
                _scan.value = if (r.docType == null && r.name == null) "We could not read this image. Please check the details manually."
                else "Read from your document: ${listOfNotNull(r.docType?.label, r.name).joinToString(" · ")}. Please confirm the details."
            } catch (e: Exception) {
                _scan.value = "Could not read the image (${e.message ?: "unknown error"}). Enter the details manually."
            } finally {
                _scanning.value = false
            }
        }
    }

    fun removeDocument(kind: DocumentKind) = _form.update { it.copy(docs = it.docs - kind) }

    fun submitNow() {
        if (_submit.value == SubmitState.Submitting) return
        val f = _form.value
        viewModelScope.launch {
            _submit.value = SubmitState.Submitting
            val fin = if (f.product == ProductLine.RETAIL_LOAN) financials(f) else null
            val r = try {
                c.service.submit(actor, NewApplication(app(f), f.docs.values.toList(), fin, f.ocrName, f.ocrDob))
            } catch (e: Exception) { Step.Refused(e.message ?: "Unexpected error") }
            when (r) {
                is Step.Ok -> _submit.value = SubmitState.Done(r.case.arn)
                is Step.Refused -> { _errors.value = r.fieldErrors; _submit.value = SubmitState.Failed(r.reason) }
            }
        }
    }

    fun dismissFailure() { if (_submit.value is SubmitState.Failed) _submit.value = SubmitState.Idle }
}
