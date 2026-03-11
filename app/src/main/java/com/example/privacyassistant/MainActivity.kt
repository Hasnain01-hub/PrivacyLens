// file not used
package com.example.privacyassistant

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.privacyassistant.ui.theme.PrivacyAssistantTheme
import com.example.privacyassistant.ContactClassificationViewModel

class MainActivity : ComponentActivity() {

    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001

    private val classificationViewModel: ContactClassificationViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ContactClassificationViewModel(application) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "MainActivity started.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }

        enableEdgeToEdge()

        setContent {
            PrivacyAssistantTheme {
                val screenState by classificationViewModel.state.collectAsState()
                val selections  by classificationViewModel.selections.collectAsState()

                when (val s = screenState) {
                    is ClassificationScreenState.Ready -> {
                        // Show classification UI fullscreen
                        ContactClassificationScreen(
                            contacts   = s.contacts,
                            selections = selections,
                            onSelect = classificationViewModel::onSelect,
                            onSave     = classificationViewModel::saveAndFinish
                        )
                    }

                    else -> {
                        // Normal main screen for all other states
                        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                            Column(
                                modifier = Modifier
                                    .padding(innerPadding)
                                    .padding(16.dp)
                            ) {
                                Text("Accessibility Service App")
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }) {
                                    Text("Open Accessibility Settings")
                                }

                                // Dev helper: show current state
                                if (s is ClassificationScreenState.NotDueYet) {
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        "Classification survey will appear after 30 days of use.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}