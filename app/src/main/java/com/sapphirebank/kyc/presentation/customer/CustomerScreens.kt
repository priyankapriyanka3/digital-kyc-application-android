package com.sapphirebank.kyc.presentation.customer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.DocumentKind
import com.sapphirebank.kyc.domain.model.DocumentRecord
import com.sapphirebank.kyc.domain.model.InfoRequest
import com.sapphirebank.kyc.domain.model.ProductLine
import com.sapphirebank.kyc.domain.model.Tone
import com.sapphirebank.kyc.domain.model.dicgcNote
import com.sapphirebank.kyc.domain.model.displayName
import com.sapphirebank.kyc.domain.model.shortName
import com.sapphirebank.kyc.domain.usecase.Step
import com.sapphirebank.kyc.presentation.CaseGate
import com.sapphirebank.kyc.presentation.CaseHeader
import com.sapphirebank.kyc.presentation.DocumentsSection
import com.sapphirebank.kyc.presentation.WorkspaceViewModel
import com.sapphirebank.kyc.presentation.common.AppTextField
import com.sapphirebank.kyc.presentation.common.Banner
import com.sapphirebank.kyc.presentation.common.DocumentCard
import com.sapphirebank.kyc.presentation.common.EmptyState
import com.sapphirebank.kyc.presentation.common.InfoRequestCard
import com.sapphirebank.kyc.presentation.common.LabelValue
import com.sapphirebank.kyc.presentation.common.LoadingState
import com.sapphirebank.kyc.presentation.common.ScreenColumn
import com.sapphirebank.kyc.presentation.common.ScreenTitle
import com.sapphirebank.kyc.presentation.common.SectionCard
import com.sapphirebank.kyc.presentation.common.WorkflowTracker
import com.sapphirebank.kyc.presentation.common.customerStatus
import com.sapphirebank.kyc.presentation.common.progressFraction
import com.sapphirebank.kyc.presentation.common.rememberDocumentPicker
import com.sapphirebank.kyc.presentation.staff.CaseQueue
import com.sapphirebank.kyc.presentation.staff.QueueFilter
import com.sapphirebank.kyc.presentation.theme.KycColors
import com.sapphirebank.kyc.presentation.theme.Radius
import com.sapphirebank.kyc.presentation.theme.Sizes
import com.sapphirebank.kyc.presentation.theme.Spacing
import java.time.Instant

private fun ProductLine.icon(): ImageVector = when (this) {
    ProductLine.SAVINGS_ACCOUNT -> Icons.Filled.Savings
    ProductLine.CURRENT_ACCOUNT -> Icons.Filled.CreditCard
    ProductLine.TERM_DEPOSIT -> Icons.AutoMirrored.Filled.TrendingUp
    ProductLine.RETAIL_LOAN -> Icons.Filled.Handshake
}

