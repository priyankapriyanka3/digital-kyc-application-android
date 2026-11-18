package com.sapphirebank.kyc.presentation.admin

import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphirebank.kyc.domain.model.Countries
import com.sapphirebank.kyc.domain.model.CountryRisk
import com.sapphirebank.kyc.domain.model.EligibilityPolicy
import com.sapphirebank.kyc.domain.model.KycThresholds
import com.sapphirebank.kyc.domain.model.ProductConfig
import com.sapphirebank.kyc.domain.model.Tone
import com.sapphirebank.kyc.domain.model.displayName
import com.sapphirebank.kyc.domain.usecase.CaseService
import com.sapphirebank.kyc.presentation.WorkspaceViewModel
import com.sapphirebank.kyc.presentation.common.AppTextField
import com.sapphirebank.kyc.presentation.common.Banner
import com.sapphirebank.kyc.presentation.common.DropdownField
import com.sapphirebank.kyc.presentation.common.EmptyState
import com.sapphirebank.kyc.presentation.common.IconTile
import com.sapphirebank.kyc.presentation.common.LabeledSwitch
import com.sapphirebank.kyc.presentation.common.ScreenColumn
import com.sapphirebank.kyc.presentation.common.ScreenTitle
import com.sapphirebank.kyc.presentation.common.SectionCard
import com.sapphirebank.kyc.presentation.common.formatTime
import com.sapphirebank.kyc.presentation.common.humanize
import com.sapphirebank.kyc.presentation.theme.KycColors
import com.sapphirebank.kyc.presentation.theme.Radius
import com.sapphirebank.kyc.presentation.theme.Sizes
import com.sapphirebank.kyc.presentation.theme.Spacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column

private enum class AdminPage(val title: String, val subtitle: String, val icon: ImageVector) {
    USERS("User Management", "Manage users and roles", Icons.Filled.Group),
    RULES("KYC Rules", "Configure KYC-001 to KYC-009", Icons.Filled.Shield),
    PRODUCTS("Product Configuration", "Savings, Current, FD/RD, Loan", Icons.Filled.AccountBalance),
    COUNTRY("Country Risk Settings", "Set risk parameters", Icons.Filled.Public),
    REFERENCE("Reference Data", "Manage master data", Icons.Filled.Storage),
}

@Composable
fun AdminHome(vm: WorkspaceViewModel) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = null }
    val current = page?.let { p -> AdminPage.entries.firstOrNull { it.name == p } }
    if (current == null) {
        ScreenColumn {
            ScreenTitle("System Configuration")
            Text(
                "Administration covers configuration only. System Admin cannot view or approve individual applications.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AdminPage.entries.forEach { p ->
                Card(
                    onClick = { page = p.name }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.card),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(Modifier.padding(Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Tone.INFO) { Icon(p.icon, contentDescription = null, tint = KycColors.Sapphire) }
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.titleSmall)
                            Text(p.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }
    } else {
        ScreenColumn {
            TextButton(onClick = { page = null }, modifier = Modifier.heightIn(min = Sizes.touch)) { Text("‹ System Configuration") }
            ScreenTitle(current.title)
            when (current) {
                AdminPage.USERS -> UsersPage(vm)
                AdminPage.RULES -> RulesPage(vm)
                AdminPage.PRODUCTS -> ProductsPage(vm)
                AdminPage.COUNTRY -> CountryPage(vm)
                AdminPage.REFERENCE -> ReferencePage(vm)
            }
        }
    }
}

@Composable
private fun UsersPage(vm: WorkspaceViewModel) {
    val config by vm.config.collectAsStateWithLifecycle()
    SectionCard("Users") {
        config.users.forEach { u ->
            LabeledSwitch(
                u.displayName, u.active, { vm.setUserActive(u.userId, it) },
                hint = humanize(u.officerRole) + " · " + u.userId + if (u.delegatedLimitInr > 0) " · limit " + com.sapphirebank.kyc.domain.model.Inr.format(u.delegatedLimitInr) else "",
            )
        }
    }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(CaseService.ASSIGNABLE_ROLES.first()) }
    var limit by remember { mutableStateOf("") }
    SectionCard("Add user") {
        AppTextField("Full name", name, { name = it })
        DropdownField("Role", CaseService.ASSIGNABLE_ROLES, role, { humanize(it) }) { role = it }
        if (role in setOf("CREDIT_OFFICER", "BRANCH_MANAGER", "SENIOR_MANAGEMENT"))
            AppTextField("Approval limit (INR)", limit, { limit = it.filter(Char::isDigit) }, keyboard = KeyboardType.Number)
        Button(
            onClick = { vm.addUser(name, role, limit.toLongOrNull() ?: 0L) { r -> if (r.ok) { name = ""; limit = "" } } },
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch),
        ) { Text("Add user") }
    }
}

