package com.sapphirebank.kyc.presentation.staff

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.ApprovalLevel
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.Decision
import com.sapphirebank.kyc.domain.model.EligibilityOutcome
import com.sapphirebank.kyc.domain.model.ProductLine
import com.sapphirebank.kyc.domain.model.Tone
import com.sapphirebank.kyc.domain.model.UserAccount
import com.sapphirebank.kyc.domain.model.shortName
import com.sapphirebank.kyc.domain.rules.DualControlEngine
import com.sapphirebank.kyc.domain.rules.ValidationChecks
import com.sapphirebank.kyc.presentation.ActionSheet
import com.sapphirebank.kyc.presentation.AuditSection
import com.sapphirebank.kyc.presentation.CaseGate
import com.sapphirebank.kyc.presentation.CaseHeader
import com.sapphirebank.kyc.presentation.DocumentsSection
import com.sapphirebank.kyc.presentation.WorkspaceViewModel
import com.sapphirebank.kyc.presentation.common.ApplicationSummaryCard
import com.sapphirebank.kyc.presentation.common.Banner
import com.sapphirebank.kyc.presentation.common.CustomerInfoCard
import com.sapphirebank.kyc.presentation.common.DropdownField
import com.sapphirebank.kyc.presentation.common.EmptyState
import com.sapphirebank.kyc.presentation.common.InfoRequestCard
import com.sapphirebank.kyc.presentation.common.LabelValue
import com.sapphirebank.kyc.presentation.common.LoadingState
import com.sapphirebank.kyc.presentation.common.MetricCard
import com.sapphirebank.kyc.presentation.common.RiskBadge
import com.sapphirebank.kyc.presentation.common.ScreenColumn
import com.sapphirebank.kyc.presentation.common.ScreenTitle
import com.sapphirebank.kyc.presentation.common.SectionCard
import com.sapphirebank.kyc.presentation.common.StatusChip
import com.sapphirebank.kyc.presentation.common.ValidationResultCard
import com.sapphirebank.kyc.presentation.common.WorkflowTracker
import com.sapphirebank.kyc.presentation.common.formatTime
import com.sapphirebank.kyc.presentation.common.humanize
import com.sapphirebank.kyc.presentation.common.isToday
import com.sapphirebank.kyc.presentation.theme.KycColors
import com.sapphirebank.kyc.presentation.theme.Sizes
import com.sapphirebank.kyc.presentation.theme.Spacing
import com.sapphirebank.kyc.presentation.theme.color

private fun awaitingMe(vm: WorkspaceViewModel, c: CaseFile): Boolean {
    val level = DualControlEngine.levelFor(vm.actor.officerRole)
    return c.status == ApplicationStatus.PENDING_REVIEW && level != null && DualControlEngine.nextLevel(c) == level
}

@Composable
private fun CreditApprovedChip(c: CaseFile) {
    if (c.approvals.any { it.level == ApprovalLevel.L1_CREDIT_OFFICER && it.decision == Decision.APPROVED })
        StatusChip("Credit Officer approved", Tone.GOOD)
}

@Composable
fun BranchDashboard(vm: WorkspaceViewModel, cases: List<CaseFile>?, open: (String) -> Unit, onViewAll: () -> Unit) {
    if (cases == null) { LoadingState("Loading pipeline…"); return }
    val pending = cases.count { it.status == ApplicationStatus.PENDING_REVIEW }
    val inReview = cases.count { !it.status.terminal && it.status != ApplicationStatus.PENDING_REVIEW && it.status != ApplicationStatus.APPROVED }
    val approvedToday = cases.count { (it.status == ApplicationStatus.APPROVED || it.status == ApplicationStatus.FULFILLED) && isToday(it.lastUpdated) }
    val mine = cases.filter { awaitingMe(vm, it) }
    ScreenColumn {
        ScreenTitle("Application Pipeline")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            MetricCard(pending.toString(), "Pending approval", Tone.WATCH, Modifier.weight(1f))
            MetricCard(inReview.toString(), "In review", Tone.INFO, Modifier.weight(1f))
            MetricCard(approvedToday.toString(), "Approved today", Tone.GOOD, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pending final decisions", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onViewAll, modifier = Modifier.heightIn(min = Sizes.touch)) { Text("View all") }
        }
        if (mine.isEmpty()) EmptyState("Nothing waiting for you", "Applications ready for your decision will appear here.")
        mine.take(3).forEach { c ->
            ApplicationSummaryCard(c, onClick = { open(c.arn) }) { CreditApprovedChip(c) }
        }
    }
}

