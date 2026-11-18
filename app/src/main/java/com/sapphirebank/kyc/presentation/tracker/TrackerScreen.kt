package com.sapphirebank.kyc.presentation.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.KycMode
import com.sapphirebank.kyc.domain.rules.ApplicationStateMachine
import com.sapphirebank.kyc.domain.rules.KycRuleEngine
import com.sapphirebank.kyc.domain.rules.StageProgress
import com.sapphirebank.kyc.domain.rules.StepState
import com.sapphirebank.kyc.presentation.common.*
import com.sapphirebank.kyc.presentation.theme.KycColors

@Composable
fun TrackerScreen(vm: TrackerViewModel) {
    val case by vm.case.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val c = case
    if (c == null) {
        EmptyState("No application yet", "Submit an application on the Application tab to start tracking it.")
        return
    }
    val status = c.application.status

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        message?.let { Banner(it.text, it.isError) }

        SectionCard("${c.application.arn} · ${c.application.customerName}") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(status.name, statusColor(status))
                RiskBadge(c.risk)
            }
            c.risk?.factors?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    "Risk factors: " + it.joinToString("; "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard("Application stages") {
            Stepper(ApplicationStateMachine.progress(status, c.failedAt))
        }

        ActionsCard(vm, c)

        if (status == ApplicationStatus.KYC_IN_PROGRESS && c.application.kycMode == KycMode.VCIP) VcipCard(vm)

        if (c.evaluations.isNotEmpty()) {
            SectionCard("KYC rule evaluations (KYC-001 to KYC-009)") {
                c.evaluations.forEach { RuleRow(it) }
            }
        }
    }
}

private fun statusColor(s: ApplicationStatus): Color = when (s) {
    ApplicationStatus.REJECTED -> KycColors.Danger
    ApplicationStatus.WITHDRAWN, ApplicationStatus.EXPIRED -> KycColors.Neutral
    ApplicationStatus.FULFILLED, ApplicationStatus.APPROVED -> KycColors.Success
    else -> Color(0xFF0F3D91)
}

@Composable
private fun Stepper(stages: List<StageProgress>) {
    Column {
        stages.forEachIndexed { i, s ->
            val color = when (s.state) {
                StepState.DONE -> KycColors.Success
                StepState.ACTIVE -> MaterialTheme.colorScheme.primary
                StepState.FAILED -> KycColors.Danger
                StepState.PENDING -> MaterialTheme.colorScheme.outlineVariant
            }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                        when (s.state) {
                            StepState.DONE -> Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            StepState.FAILED -> Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            else -> Text(s.stage.number.toString(), color = if (s.state == StepState.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (i < stages.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(color))
                }
                Column(Modifier.padding(bottom = 14.dp)) {
                    Text(s.stage.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (s.state == StepState.ACTIVE) FontWeight.Bold else FontWeight.Normal)
                    Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ActionsCard(vm: TrackerViewModel, c: CaseFile) {
    val status = c.application.status
    SectionCard("Next action") {
        when (status) {
            ApplicationStatus.DOCUMENTS_VALIDATED ->
                Button(onClick = vm::startKyc, modifier = Modifier.fillMaxWidth()) { Text("Start KYC (Stage 3)") }
            ApplicationStatus.KYC_IN_PROGRESS -> {
                OutlinedButton(onClick = vm::searchCkycr, enabled = !c.evidence.ckycrSearched, modifier = Modifier.fillMaxWidth()) {
                    Text(if (c.evidence.ckycrSearched) "CKYCR searched" else "Search CKYCR (KYC-005)")
                }
                Button(onClick = vm::completeKyc, modifier = Modifier.fillMaxWidth()) { Text("Complete KYC") }
            }
            ApplicationStatus.PENDING_REVIEW ->
                Text("Waiting for dual-control sign-off. Open the Review tab.", style = MaterialTheme.typography.bodyMedium)
            ApplicationStatus.FULFILLED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN, ApplicationStatus.EXPIRED ->
                Text("This application is closed (${status.name}).", style = MaterialTheme.typography.bodyMedium)
            else ->
                Button(onClick = vm::advance, modifier = Modifier.fillMaxWidth()) { Text("Advance from ${status.name}") }
        }
        if (!status.terminal) {
            TextButton(onClick = vm::withdraw) { Text("Withdraw application", color = KycColors.Danger) }
        }
    }
}

@Composable
private fun VcipCard(vm: TrackerViewModel) {
    val s by vm.vcip.collectAsStateWithLifecycle()
    SectionCard("V-CIP session and liveness (KYC-006)") {
        Text(
            "Record the outcome of the live video session. All 10 standards must hold or KYC-006 fails and the application is rejected.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledSwitch("Liveness check passed", s.livenessPassed, { v -> vm.editVcip { it.copy(livenessPassed = v) } })
        LabeledSwitch("Geo-tag inside India", s.geoTagInIndia, { v -> vm.editVcip { it.copy(geoTagInIndia = v, ipCountryCode = if (v) "IN" else "XX") } }, hint = "IP country: ${s.ipCountryCode}")
        LabeledSwitch("Official is a trained bank employee", s.officialIsTrainedBankEmployee, { v -> vm.editVcip { it.copy(officialIsTrainedBankEmployee = v) } })
        LabeledSwitch("PAN captured live", s.panCapturedLive, { v -> vm.editVcip { it.copy(panCapturedLive = v) } })
        LabeledSwitch("OVD verified at source", s.ovdVerifiedViaSource, { v -> vm.editVcip { it.copy(ovdVerifiedViaSource = v) } })
        LabeledSwitch("Recording hashed and stored", s.recordingHashed, { v -> vm.editVcip { it.copy(recordingHashed = v) } })
        LabeledSwitch("Concurrent audit passed", s.concurrentAuditPassed, { v -> vm.editVcip { it.copy(concurrentAuditPassed = v) } })

        Text("Random questions asked: ${s.randomQuestionsAsked} (minimum ${KycRuleEngine.MIN_RANDOM_QUESTIONS})", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = s.randomQuestionsAsked.toFloat(), onValueChange = { v -> vm.editVcip { it.copy(randomQuestionsAsked = v.toInt()) } },
            valueRange = 0f..6f, steps = 5,
        )
        Text("Face match score: %.2f (minimum %.2f)".format(s.faceMatchScore, KycRuleEngine.FACE_MATCH_MIN_VCIP), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = s.faceMatchScore.toFloat(), onValueChange = { v -> vm.editVcip { it.copy(faceMatchScore = (Math.round(v * 100) / 100.0)) } },
            valueRange = 0.5f..1f,
        )
        Button(onClick = vm::recordVcip, modifier = Modifier.fillMaxWidth()) { Text("Record V-CIP session") }
    }
}
