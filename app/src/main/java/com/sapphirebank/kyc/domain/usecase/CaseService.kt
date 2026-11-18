package com.sapphirebank.kyc.domain.usecase

import com.sapphirebank.kyc.domain.model.Actor
import com.sapphirebank.kyc.domain.model.AdminEvent
import com.sapphirebank.kyc.domain.model.ApplicationStatus.*
import com.sapphirebank.kyc.domain.model.ApprovalLevel
import com.sapphirebank.kyc.domain.model.BankConfig
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.CountryRisk
import com.sapphirebank.kyc.domain.model.CustomerApplication
import com.sapphirebank.kyc.domain.model.Decision
import com.sapphirebank.kyc.domain.model.DocType
import com.sapphirebank.kyc.domain.model.DocumentKind
import com.sapphirebank.kyc.domain.model.DocumentRecord
import com.sapphirebank.kyc.domain.model.DocumentRequirements
import com.sapphirebank.kyc.domain.model.EligibilityPolicy
import com.sapphirebank.kyc.domain.model.FinancialProfile
import com.sapphirebank.kyc.domain.model.KycThresholds
import com.sapphirebank.kyc.domain.model.Permission
import com.sapphirebank.kyc.domain.model.ProductConfig
import com.sapphirebank.kyc.domain.model.ProductLine
import com.sapphirebank.kyc.domain.model.Role
import com.sapphirebank.kyc.domain.model.RolePolicy
import com.sapphirebank.kyc.domain.model.UserAccount
import com.sapphirebank.kyc.domain.model.VcipSession
import com.sapphirebank.kyc.domain.repository.AuditTamperException
import com.sapphirebank.kyc.domain.repository.CaseRepository
import com.sapphirebank.kyc.domain.repository.CreditBureau
import com.sapphirebank.kyc.domain.rules.AccessPolicy
import com.sapphirebank.kyc.domain.rules.AuditChain
import com.sapphirebank.kyc.domain.validation.ApplicationValidator
import com.sapphirebank.kyc.domain.validation.DocumentValidators
import com.sapphirebank.kyc.domain.validation.FormField
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of a configuration change (System Admin). */
data class ActionResult(val ok: Boolean, val message: String)

data class NewApplication(
    val app: CustomerApplication,
    val documents: List<DocumentRecord>,
    val financials: FinancialProfile? = null,
    val ocrName: String? = null,
    val ocrDob: String? = null,
)

/**
 * The authorization boundary. Every entry point:
 *  1. re-resolves the caller from the user directory (the role the caller claims is never trusted),
 *  2. checks the role's permission,
 *  3. checks the caller may see the application (AccessPolicy),
 *  4. applies a pure CaseWorkflow step and persists it append-only.
 *
 * The UI hides actions a role cannot perform, but hiding is only a convenience: this class is what enforces the rules.
 * It is written to move behind a server unchanged; today it runs on-device because the app has no backend.
 */