@Composable
fun BranchCases(vm: WorkspaceViewModel, cases: List<CaseFile>?, open: (String) -> Unit) {
    val filters = listOf(
        QueueFilter("All") { true },
        QueueFilter("To decide") { awaitingMe(vm, it) },
        QueueFilter("Approved") { it.status == ApplicationStatus.APPROVED || it.status == ApplicationStatus.FULFILLED },
        QueueFilter("Rejected") { it.status == ApplicationStatus.REJECTED },
    )
    CaseQueue(
        cases = cases, filters = filters, onOpen = open,
        emptyTitle = "No applications yet", emptyBody = "Applications will appear here as customers apply.",
        header = { ScreenTitle("Cases") },
        footer = { c -> CreditApprovedChip(c) },
    )
}

@Composable
fun BranchReports(cases: List<CaseFile>?) {
    if (cases == null) { LoadingState("Loading reports…"); return }
    ScreenColumn {
        ScreenTitle("Reports")
        if (cases.isEmpty()) { EmptyState("No data yet", "Reports are built from the applications in the pipeline."); return@ScreenColumn }
        SectionCard("By product") {
            val max = cases.groupBy { it.application.productLine }.maxOf { it.value.size }.coerceAtLeast(1)
            ProductLine.entries.forEach { p ->
                val n = cases.count { it.application.productLine == p }
                Bar(p.shortName(), n, n.toFloat() / max)
            }
        }
        SectionCard("By status") {
            val groups = cases.groupBy { it.status }
            val max = groups.maxOf { it.value.size }.coerceAtLeast(1)
            groups.entries.sortedBy { it.key.ordinal }.forEach { (s, l) -> Bar(humanize(s.name), l.size, l.size.toFloat() / max) }
        }
        SectionCard("Risk") {
            val n = cases.count { it.risk?.category?.name == "HIGH" }
            LabelValue("High-risk applications", n.toString(), valueColor = if (n > 0) KycColors.Danger else MaterialTheme.colorScheme.onSurface)
            LabelValue("With open information requests", cases.count { it.openInfoRequests.isNotEmpty() }.toString())
        }
    }
}

@Composable
private fun Bar(label: String, count: Int, frac: Float) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(
            progress = { frac }, color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.fillMaxWidth().heightIn(min = 8.dp),
        )
    }
}


@Composable
fun BranchCaseScreen(vm: WorkspaceViewModel, arn: String) = CaseGate(vm, arn) { c -> BranchCase(vm, c) }

