package com.sapphirebank.kyc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toArgb
import com.sapphirebank.kyc.presentation.AppRoot
import com.sapphirebank.kyc.presentation.theme.KycColors
import com.sapphirebank.kyc.presentation.theme.SapphireKycTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(KycColors.Navy.toArgb()))
        val container = (application as KycApp).container
        setContent {
            SapphireKycTheme {
                Surface(color = KycColors.Background) { AppRoot(container) }
            }
        }
    }
}
