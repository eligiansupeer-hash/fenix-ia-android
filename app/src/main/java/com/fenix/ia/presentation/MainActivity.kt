package com.fenix.ia.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.fenix.ia.audit.auditTouches
import com.fenix.ia.presentation.theme.FenixTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FenixTheme {
                var currentRoute by remember { mutableStateOf<String?>(null) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .auditTouches { currentRoute }
                ) {
                    FenixNavHost(onRouteChanged = { currentRoute = it })
                }
            }
        }
    }
}
