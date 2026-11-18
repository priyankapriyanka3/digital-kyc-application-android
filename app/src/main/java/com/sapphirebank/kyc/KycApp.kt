package com.sapphirebank.kyc

import android.app.Application
import com.sapphirebank.kyc.data.InMemoryCaseRepository
import com.sapphirebank.kyc.data.MlKitTextRecognizer
import com.sapphirebank.kyc.data.SeedData
import com.sapphirebank.kyc.data.SimulatedBureau
import com.sapphirebank.kyc.domain.repository.CaseRepository
import com.sapphirebank.kyc.domain.repository.DocumentTextRecognizer
import com.sapphirebank.kyc.domain.usecase.CaseService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LoadState {
    data object Loading : LoadState
    data object Ready : LoadState
    data class Failed(val message: String) : LoadState
}

/**
 * Manual dependency-injection container (swap for Hilt/Koin if the project grows).
 * Demo data is created through [CaseService] in the background so the first screen can show a loading state.
 */
class AppContainer(app: Application, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
    val repository: CaseRepository = InMemoryCaseRepository()
    val service = CaseService(repository, SimulatedBureau())
    val recognizer: DocumentTextRecognizer = MlKitTextRecognizer(app)

    private val loadState = MutableStateFlow<LoadState>(LoadState.Loading)
    val load: StateFlow<LoadState> = loadState.asStateFlow()

    init { seed() }

    fun retry() = seed()

    private fun seed() {
        loadState.value = LoadState.Loading
        scope.launch {
            loadState.value = try {
                if (repository.cases.value.isEmpty()) SeedData.populate(service)
                LoadState.Ready
            } catch (e: Throwable) {
                LoadState.Failed(e.message ?: "Could not prepare demo data")
            }
        }
    }
}

class KycApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
