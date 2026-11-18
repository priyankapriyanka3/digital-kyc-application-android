package com.sapphirebank.kyc.presentation.staff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.KycMode
import com.sapphirebank.kyc.domain.model.KycOutcome
import com.sapphirebank.kyc.domain.model.RuleOutcome
import com.sapphirebank.kyc.domain.model.Tone
import com.sapphirebank.kyc.domain.model.VcipSession
import com.sapphirebank.kyc.domain.rules.ValidationChecks
import com.sapphirebank.kyc.domain.model.DocumentRequirements
import com.sapphirebank.kyc.presentation.ActionSheet
import com.sapphirebank.kyc.presentation.AuditSection
import com.sapphirebank.kyc.presentation.CaseGate
import com.sapphirebank.kyc.presentation.CaseHeader
import com.sapphirebank.kyc.presentation.DocumentsSection
import com.sapphirebank.kyc.presentation.InfoRequestSheet
import com.sapphirebank.kyc.presentation.WorkspaceViewModel
import com.sapphirebank.kyc.presentation.common.Banner
import com.sapphirebank.kyc.presentation.common.InfoRequestCard
import com.sapphirebank.kyc.presentation.common.LabeledSwitch
import com.sapphirebank.kyc.presentation.common.RuleRow
import com.sapphirebank.kyc.presentation.common.ScreenColumn
import com.sapphirebank.kyc.presentation.common.ScreenTitle
import com.sapphirebank.kyc.presentation.common.SectionCard
import com.sapphirebank.kyc.presentation.common.ValidationResultCard
import com.sapphirebank.kyc.presentation.common.WorkflowTracker
import com.sapphirebank.kyc.presentation.common.displayStatus
import com.sapphirebank.kyc.presentation.theme.KycColors
import com.sapphirebank.kyc.presentation.theme.Sizes
import com.sapphirebank.kyc.presentation.theme.Spacing

@Composable
fun AnalystQueue(vm: WorkspaceViewModel, cases: List<CaseFile>?, open: (String) -> Unit) {
    val filters = listOf(
        QueueFilter("All") { true },
        QueueFilter("Pending") { it.status == ApplicationStatus.DOCUMENTS_VALIDATED },
        QueueFilter("In review") { it.status == ApplicationStatus.KYC_IN_PROGRESS },
        QueueFilter("Info requested") { it.openInfoRequests.isNotEmpty() },
    )
    CaseQueue(
        cases = cases, filters = filters, onOpen = open,
        emptyTitle = "No assigned cases", emptyBody = "New applications assigned to you will appear here.",
        header = { ScreenTitle("Assigned Cases (${cases?.size ?: 0})") },
        status = { c -> if (c.status == ApplicationStatus.DOCUMENTS_VALIDATED) ("Pending review" to Tone.WATCH) else c.displayStatus() },
        footer = { c ->
            val need = DocumentRequirements.required(c.application).size
            val have = DocumentRequirements.required(c.application).count { k -> c.documents.any { it.kind == k } }
            val checks = ValidationChecks.build(c)
            Text(
                "Documents $have/$need · ${checks.count { it.outcome == RuleOutcome.PASS }}/${checks.size} checks passed",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
fun AnalystCaseScreen(vm: WorkspaceViewModel, arn: String) = CaseGate(vm, arn) { c -> AnalystCase(vm, c) }

@Composable
private fun AnalystCase(vm: WorkspaceViewModel, c: CaseFile) {
    val config by vm.config.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<String?>(null) }
    val openRequests = c.openInfoRequests.isNotEmpty()

    ScreenColumn {
        ScreenTitle("KYC review")
        CaseHeader(c, config.dicgcCoverInr)
        if (openRequests) {
            Banner(
                "${c.openInfoRequests.size} information request(s) are open. KYC cannot be approved or rejected until the customer responds.",
                isError = false, tone = Tone.WATCH,
            )
        }
        c.kycDecision?.let { d ->
            Banner("KYC ${d.outcome.name.lowercase()} by ${d.by}${if (d.comment.isNotBlank()) ": ${d.comment}" else ""}", isError = d.outcome == KycOutcome.REJECTED)
        }
        DocumentsSection(c)
        ValidationResultCard(ValidationChecks.build(c, config.thresholds))
        if (c.evaluations.isNotEmpty()) {
            SectionCard("KYC rules (KYC-001 to KYC-009)") { c.evaluations.forEach { RuleRow(it) } }
        }
        c.infoRequests.forEach { InfoRequestCard(it) }

        when (c.status) {
            ApplicationStatus.DOCUMENTS_VALIDATED -> SectionCard("Next action") {
                Text("Documents are validated. Start the KYC review to run the identity and address checks.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { vm.startKyc(c.arn) }, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch)) { Text("Start KYC review") }
                OutlinedButton(onClick = { sheet = "info" }, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch)) { Text("Request Information") }
            }
            ApplicationStatus.KYC_IN_PROGRESS -> {
                if (c.application.kycMode == KycMode.VCIP) VcipCard(vm, c, config.thresholds.minRandomQuestions, config.thresholds.faceMatchMinVcip)
                SectionCard("Decision") {
                    OutlinedButton(
                        onClick = { vm.searchCkycr(c.arn) }, enabled = !c.evidence.ckycrSearched,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch),
                    ) { Text(if (c.evidence.ckycrSearched) "CKYCR searched (KYC-005)" else "Search CKYCR (KYC-005)") }
                    Button(
                        onClick = { sheet = "approve" }, enabled = !openRequests,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch),
                    ) { Text("Approve KYC") }
                    OutlinedButton(onClick = { sheet = "info" }, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch)) { Text("Request Information") }
                    OutlinedButton(
                        onClick = { sheet = "reject" }, enabled = !openRequests,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch),
                    ) { Text("Reject KYC", color = if (openRequests) MaterialTheme.colorScheme.onSurfaceVariant else KycColors.Danger) }
                }
            }
            else -> Unit
        }
        WorkflowTracker(c)
        AuditSection(vm, c)
    }

    when (sheet) {
        "approve" -> {
            val needsManual = c.evidence.ocrName == null && c.evidence.vcip == null
            var manual by remember { mutableStateOf(false) }
            ActionSheet(
                title = "Approve KYC", confirmLabel = "Approve KYC", onDismiss = { sheet = null }, minChars = 0,
                textLabel = "Comment", canConfirm = !needsManual || manual,
                message = "The KYC rules KYC-001 to KYC-009 are re-checked when you confirm. A blocking failure rejects the application.",
                extra = {
                    if (needsManual) LabeledSwitch("I compared the name and date of birth with the uploaded documents", manual, { manual = it })
                },
            ) { text, done -> vm.kycApprove(c.arn, text, manual || !needsManual, done) }
        }
        "reject" -> ActionSheet(
            title = "Reject KYC", confirmLabel = "Reject KYC", onDismiss = { sheet = null }, minChars = 10,
            textLabel = "Reason", destructive = true, message = "The customer's application will be closed as rejected.",
        ) { text, done -> vm.kycReject(c.arn, text, done) }
        "info" -> InfoRequestSheet(vm, c.arn) { sheet = null }
    }
}

