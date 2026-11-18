package com.sapphirebank.kyc.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.AuditEvent
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.DocumentKind
import com.sapphirebank.kyc.domain.model.DocumentRecord
import com.sapphirebank.kyc.domain.model.InfoRequest
import com.sapphirebank.kyc.domain.model.InfoRequestStatus
import com.sapphirebank.kyc.domain.model.Inr
import com.sapphirebank.kyc.domain.model.RiskAssessment
import com.sapphirebank.kyc.domain.model.RiskCategory
import com.sapphirebank.kyc.domain.model.RuleEvaluation
import com.sapphirebank.kyc.domain.model.Tone
import com.sapphirebank.kyc.domain.model.ValidationCheck
import com.sapphirebank.kyc.domain.model.shortName
import com.sapphirebank.kyc.domain.rules.ApplicationStateMachine
import com.sapphirebank.kyc.domain.rules.StageProgress
import com.sapphirebank.kyc.domain.rules.StepState
import com.sapphirebank.kyc.presentation.theme.KycColors
import com.sapphirebank.kyc.presentation.theme.Radius
import com.sapphirebank.kyc.presentation.theme.Sizes
import com.sapphirebank.kyc.presentation.theme.Spacing
import com.sapphirebank.kyc.presentation.theme.background
import com.sapphirebank.kyc.presentation.theme.color

// ---- Layout primitives ----------------------------------------------------------------------

@Composable
fun SectionCard(
    title: String?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s + Spacing.xs)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    action?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(0.45f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

// ---- Status chips and indicators ------------------------------------------------------------

/** Application status chip. Text always carries the meaning; colour is reinforcement only. */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) = ToneChip(text, tone.color(), tone.background(), modifier)

@Composable
fun ToneChip(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = bg) {
        Text(
            text, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = Spacing.s + Spacing.xs, vertical = Spacing.xs),
        )
    }
}

fun riskTone(c: RiskCategory): Tone = when (c) { RiskCategory.LOW -> Tone.GOOD; RiskCategory.MEDIUM -> Tone.WATCH; RiskCategory.HIGH -> Tone.BAD }

@Composable
fun RiskBadge(risk: RiskAssessment?, modifier: Modifier = Modifier) {
    if (risk == null) StatusChip("Risk not assessed", Tone.INFO, modifier)
    else StatusChip("Risk ${risk.score}/100 · ${risk.category}", riskTone(risk.category), modifier)
}

/**
 * Semi-circular score gauge for risk scores (higher is worse) and bureau scores (higher is better).
 * The value is also printed as text and exposed through semantics, so the arc is decorative.
 */
