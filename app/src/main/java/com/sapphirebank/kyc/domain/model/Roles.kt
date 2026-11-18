package com.sapphirebank.kyc.domain.model

/** The six role-based workspaces. Staff roles map from the knowledge-base role names (SBK-KYC-14). */
enum class Role(val label: String) {
    CUSTOMER("Customer"),
    KYC_ANALYST("KYC Analyst"),
    CREDIT_OFFICER("Credit Officer"),
    BRANCH_MANAGER("Branch Manager"),
    COMPLIANCE_OFFICER("Compliance Officer"),
    SYSTEM_ADMIN("System Admin");

    companion object {
        /**
         * SENIOR_MANAGEMENT users (L3 sign-off for PEP / high-risk cases) work inside the Branch Manager
         * workspace. Back-office roles with no workspace (RM, V-CIP official, auditor) map to null.
         */
        fun fromOfficerRole(r: String): Role? = when (r) {
            "KYC_OPERATIONS_OFFICER" -> KYC_ANALYST
            "CREDIT_OFFICER" -> CREDIT_OFFICER
            "BRANCH_MANAGER", "SENIOR_MANAGEMENT" -> BRANCH_MANAGER
            "COMPLIANCE_OFFICER" -> COMPLIANCE_OFFICER
            "SYSTEM_ADMIN" -> SYSTEM_ADMIN
            else -> null
        }
    }
}

/** An authenticated caller. The service layer re-resolves it from the directory; the role here is never trusted. */
data class Actor(
    val userId: String,
    val displayName: String,
    val role: Role,
    val officerRole: String = "",
    val delegatedLimitInr: Long = 0,
) {
    fun label(): String = "$userId (${officerRole.ifBlank { role.name }})"
    fun toOfficer() = Officer(userId, displayName, officerRole.ifBlank { role.name }, delegatedLimitInr)
}

enum class Permission {
    APPLY, UPLOAD_DOCUMENT, RESPOND_INFO, VIEW_OWN_CASES,
    VIEW_QUEUE, RUN_KYC_STEPS, KYC_DECIDE, RAISE_INFO_REQUEST,
    CREDIT_REVIEW, VIEW_FINANCIALS,
    FINAL_DECIDE, REASSIGN, VIEW_PIPELINE,
    SCREENING_DECIDE, VIEW_AUDIT,
    ADMIN_USERS, ADMIN_CONFIG,
}

/**
 * Role to permission matrix. Compliance has VIEW_FINANCIALS but no credit/approval permission (read-only financial
 * data). System Admin has configuration permissions only: it can neither read applications nor decide on them.
 */
object RolePolicy {
    private val matrix: Map<Role, Set<Permission>> = mapOf(
        Role.CUSTOMER to setOf(Permission.APPLY, Permission.UPLOAD_DOCUMENT, Permission.RESPOND_INFO, Permission.VIEW_OWN_CASES),
        Role.KYC_ANALYST to setOf(Permission.VIEW_QUEUE, Permission.RUN_KYC_STEPS, Permission.KYC_DECIDE, Permission.RAISE_INFO_REQUEST, Permission.VIEW_AUDIT),
        Role.CREDIT_OFFICER to setOf(Permission.VIEW_QUEUE, Permission.CREDIT_REVIEW, Permission.RAISE_INFO_REQUEST, Permission.VIEW_FINANCIALS, Permission.VIEW_AUDIT),
        Role.BRANCH_MANAGER to setOf(Permission.VIEW_QUEUE, Permission.VIEW_PIPELINE, Permission.FINAL_DECIDE, Permission.REASSIGN, Permission.VIEW_FINANCIALS, Permission.VIEW_AUDIT),
        Role.COMPLIANCE_OFFICER to setOf(Permission.VIEW_QUEUE, Permission.SCREENING_DECIDE, Permission.RAISE_INFO_REQUEST, Permission.VIEW_FINANCIALS, Permission.VIEW_AUDIT),
        Role.SYSTEM_ADMIN to setOf(Permission.ADMIN_USERS, Permission.ADMIN_CONFIG),
    )

    fun allowed(role: Role): Set<Permission> = matrix[role].orEmpty()
    fun can(role: Role, p: Permission): Boolean = p in allowed(role)
}