@Composable
private fun VcipCard(vm: WorkspaceViewModel, c: CaseFile, minQuestions: Int, minFace: Double) {
    var s by remember(c.arn) { mutableStateOf(c.evidence.vcip ?: VcipSession(true, "IN", true, false, true, true, 3, true, 0.95, true)) }
    SectionCard("V-CIP session and liveness (KYC-006)") {
        Text(
            "Record the outcome of the live video session. All ten standards must hold or KYC-006 fails.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledSwitch("Liveness check passed", s.livenessPassed, { s = s.copy(livenessPassed = it) })
        LabeledSwitch("Geo-tag inside India", s.geoTagInIndia, { s = s.copy(geoTagInIndia = it, ipCountryCode = if (it) "IN" else "XX") }, hint = "IP country: ${s.ipCountryCode}")
        LabeledSwitch("Official is a trained bank employee", s.officialIsTrainedBankEmployee, { s = s.copy(officialIsTrainedBankEmployee = it) })
        LabeledSwitch("PAN captured live", s.panCapturedLive, { s = s.copy(panCapturedLive = it) })
        LabeledSwitch("OVD verified at source", s.ovdVerifiedViaSource, { s = s.copy(ovdVerifiedViaSource = it) })
        LabeledSwitch("Recording hashed and stored", s.recordingHashed, { s = s.copy(recordingHashed = it) })
        LabeledSwitch("Concurrent audit passed", s.concurrentAuditPassed, { s = s.copy(concurrentAuditPassed = it) })
        Text("Random questions asked: ${s.randomQuestionsAsked} (minimum $minQuestions)", style = MaterialTheme.typography.bodyMedium)
        Slider(value = s.randomQuestionsAsked.toFloat(), onValueChange = { s = s.copy(randomQuestionsAsked = it.toInt()) }, valueRange = 0f..6f, steps = 5)
        Text("Face match score: %.2f (minimum %.2f)".format(s.faceMatchScore, minFace), style = MaterialTheme.typography.bodyMedium)
        Slider(value = s.faceMatchScore.toFloat(), onValueChange = { s = s.copy(faceMatchScore = Math.round(it * 100) / 100.0) }, valueRange = 0.5f..1f)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Button(
                onClick = { vm.recordVcip(c.arn, s) },
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.weight(1f).heightIn(min = Sizes.touch),
            ) { Text("Record V-CIP session") }
        }
    }
}
