package com.sapphirebank.kyc.presentation.staff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.ApprovalLevel
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.Decision
import com.sapphirebank.kyc.domain.model.DocumentKind
import com.sapphirebank.kyc.domain.model.EligibilityOutcome
import com.sapphirebank.kyc.domain.model.Inr
import com.sapphirebank.kyc.domain.model.ProductLine
import com.sapphirebank.kyc.domain.model.Tone
import com.sapphirebank.kyc.domain.rules.DualControlEngine
import com.sapphirebank.kyc.domain.usecase.Step
import com.sapphirebank.kyc.presentation.ActionSheet
import com.sapphirebank.kyc.presentation.AuditSection
import com.sapphirebank.kyc.presentation.CaseGate
import com.sapphirebank.kyc.presentation.CaseHeader
import com.sapphirebank.kyc.presentation.DocumentsSection
import com.sapphirebank.kyc.presentation.InfoRequestSheet
import com.sapphirebank.kyc.presentation.WorkspaceViewModel
import com.sapphirebank.kyc.presentation.common.AppTextField
import com.sapphirebank.kyc.presentation.common.Banner
import com.sapphirebank.kyc.presentation.common.InfoRequestCard
import com.sapphirebank.kyc.presentation.common.LabelValue
import com.sapphirebank.kyc.presentation.common.RiskBadge
import com.sapphirebank.kyc.presentation.common.ScoreGauge
import com.sapphirebank.kyc.presentation.common.ScreenColumn
import com.sapphirebank.kyc.presentation.common.ScreenTitle
import com.sapphirebank.kyc.presentation.common.SectionCard
import com.sapphirebank.kyc.presentation.common.StatusChip
import com.sapphirebank.kyc.presentation.common.WorkflowTracker
import com.sapphirebank.kyc.presentation.common.formatTime
import com.sapphirebank.kyc.presentation.theme.Sizes
import com.sapphirebank.kyc.presentation.theme.Spacing
import com.sapphirebank.kyc.presentation.theme.color

@Composable
fun CreditQueue(vm: WorkspaceViewModel, cases: List<CaseFile>?, open: (String) -> Unit) {
    val filters = listOf(
        QueueFilter("All") { true },
        QueueFilter("To review") { it.status == ApplicationStatus.PENDING_REVIEW && DualControlEngine.nextLevel(it) == ApprovalLevel.L1_CREDIT_OFFICER },
        QueueFilter("Loans") { it.application.productLine == ProductLine.RETAIL_LOAN },
        QueueFilter("Decided") { c -> c.approvals.any { it.level == ApprovalLevel.L1_CREDIT_OFFICER } },
    )
    CaseQueue(
        cases = cases, filters = filters, onOpen = open,
        emptyTitle = "No eligibility reviews", emptyBody = "Applications that need an eligibility review will appear here.",
        header = { ScreenTitle("Eligibility Review") },
    )
}

@Composable
fun CreditCaseScreen(vm: WorkspaceViewModel, arn: String) = CaseGate(vm, arn) { c -> CreditCase(vm, c) }

private enum class Recommendation(val label: String) {
    APPROVE("Approve"), DOCS("Request Documents"), DECLINE("Decline")
}

