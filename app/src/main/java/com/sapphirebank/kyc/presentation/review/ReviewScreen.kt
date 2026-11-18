package com.sapphirebank.kyc.presentation.review

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.ApprovalLevel
import com.sapphirebank.kyc.domain.model.AuditEvent
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.Decision
import com.sapphirebank.kyc.domain.model.Officer
import com.sapphirebank.kyc.domain.rules.DualControlEngine
import com.sapphirebank.kyc.presentation.common.*
import com.sapphirebank.kyc.presentation.theme.KycColors

@Composable
fun ReviewScreen(vm: ReviewViewModel) {
    val case by vm.case.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val verification by vm.verification.collectAsStateWithLifecycle()
    val c = case
    if (c == null) {
        EmptyState("Nothing to review", "Submit and progress an application first.")
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        message?.let { Banner(it.text, it.isError) }

        SectionCard("${c.application.arn} · ${c.application.customerName}") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(c.application.status.name, KycColors.Neutral)
                RiskBadge(c.risk)
            }
            Text(
                "INR ${c.application.sanctionAmountInr} · ${c.application.productLine.name.replace('_', ' ')}" +
                    if (c.application.isPep) " · PEP" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        DualControlCard(vm, c)

        SectionCard("Rule evaluations") {
            Text("Stage 3 · KYC", style = MaterialTheme.typography.labelLarge)
            if (c.evaluations.isEmpty()) Text("Not evaluated yet.", style = MaterialTheme.typography.bodySmall)
            c.evaluations.forEach { RuleRow(it) }
            HorizontalDivider()
            Text("Stage 6 · Dual control", style = MaterialTheme.typography.labelLarge)
            DualControlEngine.evaluate(c).forEach { RuleRow(it) }
        }

        AuditCard(vm, c, verification)
    }
}

@Composable
private fun DualControlCard(vm: ReviewViewModel, c: CaseFile) {
    val levels = DualControlEngine.requiredLevels(c)
    SectionCard("Dual-control sign-off") {
        Text(
            "Credit Officer approves first, then Branch Manager" + if (ApprovalLevel.L3_SENIOR_MANAGEMENT in levels) ", then Senior Management (PEP / high risk)." else ".",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        levels.forEach { level -> LevelRow(vm, c, level) }
        if (c.application.status == ApplicationStatus.APPROVED) {
            Button(onClick = vm::fulfil, modifier = Modifier.fillMaxWidth()) { Text("Open account (Stage 7)") }
        }
    }
}

@Composable
private fun LevelRow(vm: ReviewViewModel, c: CaseFile, level: ApprovalLevel) {
    val decided = c.approvals.firstOrNull { it.level == level }
    val officers = vm.officersFor(level)
    var selected by remember(level) { mutableStateOf(officers.firstOrNull()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(level.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Pill(
                decided?.decision?.name ?: "AWAITING",
                when (decided?.decision) {
                    Decision.APPROVED -> KycColors.Success
                    Decision.REJECTED -> KycColors.Danger
                    null -> KycColors.Warning
                },
            )
        }
        if (decided != null) {
            Text("${decided.approver.displayName} (${decided.approver.userId}) at ${decided.decidedAt}", style = MaterialTheme.typography.bodySmall)
            Text("sign-off sha256 ${decided.signOffSha256.take(16)}…", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        } else if (c.application.status == ApplicationStatus.PENDING_REVIEW && selected != null) {
            DropdownField(
                "Officer", officers, selected!!,
                { "${it.displayName} · limit INR ${it.delegatedLimitInr}" },
                { selected = it },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.signOff(level, selected!!, Decision.APPROVED) }) { Text("Approve") }
                OutlinedButton(onClick = { vm.signOff(level, selected!!, Decision.REJECTED) }) { Text("Reject") }
            }
        } else {
            Text("Opens when the application reaches PENDING_REVIEW.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AuditCard(vm: ReviewViewModel, c: CaseFile, v: com.sapphirebank.kyc.domain.rules.AuditChain.Verification?) {
    SectionCard("Audit trail (hash-chained, Stage 8)") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::verify) { Text("Verify chain") }
            OutlinedButton(onClick = vm::tamper) { Text("Simulate tamper") }
        }
        v?.let { Banner(it.message, isError = !it.valid) }
        c.audit.asReversed().forEach { AuditRow(it, brokenAt = v?.brokenAtSequence) }
    }
}

@Composable
private fun AuditRow(e: AuditEvent, brokenAt: Int?) {
    val broken = brokenAt == e.sequenceNo
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#${e.sequenceNo}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(e.eventType, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (broken) Pill("BROKEN", KycColors.Danger)
        }
        Text("${e.occurredAt} · ${e.actor}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "prev ${e.previousEventHash.take(12)}…  hash ${e.eventHash.take(12)}…",
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            color = if (broken) KycColors.Danger else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
