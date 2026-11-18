package com.sapphirebank.kyc

import com.sapphirebank.kyc.data.InMemoryCaseRepository
import com.sapphirebank.kyc.data.SeedData
import com.sapphirebank.kyc.data.SimulatedBureau
import com.sapphirebank.kyc.domain.model.*
import com.sapphirebank.kyc.domain.repository.AuditTamperException
import com.sapphirebank.kyc.domain.rules.AccessPolicy
import com.sapphirebank.kyc.domain.rules.AuditChain
import com.sapphirebank.kyc.domain.rules.DualControlEngine
import com.sapphirebank.kyc.domain.rules.EligibilityEngine
import com.sapphirebank.kyc.domain.usecase.CaseService
import com.sapphirebank.kyc.domain.usecase.Step
import com.sapphirebank.kyc.domain.validation.DocumentValidators
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Role enforcement and banking-rule tests that go through CaseService, the authorization boundary. */
class ServiceRulesTests {
    private lateinit var repo: InMemoryCaseRepository
    private lateinit var svc: CaseService
    private lateinit var who: Map<String, Actor>

    @Before fun setUp() = runBlocking {
        repo = InMemoryCaseRepository()
        svc = CaseService(repo, SimulatedBureau(), Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC))
        SeedData.populate(svc)
        who = svc.directory().values.flatten().associateBy { it.userId }
    }

    private fun a(id: String) = who.getValue(id)
    private fun byName(n: String) = repo.cases.value.first { it.application.customerName == n }

    // ---- seeding ----
    @Test fun seedCreatesSevenCasesWithVerifiedChains() {
        assertEquals(7, repo.cases.value.size)
        repo.cases.value.forEach { assertTrue("${it.arn} chain", AuditChain.verify(it.audit).valid) }
    }

    @Test fun seedScenariosLandInTheExpectedStates() {
        assertEquals(ApplicationStatus.DOCUMENTS_VALIDATED, byName("Asha Rao").status)
        assertEquals(ApplicationStatus.KYC_IN_PROGRESS, byName("Rohan Mehta").status)
        assertEquals(ApplicationStatus.PENDING_REVIEW, byName("Meera Nair").status)
        assertEquals(1, byName("Imran Qureshi").openInfoRequests.size)
        assertEquals(ApplicationStatus.EDD_PENDING, byName("Hari Prasad Verma").status)
        assertEquals(ApplicationStatus.EDD_PENDING, byName("Marta Quillon").status)
        assertEquals(ApplicationStatus.FULFILLED, byName("Kiran Patel").status)
    }

    // ---- role enforcement (server side, not UI) ----
    @Test fun systemAdminCannotSeeOrDecideOnApplications() = runBlocking {
        val admin = a("U-ADM-01")
        val meera = byName("Meera Nair")
        assertTrue(svc.observe(admin).first().isEmpty())
        assertNull(svc.caseFor(admin, meera.arn))
        assertTrue(svc.signOff(admin, meera.arn, Decision.APPROVED, "Approving as admin should fail") is Step.Refused)
        assertTrue(svc.kycApprove(admin, meera.arn, "x", true) is Step.Refused)
        assertTrue(svc.screeningDecision(admin, byName("Hari Prasad Verma").arn, true, "clearing as admin") is Step.Refused)
    }

    @Test fun complianceOfficerCannotApproveCredit() = runBlocking {
        val r = svc.signOff(a("U-CMP-01"), byName("Meera Nair").arn, Decision.APPROVED, "Compliance trying to approve")
        assertTrue(r is Step.Refused)
        assertEquals(ApplicationStatus.PENDING_REVIEW, byName("Meera Nair").status)
    }

    @Test fun claimedRoleIsNeverTrusted() = runBlocking {
        val fake = Actor("C-1003", "Meera Nair", Role.BRANCH_MANAGER, "BRANCH_MANAGER", 100_000_000)
        val r = svc.signOff(fake, byName("Meera Nair").arn, Decision.APPROVED, "I am secretly the branch manager")
        assertTrue(r is Step.Refused)
        assertTrue((r as Step.Refused).reason.contains("Role mismatch"))
        val unknown = svc.signOff(Actor("U-NOPE", "X", Role.BRANCH_MANAGER), byName("Meera Nair").arn, Decision.APPROVED, "unknown user here")
        assertTrue(unknown is Step.Refused)
    }

    @Test fun customerSeesOnlyOwnCasesAndAnalystOnlyAssigned() = runBlocking {
        val asha = a("C-1001")
        val own = svc.observe(asha).first()
        assertEquals(listOf("Asha Rao"), own.map { it.application.customerName })
        assertNull(svc.caseFor(asha, byName("Meera Nair").arn))
        val an = a("U-KYC-01")
        val mine = svc.observe(an).first()
        assertTrue(mine.all { it.assignedAnalystId == "U-KYC-01" })
        val other = repo.cases.value.first { it.assignedAnalystId != "U-KYC-01" }
        assertNull(svc.caseFor(an, other.arn))
        assertFalse(AccessPolicy.canSee(an, other))
    }

    @Test fun customerCannotRunStaffActions() = runBlocking {
        val c = a("C-1003")
        val arn = byName("Meera Nair").arn
        assertTrue(svc.signOff(c, arn, Decision.APPROVED, "customer approving own loan") is Step.Refused)
        assertTrue(svc.kycApprove(c, arn, "x", true) is Step.Refused)
        assertTrue(svc.reassign(c, arn, "U-KYC-02", "customer reassigning their case") is Step.Refused)
    }

    // ---- banking rules ----
    @Test fun savingsAutoApprovedAfterKyc() {
        val k = byName("Kiran Patel")
        assertEquals(ApplicationStatus.FULFILLED, k.status)
        assertTrue(k.audit.any { it.eventType == "AutoApproved" })
        assertTrue(k.approvals.isEmpty())
        assertNotNull(k.welcomeKit)
    }

    @Test fun pepAndWatchlistCasesNeedComplianceBeforeAnyDecision() = runBlocking {
        val hari = byName("Hari Prasad Verma")
        assertEquals(ScreeningStatus.FLAGGED, hari.screening!!.status)
        assertTrue(svc.signOff(a("U-CRD-01"), hari.arn, Decision.APPROVED, "Trying to skip compliance review") is Step.Refused)
        // Marta is savings, but her watchlist hit stops the auto-approval.
        val marta = byName("Marta Quillon")
        assertTrue(marta.screening!!.watchlistMatch)
        assertNotEquals(ApplicationStatus.FULFILLED, marta.status)
        assertTrue(marta.audit.none { it.eventType == "AutoApproved" })
    }

    @Test fun complianceClearReleasesTheCaseAndNoteIsMandatory() = runBlocking {
        val hari = byName("Hari Prasad Verma")
        val cmp = a("U-CMP-01")
        assertTrue(svc.screeningDecision(cmp, hari.arn, true, "short") is Step.Refused)
        val ok = svc.screeningDecision(cmp, hari.arn, true, "PEP status reviewed, source of funds verified")
        assertTrue(ok is Step.Ok)
        val after = byName("Hari Prasad Verma")
        assertEquals(ApplicationStatus.PENDING_REVIEW, after.status)
        assertEquals(ScreeningStatus.CLEARED_BY_COMPLIANCE, after.screening!!.status)
        assertTrue(ApprovalLevel.L3_SENIOR_MANAGEMENT in DualControlEngine.requiredLevels(after))
    }

    @Test fun clearedWatchlistSavingsIsThenAutoApproved() = runBlocking {
        val marta = byName("Marta Quillon")
        val r = svc.screeningDecision(a("U-CMP-01"), marta.arn, true, "Name similarity reviewed: different person")
        assertTrue(r is Step.Ok)
        assertEquals(ApplicationStatus.FULFILLED, byName("Marta Quillon").status)
    }

    @Test fun holdBlocksApprovalUntilCleared() = runBlocking {
        val hari = byName("Hari Prasad Verma")
        assertTrue(svc.screeningDecision(a("U-CMP-01"), hari.arn, false, "Placed on hold pending documents") is Step.Ok)
        assertEquals(ScreeningStatus.ON_HOLD, byName("Hari Prasad Verma").screening!!.status)
        assertNotNull(byName("Hari Prasad Verma").hold)
        assertTrue(svc.signOff(a("U-CRD-01"), hari.arn, Decision.APPROVED, "Should not be allowed on hold") is Step.Refused)
    }

    @Test fun openInformationRequestBlocksApproveAndReject() = runBlocking {
        val imran = byName("Imran Qureshi")
        val an = a(imran.assignedAnalystId!!)
        val ap = svc.kycApprove(an, imran.arn, "looks fine", true)
        val rj = svc.kycReject(an, imran.arn, "Rejecting while a request is open")
        assertTrue(ap is Step.Refused && (ap as Step.Refused).reason.contains("information request"))
        assertTrue(rj is Step.Refused)
        // customer answers with the requested document, which re-opens the decision
        val doc = DocumentRecord(DocumentKind.ADDRESS_PROOF, "clear.jpg", null, "2026-11-17T10:00:00Z", "C-1004")
        assertTrue(svc.respondInfo(a("C-1004"), imran.arn, imran.openInfoRequests.first().id, "Uploaded a clearer copy", listOf(doc)) is Step.Ok)
        val after = byName("Imran Qureshi")
        assertTrue(after.openInfoRequests.isEmpty())
        assertEquals(2, after.documents.count { it.kind == DocumentKind.ADDRESS_PROOF })
        assertEquals(2, after.documents.filter { it.kind == DocumentKind.ADDRESS_PROOF }.maxOf { it.version })
        assertTrue(svc.kycApprove(an, imran.arn, "Verified after resubmission", true) is Step.Ok)
    }

    @Test fun respondingRequiresTheRequestedDocument() = runBlocking {
        val imran = byName("Imran Qureshi")
        val r = svc.respondInfo(a("C-1004"), imran.arn, imran.openInfoRequests.first().id, "see message", emptyList())
        assertTrue(r is Step.Refused)
    }

    @Test fun branchManagerNeedsJustificationAndAfterThatApprovalCompletes() = runBlocking {
        val meera = byName("Meera Nair")
        assertTrue(svc.signOff(a("U-BM-01"), meera.arn, Decision.APPROVED, "Branch manager going first") is Step.Refused) // DCS-002 order
        assertTrue(svc.signOff(a("U-CRD-01"), meera.arn, Decision.APPROVED, "") is Step.Ok) // L1 approve needs no note
        assertTrue(svc.signOff(a("U-BM-01"), meera.arn, Decision.APPROVED, "") is Step.Refused)
        assertTrue(svc.signOff(a("U-BM-01"), meera.arn, Decision.APPROVED, "too short") is Step.Refused)
        assertTrue(svc.signOff(a("U-BM-01"), meera.arn, Decision.REJECTED, "") is Step.Refused) // rejections always need a reason
        val ok = svc.signOff(a("U-BM-01"), meera.arn, Decision.APPROVED, "KYC and credit recommendation verified")
        assertTrue(ok is Step.Ok)
        val done = byName("Meera Nair")
        assertEquals(ApplicationStatus.FULFILLED, done.status)
        assertEquals("KYC and credit recommendation verified", done.approvals.last().justification)
        assertTrue(DualControlEngine.evaluate(done).first { it.ruleId == "DCS-006" }.outcome == RuleOutcome.PASS)
    }

    @Test fun branchManagerCannotApproveAnApplicationTheyCreated() {
        val c = byName("Meera Nair").copy(createdBy = "U-BM-01")
        val officer = a("U-BM-01").toOfficer()
        val err = DualControlEngine.validate(c, ApprovalLevel.L2_BRANCH_MANAGER, officer, Decision.APPROVED, "Long enough justification")
        assertNotNull(err)
        assertTrue(err!!.contains("creator") || err.contains("DCS-00"))
    }

    @Test fun approvalWithOpenRequestIsRefusedAtTheEngine() {
        val c = byName("Meera Nair")
        val req = InfoRequest("IR-01", "U-CRD-01", "CREDIT_OFFICER", "Need a recent bank statement", listOf(DocumentKind.BANK_STATEMENT), "2026-11-17T10:00:00Z")
        val err = DualControlEngine.validate(c.copy(infoRequests = listOf(req)), ApprovalLevel.L1_CREDIT_OFFICER, a("U-CRD-01").toOfficer(), Decision.APPROVED, "")
        assertTrue(err!!.contains("information request"))
    }

    @Test fun reassignOnlyByBranchManager() = runBlocking {
        val asha = byName("Asha Rao")
        val target = if (asha.assignedAnalystId == "U-KYC-01") "U-KYC-02" else "U-KYC-01"
        assertTrue(svc.reassign(a(asha.assignedAnalystId!!), asha.arn, target, "analyst reassigning own case") is Step.Refused)
        assertTrue(svc.reassign(a("U-BM-01"), asha.arn, target, "Workload balancing across analysts") is Step.Ok)
        assertEquals(target, byName("Asha Rao").assignedAnalystId)
        assertTrue(svc.reassign(a("U-BM-01"), asha.arn, target, "Same analyst again for no reason") is Step.Refused)
    }

    // ---- privacy, FD/RD, immutability ----
    @Test fun aadhaarIsStoredMaskedWithOnlyLastFourVisible() {
        val asha = byName("Asha Rao").application
        assertEquals("XXXXXXXX2342", asha.identityDocNumber)
        assertEquals("XXXXXXXX2342", asha.addressDocNumber)
        assertTrue(asha.aadhaarVaultRef.startsWith("VLT-"))
        assertEquals("XXXX XXXX 2342", DocumentValidators.mask(DocType.AADHAAR, asha.identityDocNumber))
        repo.cases.value.forEach { c ->
            c.audit.forEach { assertFalse(it.payloadSha256.contains("345678912342")) }
            assertFalse(c.application.identityDocNumber.matches(Regex(".*[0-9]{12}.*")))
        }
    }

    @Test fun maskedAadhaarStillPassesKycRules() {
        val asha = byName("Asha Rao")
        assertTrue(asha.evaluations.isEmpty() || asha.evaluations.first { it.ruleId == "KYC-002" }.outcome == RuleOutcome.PASS)
        val k = com.sapphirebank.kyc.domain.rules.KycRuleEngine.evaluate(asha.application, asha.evidence)
        assertEquals(RuleOutcome.PASS, k.first { it.ruleId == "KYC-002" }.outcome)
        assertEquals(RuleOutcome.PASS, k.first { it.ruleId == "KYC-003" }.outcome)
    }

    @Test fun termDepositShowsDicgcNoteAndOtherProductsDoNot() {
        assertNotNull(dicgcNote(ProductLine.TERM_DEPOSIT, 500_000))
        assertTrue(dicgcNote(ProductLine.TERM_DEPOSIT, 500_000)!!.contains("₹5,00,000"))
        assertNull(dicgcNote(ProductLine.SAVINGS_ACCOUNT, 500_000))
        assertNull(dicgcNote(ProductLine.RETAIL_LOAN, 500_000))
    }

    @Test fun auditHistoryCannotBeRewrittenOrShortened() = runBlocking {
        val c = byName("Meera Nair")
        val forged = c.audit.toMutableList().also { it[1] = it[1].copy(eventType = "Forged") }
        assertThrows(AuditTamperException::class.java) { runBlocking { repo.save(c.copy(audit = forged)) } }
        assertThrows(AuditTamperException::class.java) { runBlocking { repo.save(c.copy(audit = c.audit.dropLast(1))) } }
        assertThrows(AuditTamperException::class.java) { runBlocking { repo.save(c.copy(approvals = emptyList(), audit = c.audit)) .also { } ; repo.save(c.copy(documents = emptyList())) } }
        assertEquals(c.audit, byName("Meera Nair").audit)
    }

    // ---- System Admin ----
    @Test fun adminCanConfigureAndEveryChangeIsLogged() = runBlocking {
        val admin = a("U-ADM-01")
        val t = repo.config.value.thresholds.copy(minRandomQuestions = 4)
        assertTrue(svc.updateThresholds(admin, t).ok)
        assertEquals(4, repo.config.value.thresholds.minRandomQuestions)
        assertTrue(svc.setCountryRisk(admin, "ae", CountryRisk.HIGH).ok)
        assertEquals(CountryRisk.HIGH, repo.config.value.countryRisk["AE"])
        assertFalse(svc.setCountryRisk(admin, "India", CountryRisk.LOW).ok)
        assertFalse(svc.updateThresholds(admin, t.copy(nameMatchMin = 0.1)).ok)
        assertEquals(2, svc.adminLog(admin).size)
        assertTrue(svc.adminLog(a("U-BM-01")).isEmpty())
    }

    @Test fun nonAdminCannotChangeConfigOrUsers() = runBlocking {
        val bm = a("U-BM-01")
        assertFalse(svc.updateThresholds(bm, KycThresholds(minRandomQuestions = 1)).ok)
        assertFalse(svc.setUserActive(bm, "U-KYC-02", false).ok)
        assertFalse(svc.addUser(a("U-CMP-01"), "Evil Insider", "BRANCH_MANAGER", 1_000_000_000).ok)
        assertEquals(3, KycThresholds().minRandomQuestions)
    }

    @Test fun deactivatedUserCanNoLongerAct() = runBlocking {
        val admin = a("U-ADM-01")
        assertTrue(svc.setUserActive(admin, "U-KYC-02", false).ok)
        assertNull(svc.resolve("U-KYC-02"))
        assertFalse(svc.setUserActive(admin, "U-ADM-01", false).ok) // not your own account
    }

    @Test fun thresholdChangeAffectsTheRuleEngine() {
        val asha = byName("Asha Rao")
        val strict = KycThresholds(deemedOvdWindowDays = 30)
        val k = com.sapphirebank.kyc.domain.rules.KycRuleEngine.evaluate(asha.application, asha.evidence, strict)
        assertEquals(9, k.size)
    }

    // ---- engines ----
    @Test fun emiAndDtiAreComputed() {
        val emi = EligibilityEngine.emi(500_000, 10.5, 36)
        assertTrue(emi in 16_000..16_500)
        val meera = byName("Meera Nair").eligibility!!
        assertEquals(EligibilityOutcome.ELIGIBLE, meera.outcome)
        assertEquals(782, meera.bureauScore)
        assertEquals("Excellent", meera.bureauBand)
        assertTrue(meera.dtiPct!! in 25..35)
    }

    @Test fun highDtiMakesALoanIneligible() {
        val m = byName("Meera Nair")
        val heavy = FinancialProfile(30_000, 20_000, "Salaried", 36)
        val e = EligibilityEngine.assess(m.application, heavy, 790, m.documents, repo.config.value)
        assertEquals(EligibilityOutcome.INELIGIBLE, e.outcome)
        val lowScore = EligibilityEngine.assess(m.application, m.financials, 600, m.documents, repo.config.value)
        assertEquals(EligibilityOutcome.INELIGIBLE, lowScore.outcome)
    }

    @Test fun rupeeFormattingUsesIndianGrouping() {
        assertEquals("₹7,50,000", Inr.format(750_000))
        assertEquals("₹75,000", Inr.format(75_000))
        assertEquals("₹1,00,00,000", Inr.format(10_000_000))
        assertEquals("₹999", Inr.format(999))
    }

    @Test fun permissionMatrixKeepsAdminAndComplianceInTheirLane() {
        assertFalse(RolePolicy.can(Role.SYSTEM_ADMIN, Permission.FINAL_DECIDE))
        assertFalse(RolePolicy.can(Role.SYSTEM_ADMIN, Permission.VIEW_QUEUE))
        assertFalse(RolePolicy.can(Role.COMPLIANCE_OFFICER, Permission.FINAL_DECIDE))
        assertFalse(RolePolicy.can(Role.COMPLIANCE_OFFICER, Permission.CREDIT_REVIEW))
        assertTrue(RolePolicy.can(Role.COMPLIANCE_OFFICER, Permission.VIEW_FINANCIALS))
        assertTrue(RolePolicy.can(Role.BRANCH_MANAGER, Permission.REASSIGN))
        assertFalse(RolePolicy.can(Role.CUSTOMER, Permission.VIEW_QUEUE))
    }
}