@Composable
private fun CreditCase(vm: WorkspaceViewModel, c: CaseFile) {
    val config by vm.config.collectAsStateWithLifecycle()
    val a = c.application
    val e = c.eligibility
    val fin = c.financials
    val myTurn = c.status == ApplicationStatus.PENDING_REVIEW && DualControlEngine.nextLevel(c) == ApprovalLevel.L1_CREDIT_OFFICER
    var choice by remember { mutableStateOf(Recommendation.APPROVE) }
    var comment by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    ScreenColumn {
        ScreenTitle("Eligibility Review")
        CaseHeader(c, config.dicgcCoverInr)

        SectionCard("Financial details") {
            if (fin != null) {
                LabelValue("Monthly income", Inr.format(fin.monthlyIncomeInr))
                LabelValue("Existing EMIs", Inr.format(fin.existingEmiInr))
                LabelValue("Employment", fin.employmentType)
            }
            LabelValue("Requested amount", Inr.format(a.sanctionAmountInr))
            val has = { k: DocumentKind -> c.documents.any { it.kind == k } }
            if (a.productLine == ProductLine.RETAIL_LOAN) LabelValue("Income proof", if (has(DocumentKind.INCOME_PROOF)) "Uploaded" else "Missing",
                valueColor = if (has(DocumentKind.INCOME_PROOF)) Tone.GOOD.color() else Tone.BAD.color())
            if (a.productLine == ProductLine.CURRENT_ACCOUNT) LabelValue("Business proof", if (has(DocumentKind.BUSINESS_PROOF)) "Uploaded" else "Missing",
                valueColor = if (has(DocumentKind.BUSINESS_PROOF)) Tone.GOOD.color() else Tone.BAD.color())
            if (fin == null && a.productLine != ProductLine.RETAIL_LOAN) {
                Text("No income details are collected for this product.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (e == null) {
            SectionCard("Eligibility") {
                Text("Eligibility has not been assessed yet. It runs automatically once KYC and AML screening are complete.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            SectionCard("Eligibility indicators", action = {
                StatusChip(e.outcome.label, when (e.outcome) { EligibilityOutcome.ELIGIBLE -> Tone.GOOD; EligibilityOutcome.REFER -> Tone.WATCH; EligibilityOutcome.INELIGIBLE -> Tone.BAD })
            }) {
                e.bureauScore?.let { s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                        ScoreGauge(s, 300, 900, "Bureau", if (s >= config.eligibility.minBureauScore) Tone.GOOD else if (s >= config.eligibility.referBureauScore) Tone.WATCH else Tone.BAD)
                        Text("${e.bureauBand ?: ""} bureau score (simulated)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                e.indicators.forEach { LabelValue(it.label, it.value, valueColor = it.tone.color()) }
                e.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Tone.WATCH.color()) }
            }
        }
        c.risk?.let { SectionCard("Risk") { RiskBadge(it); it.factors.forEach { f -> Text("• $f", style = MaterialTheme.typography.bodySmall) } } }
        DocumentsSection(c)
        c.infoRequests.forEach { InfoRequestCard(it) }

        val mine = c.approvals.firstOrNull { it.level == ApprovalLevel.L1_CREDIT_OFFICER }
        when {
            mine != null -> SectionCard("Your recommendation") {
                StatusChip(mine.decision.name.lowercase().replaceFirstChar { it.uppercase() }, if (mine.decision == Decision.APPROVED) Tone.GOOD else Tone.BAD)
                Text("${mine.approver.displayName} · ${formatTime(mine.decidedAt)}", style = MaterialTheme.typography.bodySmall)
                if (mine.justification.isNotBlank()) Text(mine.justification, style = MaterialTheme.typography.bodyMedium)
            }
            myTurn -> SectionCard("Recommendation") {
                if (c.openInfoRequests.isNotEmpty()) Banner("An information request is open. A recommendation cannot be recorded until the customer responds.", isError = false, tone = Tone.WATCH)
                Recommendation.entries.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Sizes.touch).selectable(selected = choice == r, role = Role.RadioButton, onClick = { choice = r; error = null }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == r, onClick = null)
                        Text(r.label, Modifier.padding(start = Spacing.s), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                AppTextField(
                    label = if (choice == Recommendation.APPROVE) "Add comments (optional)" else "Reason (required, 10+ characters)",
                    value = comment, onChange = { comment = it; error = null }, singleLine = false, minLines = 2,
                )
                error?.let { Banner(it, isError = true) }
                Button(
                    onClick = {
                        error = null
                        when (choice) {
                            Recommendation.APPROVE -> vm.signOff(c.arn, Decision.APPROVED, comment) { r -> if (r is Step.Refused) error = r.reason }
                            Recommendation.DECLINE -> vm.signOff(c.arn, Decision.REJECTED, comment) { r -> if (r is Step.Refused) error = r.reason }
                            Recommendation.DOCS -> showInfo = true
                        }
                    },
                    enabled = c.openInfoRequests.isEmpty() || choice == Recommendation.DOCS,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch),
                ) { Text("Submit Recommendation") }
            }
            else -> SectionCard("Recommendation") {
                Text(
                    if (c.status.terminal) "This application is closed (${c.status.name.lowercase().replace('_', ' ')})."
                    else "A recommendation can be recorded once the application reaches review.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        WorkflowTracker(c)
        AuditSection(vm, c)
    }
    if (showInfo) InfoRequestSheet(vm, c.arn) { showInfo = false }
}
