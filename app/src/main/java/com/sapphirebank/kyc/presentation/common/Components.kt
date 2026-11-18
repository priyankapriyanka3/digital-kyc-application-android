package com.sapphirebank.kyc.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sapphirebank.kyc.domain.model.RiskAssessment
import com.sapphirebank.kyc.domain.model.RiskCategory
import com.sapphirebank.kyc.domain.model.RuleEvaluation
import com.sapphirebank.kyc.domain.model.RuleOutcome
import com.sapphirebank.kyc.presentation.theme.KycColors

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
        Text(
            text, color = color, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

fun outcomeColor(o: RuleOutcome): Color = when (o) {
    RuleOutcome.PASS -> KycColors.Success
    RuleOutcome.FAIL -> KycColors.Danger
    RuleOutcome.REFER -> KycColors.Warning
    RuleOutcome.OVERRIDDEN -> KycColors.Purple
    RuleOutcome.PENDING, RuleOutcome.NOT_APPLICABLE -> KycColors.Neutral
}

fun riskColor(c: RiskCategory): Color = when (c) {
    RiskCategory.LOW -> KycColors.Success
    RiskCategory.MEDIUM -> KycColors.Warning
    RiskCategory.HIGH -> KycColors.Danger
}

@Composable
fun RiskBadge(risk: RiskAssessment?, modifier: Modifier = Modifier) {
    if (risk == null) {
        Pill("Risk: not assessed", KycColors.Neutral, modifier)
        return
    }
    Pill("Risk ${risk.score}/100 · ${risk.category}", riskColor(risk.category), modifier)
}

@Composable
fun RuleRow(e: RuleEvaluation) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(e.ruleId, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(e.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Pill(e.outcome.name.replace('_', ' '), outcomeColor(e.outcome))
        }
        Text(
            "${e.severity} · ${e.detail}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T,
    text: (T) -> String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = text(selected), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); expanded = false })
            }
        }
    }
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, hint: String? = null) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun Banner(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    val c = if (isError) KycColors.Danger else KycColors.Success
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp), color = c.copy(alpha = 0.10f), border = BorderStroke(1.dp, c.copy(alpha = 0.4f))) {
        Text(text, Modifier.padding(12.dp), color = c, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