@Composable
private fun RulesPage(vm: WorkspaceViewModel) {
    val config by vm.config.collectAsStateWithLifecycle()
    val t = config.thresholds
    var name by remember(t) { mutableStateOf(t.nameMatchMin.toString()) }
    var face8 by remember(t) { mutableStateOf(t.faceMatchMinKyc008.toString()) }
    var faceV by remember(t) { mutableStateOf(t.faceMatchMinVcip.toString()) }
    var q by remember(t) { mutableStateOf(t.minRandomQuestions.toString()) }
    var dep by remember(t) { mutableStateOf(t.limitedKycDepositCapInr.toString()) }
    var loan by remember(t) { mutableStateOf(t.limitedKycLoanCapInr.toString()) }
    var win by remember(t) { mutableStateOf(t.deemedOvdWindowDays.toString()) }
    var err by remember { mutableStateOf<String?>(null) }
    SectionCard("KYC rule thresholds") {
        Text("Values from the rule catalogue. Changes apply to rules evaluated from now on and are written to the configuration log.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppTextField("KYC-008 name match minimum (0.50 to 1.00)", name, { name = it }, keyboard = KeyboardType.Decimal)
        AppTextField("KYC-008 face match minimum", face8, { face8 = it }, keyboard = KeyboardType.Decimal)
        AppTextField("KYC-006 V-CIP face match minimum", faceV, { faceV = it }, keyboard = KeyboardType.Decimal)
        AppTextField("KYC-006 random questions (minimum)", q, { q = it }, keyboard = KeyboardType.Number)
        AppTextField("KYC-007 deposit cap (INR)", dep, { dep = it }, keyboard = KeyboardType.Number)
        AppTextField("KYC-007 loan cap (INR)", loan, { loan = it }, keyboard = KeyboardType.Number)
        AppTextField("KYC-003 deemed-OVD window (days)", win, { win = it }, keyboard = KeyboardType.Number)
        err?.let { Banner(it, isError = true) }
        Button(
            onClick = {
                val n = name.toDoubleOrNull(); val f8 = face8.toDoubleOrNull(); val fv = faceV.toDoubleOrNull()
                val qq = q.toIntOrNull(); val d = dep.toLongOrNull(); val l = loan.toLongOrNull(); val w = win.toIntOrNull()
                if (n == null || f8 == null || fv == null || qq == null || d == null || l == null || w == null) err = "Enter a valid number in every field"
                else { err = null; vm.saveThresholds(KycThresholds(n, f8, fv, qq, d, l, w)) }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch),
        ) { Text("Save thresholds") }
    }

    val p = config.eligibility
    var maxDti by remember(p) { mutableStateOf(p.maxDtiPct.toString()) }
    var referDti by remember(p) { mutableStateOf(p.referDtiPct.toString()) }
    var minB by remember(p) { mutableStateOf(p.minBureauScore.toString()) }
    var referB by remember(p) { mutableStateOf(p.referBureauScore.toString()) }
    var err2 by remember { mutableStateOf<String?>(null) }
    SectionCard("Eligibility policy (illustrative defaults)") {
        AppTextField("Maximum debt-to-income (%)", maxDti, { maxDti = it }, keyboard = KeyboardType.Number)
        AppTextField("Refer above debt-to-income (%)", referDti, { referDti = it }, keyboard = KeyboardType.Number)
        AppTextField("Minimum bureau score", minB, { minB = it }, keyboard = KeyboardType.Number)
        AppTextField("Refer below bureau score", referB, { referB = it }, keyboard = KeyboardType.Number)
        err2?.let { Banner(it, isError = true) }
        Button(
            onClick = {
                val a = maxDti.toIntOrNull(); val b = referDti.toIntOrNull(); val c = minB.toIntOrNull(); val d = referB.toIntOrNull()
                if (a == null || b == null || c == null || d == null) err2 = "Enter a valid number in every field"
                else { err2 = null; vm.saveEligibility(EligibilityPolicy(a, b, c, d)) }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch),
        ) { Text("Save policy") }
    }
}

@Composable
private fun ProductsPage(vm: WorkspaceViewModel) {
    val config by vm.config.collectAsStateWithLifecycle()
    config.products.forEach { p -> ProductEditor(vm, p) }
}

@Composable
private fun ProductEditor(vm: WorkspaceViewModel, p: ProductConfig) {
    var enabled by remember(p) { mutableStateOf(p.enabled) }
    var min by remember(p) { mutableStateOf(p.minAmountInr.toString()) }
    var max by remember(p) { mutableStateOf(p.maxAmountInr.toString()) }
    var rate by remember(p) { mutableStateOf(p.annualRatePct.toString()) }
    var tenure by remember(p) { mutableStateOf(p.maxTenureMonths.toString()) }
    var err by remember(p) { mutableStateOf<String?>(null) }
    SectionCard(p.product.displayName()) {
        LabeledSwitch("Offered to customers", enabled, { enabled = it })
        AppTextField("Minimum amount (INR)", min, { min = it }, keyboard = KeyboardType.Number)
        AppTextField("Maximum amount (INR)", max, { max = it }, keyboard = KeyboardType.Number)
        AppTextField("Annual rate (%)", rate, { rate = it }, keyboard = KeyboardType.Decimal)
        AppTextField("Maximum tenure (months, 0 = not applicable)", tenure, { tenure = it }, keyboard = KeyboardType.Number)
        err?.let { Banner(it, isError = true) }
        Button(
            onClick = {
                val a = min.toLongOrNull(); val b = max.toLongOrNull(); val r = rate.toDoubleOrNull(); val t = tenure.toIntOrNull()
                if (a == null || b == null || r == null || t == null) err = "Enter a valid number in every field"
                else { err = null; vm.saveProduct(p.copy(enabled = enabled, minAmountInr = a, maxAmountInr = b, annualRatePct = r, maxTenureMonths = t)) }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch),
        ) { Text("Save ${p.product.displayName()}") }
    }
}

@Composable
private fun CountryPage(vm: WorkspaceViewModel) {
    val config by vm.config.collectAsStateWithLifecycle()
    SectionCard("Country risk") {
        config.countryRisk.toSortedMap().forEach { (code, risk) ->
            DropdownField(Countries.label(code), CountryRisk.entries, risk, { it.label }) { vm.setCountryRisk(code, it) }
        }
    }
    var code by remember { mutableStateOf("") }
    var risk by remember { mutableStateOf(CountryRisk.MEDIUM) }
    SectionCard("Add or update a country") {
        AppTextField("Country code (2 letters)", code, { code = it.uppercase().take(2) })
        DropdownField("Risk", CountryRisk.entries, risk, { it.label }) { risk = it }
        Button(onClick = { vm.setCountryRisk(code, risk) { r -> if (r.ok) code = "" } }, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch)) { Text("Save country") }
    }
}

