package com.sapphirebank.kyc.presentation.staff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.Countries
import com.sapphirebank.kyc.domain.model.CountryRisk
import com.sapphirebank.kyc.domain.model.Inr
import com.sapphirebank.kyc.domain.model.ScreeningStatus
import com.sapphirebank.kyc.domain.model.Tone
import com.sapphirebank.kyc.presentation.ActionSheet
import com.sapphirebank.kyc.presentation.AuditSection
import com.sapphirebank.kyc.presentation.CaseGate
import com.sapphirebank.kyc.presentation.CaseHeader
import com.sapphirebank.kyc.presentation.InfoRequestSheet
import com.sapphirebank.kyc.presentation.WorkspaceViewModel
import com.sapphirebank.kyc.presentation.common.Banner
import com.sapphirebank.kyc.presentation.common.DropdownField
import com.sapphirebank.kyc.presentation.common.EmptyState
import com.sapphirebank.kyc.presentation.common.InfoRequestCard
import com.sapphirebank.kyc.presentation.common.LabelValue
import com.sapphirebank.kyc.presentation.common.LoadingState
import com.sapphirebank.kyc.presentation.common.RiskBadge
import com.sapphirebank.kyc.presentation.common.RiskScoreIndicator
import com.sapphirebank.kyc.presentation.common.ScreenColumn
import com.sapphirebank.kyc.presentation.common.ScreenTitle
import com.sapphirebank.kyc.presentation.common.SectionCard
import com.sapphirebank.kyc.presentation.common.StatusChip
import com.sapphirebank.kyc.presentation.common.WorkflowTracker
import com.sapphirebank.kyc.presentation.common.formatTime
import com.sapphirebank.kyc.presentation.common.humanize
import com.sapphirebank.kyc.presentation.theme.KycColors
import com.sapphirebank.kyc.presentation.theme.Sizes
import com.sapphirebank.kyc.presentation.theme.Spacing

private fun CaseFile.flagged() = screening?.status == ScreeningStatus.FLAGGED

