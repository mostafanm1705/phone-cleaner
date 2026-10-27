package com.mostafa.phonecleaner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mostafa.phonecleaner.MainViewModel
import com.mostafa.phonecleaner.R
import com.mostafa.phonecleaner.scan.Category
import com.mostafa.phonecleaner.util.Perms
import com.mostafa.phonecleaner.util.sizeText

sealed interface Screen {
    data object Home : Screen
    data class Files(val category: Category) : Screen
    data class Apps(val tab: Int) : Screen
    data object PermissionAudit : Screen
    data object Health : Screen
    data object Trash : Screen
    data object Settings : Screen
}

@Composable
fun AppRoot(
    startScanRequested: Boolean = false,
    onStartScanHandled: () -> Unit = {},
    vm: MainViewModel = viewModel(),
) {
    val ctx = LocalContext.current

    // Ask for notification permission (Android 13+) once storage access is granted.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(vm.hasAllFiles) {
        if (vm.hasAllFiles && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Opened from a "Scan now" notification
    LaunchedEffect(startScanRequested, vm.hasAllFiles) {
        if (startScanRequested && vm.hasAllFiles) {
            vm.startScan()
            onStartScanHandled()
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                vm.refreshPermissions()
                vm.refreshStorage()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val snackbar = remember { SnackbarHostState() }
    val msg = vm.message
    LaunchedEffect(msg) {
        if (msg != null) {
            val text = when (msg.text) {
                R.string.msg_restored -> ctx.getString(msg.text, msg.arg.toInt())
                R.string.msg_trashed, R.string.msg_deleted -> ctx.getString(msg.text, ctx.sizeText(msg.arg))
                else -> ctx.getString(msg.text)
            }
            snackbar.showSnackbar(text)
            vm.message = null
        }
    }

    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    val nav: (Screen) -> Unit = { stack = stack + it }
    val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1) { back() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            if (!vm.hasAllFiles) {
                PermissionScreen()
            } else {
                when (val s = stack.last()) {
                    Screen.Home -> HomeScreen(vm, nav)
                    is Screen.Files -> CategoryScreen(vm, s.category, back)
                    is Screen.Apps -> AppsScreen(vm, s.tab, back)
                    Screen.PermissionAudit -> PermissionAuditScreen(vm, back)
                    Screen.Health -> HealthScreen(vm, back)
                    Screen.Trash -> TrashScreen(vm, back)
                    Screen.Settings -> SettingsScreen(vm, back)
                }
            }
            SnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 72.dp)
            )
        }
    }
}

@Composable
private fun PermissionScreen() {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(32.dp))
        Icon(
            Icons.Filled.CleaningServices, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.perm_files_explain),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        InfoNote(stringResource(R.string.perm_privacy))
        Spacer(Modifier.height(24.dp))
        Button(onClick = { Perms.requestAllFiles(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.grant_access))
        }
    }
}
