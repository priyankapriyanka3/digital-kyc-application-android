package com.sapphirebank.kyc.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.sapphirebank.kyc.domain.model.Actor
import com.sapphirebank.kyc.domain.model.Role
import com.sapphirebank.kyc.domain.model.Tone
import com.sapphirebank.kyc.presentation.common.Banner
import com.sapphirebank.kyc.presentation.common.SectionCard
import com.sapphirebank.kyc.presentation.common.humanize
import com.sapphirebank.kyc.presentation.theme.KycColors
import com.sapphirebank.kyc.presentation.theme.Sizes
import com.sapphirebank.kyc.presentation.theme.Spacing

private fun Role.blurb(): String = when (this) {
    Role.CUSTOMER -> "Open accounts, upload documents and track applications"
    Role.KYC_ANALYST -> "Verify identity documents and the KYC rules"
    Role.CREDIT_OFFICER -> "Review eligibility and recommend"
    Role.BRANCH_MANAGER -> "Final approvals and the application pipeline"
    Role.COMPLIANCE_OFFICER -> "AML / PEP screening, flags and holds"
    Role.SYSTEM_ADMIN -> "Users, KYC rules, products and reference data"
}

/** Demo sign-in. There are no passwords in this build; the service layer authorises every action regardless. */
@Composable
fun SignInScreen(directory: Map<Role, List<Actor>>, onSignIn: (Actor) -> Unit) {
    Column(Modifier.fillMaxSize().background(KycColors.Background)) {
        Column(
            Modifier.fillMaxWidth().background(KycColors.Navy).statusBarsPadding().padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text("Sapphire Bank", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("Digital KYC and account opening", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = Sizes.maxContentWidth).fillMaxSize(),
                contentPadding = PaddingValues(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                item {
                    Banner(
                        "Demo sign-in: choose a user to open that role's workspace. This build has no passwords or backend. " +
                            "Production must use real authentication; every action is authorised in the service layer, not by this screen.",
                        isError = false, tone = Tone.INFO,
                    )
                }
                items(Role.entries.toList(), key = { it.name }) { role ->
                    val users = directory[role].orEmpty()
                    SectionCard(role.label) {
                        Text(role.blurb(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        users.forEachIndexed { i, u ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = Sizes.touch)
                                    .clickable(
                                        onClickLabel = "Sign in as ${u.displayName}, ${role.label}",
                                        role = androidx.compose.ui.semantics.Role.Button,
                                    ) { onSignIn(u) }
                                    .padding(vertical = Spacing.s),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(u.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${u.userId} · ${humanize(u.officerRole)}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("Sign in", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        if (users.isEmpty()) Text("No active users for this role.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