@Composable
fun ComplianceQueue(vm: WorkspaceViewModel, cases: List<CaseFile>?, open: (String) -> Unit) {
    val filters = listOf(
        QueueFilter("All") { true },
        QueueFilter("Flagged") { it.flagged() },
        QueueFilter("On hold") { it.screening?.status == ScreeningStatus.ON_HOLD },
        QueueFilter("Cleared") { it.screening?.status == ScreeningStatus.CLEARED_BY_COMPLIANCE },
    )
    val flagged = cases?.count { it.flagged() } ?: 0
    CaseQueue(
        cases = cases, filters = filters, onOpen = open,
        emptyTitle = "No screened applications", emptyBody = "Flagged and high-risk applications will appear here.",
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ScreenTitle("AML / PEP Screening")
                if (flagged > 0) Banner("AML/PEP alert: $flagged application(s) need a decision.", isError = true)
            }
        },
        footer = { c ->
            c.screening?.let {
                Text(
                    "Risk ${it.riskScore}/100 · ${it.countryRisk.label} country risk" + if (it.watchlistMatch) " · watchlist match" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
fun ComplianceCaseScreen(vm: WorkspaceViewModel, arn: String) = CaseGate(vm, arn) { c -> ComplianceCase(vm, c) }

@Composable
private fun ComplianceCase(vm: WorkspaceViewModel, c: CaseFile) {
    val config by vm.config.collectAsStateWithLifecycle()
    val sc = c.screening
    var sheet by remember { mutableStateOf<String?>(null) }

    ScreenColumn {
        ScreenTitle("Compliance Review")
        when (sc?.status) {
            ScreeningStatus.FLAGGED ->
                Banner(if (sc.watchlistMatch) "AML/PEP alert: possible watchlist match detected" else "AML/PEP alert: flagged for review", isError = true)
            ScreeningStatus.ON_HOLD -> Banner("Account is on hold pending compliance review.", isError = false, tone = Tone.WATCH)
            ScreeningStatus.CLEARED_BY_COMPLIANCE -> Banner("Cleared by compliance.", isError = false)
            else -> Unit
        }
        CaseHeader(c, config.dicgcCoverInr)

        if (sc == null) {
            SectionCard("Screening") { Text("This application has not reached AML/PEP screening yet.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            SectionCard("Risk details", action = { RiskBadge(c.risk) }) {
                LabelValue("Watchlist match", if (sc.watchlistMatch) "Yes (${sc.matchedName})" else "No", valueColor = if (sc.watchlistMatch) KycColors.Danger else KycColors.Success)
                LabelValue("PEP", if (sc.pepMatch) "Yes" else "No", valueColor = if (sc.pepMatch) KycColors.Danger else KycColors.Success)
                LabelValue(
                    "Country risk", "${Countries.label(sc.countryCode)}: ${sc.countryRisk.label}",
                    valueColor = when (sc.countryRisk) { CountryRisk.HIGH -> KycColors.Danger; CountryRisk.MEDIUM -> KycColors.Warning; CountryRisk.LOW -> KycColors.Success },
                )
                RiskScoreIndicator(sc.riskScore)
                c.risk?.factors?.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            SectionCard("Screening evidence") {
                sc.evidence.forEach { e ->
                    Column {
                        Text(e.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(e.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        SectionCard("Financial details (read-only)") {
            LabelValue("Requested amount", Inr.format(c.application.sanctionAmountInr))
            c.financials?.let {
                LabelValue("Monthly income", Inr.format(it.monthlyIncomeInr))
                LabelValue("Existing EMIs", Inr.format(it.existingEmiInr))
            }
            Text("Compliance has read-only access to financial data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        c.infoRequests.forEach { InfoRequestCard(it) }

        if (sc != null) {
            SectionCard("Decision") {
                if (sc.decisionNote.isNotBlank()) {
                    StatusChip(humanize(sc.status.name), if (sc.status == ScreeningStatus.ON_HOLD) Tone.BAD else Tone.GOOD)
                    Text("${sc.decidedBy ?: ""} · ${sc.decidedAt?.let { formatTime(it) } ?: ""}", style = MaterialTheme.typography.bodySmall)
                    Text(sc.decisionNote, style = MaterialTheme.typography.bodyMedium)
                }
                val actionable = !c.status.terminal && (sc.status == ScreeningStatus.FLAGGED || sc.status == ScreeningStatus.ON_HOLD)
                if (actionable) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        OutlinedButton(onClick = { sheet = "clear" }, modifier = Modifier.weight(1f).heightIn(min = Sizes.touch)) { Text("Clear") }
                        Button(onClick = { sheet = "hold" }, enabled = sc.status == ScreeningStatus.FLAGGED, modifier = Modifier.weight(1f).heightIn(min = Sizes.touch)) { Text("Place on Hold") }
                    }
                } else if (sc.decisionNote.isBlank()) {
                    Text("No decision is needed for this application.", style = MaterialTheme.typography.bodyMedium)
                }
                if (!c.status.terminal) OutlinedButton(onClick = { sheet = "info" }, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch)) { Text("Request information") }
            }
        }
        WorkflowTracker(c)
        AuditSection(vm, c)
    }

    when (sheet) {
        "clear" -> ActionSheet(
            title = "Clear application", confirmLabel = "Clear", onDismiss = { sheet = null }, minChars = 10, textLabel = "Clearance note",
            message = "Confirm the flag is a false positive or has been resolved. The application then continues to eligibility.",
            perform = { text, done -> vm.screeningDecision(c.arn, true, text, done) },
        )
        "hold" -> ActionSheet(
            title = "Place on hold", confirmLabel = "Place on hold", onDismiss = { sheet = null }, minChars = 10, destructive = true, textLabel = "Reason for hold",
            message = "Approval is blocked until a Compliance Officer clears the application.",
            perform = { text, done -> vm.screeningDecision(c.arn, false, text, done) },
        )
        "info" -> InfoRequestSheet(vm, c.arn) { sheet = null }
    }
}

/** Read-only audit history for the applications the role can see. */
@Composable
fun AuditTab(vm: WorkspaceViewModel, cases: List<CaseFile>?) {
    if (cases == null) { LoadingState("Loading audit history…"); return }
    var arn by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenColumn {
        ScreenTitle("Audit")
        if (cases.isEmpty()) {
            EmptyState("No audit history", "Audit events appear here once applications exist.")
            return@ScreenColumn
        }
        val selected = cases.firstOrNull { it.arn == arn } ?: cases.first()
        DropdownField("Application", cases, selected, { "${it.arn} · ${it.application.customerName}" }) { arn = it.arn }
        AuditSection(vm, selected)
    }
}
