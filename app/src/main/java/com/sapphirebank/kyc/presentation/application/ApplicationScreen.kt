package com.sapphirebank.kyc.presentation.application

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.DocType
import com.sapphirebank.kyc.domain.model.KycMode
import com.sapphirebank.kyc.domain.model.ProductLine
import com.sapphirebank.kyc.domain.validation.DocumentValidators
import com.sapphirebank.kyc.domain.validation.FormField
import com.sapphirebank.kyc.presentation.common.*

@Composable
fun ApplicationScreen(vm: ApplicationViewModel) {
    val form by vm.form.collectAsStateWithLifecycle()
    val case by vm.case.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val submitted = case
        if (submitted != null) {
            SubmittedCard(submitted, form.message, vm::newApplication)
        } else {
            ApplicationForm(vm, form)
        }
    }
}

@Composable
private fun SubmittedCard(case: CaseFile, message: String?, onNew: () -> Unit) {
    val a = case.application
    if (message != null) Banner(message, isError = false)
    SectionCard("Application ${a.arn}") {
        InfoRow("Customer", a.customerName)
        InfoRow("Product", "${a.productLine.name.replace('_', ' ')} · INR ${a.sanctionAmountInr}")
        InfoRow("PAN", if (a.panNumber.isNotBlank()) DocumentValidators.mask(DocType.PAN, a.panNumber) else "Form 60 submitted")
        InfoRow("Identity (${a.identityDoc.label})", DocumentValidators.mask(a.identityDoc, a.identityDocNumber))
        InfoRow("Address (${a.addressDoc.label})", DocumentValidators.mask(a.addressDoc, a.addressDocNumber))
        InfoRow("KYC mode", a.kycMode.label)
        InfoRow("Status", a.status.name)
        Text(
            "Continue on the Tracker tab to run KYC (V-CIP) and AML screening.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onNew) { Text("Start a new application") }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ApplicationForm(vm: ApplicationViewModel, f: FormState) {
    val errors = vm.visibleErrors(f)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.scan(uri.toString())
    }

    f.message?.let { Banner(it, f.messageIsError) }

    SectionCard("1 · Applicant") {
        Field("Full name", f.name, errors[FormField.NAME]) { v -> vm.update { it.copy(name = v) } }
        Field("Date of birth (dd/MM/yyyy)", f.dob, errors[FormField.DOB], KeyboardType.Number) { v -> vm.update { it.copy(dob = v) } }
        DropdownField("Product", ProductLine.entries, f.product, { it.name.replace('_', ' ') }) { p -> vm.update { it.copy(product = p) } }
        Field("Sanction / deposit amount (INR)", f.amount, errors[FormField.AMOUNT], KeyboardType.Number) { v -> vm.update { it.copy(amount = v) } }
        LabeledSwitch("Politically exposed person (PEP)", f.pep, { v -> vm.update { it.copy(pep = v) } }, hint = "PEP forces HIGH risk and L3 senior sign-off (DCS-004)")
        LabeledSwitch("Non-individual customer", f.nonIndividual, { v -> vm.update { it.copy(nonIndividual = v) } })
        if (f.nonIndividual) {
            LabeledSwitch("Beneficial owner recorded (KYC-009, 10% threshold)", f.beneficialOwner, { v -> vm.update { it.copy(beneficialOwner = v) } })
        }
    }

    SectionCard("2 · Documents") {
        Button(onClick = { picker.launch("image/*") }, enabled = !f.scanning, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.DocumentScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (f.scanning) "Reading document…" else "Scan PAN / Aadhaar image (on-device OCR)")
        }
        if (f.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        f.scanSummary?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Field(
            "PAN (AAAAA9999A)", f.pan, errors[FormField.PAN], enabled = !f.form60,
            onChange = { v -> vm.update { it.copy(pan = v.uppercase().take(10)) } },
        )
        LabeledSwitch("No PAN: submit Form 60 instead", f.form60, { v -> vm.update { it.copy(form60 = v, pan = if (v) "" else it.pan) } },
            hint = "KYC-001: exactly one of PAN or Form 60")

        DropdownField("Proof of identity", DocType.entries.filter { it.provesIdentity && it != DocType.FORM60 }, f.identityDoc, { it.label }) { d ->
            vm.update { it.copy(identityDoc = d) }
        }
        Field("Identity document number", f.identityNo, errors[FormField.IDENTITY], KeyboardType.Text) { v -> vm.update { it.copy(identityNo = v) } }

        DropdownField("Proof of address", DocType.entries.filter { it.provesAddress }, f.addressDoc, { it.label }) { d ->
            vm.update { it.copy(addressDoc = d) }
        }
        Field("Address document number", f.addressNo, errors[FormField.ADDRESS], KeyboardType.Text) { v -> vm.update { it.copy(addressNo = v) } }
    }

    SectionCard("3 · Verification mode") {
        DropdownField("KYC mode", KycMode.entries, f.kycMode, { it.label }) { m -> vm.update { it.copy(kycMode = m) } }
        if (f.kycMode == KycMode.AADHAAR_OTP_EKYC) {
            LabeledSwitch("Customer gives explicit consent for Aadhaar authentication", f.consent, { v -> vm.update { it.copy(consent = v) } })
            errors[FormField.CONSENT]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }

    SectionCard("KYC rule pre-check (KYC-001 to KYC-004)") {
        vm.preCheck(f).forEach { RuleRow(it) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = vm::fillSample) { Text("Fill sample") }
        Button(onClick = vm::submit, modifier = Modifier.weight(1f)) { Text("Submit application") }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: String?,
    keyboard: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        isError = error != null, enabled = enabled, singleLine = true,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
