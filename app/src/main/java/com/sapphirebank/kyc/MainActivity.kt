package com.sapphirebank.kyc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sapphirebank.kyc.presentation.application.ApplicationScreen
import com.sapphirebank.kyc.presentation.application.ApplicationViewModel
import com.sapphirebank.kyc.presentation.common.ViewModelFactory
import com.sapphirebank.kyc.presentation.review.ReviewScreen
import com.sapphirebank.kyc.presentation.review.ReviewViewModel
import com.sapphirebank.kyc.presentation.theme.SapphireKycTheme
import com.sapphirebank.kyc.presentation.tracker.TrackerScreen
import com.sapphirebank.kyc.presentation.tracker.TrackerViewModel

private enum class Tab(val title: String, val icon: ImageVector) {
    APPLICATION("Application", Icons.Filled.AssignmentInd),
    TRACKER("Tracker", Icons.Filled.Timeline),
    REVIEW("Review", Icons.Filled.FactCheck),
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KycApp).container
        val factory = ViewModelFactory(container)

        setContent {
            SapphireKycTheme {
                var tab by rememberSaveable { mutableStateOf(Tab.APPLICATION) }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Sapphire Bank · Digital KYC") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t, onClick = { tab = t },
                                    icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.title) },
                                )
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                ) { pad ->
                    val m = Modifier.padding(pad)
                    // ViewModels are scoped to the activity so state survives tab switches.
                    val appVm: ApplicationViewModel = viewModel(factory = factory)
                    val trackerVm: TrackerViewModel = viewModel(factory = factory)
                    val reviewVm: ReviewViewModel = viewModel(factory = factory)
                    androidx.compose.foundation.layout.Box(m) {
                        when (tab) {
                            Tab.APPLICATION -> ApplicationScreen(appVm)
                            Tab.TRACKER -> TrackerScreen(trackerVm)
                            Tab.REVIEW -> ReviewScreen(reviewVm)
                        }
                    }
                }
            }
        }
    }
}
