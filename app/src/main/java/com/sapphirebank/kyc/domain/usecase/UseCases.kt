package com.sapphirebank.kyc.domain.usecase

import com.sapphirebank.kyc.domain.model.ApprovalLevel
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.CustomerApplication
import com.sapphirebank.kyc.domain.model.Decision
import com.sapphirebank.kyc.domain.model.Officer
import com.sapphirebank.kyc.domain.model.VcipSession
import com.sapphirebank.kyc.domain.repository.CaseRepository
import com.sapphirebank.kyc.domain.repository.DocumentTextRecognizer
import com.sapphirebank.kyc.domain.rules.AuditChain
import com.sapphirebank.kyc.domain.validation.OcrExtractor
import com.sapphirebank.kyc.domain.validation.OcrResult
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Shared plumbing: apply a pure workflow step to the stored case and persist it. */
class Workflow(private val repo: CaseRepository, private val clock: Clock = Clock.systemUTC()) {
    fun nowIso(): String = Instant.now(clock).toString()
    fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata")))

    suspend fun apply(block: (CaseFile) -> Step): Step {
        val cur = repo.current.value ?: return Step.Refused("No application in progress")
        val r = block(cur)
        if (r is Step.Ok) repo.save(r.case)
        return r
    }
}

class SubmitApplication(private val repo: CaseRepository, private val wf: Workflow) {
    suspend operator fun invoke(app: CustomerApplication, ocrName: String?): Step {
        val rm = repo.officers.first { it.role == "RELATIONSHIP_MANAGER" }
        val r = CaseWorkflow.submit(app, rm.userId, ocrName, wf.nowIso(), wf.today(), repo.nextArnSequence())
        if (r is Step.Ok) repo.save(r.case)
        return r
    }
}

class ScanDocument(private val recognizer: DocumentTextRecognizer) {
    suspend operator fun invoke(imageUri: String): OcrResult = OcrExtractor.parse(recognizer.recognize(imageUri))
}

class StartKyc(private val wf: Workflow) {
    suspend operator fun invoke() = wf.apply { CaseWorkflow.startKyc(it, wf.nowIso()) }
}

class SearchCkycr(private val wf: Workflow) {
    suspend operator fun invoke() = wf.apply { CaseWorkflow.searchCkycr(it, wf.nowIso()) }
}

class RecordVcipSession(private val repo: CaseRepository, private val wf: Workflow) {
    suspend operator fun invoke(session: VcipSession): Step {
        val official = repo.officers.first { it.role == "VCIP_OFFICIAL" }
        return wf.apply { CaseWorkflow.recordVcip(it, session, official.userId, wf.nowIso()) }
    }
}

class CompleteKyc(private val wf: Workflow) {
    suspend operator fun invoke() = wf.apply { CaseWorkflow.completeKyc(it, wf.nowIso()) }
}

class AdvanceStage(private val wf: Workflow) {
    suspend operator fun invoke() = wf.apply { CaseWorkflow.advance(it, wf.nowIso()) }
}

class SignOff(private val wf: Workflow) {
    suspend operator fun invoke(level: ApprovalLevel, officer: Officer, decision: Decision) =
        wf.apply { CaseWorkflow.signOff(it, level, officer, decision, wf.nowIso()) }
}

class WithdrawApplication(private val wf: Workflow) {
    suspend operator fun invoke() = wf.apply { CaseWorkflow.withdraw(it, wf.nowIso()) }
}

class VerifyAuditChain {
    operator fun invoke(case: CaseFile) = AuditChain.verify(case.audit)
}

class ResetCase(private val repo: CaseRepository) {
    suspend operator fun invoke() = repo.clear()
}

class ObserveCase(private val repo: CaseRepository) {
    operator fun invoke(): kotlinx.coroutines.flow.StateFlow<CaseFile?> = repo.current
}

/** Demo-only: silently alters the first audit event so the hash-chain verifier can be shown detecting it. */
class SimulateAuditTamper(private val repo: CaseRepository) {
    suspend operator fun invoke() {
        val c = repo.current.value ?: return
        if (c.audit.isEmpty()) return
        val altered = c.audit.toMutableList()
        altered[0] = altered[0].copy(payloadSha256 = "f".repeat(64))
        repo.save(c.copy(audit = altered))
    }
}