class CaseService(
    private val repo: CaseRepository,
    private val bureau: CreditBureau,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val gate = Mutex()
    private fun now(): String = Instant.now(clock).toString()
    private fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata")))
    private val cfg: BankConfig get() = repo.config.value

    // ---- identity ----------------------------------------------------------------------------
    fun resolve(userId: String): Actor? {
        cfg.customers.firstOrNull { it.userId == userId }?.let { return Actor(it.userId, it.displayName, Role.CUSTOMER, "CUSTOMER") }
        val u = cfg.user(userId)?.takeIf { it.active } ?: return null
        val role = Role.fromOfficerRole(u.officerRole) ?: Role.SYSTEM_ADMIN.takeIf { u.officerRole == "SYSTEM_ADMIN" } ?: return null
        return Actor(u.userId, u.displayName, role, u.officerRole, u.delegatedLimitInr)
    }

    /** Everyone who can sign in on the demo sign-in screen, grouped by workspace. */
    fun directory(): Map<Role, List<Actor>> {
        val staff = cfg.users.filter { it.active }.mapNotNull { resolve(it.userId) }
        val customers = cfg.customers.mapNotNull { resolve(it.userId) }
        return (staff + customers).groupBy { it.role }
    }

    // ---- reads -------------------------------------------------------------------------------
    fun observe(claimed: Actor): Flow<List<CaseFile>> = repo.cases.map { all ->
        val a = resolve(claimed.userId)
        if (a == null || a.role != claimed.role) emptyList() else AccessPolicy.visible(a, all).sortedByDescending { it.lastUpdated }
    }

    fun caseFor(claimed: Actor, arn: String): CaseFile? {
        val a = resolve(claimed.userId)?.takeIf { it.role == claimed.role } ?: return null
        return repo.find(arn)?.takeIf { AccessPolicy.canSee(a, it) }
    }

    fun verifyAudit(claimed: Actor, arn: String): AuditChain.Verification? {
        val a = resolve(claimed.userId)?.takeIf { it.role == claimed.role } ?: return null
        if (!RolePolicy.can(a.role, Permission.VIEW_AUDIT) && a.role != Role.CUSTOMER) return null
        return caseFor(claimed, arn)?.let { AuditChain.verify(it.audit) }
    }

    fun adminLog(claimed: Actor): List<AdminEvent> {
        val a = resolve(claimed.userId)?.takeIf { it.role == claimed.role } ?: return emptyList()
        return if (RolePolicy.can(a.role, Permission.ADMIN_CONFIG)) repo.adminLog.value else emptyList()
    }

    // ---- plumbing ----------------------------------------------------------------------------
    private fun deny(what: String, a: Actor) = Step.Refused("${a.role.label} is not permitted to $what")

    private suspend fun act(
        claimed: Actor, perm: Permission?, arn: String?, what: String, block: (Actor, CaseFile?) -> Step,
    ): Step = gate.withLock {
        val actor = resolve(claimed.userId) ?: return@withLock Step.Refused("Unknown or inactive user")
        if (actor.role != claimed.role) return@withLock Step.Refused("Role mismatch for ${claimed.userId}")
        if (perm != null && !RolePolicy.can(actor.role, perm)) return@withLock deny(what, actor)
        var c: CaseFile? = null
        if (arn != null) {
            c = repo.find(arn)
            if (c == null || !AccessPolicy.canSee(actor, c)) return@withLock Step.Refused("Application not found")
        }
        val r = block(actor, c)
        if (r is Step.Ok) {
            try { repo.save(r.case) } catch (e: AuditTamperException) { return@withLock Step.Refused("Integrity check failed: ${e.message}") }
        }
        r
    }

    /** Runs the system-driven stages (AML, eligibility, review creation, auto-approval, account opening) until a human is needed. */
    private fun runPipeline(start: CaseFile): CaseFile {
        var c = start
        var guard = 0
        while (guard++ < 12) {
            val st = c.application.status
            if (st !in setOf(KYC_VERIFIED, AML_SCREENING, AML_CLEARED, ELIGIBILITY_CHECK, ELIGIBLE, APPROVED)) break
            val score = if (st == ELIGIBILITY_CHECK) bureau.score(c.application.panNumber) else null
            val r = CaseWorkflow.advance(c, now(), cfg, score)
            if (r !is Step.Ok) break
            c = r.case
        }
        return c
    }

    // ---- Customer ----------------------------------------------------------------------------
    suspend fun submit(claimed: Actor, req: NewApplication): Step = act(claimed, Permission.APPLY, null, "open an account") { actor, _ ->
        val app = req.app
        val errors = linkedMapOf<FormField, String>()
        errors.putAll(ApplicationValidator.validate(app, today()))
        val prod = cfg.product(app.productLine)
        if (!prod.enabled) return@act Step.Refused("${app.productLine.name} is not currently offered")
        if (app.sanctionAmountInr !in prod.minAmountInr..prod.maxAmountInr && !errors.containsKey(FormField.AMOUNT))
            errors[FormField.AMOUNT] = "Amount must be between ${com.sapphirebank.kyc.domain.model.Inr.format(prod.minAmountInr)} and ${com.sapphirebank.kyc.domain.model.Inr.format(prod.maxAmountInr)}"
        if (app.productLine == ProductLine.RETAIL_LOAN) {
            val f = req.financials
            if (f == null || f.monthlyIncomeInr <= 0) errors[FormField.INCOME] = "Enter your monthly income"
            if (f == null || f.tenureMonths <= 0 || (prod.maxTenureMonths > 0 && f.tenureMonths > prod.maxTenureMonths))
                errors[FormField.TENURE] = "Tenure must be 1 to ${prod.maxTenureMonths} months"
        }
        val missing = DocumentRequirements.missing(app, req.documents)
        if (missing.isNotEmpty()) errors[FormField.DOCUMENTS] = "Upload: " + missing.joinToString { it.label }
        if (errors.isNotEmpty()) return@act Step.Refused("Fix ${errors.size} item(s) before submitting", errors)

        // Aadhaar is vaulted: only the masked form (last 4 digits) is stored on the case.
        val aadhaarUsed = app.identityDoc == DocType.AADHAAR || app.addressDoc == DocType.AADHAAR
        val masked = app.copy(
            identityDocNumber = if (app.identityDoc == DocType.AADHAAR) DocumentValidators.maskedForStorage(app.identityDocNumber) else app.identityDocNumber,
            addressDocNumber = if (app.addressDoc == DocType.AADHAAR) DocumentValidators.maskedForStorage(app.addressDocNumber) else app.addressDocNumber,
            aadhaarVaultRef = if (aadhaarUsed) "VLT-" + UUID.randomUUID().toString().take(8).uppercase() else "",
        )
        val rm = cfg.activeUsers("RELATIONSHIP_MANAGER").firstOrNull()?.userId ?: "U-RM-01"
        val analyst = pickAnalyst()
        CaseWorkflow.submit(
            masked, rm, req.ocrName, now(), today(), repo.nextArnSequence(),
            createdBy = actor.userId, createdByRole = "CUSTOMER", documents = req.documents, financials = req.financials,
            ocrDob = req.ocrDob, assignedAnalystId = analyst, validated = true, cfg = cfg,
        )
    }

    private fun pickAnalyst(): String? {
        val analysts = cfg.activeUsers("KYC_OPERATIONS_OFFICER")
        val load = repo.cases.value.filter { !it.application.status.terminal }.groupingBy { it.assignedAnalystId }.eachCount()
        return analysts.minByOrNull { load[it.userId] ?: 0 }?.userId
    }

    suspend fun respondInfo(claimed: Actor, arn: String, requestId: String, response: String, docs: List<DocumentRecord>): Step =
        act(claimed, Permission.RESPOND_INFO, arn, "respond to information requests") { actor, c ->
            val r = CaseWorkflow.respondInfo(c!!, actor.userId, requestId, response, docs.map { it.copy(uploadedBy = actor.userId) }, now())
            if (r is Step.Ok) Step.Ok(CaseWorkflow.tryAutoApprove(r.case, now())).let { s -> Step.Ok(runPipeline(s.case)) } else r
        }

    suspend fun withdraw(claimed: Actor, arn: String): Step =
        act(claimed, Permission.VIEW_OWN_CASES, arn, "withdraw an application") { actor, c ->
            if (actor.role != Role.CUSTOMER) deny("withdraw an application", actor) else CaseWorkflow.withdraw(c!!, now(), actor.label())
        }

    // ---- KYC Analyst -------------------------------------------------------------------------
    suspend fun startKyc(claimed: Actor, arn: String): Step =
        act(claimed, Permission.RUN_KYC_STEPS, arn, "start KYC") { a, c -> CaseWorkflow.startKyc(c!!, now(), a.label(), cfg) }

    suspend fun searchCkycr(claimed: Actor, arn: String): Step =
        act(claimed, Permission.RUN_KYC_STEPS, arn, "search CKYCR") { _, c -> CaseWorkflow.searchCkycr(c!!, now(), cfg) }

    suspend fun recordVcip(claimed: Actor, arn: String, session: VcipSession): Step =
        act(claimed, Permission.RUN_KYC_STEPS, arn, "record a V-CIP session") { _, c ->
            val official = cfg.activeUsers("VCIP_OFFICIAL").firstOrNull()?.userId ?: "U-VCP-01"
            CaseWorkflow.recordVcip(c!!, session, official, now(), cfg)
        }

    suspend fun kycApprove(claimed: Actor, arn: String, comment: String, manualCompared: Boolean): Step =
        act(claimed, Permission.KYC_DECIDE, arn, "approve KYC") { a, c ->
            val r = CaseWorkflow.kycApprove(c!!, a.userId, comment, manualCompared, now(), cfg)
            if (r is Step.Ok) Step.Ok(runPipeline(r.case)) else r
        }

    suspend fun kycReject(claimed: Actor, arn: String, comment: String): Step =
        act(claimed, Permission.KYC_DECIDE, arn, "reject KYC") { a, c -> CaseWorkflow.kycReject(c!!, a.userId, comment, now(), cfg) }

    /** KYC Analyst, Credit Officer and Compliance Officer can ask the customer for more information or documents. */
    suspend fun requestInfo(claimed: Actor, arn: String, message: String, kinds: List<DocumentKind>): Step =
        act(claimed, Permission.RAISE_INFO_REQUEST, arn, "request information") { a, c ->
            CaseWorkflow.requestInfo(c!!, a.userId, a.officerRole, message, kinds, now())
        }

    // ---- Compliance --------------------------------------------------------------------------
    suspend fun screeningDecision(claimed: Actor, arn: String, clear: Boolean, note: String): Step =
        act(claimed, Permission.SCREENING_DECIDE, arn, if (clear) "clear a flagged application" else "place an application on hold") { a, c ->
            val r = CaseWorkflow.screeningDecision(c!!, a.userId, clear, note, now())
            if (r is Step.Ok) Step.Ok(runPipeline(r.case)) else r
        }

    // ---- Credit Officer / Branch Manager / Senior Management ---------------------------------
    suspend fun signOff(claimed: Actor, arn: String, decision: Decision, justification: String): Step =
        act(claimed, null, arn, "record a decision") { a, c ->
            val level = when (a.officerRole) {
                "CREDIT_OFFICER" -> ApprovalLevel.L1_CREDIT_OFFICER
                "BRANCH_MANAGER" -> ApprovalLevel.L2_BRANCH_MANAGER
                "SENIOR_MANAGEMENT" -> ApprovalLevel.L3_SENIOR_MANAGEMENT
                else -> return@act deny("record an approval decision", a)
            }
            val need = if (level == ApprovalLevel.L1_CREDIT_OFFICER) Permission.CREDIT_REVIEW else Permission.FINAL_DECIDE
            if (!RolePolicy.can(a.role, need)) return@act deny("record an approval decision", a)
            val r = CaseWorkflow.signOff(c!!, level, a.toOfficer(), decision, now(), justification, cfg)
            if (r is Step.Ok) Step.Ok(runPipeline(r.case)) else r
        }

    suspend fun reassign(claimed: Actor, arn: String, newAnalystId: String, reason: String): Step =
        act(claimed, Permission.REASSIGN, arn, "reassign applications") { a, c ->
            val target = cfg.user(newAnalystId)?.takeIf { it.active && it.officerRole == "KYC_OPERATIONS_OFFICER" }
                ?: return@act Step.Refused("Choose an active KYC Analyst")
            if (reason.trim().length < CaseWorkflow.MIN_NOTE_CHARS) return@act Step.Refused("Give a reason of at least ${CaseWorkflow.MIN_NOTE_CHARS} characters")
            CaseWorkflow.reassign(c!!, target.userId, a.userId, reason, now())
        }

    // ---- System Admin (configuration only; never touches an application) ----------------------
    private suspend fun admin(claimed: Actor, perm: Permission, action: String, detail: String, change: (BankConfig, Actor) -> Pair<BankConfig?, String>): ActionResult =
        gate.withLock {
            val actor = resolve(claimed.userId) ?: return@withLock ActionResult(false, "Unknown or inactive user")
            if (actor.role != claimed.role) return@withLock ActionResult(false, "Role mismatch for ${claimed.userId}")
            if (!RolePolicy.can(actor.role, perm)) return@withLock ActionResult(false, "${actor.role.label} is not permitted to $action")
            val (next, message) = change(cfg, actor)
            if (next == null) return@withLock ActionResult(false, message)
            repo.updateConfig(next, AdminEvent(now(), actor.label(), action, detail))
            ActionResult(true, message)
        }

    suspend fun setUserActive(claimed: Actor, userId: String, active: Boolean): ActionResult =
        admin(claimed, Permission.ADMIN_USERS, if (active) "Activate user" else "Deactivate user", userId) { c, a ->
            val u = c.user(userId) ?: return@admin null to "Unknown user"
            if (!active && userId == a.userId) return@admin null to "You cannot deactivate your own account"
            if (!active && c.users.count { it.active && it.officerRole == u.officerRole && it.userId != userId } == 0 && u.officerRole in MANDATORY_ROLES)
                return@admin null to "At least one active ${u.officerRole.replace('_', ' ').lowercase()} is required"
            c.copy(users = c.users.map { if (it.userId == userId) it.copy(active = active) else it }) to "${u.displayName} ${if (active) "activated" else "deactivated"}"
        }

    suspend fun addUser(claimed: Actor, name: String, officerRole: String, limitInr: Long): ActionResult =
        admin(claimed, Permission.ADMIN_USERS, "Add user", "$name / $officerRole") { c, _ ->
            if (name.trim().length < 3) return@admin null to "Enter the user's full name"
            if (officerRole !in ASSIGNABLE_ROLES) return@admin null to "Unknown role"
            val prefix = officerRole.split('_').joinToString("") { it.first().toString() }.take(3)
            val n = c.users.count { it.officerRole == officerRole } + 1
            val id = "U-$prefix-%02d".format(n)
            if (c.user(id) != null) return@admin null to "User id $id already exists"
            c.copy(users = c.users + UserAccount(id, name.trim(), officerRole, if (limitInr > 0) limitInr else 0)) to "Added ${name.trim()} as $id"
        }

    suspend fun updateThresholds(claimed: Actor, t: KycThresholds): ActionResult =
        admin(claimed, Permission.ADMIN_CONFIG, "Update KYC rule thresholds", t.toString()) { c, _ ->
            val bad = when {
                t.nameMatchMin !in 0.5..1.0 || t.faceMatchMinKyc008 !in 0.5..1.0 || t.faceMatchMinVcip !in 0.5..1.0 -> "Match scores must be between 0.50 and 1.00"
                t.minRandomQuestions !in 1..10 -> "Random questions must be 1 to 10"
                t.limitedKycDepositCapInr <= 0 || t.limitedKycLoanCapInr <= 0 -> "Caps must be positive"
                t.deemedOvdWindowDays !in 1..365 -> "Deemed-OVD window must be 1 to 365 days"
                else -> null
            }
            if (bad != null) null to bad else c.copy(thresholds = t) to "KYC rule thresholds saved"
        }

    suspend fun updateProduct(claimed: Actor, p: ProductConfig): ActionResult =
        admin(claimed, Permission.ADMIN_CONFIG, "Update product", p.product.name) { c, _ ->
            when {
                p.minAmountInr <= 0 || p.maxAmountInr < p.minAmountInr -> null to "Maximum must be at least the minimum"
                p.annualRatePct < 0 || p.annualRatePct > 40 -> null to "Rate must be between 0 and 40%"
                p.maxTenureMonths < 0 -> null to "Tenure cannot be negative"
                else -> c.copy(products = c.products.map { if (it.product == p.product) p else it }) to "${p.product.name} saved"
            }
        }

    suspend fun setCountryRisk(claimed: Actor, code: String, risk: CountryRisk): ActionResult =
        admin(claimed, Permission.ADMIN_CONFIG, "Set country risk", "$code=$risk") { c, _ ->
            val k = code.trim().uppercase()
            if (!Regex("^[A-Z]{2}$").matches(k)) null to "Use a two-letter country code, e.g. IN"
            else c.copy(countryRisk = c.countryRisk + (k to risk)) to "$k set to ${risk.label} risk"
        }

    suspend fun addReference(claimed: Actor, kind: ReferenceKind, value: String): ActionResult =
        admin(claimed, Permission.ADMIN_CONFIG, "Add reference data", "${kind.name}: $value") { c, _ ->
            val v = value.trim()
            val list = if (kind == ReferenceKind.BRANCH) c.branches else c.employmentTypes
            when {
                v.length < 2 -> null to "Enter a value"
                list.any { it.equals(v, true) } -> null to "$v already exists"
                kind == ReferenceKind.BRANCH -> c.copy(branches = c.branches + v) to "Branch added"
                else -> c.copy(employmentTypes = c.employmentTypes + v) to "Employment type added"
            }
        }

    suspend fun removeReference(claimed: Actor, kind: ReferenceKind, value: String): ActionResult =
        admin(claimed, Permission.ADMIN_CONFIG, "Remove reference data", "${kind.name}: $value") { c, _ ->
            val list = if (kind == ReferenceKind.BRANCH) c.branches else c.employmentTypes
            when {
                list.size <= 1 -> null to "At least one entry must remain"
                kind == ReferenceKind.BRANCH -> c.copy(branches = c.branches - value) to "Branch removed"
                else -> c.copy(employmentTypes = c.employmentTypes - value) to "Employment type removed"
            }
        }

    suspend fun updateEligibilityPolicy(claimed: Actor, p: EligibilityPolicy): ActionResult =
        admin(claimed, Permission.ADMIN_CONFIG, "Update eligibility policy", p.toString()) { c, _ ->
            if (p.maxDtiPct !in 10..100 || p.referDtiPct < p.maxDtiPct || p.referDtiPct > 100) null to "DTI limits must satisfy 10 <= max <= refer <= 100"
            else if (p.minBureauScore !in 300..900 || p.referBureauScore !in 300..p.minBureauScore) null to "Bureau limits must satisfy 300 <= refer <= min <= 900"
            else c.copy(eligibility = p) to "Eligibility policy saved"
        }

    enum class ReferenceKind { BRANCH, EMPLOYMENT }

    companion object {
        val MANDATORY_ROLES = setOf("KYC_OPERATIONS_OFFICER", "CREDIT_OFFICER", "BRANCH_MANAGER", "COMPLIANCE_OFFICER", "SYSTEM_ADMIN")
        val ASSIGNABLE_ROLES = listOf(
            "KYC_OPERATIONS_OFFICER", "CREDIT_OFFICER", "BRANCH_MANAGER", "SENIOR_MANAGEMENT", "COMPLIANCE_OFFICER", "SYSTEM_ADMIN",
        )
    }
}