@Composable
private fun ReferencePage(vm: WorkspaceViewModel) {
    val config by vm.config.collectAsStateWithLifecycle()
    RefList("Branches", config.branches, CaseService.ReferenceKind.BRANCH, vm)
    RefList("Employment types", config.employmentTypes, CaseService.ReferenceKind.EMPLOYMENT, vm)
}

@Composable
private fun RefList(title: String, items: List<String>, kind: CaseService.ReferenceKind, vm: WorkspaceViewModel) {
    var v by remember { mutableStateOf("") }
    SectionCard(title) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.removeReference(kind, item) }, modifier = Modifier.heightIn(min = Sizes.touch)) { Text("Remove", color = KycColors.Danger) }
            }
        }
        AppTextField("Add to $title", v, { v = it })
        Button(onClick = { vm.addReference(kind, v) { r -> if (r.ok) v = "" } }, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch)) { Text("Add") }
    }
}

@Composable
fun AdminLogs(vm: WorkspaceViewModel) {
    val log by vm.adminLog.collectAsStateWithLifecycle()
    ScreenColumn {
        ScreenTitle("Configuration log")
        Text("Append-only record of every administrative change.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (log.isEmpty()) EmptyState("No changes yet", "Configuration changes are listed here with who made them and when.")
        log.asReversed().forEach { e ->
            SectionCard(null) {
                Text(e.action, style = MaterialTheme.typography.titleSmall)
                Text(e.detail, style = MaterialTheme.typography.bodyMedium)
                Text("${formatTime(e.at)} · ${e.actor}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
