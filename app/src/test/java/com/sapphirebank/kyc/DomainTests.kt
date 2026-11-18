package com.sapphirebank.kyc

import com.sapphirebank.kyc.data.SeedData
import com.sapphirebank.kyc.domain.model.*
import com.sapphirebank.kyc.domain.rules.*
import com.sapphirebank.kyc.domain.usecase.CaseWorkflow
import com.sapphirebank.kyc.domain.usecase.Step
import com.sapphirebank.kyc.domain.validation.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DomainTests {
    private val today = LocalDate.of(2026, 11, 17)
    private val now = "2026-11-17T10:00:00Z"
    private fun off(role: String) = SeedData.officers.first { it.role == role }

    private fun app(
        mode: KycMode = KycMode.IN_PERSON, product: ProductLine = ProductLine.SAVINGS_ACCOUNT,
        amount: Long = 50_000, pep: Boolean = false, pan: String = "ABCDE1234F",
        id: DocType = DocType.AADHAAR, idNo: String = "234567890124",
        consent: Boolean = false, nonInd: Boolean = false, bo: Boolean = false,
    ) = CustomerApplication(
        "", "Asha Rao", "15/04/1990", product, amount, pan, false, id, idNo,
        DocType.AADHAAR, "234567890124", mode, consent, pep, nonInd, bo, ApplicationStatus.DRAFT,
    )

    private val goodVcip = VcipSession(true, "IN", true, true, true, true, 4, true, 0.95, true)

    private fun submitted(a: CustomerApplication = app(), ocr: String? = "Asha Rao"): CaseFile =
        (CaseWorkflow.submit(a, "U-RM-01", ocr, now, today, 1) as Step.Ok).case

    // ---- validators ----
    @Test fun panRegex() {
        assertTrue(DocumentValidators.isValidPan("abcde1234f"))
        assertFalse(DocumentValidators.isValidPan("ABCD12345F"))
        assertFalse(DocumentValidators.isValidPan("ABCDE12345"))
    }

    @Test fun aadhaarVerhoeff() {
        assertTrue(DocumentValidators.isValidAadhaar("234567890124"))
        assertTrue(DocumentValidators.isValidAadhaar("2345 6789 0124"))
        assertFalse(DocumentValidators.isValidAadhaar("234567890125"))
        assertFalse(DocumentValidators.isValidAadhaar("034567890124"))
        assertFalse(DocumentValidators.isValidAadhaar("23456789012"))
    }

    @Test fun masking() {
        assertEquals("XXXX XXXX 0124", DocumentValidators.mask(DocType.AADHAAR, "234567890124"))
        assertEquals("AB••••••4F", DocumentValidators.mask(DocType.PAN, "ABCDE1234F"))
    }

    @Test fun nameMatch() {
        assertEquals(1.0, DocumentValidators.nameMatchScore("Asha Rao", "ASHA RAO"), 1e-9)
        assertTrue(DocumentValidators.nameMatchScore("Asha Rao", "Ravi Kumar") < 0.85)
    }

    @Test fun ocrParsesPanCard() {
        val r = OcrExtractor.parse("INCOME TAX DEPARTMENT\nName\nASHA RAO\n15/04/1990\nABCDE1234F")
        assertEquals(DocType.PAN, r.docType)
        assertEquals("ABCDE1234F", r.number)
        assertEquals("ASHA RAO", r.name)
        assertEquals("15/04/1990", r.dateOfBirth)
    }

    @Test fun ocrParsesAadhaar() {
        val r = OcrExtractor.parse("Government of India\nAsha Rao\nDOB: 15/04/1990\n2345 6789 0124")
        assertEquals(DocType.AADHAAR, r.docType)
        assertEquals("234567890124", r.number)
    }

    @Test fun formRejectsMinorAndBadPan() {
        val bad = app(pan = "XX").copy(dateOfBirth = "01/01/2015")
        val e = ApplicationValidator.validate(bad, today)
        assertTrue(FormField.DOB in e && FormField.PAN in e)
    }

    // ---- KYC rules ----
    @Test fun happyPathInPersonPassesAllRules() {
        val c = submitted()
        val k = (CaseWorkflow.startKyc(c, now) as Step.Ok).case
        val done = CaseWorkflow.completeKyc(k, now) as Step.Ok
        assertEquals(ApplicationStatus.KYC_VERIFIED, done.case.application.status)
        assertEquals(9, done.case.evaluations.size)
    }

    @Test fun kyc001FailsWithoutPanOrForm60() {
        val e = KycRuleEngine.evaluate(app(pan = ""), KycEvidence())
        assertEquals(RuleOutcome.FAIL, e.first { it.ruleId == "KYC-001" }.outcome)
    }

    @Test fun kyc004RequiresConsent() {
        val a = app(mode = KycMode.AADHAAR_OTP_EKYC, consent = false)
        assertTrue(ApplicationValidator.validate(a, today).containsKey(FormField.CONSENT))
        val e = KycRuleEngine.evaluate(a, KycEvidence())
        assertEquals(RuleOutcome.FAIL, e.first { it.ruleId == "KYC-004" }.outcome)
    }

    @Test fun kyc006VcipStandards() {
        val a = app(mode = KycMode.VCIP)
        val pass = KycRuleEngine.evaluate(a, KycEvidence(vcip = goodVcip)).first { it.ruleId == "KYC-006" }
        assertEquals(RuleOutcome.PASS, pass.outcome)
        val bad = goodVcip.copy(livenessPassed = false, randomQuestionsAsked = 2, ipCountryCode = "US")
        val fail = KycRuleEngine.evaluate(a, KycEvidence(vcip = bad)).first { it.ruleId == "KYC-006" }
        assertEquals(RuleOutcome.FAIL, fail.outcome)
        assertTrue(fail.detail.contains("liveness") && fail.detail.contains("2/3"))
        val pending = KycRuleEngine.evaluate(a, KycEvidence()).first { it.ruleId == "KYC-006" }
        assertEquals(RuleOutcome.PENDING, pending.outcome)
    }

    @Test fun kyc007LimitedKycCap() {
        val over = app(mode = KycMode.AADHAAR_OTP_EKYC, consent = true, amount = 150_000)
        assertEquals(RuleOutcome.FAIL, KycRuleEngine.evaluate(over, KycEvidence()).first { it.ruleId == "KYC-007" }.outcome)
        val ok = app(mode = KycMode.AADHAAR_OTP_EKYC, consent = true, amount = 90_000)
        assertEquals(RuleOutcome.PASS, KycRuleEngine.evaluate(ok, KycEvidence()).first { it.ruleId == "KYC-007" }.outcome)
    }

    @Test fun kyc008FallsBackToVcipWhenNoScan() {
        val s = VcipSession(true, "IN", true, true, true, true, 3, true, 0.95, true)
        assertEquals(RuleOutcome.PENDING, KycRuleEngine.evaluate(app(), KycEvidence()).first { it.ruleId == "KYC-008" }.outcome)
        assertEquals(RuleOutcome.PASS, KycRuleEngine.evaluate(app(), KycEvidence(vcip = s)).first { it.ruleId == "KYC-008" }.outcome)
        assertEquals(RuleOutcome.REFER, KycRuleEngine.evaluate(app(), KycEvidence(vcip = s.copy(panCapturedLive = false))).first { it.ruleId == "KYC-008" }.outcome)
    }

    @Test fun kyc008NameMismatchRefers() {
        val e = KycRuleEngine.evaluate(app(), KycEvidence(ocrName = "Ravi Kumar")).first { it.ruleId == "KYC-008" }
        assertEquals(RuleOutcome.REFER, e.outcome)
    }

    @Test fun kyc009BeneficialOwner() {
        val a = app(product = ProductLine.CURRENT_ACCOUNT, nonInd = true, bo = false)
        assertEquals(RuleOutcome.FAIL, KycRuleEngine.evaluate(a, KycEvidence()).first { it.ruleId == "KYC-009" }.outcome)
        assertEquals(RuleOutcome.NOT_APPLICABLE, KycRuleEngine.evaluate(app(), KycEvidence()).first { it.ruleId == "KYC-009" }.outcome)
    }

    @Test fun blockingFailureRejectsApplication() {
        val c = submitted(app(product = ProductLine.CURRENT_ACCOUNT, nonInd = true, bo = false))
        val k = (CaseWorkflow.startKyc(c, now) as Step.Ok).case
        val r = (CaseWorkflow.completeKyc(k, now) as Step.Ok).case
        assertEquals(ApplicationStatus.REJECTED, r.application.status)
        assertEquals(StepState.FAILED, ApplicationStateMachine.progress(r.application.status, r.failedAt)[2].state)
    }

    // ---- state machine / stepper ----
    @Test fun transitionTable() {
        assertTrue(ApplicationStateMachine.canMove(ApplicationStatus.AML_SCREENING, ApplicationStatus.EDD_PENDING))
        assertFalse(ApplicationStateMachine.canMove(ApplicationStatus.DRAFT, ApplicationStatus.APPROVED))
    }

    @Test fun stepperHasEightStages() {
        val p = ApplicationStateMachine.progress(ApplicationStatus.KYC_IN_PROGRESS)
        assertEquals(8, p.size)
        assertEquals(StepState.DONE, p[0].state)
        assertEquals(StepState.DONE, p[1].state)
        assertEquals(StepState.ACTIVE, p[2].state)
        assertEquals(StepState.PENDING, p[3].state)
        assertEquals(StepState.ACTIVE, p[7].state)
    }

    // ---- audit chain ----
    @Test fun auditChainIsTamperEvident() {
        val c = submitted()
        assertTrue(c.audit.size >= 3)
        assertEquals(AuditChain.GENESIS, c.audit.first().previousEventHash)
        assertTrue(AuditChain.verify(c.audit).valid)
        val tampered = c.audit.toMutableList().also { it[1] = it[1].copy(eventType = "Forged") }
        val v = AuditChain.verify(tampered)
        assertFalse(v.valid)
        assertEquals(2, v.brokenAtSequence)
        val dropped = c.audit.filterIndexed { i, _ -> i != 1 }
        assertFalse(AuditChain.verify(dropped).valid)
    }

    // ---- dual control ----
    private fun pendingReview(a: CustomerApplication = app(), rm: String = "U-RM-01"): CaseFile {
        var c = submitted(a)
        c = (CaseWorkflow.startKyc(c, now) as Step.Ok).case
        c = (CaseWorkflow.completeKyc(c, now) as Step.Ok).case
        var guard = 0
        while (c.application.status != ApplicationStatus.PENDING_REVIEW && guard++ < 12) {
            c = (CaseWorkflow.advance(c, now) as Step.Ok).case
        }
        assertEquals(ApplicationStatus.PENDING_REVIEW, c.application.status)
        return c
    }

    @Test fun dualControlHappyPath() {
        var c = pendingReview()
        val l2first = CaseWorkflow.signOff(c, ApprovalLevel.L2_BRANCH_MANAGER, off("BRANCH_MANAGER"), Decision.APPROVED, now)
        assertTrue((l2first as Step.Refused).reason.contains("DCS-002"))
        c = (CaseWorkflow.signOff(c, ApprovalLevel.L1_CREDIT_OFFICER, off("CREDIT_OFFICER"), Decision.APPROVED, now) as Step.Ok).case
        assertEquals(ApplicationStatus.PENDING_REVIEW, c.application.status)
        c = (CaseWorkflow.signOff(c, ApprovalLevel.L2_BRANCH_MANAGER, off("BRANCH_MANAGER"), Decision.APPROVED, now) as Step.Ok).case
        assertEquals(ApplicationStatus.APPROVED, c.application.status)
        assertTrue(DualControlEngine.evaluate(c).filter { it.ruleId != "DCS-004" }.all { it.outcome == RuleOutcome.PASS || it.ruleId == "DCS-002" })
        assertTrue(AuditChain.verify(c.audit).valid)
        c = (CaseWorkflow.advance(c, now) as Step.Ok).case
        assertEquals(ApplicationStatus.FULFILLED, c.application.status)
    }

    @Test fun wrongRoleAndLimitRefused() {
        val c = pendingReview(app(amount = 3_000_000, product = ProductLine.RETAIL_LOAN))
        val wrongRole = CaseWorkflow.signOff(c, ApprovalLevel.L1_CREDIT_OFFICER, off("BRANCH_MANAGER"), Decision.APPROVED, now)
        assertTrue(wrongRole is Step.Refused)
        val overLimit = CaseWorkflow.signOff(c, ApprovalLevel.L1_CREDIT_OFFICER, off("CREDIT_OFFICER"), Decision.APPROVED, now)
        assertTrue((overLimit as Step.Refused).reason.contains("limit"))
    }

    @Test fun segregationOfDuties() {
        val c = pendingReview()
        val rmAsApprover = off("CREDIT_OFFICER").copy(userId = c.sourcingRmId)
        val r = CaseWorkflow.signOff(c, ApprovalLevel.L1_CREDIT_OFFICER, rmAsApprover, Decision.APPROVED, now)
        assertTrue((r as Step.Refused).reason.contains("DCS-003"))
        // the same person cannot sign two levels (DCS-001)
        val l1 = (CaseWorkflow.signOff(c, ApprovalLevel.L1_CREDIT_OFFICER, off("CREDIT_OFFICER"), Decision.APPROVED, now) as Step.Ok).case
        val dup = off("BRANCH_MANAGER").copy(userId = "U-CRD-01")
        assertTrue((CaseWorkflow.signOff(l1, ApprovalLevel.L2_BRANCH_MANAGER, dup, Decision.APPROVED, now) as Step.Refused).reason.contains("DCS-001"))
    }

    @Test fun pepNeedsSeniorManagement() {
        var c = pendingReview(app(pep = true))
        assertEquals(RiskCategory.HIGH, c.risk!!.category)
        assertTrue(ApprovalLevel.L3_SENIOR_MANAGEMENT in DualControlEngine.requiredLevels(c))
        c = (CaseWorkflow.signOff(c, ApprovalLevel.L1_CREDIT_OFFICER, off("CREDIT_OFFICER"), Decision.APPROVED, now) as Step.Ok).case
        c = (CaseWorkflow.signOff(c, ApprovalLevel.L2_BRANCH_MANAGER, off("BRANCH_MANAGER"), Decision.APPROVED, now) as Step.Ok).case
        assertEquals(ApplicationStatus.PENDING_REVIEW, c.application.status) // DCS-004 holds it
        c = (CaseWorkflow.signOff(c, ApprovalLevel.L3_SENIOR_MANAGEMENT, off("SENIOR_MANAGEMENT"), Decision.APPROVED, now) as Step.Ok).case
        assertEquals(ApplicationStatus.APPROVED, c.application.status)
    }

    @Test fun tamperedSignOffDetected() {
        var c = pendingReview()
        c = (CaseWorkflow.signOff(c, ApprovalLevel.L1_CREDIT_OFFICER, off("CREDIT_OFFICER"), Decision.APPROVED, now) as Step.Ok).case
        val forged = c.copy(approvals = c.approvals.map { it.copy(decidedAt = "2020-01-01T00:00:00Z") })
        assertEquals(RuleOutcome.FAIL, DualControlEngine.evaluate(forged).first { it.ruleId == "DCS-006" }.outcome)
    }

    @Test fun rejectionTerminatesAtReview() {
        var c = pendingReview()
        c = (CaseWorkflow.signOff(c, ApprovalLevel.L1_CREDIT_OFFICER, off("CREDIT_OFFICER"), Decision.REJECTED, now) as Step.Ok).case
        assertEquals(ApplicationStatus.REJECTED, c.application.status)
        assertEquals(StepState.FAILED, ApplicationStateMachine.progress(c.application.status, c.failedAt)[5].state)
    }
}
