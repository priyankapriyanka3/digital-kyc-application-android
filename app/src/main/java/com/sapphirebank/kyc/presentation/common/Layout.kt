package com.sapphirebank.kyc.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sapphirebank.kyc.domain.model.Permission
import com.sapphirebank.kyc.domain.model.Role
import com.sapphirebank.kyc.presentation.theme.Spacing

/** Standard scrolling page: 16dp padding and 16dp between cards. */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
        content = content,
    )
}

fun Permission.label(): String = when (this) {
    Permission.APPLY -> "Open accounts, deposits and loans"
    Permission.UPLOAD_DOCUMENT -> "Upload documents"
    Permission.RESPOND_INFO -> "Respond to information requests"
    Permission.VIEW_OWN_CASES -> "View and track your own applications"
    Permission.VIEW_QUEUE -> "View the applications in your queue"
    Permission.RUN_KYC_STEPS -> "Run KYC verification steps"
    Permission.KYC_DECIDE -> "Approve or reject KYC"
    Permission.RAISE_INFO_REQUEST -> "Request information from the customer"
    Permission.CREDIT_REVIEW -> "Recommend on eligibility"
    Permission.VIEW_FINANCIALS -> "View financial details (read-only)"
    Permission.FINAL_DECIDE -> "Make the final approval decision"
    Permission.REASSIGN -> "Reassign applications to analysts"
    Permission.VIEW_PIPELINE -> "View the application pipeline"
    Permission.SCREENING_DECIDE -> "Clear or hold AML/PEP flagged applications"
    Permission.VIEW_AUDIT -> "View the audit history"
    Permission.ADMIN_USERS -> "Manage users and roles"
    Permission.ADMIN_CONFIG -> "Configure rules, products, country risk and reference data"
}

/** Restrictions worth stating plainly to the user. The service layer enforces them; this is only the explanation. */
fun Role.restrictions(): List<String> = when (this) {
    Role.CUSTOMER -> listOf("Cannot see anyone else's application")
    Role.KYC_ANALYST -> listOf("Only sees applications assigned to you", "Cannot decide while an information request is open")
    Role.CREDIT_OFFICER -> listOf("Approvals are limited to your delegated limit")
    Role.BRANCH_MANAGER -> listOf("Cannot approve an application you created", "A justification is mandatory for every decision")
    Role.COMPLIANCE_OFFICER -> listOf("Read-only access to financial data", "Cannot approve applications")
    Role.SYSTEM_ADMIN -> listOf("Cannot view or approve individual applications")
}
