package com.sapphirebank.kyc

import android.app.Application
import com.sapphirebank.kyc.data.InMemoryCaseRepository
import com.sapphirebank.kyc.data.MlKitTextRecognizer
import com.sapphirebank.kyc.domain.repository.CaseRepository
import com.sapphirebank.kyc.domain.usecase.*

/** Manual dependency-injection container (swap for Hilt/Koin if the project grows). */
class AppContainer(app: Application) {
    val repository: CaseRepository = InMemoryCaseRepository()
    private val workflow = Workflow(repository)

    val observeCase = ObserveCase(repository)
    val submitApplication = SubmitApplication(repository, workflow)
    val scanDocument = ScanDocument(MlKitTextRecognizer(app))
    val startKyc = StartKyc(workflow)
    val searchCkycr = SearchCkycr(workflow)
    val recordVcipSession = RecordVcipSession(repository, workflow)
    val completeKyc = CompleteKyc(workflow)
    val advanceStage = AdvanceStage(workflow)
    val signOff = SignOff(workflow)
    val withdraw = WithdrawApplication(workflow)
    val verifyAuditChain = VerifyAuditChain()
    val simulateAuditTamper = SimulateAuditTamper(repository)
    val resetCase = ResetCase(repository)
}

class KycApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
