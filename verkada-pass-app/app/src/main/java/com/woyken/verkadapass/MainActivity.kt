package com.woyken.verkadapass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.woyken.verkadapass.ui.DoorListScreen
import com.woyken.verkadapass.ui.EmailInputScreen
import com.woyken.verkadapass.ui.MagicLinkInputScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: MainViewModel = viewModel()
                    val state by viewModel.state.collectAsState()

                    when (state.screen) {
                        AppScreen.Loading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        AppScreen.EmailInput -> {
                            EmailInputScreen(
                                state = state,
                                onEmailChanged = viewModel::onEmailChanged,
                                onSubmit = viewModel::submitEmail,
                            )
                        }
                        AppScreen.MagicLinkInput -> {
                            MagicLinkInputScreen(
                                state = state,
                                onUrlChanged = viewModel::onMagicLinkUrlChanged,
                                onSubmit = viewModel::submitMagicLink,
                            )
                        }
                        AppScreen.DoorList -> {
                            DoorListScreen(
                                state = state,
                                onUnlock = viewModel::unlockDoor,
                                onRefresh = viewModel::refreshDoors,
                                onLogout = viewModel::logout,
                                onDismissError = viewModel::dismissError,
                            )
                        }
                    }
                }
            }
        }
    }
}
