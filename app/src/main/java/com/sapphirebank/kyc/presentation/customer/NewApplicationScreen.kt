package com.sapphirebank.kyc.presentation.customer

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sapphirebank.kyc.AppContainer
import com.sapphirebank.kyc.domain.model.Actor
import com.sapphirebank.kyc.domain.model.Countries
import com.sapphirebank.kyc.domain.model.DocType
import com.sapphirebank.kyc.domain.model.DocumentKind
import com.sapphirebank.kyc.domain.model.Inr
import com.sapphirebank.kyc.domain.model.KycMode
import com.sapphirebank.kyc.domain.model.ProductLine
import com.sapphirebank.kyc.domain.model.Tone
import com.sapphirebank.kyc.domain.model.dicgcNote
import com.sapphirebank.kyc.domain.model.displayName
import com.sapphirebank.kyc.domain.validation.DocumentValidators
import com.sapphirebank.kyc.domain.validation.FormField
import com.sapphirebank.kyc.presentation.common.AppTextField
import com.sapphirebank.kyc.presentation.common.Banner
import com.sapphirebank.kyc.presentation.common.DocumentCard
import com.sapphirebank.kyc.presentation.common.DropdownField
import com.sapphirebank.kyc.presentation.common.LabelValue
import com.sapphirebank.kyc.presentation.common.LabeledSwitch
import com.sapphirebank.kyc.presentation.common.ScreenTitle
import com.sapphirebank.kyc.presentation.common.SectionCard
import com.sapphirebank.kyc.presentation.common.rememberDocumentPicker
import com.sapphirebank.kyc.presentation.common.vmFactory
import com.sapphirebank.kyc.presentation.theme.KycColors
import com.sapphirebank.kyc.presentation.theme.Radius
import com.sapphirebank.kyc.presentation.theme.Sizes
import com.sapphirebank.kyc.presentation.theme.Spacing

private val STEP_TITLES = mapOf(0 to "Choose a product", 1 to "About you", 2 to "Identity and address", 3 to "Your finances", 4 to "Upload documents", 5 to "Review and submit")

@Composable
fun NewApplicationScreen(
    container: AppContainer, actor: Actor, session: Int, preselect: String?,
    onClose: () -> Unit, onOpenCase: (String) -> Unit,
) {
    val vm: NewApplicationViewModel = viewModel(
        key = "apply-${actor.userId}-$session",
        factory = vmFactory { NewApplicationViewModel(container, actor, preselect?.let { runCatching { ProductLine.valueOf(it) }.getOrNull() }) },
    )
    val form by vm.form.collectAsStateWithLifecycle()
    val step by vm.step.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val submit by vm.submit.collectAsStateWithLifecycle()
    val scanNote by vm.scanNote.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val picker = rememberDocumentPicker { kind, uri, name -> vm.onDocumentPicked(kind, uri, name) }

    val done = submit as? SubmitState.Done
    if (done != null) {
        SubmittedView(done.arn, form.product, vm.config.dicgcCoverInr, onTrack = { onOpenCase(done.arn) }, onClose = onClose)
        return
    }

    val seq = vm.steps()
    val pos = seq.indexOf(step).coerceAtLeast(0)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text("Step ${pos + 1} of ${seq.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { (pos + 1f) / seq.size }, modifier = Modifier.fillMaxWidth())
            ScreenTitle(STEP_TITLES[step].orEmpty())
            (submit as? SubmitState.Failed)?.let { Banner(it.message, isError = true) }

            when (step) {
                0 -> ProductStep(vm, form, errors)
                1 -> AboutStep(vm, form, errors)
                2 -> IdentityStep(vm, form, errors)
                3 -> FinanceStep(vm, form, errors)
                4 -> DocumentsStep(vm, form, errors, scanning, scanNote, picker)
                else -> ReviewStep(vm, form)
            }
        }
        Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(onClick = { if (pos == 0) onClose() else vm.back() }, modifier = Modifier.weight(1f).heightIn(min = Sizes.touch)) {
                    Text(if (pos == 0) "Cancel" else "Back")
                }
                if (step < 5) {
                    Button(onClick = { vm.next() }, modifier = Modifier.weight(1f).heightIn(min = Sizes.touch)) { Text("Continue") }
                } else {
                    Button(
                        onClick = vm::submitNow, enabled = submit != SubmitState.Submitting,
                        modifier = Modifier.weight(1f).heightIn(min = Sizes.touch),
                    ) {
                        if (submit == SubmitState.Submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Text("Submit application")
                    }
                }
            }
        }
    }
}