@Composable
fun ScoreGauge(
    value: Int, min: Int, max: Int, label: String, tone: Tone, modifier: Modifier = Modifier,
) {
    val frac = ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.outlineVariant
    val arc = tone.color()
    Box(
        modifier.size(Sizes.gauge).semantics { contentDescription = "$label $value out of $max" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
            val inset = 8.dp.toPx()
            val s = Size(size.width - inset * 2, size.height - inset * 2)
            val tl = Offset(inset, inset)
            drawArc(track, 150f, 240f, false, tl, s, style = stroke)
            drawArc(arc, 150f, 240f * frac, false, tl, s, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RiskScoreIndicator(score: Int, modifier: Modifier = Modifier) {
    val tone = if (score >= 70) Tone.BAD else if (score >= 40) Tone.WATCH else Tone.GOOD
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row {
            Text("Risk score", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$score/100", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = tone.color())
        }
        LinearProgressIndicator(
            progress = { score / 100f }, color = tone.color(), trackColor = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).semantics { contentDescription = "Risk score $score out of 100" },
        )
    }
}

// ---- Cards ----------------------------------------------------------------------------------

@Composable
fun MetricCard(value: String, label: String, tone: Tone, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val base = Modifier.semantics(mergeDescendants = true) { contentDescription = "$value $label" }
    Card(
        modifier = modifier.then(base),
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Column(Modifier.padding(Spacing.m).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tone.color()))
            Spacer(Modifier.height(Spacing.s))
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One row per application, used by every queue: ARN, product, customer, amount and a status chip. */
@Composable
fun ApplicationSummaryCard(
    c: CaseFile, modifier: Modifier = Modifier, onClick: () -> Unit,
    status: Pair<String, Tone> = c.displayStatus(),
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val (statusText, tone) = status
    Card(
        onClick = onClick, modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ARN: ${c.arn}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                StatusChip(statusText, tone)
            }
            Text(
                "${c.application.productLine.shortName()} · ${c.application.customerName}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Inr.format(c.application.sanctionAmountInr) + " · " + formatTime(c.lastUpdated),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            footer?.invoke(this)
        }
    }
}

@Composable
fun CustomerInfoCard(c: CaseFile, modifier: Modifier = Modifier) {
    val a = c.application
    SectionCard("Customer", modifier) {
        LabelValue("Name", a.customerName)
        LabelValue("Date of birth", a.dateOfBirth)
        LabelValue("PAN", if (a.panNumber.isNotBlank()) com.sapphirebank.kyc.domain.validation.DocumentValidators.mask(com.sapphirebank.kyc.domain.model.DocType.PAN, a.panNumber) else "Form 60")
        LabelValue("Identity (${a.identityDoc.label})", com.sapphirebank.kyc.domain.validation.DocumentValidators.mask(a.identityDoc, a.identityDocNumber).let { if (a.identityDoc == com.sapphirebank.kyc.domain.model.DocType.AADHAAR) aadhaarDisplay(a.identityDocNumber) else it })
        LabelValue("Country", com.sapphirebank.kyc.domain.model.Countries.label(a.countryCode))
        LabelValue("KYC mode", a.kycMode.label)
    }
}

/** Aadhaar is always shown masked with only the last four digits visible, whatever form is stored. */
fun aadhaarDisplay(stored: String): String {
    val d = stored.filter { it.isLetterOrDigit() }
    return if (d.length >= 4) "XXXX XXXX " + d.takeLast(4) else "XXXX XXXX XXXX"
}

// ---- Documents and validation ---------------------------------------------------------------

@Composable
fun DocumentCard(
    kind: DocumentKind, record: DocumentRecord?, modifier: Modifier = Modifier,
    onUpload: (() -> Unit)? = null, onPreview: (() -> Unit)? = null,
) {
    val uploaded = record != null
    Card(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (uploaded) KycColors.Success.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            IconTile(if (uploaded) Tone.GOOD else Tone.INFO) { Icon(Icons.Filled.Description, contentDescription = null, tint = if (uploaded) KycColors.Success else KycColors.Sapphire) }
            Column(Modifier.weight(1f)) {
                Text(kind.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (uploaded) "${record!!.fileName} · v${record.version}" else kind.hint,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uploaded && onPreview != null) TextButton(onClick = onPreview, modifier = Modifier.heightIn(min = Sizes.touch)) { Text("Preview") }
            if (onUpload != null) OutlinedButton(onClick = onUpload, modifier = Modifier.heightIn(min = Sizes.touch)) { Text(if (uploaded) "Replace" else "Upload") }
            else StatusChip(if (uploaded) "Uploaded" else "Missing", if (uploaded) Tone.GOOD else Tone.BAD)
        }
    }
}

@Composable
fun IconTile(tone: Tone, content: @Composable () -> Unit) {
    Box(Modifier.size(Sizes.iconTile).clip(RoundedCornerShape(Radius.field)).background(tone.background()), contentAlignment = Alignment.Center) { content() }
}

/** Compact thumbnail tile for a document (used on the analyst card). Not a real image preview. */
@Composable
fun DocumentTile(label: String, present: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Column(
        modifier.widthIn(min = 72.dp).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(if (present) Tone.INFO else Tone.BAD) { Icon(Icons.Filled.Description, null, tint = if (present) KycColors.Sapphire else KycColors.Danger) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ValidationResultCard(checks: List<ValidationCheck>, modifier: Modifier = Modifier) {
    SectionCard("Validation results", modifier) {
        checks.forEach { ch ->
            val tone = ch.outcome.tone()
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s + Spacing.xs), verticalAlignment = Alignment.Top) {
                val icon = when (tone) { Tone.GOOD -> Icons.Filled.Check; Tone.BAD -> Icons.Filled.Close; else -> Icons.Filled.Warning }
                Icon(icon, contentDescription = ch.outcome.label(), tint = tone.color(), modifier = Modifier.size(20.dp))
                Column {
                    Text(ch.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(ch.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun RuleRow(e: RuleEvaluation) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(e.ruleId, style = MaterialTheme.typography.labelLarge)
            Text(e.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            StatusChip(e.outcome.label(), e.outcome.tone())
        }
        Text("${e.severity.name.lowercase().replaceFirstChar { it.uppercase() }} · ${e.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Workflow, requests, audit --------------------------------------------------------------

/** Eight-stage tracker driven by the application state machine. */
@Composable
fun WorkflowTracker(case: CaseFile, modifier: Modifier = Modifier) {
    val stages = ApplicationStateMachine.progress(case.status, case.failedAt)
    SectionCard("Application progress", modifier) {
        Column {
            stages.forEachIndexed { i, s -> StageRow(s, last = i == stages.lastIndex) }
        }
    }
}

@Composable
private fun StageRow(s: StageProgress, last: Boolean) {
    val color = when (s.state) {
        StepState.DONE -> KycColors.Success
        StepState.ACTIVE -> MaterialTheme.colorScheme.primary
        StepState.FAILED -> KycColors.Danger
        StepState.PENDING -> MaterialTheme.colorScheme.outlineVariant
    }
    val stateWord = when (s.state) { StepState.DONE -> "complete"; StepState.ACTIVE -> "current"; StepState.FAILED -> "failed"; StepState.PENDING -> "pending" }
    Row(
        Modifier.height(IntrinsicSize.Min).semantics(mergeDescendants = true) { contentDescription = "Stage ${s.stage.number}, ${s.stage.title}, $stateWord" },
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                when (s.state) {
                    StepState.DONE -> Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    StepState.FAILED -> Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    else -> Text(s.stage.number.toString(), color = if (s.state == StepState.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(color))
        }
        Column(Modifier.padding(bottom = Spacing.m)) {
            Text(s.stage.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (s.state == StepState.ACTIVE) FontWeight.Bold else FontWeight.Normal)
            Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InfoRequestCard(r: InfoRequest, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val open = r.status == InfoRequestStatus.OPEN
    Card(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = if (open) KycColors.WarningBg else MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (open) KycColors.Warning.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${r.id} · from ${humanize(r.raisedByRole)}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                StatusChip(if (open) "Open" else "Answered", if (open) Tone.WATCH else Tone.GOOD)
            }
            Text(r.message, style = MaterialTheme.typography.bodyMedium)
            if (r.kinds.isNotEmpty()) Text("Needed: " + r.kinds.joinToString { it.label }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!open && r.response.isNotBlank()) Text("Reply: ${r.response}", style = MaterialTheme.typography.bodySmall)
            action?.invoke()
        }
    }
}

/** Newest-first, read-only list of hash-chained events. There is deliberately no edit or delete affordance. */
@Composable
fun AuditTimeline(events: List<AuditEvent>, brokenAt: Int? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (events.isEmpty()) Text("No audit events yet.", style = MaterialTheme.typography.bodyMedium)
        events.asReversed().forEach { e ->
            val broken = brokenAt == e.sequenceNo
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(if (broken) KycColors.Danger else MaterialTheme.colorScheme.primary))
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                        Text("#${e.sequenceNo} ${humanize(e.eventType)}", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (broken) StatusChip("Altered", Tone.BAD)
                    }
                    Text("${formatTime(e.occurredAt)} · ${e.actor}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "hash ${e.eventHash.take(12)}…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                        color = if (broken) KycColors.Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---- Inputs ---------------------------------------------------------------------------------

@Composable
fun SearchFilterBar(
    query: String, onQuery: (String) -> Unit,
    filters: List<String>, selected: String, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier, sortLabel: String? = null, onSort: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            label = { Text("Search by ARN or customer") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                filters.forEach { f ->
                    FilterChip(selected = f == selected, onClick = { onSelect(f) }, label = { Text(f) }, modifier = Modifier.heightIn(min = 32.dp))
                }
            }
            if (sortLabel != null && onSort != null) TextButton(onClick = onSort, modifier = Modifier.heightIn(min = Sizes.touch)) { Text(sortLabel) }
        }
    }
}

@Composable
fun AppTextField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    error: String? = null, keyboard: KeyboardType = KeyboardType.Text, enabled: Boolean = true,
    singleLine: Boolean = true, minLines: Int = 1, supporting: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        isError = error != null, enabled = enabled, singleLine = singleLine, minLines = minLines,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownField(
    label: String, options: List<T>, selected: T, text: (T) -> String,
    modifier: Modifier = Modifier.fillMaxWidth(), onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = text(selected), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); expanded = false }) }
        }
    }
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, hint: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Sizes.touch).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Confirmation sheet for approve / reject / hold decisions with an optional mandatory justification. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecisionSheet(
    title: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit,
    textLabel: String = "Justification", minChars: Int = 0, destructive: Boolean = false,
    message: String? = null, errorMessage: String? = null, busy: Boolean = false,
    canConfirm: Boolean = true, extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var text by remember { mutableStateOf("") }
    val tooShort = text.trim().length < minChars
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = Spacing.m).padding(bottom = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppTextField(
                label = if (minChars > 0) "$textLabel (required)" else "$textLabel (optional)", value = text, onChange = { text = it },
                singleLine = false, minLines = 3,
                supporting = if (minChars > 0) "At least $minChars characters (${text.trim().length}/$minChars)" else null,
            )
            extra?.invoke(this)
            if (errorMessage != null) Banner(errorMessage, isError = true)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = Sizes.touch)) { Text("Cancel") }
                Button(
                    onClick = { onConfirm(text.trim()) }, enabled = !tooShort && !busy && canConfirm,
                    colors = if (destructive) ButtonDefaults.buttonColors(containerColor = KycColors.Danger) else ButtonDefaults.buttonColors(),
                    modifier = Modifier.weight(1f).heightIn(min = Sizes.touch),
                ) { Text(confirmLabel) }
            }
        }
    }
}

// ---- Feedback states ------------------------------------------------------------------------

@Composable
fun Banner(text: String, isError: Boolean, modifier: Modifier = Modifier, tone: Tone? = null) {
    val t = tone ?: if (isError) Tone.BAD else Tone.GOOD
    Surface(
        modifier.fillMaxWidth().semantics { contentDescription = (if (t == Tone.BAD) "Error: " else "") + text },
        shape = RoundedCornerShape(Radius.field), color = t.background(), border = BorderStroke(1.dp, t.color().copy(alpha = 0.4f)),
    ) { Text(text, Modifier.padding(Spacing.m - 4.dp), color = t.color(), style = MaterialTheme.typography.bodyMedium) }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.s), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = Sizes.touch)) { Text(actionLabel) }
    }
}

@Composable
fun LoadingState(text: String = "Loading…", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = text })
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = KycColors.Danger)
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = Sizes.touch)) { Text("Retry") }
    }
}

fun statusIsActive(s: ApplicationStatus) = !s.terminal