@Composable
fun CustomerHome(vm: WorkspaceViewModel, cases: List<CaseFile>?, onApply: (String?) -> Unit, onOpen: (String) -> Unit, onSeeAll: () -> Unit) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    ScreenColumn {
        // Hero
        Card(
            shape = RoundedCornerShape(Radius.card),
            colors = CardDefaults.cardColors(containerColor = KycColors.Navy),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("Welcome to\nSapphire Bank", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text("Hello, ${vm.actor.displayName}. Your trusted banking partner.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD0DAF0))
            }
        }
        Button(onClick = { onApply(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch)) {
            Text("Open an Account")
            Spacer(Modifier.width(Spacing.s))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }

        // In-progress application
        val active = cases?.firstOrNull { !it.status.terminal }
        when {
            cases == null -> LoadingState("Loading your applications…")
            active != null -> {
                val (label, tone) = active.customerStatus()
                Card(
                    onClick = { onOpen(active.arn) }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.card),
                    colors = CardDefaults.cardColors(containerColor = KycColors.SapphireContainer),
                ) {
                    Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Text("Your ${active.application.productLine.shortName()} application is in progress", style = MaterialTheme.typography.titleSmall)
                        Text("ARN: ${active.arn} · $label", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(
                            progress = { active.progressFraction() }, modifier = Modifier.fillMaxWidth().height(8.dp)
                                .semantics { contentDescription = "Application ${(active.progressFraction() * 100).toInt()} percent complete" },
                            color = tone.let { if (it == Tone.WATCH) KycColors.Warning else KycColors.Sapphire }, trackColor = Color.White,
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Our Products", Modifier.weight(1f))
            TextButton(onClick = onSeeAll, modifier = Modifier.heightIn(min = Sizes.touch)) { Text("My applications") }
        }
        val products = cfg.products.filter { it.enabled }
        products.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), modifier = Modifier.fillMaxWidth()) {
                row.forEach { p -> ProductTile(p.product, p.tagline, Modifier.weight(1f)) { onApply(p.product.name) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        val fd = cfg.product(ProductLine.TERM_DEPOSIT)
        if (fd.enabled) dicgcNote(ProductLine.TERM_DEPOSIT, cfg.dicgcCoverInr)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProductTile(p: ProductLine, tagline: String, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = modifier.heightIn(min = 112.dp),
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Spacing.m).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(p.icon(), contentDescription = null, tint = KycColors.Sapphire, modifier = Modifier.size(28.dp))
            Text(p.shortName(), style = MaterialTheme.typography.titleSmall)
            Text(tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CustomerApplications(vm: WorkspaceViewModel, cases: List<CaseFile>?, onOpen: (String) -> Unit, onApply: () -> Unit) {
    val filters = listOf(
        QueueFilter("All") { true },
        QueueFilter("In progress") { !it.status.terminal && it.openInfoRequests.isEmpty() },
        QueueFilter("Action needed") { it.openInfoRequests.isNotEmpty() },
        QueueFilter("Completed") { it.status.terminal },
    )
    CaseQueue(
        cases = cases, filters = filters, onOpen = onOpen,
        emptyTitle = "No applications yet", emptyBody = "Open an account to get started.",
        status = { it.customerStatus() },
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle("My applications", Modifier.weight(1f))
                OutlinedButton(onClick = onApply, modifier = Modifier.heightIn(min = Sizes.touch)) { Text("New") }
            }
        },
    )
}

@Composable
fun CustomerCaseScreen(vm: WorkspaceViewModel, arn: String) = CaseGate(vm, arn) { c -> CustomerCase(vm, c) }

@Composable
private fun CustomerCase(vm: WorkspaceViewModel, c: CaseFile) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    var confirmWithdraw by rememberSaveable { mutableStateOf(false) }
    ScreenColumn {
        CaseHeader(c, cfg.dicgcCoverInr, c.customerStatus())
        c.openInfoRequests.forEach { r -> RespondCard(vm, c, r) }
        WorkflowTracker(c)
        c.welcomeKit?.let { kit ->
            SectionCard("Account created") {
                Banner("Your account is open. Welcome to Sapphire Bank.", isError = false)
                LabelValue("Account number", kit.accountNumber)
                LabelValue("IFSC", kit.ifsc)
                LabelValue("Branch", kit.branch)
                Text("Your welcome kit", style = MaterialTheme.typography.titleSmall)
                kit.items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                kit.dicgcNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (c.status == ApplicationStatus.REJECTED) {
            Banner("Your application was not approved. Contact your branch for details.", isError = true)
        }
        c.infoRequests.filter { it.status != com.sapphirebank.kyc.domain.model.InfoRequestStatus.OPEN }.forEach { InfoRequestCard(it) }
        DocumentsSection(c)
        if (!c.status.terminal) {
            OutlinedButton(onClick = { confirmWithdraw = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch)) { Text("Withdraw application") }
        }
    }
    if (confirmWithdraw) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmWithdraw = false },
            title = { Text("Withdraw this application?") },
            text = { Text("This cannot be undone. You can open a new application at any time.") },
            confirmButton = { TextButton(onClick = { confirmWithdraw = false; vm.withdraw(c.arn) }) { Text("Withdraw", color = KycColors.Danger) } },
            dismissButton = { TextButton(onClick = { confirmWithdraw = false }) { Text("Keep it") } },
        )
    }
}

/** Customer response to an information request: upload each requested document and add a note. */
@Composable
private fun RespondCard(vm: WorkspaceViewModel, c: CaseFile, r: InfoRequest) {
    var reply by rememberSaveable(r.id) { mutableStateOf("") }
    var picked by remember(r.id) { mutableStateOf<Map<DocumentKind, DocumentRecord>>(emptyMap()) }
    var error by remember(r.id) { mutableStateOf<String?>(null) }
    var sending by remember(r.id) { mutableStateOf(false) }
    val pick = rememberDocumentPicker { kind, uri, name ->
        picked = picked + (kind to DocumentRecord(kind, name, uri, Instant.now().toString(), vm.actor.userId))
    }
    SectionCard("Action needed: ${r.id}") {
        Banner(r.message, isError = false, tone = Tone.WATCH)
        r.kinds.forEach { k -> DocumentCard(k, picked[k], onUpload = { pick(k) }) }
        AppTextField("Your reply (optional)", reply, { reply = it }, singleLine = false, minLines = 2)
        error?.let { Banner(it, isError = true) }
        Button(
            onClick = {
                sending = true; error = null
                vm.respondInfo(c.arn, r.id, reply, picked.values.toList()) { s ->
                    sending = false
                    if (s is Step.Refused) error = s.reason
                }
            },
            enabled = !sending, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch),
        ) { Text(if (sending) "Sending…" else "Send response") }
    }
}