@Composable
private fun SubmittedView(arn: String, product: ProductLine, cover: Long, onTrack: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        ScreenTitle("Application submitted")
        Banner("Thank you. Your ${product.displayName().lowercase()} application is being reviewed.", isError = false)
        SectionCard(null) {
            LabelValue("Application number", arn)
            Text("Keep this number to track your application. We will notify you if we need anything else.", style = MaterialTheme.typography.bodyMedium)
            dicgcNote(product, cover)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Button(onClick = onTrack, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch)) { Text("Track application") }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch)) { Text("Back to home") }
    }
}

@Composable
private fun ProductStep(vm: NewApplicationViewModel, f: AppForm, errors: Map<FormField, String>) {
    val cfg = vm.config
    cfg.products.filter { it.enabled }.forEach { p ->
        val sel = p.product == f.product
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.card),
            color = if (sel) KycColors.SapphireContainer else MaterialTheme.colorScheme.surface,
            border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) KycColors.Sapphire else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().selectable(selected = sel, role = Role.RadioButton) { vm.update { it.copy(product = p.product) } },
        ) {
            Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = sel, onClick = null)
                Spacer(Modifier.width(Spacing.m))
                Column {
                    Text(p.product.displayName(), style = MaterialTheme.typography.titleSmall)
                    Text(p.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    val label = when (f.product) { ProductLine.RETAIL_LOAN -> "Loan amount (₹)"; ProductLine.TERM_DEPOSIT -> "Deposit amount (₹)"; else -> "Opening deposit (₹)" }
    AppTextField(label, f.amount, { v -> vm.update { it.copy(amount = v.filter(Char::isDigit)) } }, error = errors[FormField.AMOUNT], keyboard = KeyboardType.Number)
    dicgcNote(f.product, cfg.dicgcCoverInr)?.let { Banner(it, isError = false, tone = Tone.INFO) }
}

@Composable
private fun AboutStep(vm: NewApplicationViewModel, f: AppForm, errors: Map<FormField, String>) {
    AppTextField("Full name (as on documents)", f.name, { v -> vm.update { it.copy(name = v) } }, error = errors[FormField.NAME])
    AppTextField("Date of birth (dd/MM/yyyy)", f.dob, { v -> vm.update { it.copy(dob = v) } }, error = errors[FormField.DOB], keyboard = KeyboardType.Number)
    DropdownField("Country of residence", Countries.names.keys.toList(), f.country, { Countries.label(it) }) { c -> vm.update { it.copy(country = c) } }
    AppTextField(
        "PAN (AAAAA9999A)", f.pan, { v -> vm.update { it.copy(pan = v.uppercase().take(10)) } },
        error = errors[FormField.PAN], enabled = !f.form60, supporting = if (f.form60) "Not needed with Form 60" else null,
    )
    LabeledSwitch("I do not have a PAN (submit Form 60)", f.form60, { on -> vm.update { it.copy(form60 = on, pan = if (on) "" else it.pan) } }, hint = "Provide either PAN or Form 60, not both")
    LabeledSwitch("I am a politically exposed person (PEP)", f.pep, { on -> vm.update { it.copy(pep = on) } }, hint = "Requires additional compliance review")
    LabeledSwitch("This is for a business or organisation", f.nonIndividual, { on -> vm.update { it.copy(nonIndividual = on) } })
    if (f.nonIndividual) LabeledSwitch("Beneficial owner details are available", f.beneficialOwner, { on -> vm.update { it.copy(beneficialOwner = on) } }, hint = "Anyone holding 10% or more")
}

@Composable
private fun IdentityStep(vm: NewApplicationViewModel, f: AppForm, errors: Map<FormField, String>) {
    val idDocs = DocType.entries.filter { it.provesIdentity && it != DocType.FORM60 }
    DropdownField("Proof of identity", idDocs, f.identityDoc, { it.label }) { d -> vm.update { it.copy(identityDoc = d) } }
    AppTextField(
        if (f.identityDoc == DocType.AADHAAR) "Aadhaar number" else "${f.identityDoc.label} number", f.identityNo,
        { v -> vm.update { it.copy(identityNo = v.take(20)) } }, error = errors[FormField.IDENTITY],
        keyboard = if (f.identityDoc == DocType.AADHAAR) KeyboardType.NumberPassword else KeyboardType.Text,
        visualTransformation = if (f.identityDoc == DocType.AADHAAR) LastFourVisible else VisualTransformation.None,
        supporting = if (f.identityDoc == DocType.AADHAAR) "Only the last 4 digits stay visible. We store only the masked number." else null,
    )
    LabeledSwitch("Use the same document as address proof", f.addressSame, { on -> vm.update { it.copy(addressSame = on) } })
    if (!f.addressSame) {
        DropdownField("Proof of address", DocType.entries.filter { it.provesAddress }, f.addressDoc, { it.label }) { d -> vm.update { it.copy(addressDoc = d) } }
        AppTextField("${f.addressDoc.label} number", f.addressNo, { v -> vm.update { it.copy(addressNo = v.take(20)) } }, error = errors[FormField.ADDRESS],
            visualTransformation = if (f.addressDoc == DocType.AADHAAR) LastFourVisible else VisualTransformation.None)
    } else errors[FormField.ADDRESS]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    DropdownField("How should we verify you?", KycMode.entries, f.kycMode, { it.label }) { m -> vm.update { it.copy(kycMode = m) } }
    if (f.kycMode == KycMode.AADHAAR_OTP_EKYC) {
        LabeledSwitch("I consent to Aadhaar authentication", f.consent, { on -> vm.update { it.copy(consent = on) } })
        errors[FormField.CONSENT]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun FinanceStep(vm: NewApplicationViewModel, f: AppForm, errors: Map<FormField, String>) {
    val cfg = vm.config
    AppTextField("Monthly income (₹)", f.income, { v -> vm.update { it.copy(income = v.filter(Char::isDigit)) } }, error = errors[FormField.INCOME], keyboard = KeyboardType.Number)
    AppTextField("Existing monthly EMIs (₹)", f.existingEmi, { v -> vm.update { it.copy(existingEmi = v.filter(Char::isDigit)) } }, keyboard = KeyboardType.Number)
    DropdownField("Employment type", cfg.employmentTypes, f.employment, { it }) { e -> vm.update { it.copy(employment = e) } }
    AppTextField(
        "Tenure (months)", f.tenure, { v -> vm.update { it.copy(tenure = v.filter(Char::isDigit).take(3)) } },
        error = errors[FormField.TENURE], keyboard = KeyboardType.Number,
        supporting = "Up to ${cfg.product(f.product).maxTenureMonths} months",
    )
}

@Composable
private fun DocumentsStep(
    vm: NewApplicationViewModel, f: AppForm, errors: Map<FormField, String>,
    scanning: Boolean, scanNote: String?, picker: (DocumentKind) -> Unit,
) {
    Text("Upload a clear photo of each document. Aadhaar copies must be masked: only the last 4 digits may be visible.", style = MaterialTheme.typography.bodyMedium)
    errors[FormField.DOCUMENTS]?.let { Banner(it, isError = true) }
    if (scanning) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Reading your document…", style = MaterialTheme.typography.bodySmall) }
    scanNote?.let { Banner(it, isError = false, tone = Tone.INFO) }
    vm.requiredDocs().forEach { k -> DocumentCard(k, f.docs[k], onUpload = { picker(k) }) }
}

@Composable
private fun ReviewStep(vm: NewApplicationViewModel, f: AppForm) {
    val a = vm.app(f)
    SectionCard("Your application") {
        LabelValue("Product", f.product.displayName())
        LabelValue("Amount", Inr.format(a.sanctionAmountInr))
        LabelValue("Name", a.customerName)
        LabelValue("Date of birth", a.dateOfBirth)
        LabelValue("Country", Countries.label(a.countryCode))
        LabelValue("PAN", if (a.panNumber.isNotBlank()) DocumentValidators.mask(DocType.PAN, a.panNumber) else "Form 60")
        LabelValue(a.identityDoc.label, if (a.identityDoc == DocType.AADHAAR) "XXXX XXXX " + a.identityDocNumber.filter { it.isDigit() }.takeLast(4) else DocumentValidators.mask(a.identityDoc, a.identityDocNumber))
        LabelValue("Verification", a.kycMode.label)
        if (f.product == ProductLine.RETAIL_LOAN) {
            val fin = vm.financials(f)
            LabelValue("Monthly income", Inr.format(fin.monthlyIncomeInr))
            LabelValue("Tenure", "${fin.tenureMonths} months")
        }
    }
    SectionCard("Documents") {
        f.docs.values.forEach { LabelValue(it.kind.label, it.fileName) }
    }
    dicgcNote(f.product, vm.config.dicgcCoverInr)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Text("By submitting you confirm the details are correct and consent to verification of your documents.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
