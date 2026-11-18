package com.sapphirebank.kyc.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.DocumentKind
import com.sapphirebank.kyc.domain.model.DocumentRecord
import com.sapphirebank.kyc.domain.model.DocumentRequirements
import com.sapphirebank.kyc.domain.model.Inr
import com.sapphirebank.kyc.domain.model.Tone
import com.sapphirebank.kyc.domain.model.dicgcNote
import com.sapphirebank.kyc.domain.model.displayName
import com.sapphirebank.kyc.domain.usecase.Step
import com.sapphirebank.kyc.presentation.common.AuditTimeline
import com.sapphirebank.kyc.presentation.common.Banner
import com.sapphirebank.kyc.presentation.common.DecisionSheet
import com.sapphirebank.kyc.presentation.common.DocumentCard
import com.sapphirebank.kyc.presentation.common.DocumentPreviewDialog
import com.sapphirebank.kyc.presentation.common.EmptyState
import com.sapphirebank.kyc.presentation.common.LabelValue
import com.sapphirebank.kyc.presentation.common.LabeledSwitch
import com.sapphirebank.kyc.presentation.common.LoadingState
import com.sapphirebank.kyc.presentation.common.SectionCard
import com.sapphirebank.kyc.presentation.common.StatusChip
import com.sapphirebank.kyc.presentation.common.displayStatus
import com.sapphirebank.kyc.presentation.theme.Sizes
import com.sapphirebank.kyc.presentation.theme.Spacing

/** Looks the case up in the role-filtered list and handles the loading and not-found states. */
@Composable
fun CaseGate(vm: WorkspaceViewModel, arn: String, content: @Composable (CaseFile) -> Unit) {
    val cases by vm.cases.collectAsStateWithLifecycle()
    val c = cases?.firstOrNull { it.arn == arn }
    when {
        cases == null -> LoadingState("Loading application…")
        c == null -> EmptyState("Application not found", "It may have been reassigned, or you may not have access to it.")
        else -> content(c)
    }
}

@Composable
fun CaseHeader(c: CaseFile, coverInr: Long, status: Pair<String, Tone> = c.displayStatus()) {
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ARN: ${c.arn}", style = MaterialTheme.typography.titleMedium)
                Text(c.application.productLine.displayName(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusChip(status.first, status.second)
        }
        LabelValue("Customer", c.application.customerName)
        LabelValue("Amount", Inr.format(c.application.sanctionAmountInr))
        dicgcNote(c.application.productLine, coverInr)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DocumentsSection(c: CaseFile) {
    var preview by remember { mutableStateOf<DocumentRecord?>(null) }
    val latest = DocumentRequirements.latest(c.documents)
    SectionCard("Documents") {
        val kinds = (DocumentRequirements.required(c.application) + latest.keys).distinct()
        kinds.forEach { k -> DocumentCard(k, latest[k], onPreview = latest[k]?.let { r -> { preview = r } }) }
    }
    preview?.let { DocumentPreviewDialog(it) { preview = null } }
}

@Composable
fun AuditSection(vm: WorkspaceViewModel, c: CaseFile) {
    val verified by vm.verified.collectAsStateWithLifecycle()
    val v = verified[c.arn]
    SectionCard(
        "Audit history",
        action = { OutlinedButton(onClick = { vm.verify(c.arn) }, modifier = Modifier.heightIn(min = Sizes.touch)) { Text("Verify chain") } },
    ) {
        Text(
            "Append-only and hash-chained. Entries cannot be edited or deleted.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        v?.let { Banner(it.message, isError = !it.valid) }
        AuditTimeline(c.audit, brokenAt = v?.brokenAtSequence)
    }
}

/** Bottom sheet that runs an action through the ViewModel and shows the service's refusal reason inline. */
@Composable
fun ActionSheet(
    title: String, confirmLabel: String, onDismiss: () -> Unit, minChars: Int,
    textLabel: String = "Justification", destructive: Boolean = false, message: String? = null,
    canConfirm: Boolean = true, extra: (@Composable ColumnScope.() -> Unit)? = null,
    perform: (text: String, done: (Step) -> Unit) -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    DecisionSheet(
        title = title, confirmLabel = confirmLabel, onDismiss = onDismiss,
        onConfirm = { t ->
            busy = true; error = null
            perform(t) { r ->
                busy = false
                when (r) { is Step.Ok -> onDismiss(); is Step.Refused -> error = r.reason }
            }
        },
        textLabel = textLabel, minChars = minChars, destructive = destructive, message = message,
        errorMessage = error, busy = busy, canConfirm = canConfirm, extra = extra,
    )
}

/** Raise an information request to the customer: free text plus the documents needed. */
@Composable
fun InfoRequestSheet(vm: WorkspaceViewModel, arn: String, onDismiss: () -> Unit) {
    var kinds by remember { mutableStateOf(setOf<DocumentKind>()) }
    ActionSheet(
        title = "Request information", confirmLabel = "Send request", onDismiss = onDismiss, minChars = 5,
        textLabel = "What do you need from the customer?",
        message = "The customer is notified. Approval and rejection are blocked until they respond.",
        extra = {
            Text("Documents needed (optional)", style = MaterialTheme.typography.labelLarge)
            Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                DocumentKind.entries.forEach { k ->
                    LabeledSwitch(k.label, k in kinds, { on -> kinds = if (on) kinds + k else kinds - k })
                }
            }
        },
        perform = { text, done -> vm.requestInfo(arn, text, kinds.toList(), done) },
    )
}
