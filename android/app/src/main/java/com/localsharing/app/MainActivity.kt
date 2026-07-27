package com.localsharing.app

import android.Manifest
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.localsharing.app.ui.ConnectScreen
import com.localsharing.app.ui.HomeScreen
import com.localsharing.app.ui.ScannerScreen
import com.localsharing.app.ui.theme.LocalSharingTheme
import com.localsharing.app.viewmodel.ConnState
import com.localsharing.app.viewmodel.ShareViewModel

class MainActivity : FragmentActivity() {
    private val vm: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalSharingTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(vm)
                }
            }
        }
    }
}

@Composable
fun AppRoot(vm: ShareViewModel) {
    val conn by vm.connState.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    val camLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scanning = true
    }

    when {
        scanning -> ScannerScreen(
            onResult = { url ->
                scanning = false
                vm.connectFromUrl(url)
            },
            onCancel = { scanning = false },
        )
        conn is ConnState.Connected -> HomeScreen(vm)
        else -> ConnectScreen(
            vm,
            onScan = { camLauncher.launch(Manifest.permission.CAMERA) },
        )
    }
}