@Composable
private fun BranchCase(vm: WorkspaceViewModel, c: CaseFile) {
    val config by vm.config.collectAsStateWithLifecycle()
    val actor = vm.actor
    val myTurn = awaitingMe(vm, c)
    val sole = c.createdBy == actor.userId
    var sheet by remember { mutableStateOf<String?>(null) }
    val analysts = vm.analysts()
    var target by remember(c.assignedAnalystId) { mutableStateOf<UserAccount?>(analysts.firstOrNull { it.userId != c.assignedAnalystId }) }

    ScreenColumn {
        ScreenTitle("Final Decision")
        CaseHeader(c, config.dicgcCoverInr)
        CustomerInfoCard(c)

        SectionCard("KYC review") {
            c.kycDecision?.let {
                StatusChip(humanize(it.outcome.name), if (it.outcome.name == "APPROVED") Tone.GOOD else Tone.BAD)
                Text("${it.by} · ${formatTime(it.at)}", style = MaterialTheme.typography.bodySmall)
                if (it.comment.isNotBlank()) Text(it.comment, style = MaterialTheme.typography.bodyMedium)
            } ?: Text("KYC decision pending.", style = MaterialTheme.typography.bodyMedium)
        }
        if (c.evaluations.isNotEmpty()) ValidationResultCard(ValidationChecks.build(c, config.thresholds))

        SectionCard("Credit recommendation") {
            val l1 = c.approvals.firstOrNull { it.level == ApprovalLevel.L1_CREDIT_OFFICER }
            if (l1 != null) {
                StatusChip(if (l1.decision == Decision.APPROVED) "Approved" else "Declined", if (l1.decision == Decision.APPROVED) Tone.GOOD else Tone.BAD)
                Text("${l1.approver.displayName} · ${formatTime(l1.decidedAt)}", style = MaterialTheme.typography.bodySmall)
                if (l1.justification.isNotBlank()) Text(l1.justification, style = MaterialTheme.typography.bodyMedium)
            } else Text("Awaiting the Credit Officer.", style = MaterialTheme.typography.bodyMedium)
            c.eligibility?.let { e ->
                StatusChip(e.outcome.label, when (e.outcome) { EligibilityOutcome.ELIGIBLE -> Tone.GOOD; EligibilityOutcome.REFER -> Tone.WATCH; EligibilityOutcome.INELIGIBLE -> Tone.BAD })
                e.indicators.forEach { LabelValue(it.label, it.value, valueColor = it.tone.color()) }
            }
        }
        c.risk?.let { SectionCard("Risk") { RiskBadge(it) } }
        DocumentsSection(c)
        c.infoRequests.forEach { InfoRequestCard(it) }

        SectionCard("Decision") {
            if (sole) Banner("You created this application, so you cannot approve it (segregation of duties).", isError = true)
            if (c.openInfoRequests.isNotEmpty())
                Banner("${c.openInfoRequests.size} information request(s) are open. Approve and Reject are disabled until the customer responds.", isError = false, tone = Tone.WATCH)
            if (myTurn) {
                Text("A written justification is mandatory for every decision.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Button(onClick = { sheet = "approve" }, enabled = c.openInfoRequests.isEmpty() && !sole, modifier = Modifier.weight(1f).heightIn(min = Sizes.touch)) { Text("Approve") }
                    OutlinedButton(
                        onClick = { sheet = "reject" }, enabled = c.openInfoRequests.isEmpty() && !sole,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = KycColors.Danger),
                        modifier = Modifier.weight(1f).heightIn(min = Sizes.touch),
                    ) { Text("Reject") }
                }
            } else {
                Text(
                    when {
                        c.status.terminal -> "This application is closed (${humanize(c.status.name)})."
                        c.status == ApplicationStatus.PENDING_REVIEW -> "Waiting for an earlier approval level before your decision."
                        else -> "A final decision opens once KYC, screening and eligibility are complete."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!c.status.terminal && analysts.size > 1) {
                OutlinedButton(onClick = { sheet = "reassign" }, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch)) { Text("Reassign to another analyst") }
            }
        }
        WorkflowTracker(c)
        AuditSection(vm, c)
    }

    when (sheet) {
        "approve" -> ActionSheet(
            title = "Approve application", confirmLabel = "Approve", onDismiss = { sheet = null }, minChars = 10,
            textLabel = "Decision justification", message = "Recorded permanently in the audit trail.",
            perform = { text, done -> vm.signOff(c.arn, Decision.APPROVED, text, done) },
        )
        "reject" -> ActionSheet(
            title = "Reject application", confirmLabel = "Reject", onDismiss = { sheet = null }, minChars = 10, destructive = true,
            textLabel = "Decision justification", message = "The customer will be told the application was not approved.",
            perform = { text, done -> vm.signOff(c.arn, Decision.REJECTED, text, done) },
        )
        "reassign" -> ActionSheet(
            title = "Reassign application", confirmLabel = "Reassign", onDismiss = { sheet = null }, minChars = 10,
            textLabel = "Reason", canConfirm = target != null,
            extra = {
                val options = analysts.filter { it.userId != c.assignedAnalystId }
                val sel = target
                if (sel != null && options.isNotEmpty()) DropdownField("Analyst", options, sel, { it.displayName }) { target = it }
            },
            perform = { text, done -> target?.let { vm.reassign(c.arn, it.userId, text, done) } },
        )
    }
}
