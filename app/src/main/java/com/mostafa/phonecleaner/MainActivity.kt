package com.mostafa.phonecleaner

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import com.mostafa.phonecleaner.notify.Notifier
import com.mostafa.phonecleaner.ui.AppRoot
import com.mostafa.phonecleaner.ui.CleanerTheme

class MainActivity : AppCompatActivity() {

    /** Set when the app is opened from a "Scan now" notification. */
    private val startScanRequest = mutableStateOf(false)

    /** Set when the app is opened from the "scan finished" notification. */
    private val openReportRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readIntent(intent)
        setContent {
            CleanerTheme {
                AppRoot(
                    startScanRequested = startScanRequest.value,
                    onStartScanHandled = { startScanRequest.value = false },
                    openReportRequested = openReportRequest.value,
                    onOpenReportHandled = { openReportRequest.value = false }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(Notifier.EXTRA_START_SCAN, false) == true) {
            startScanRequest.value = true
            intent.removeExtra(Notifier.EXTRA_START_SCAN)
        }
        if (intent?.getBooleanExtra(Notifier.EXTRA_OPEN_REPORT, false) == true) {
            openReportRequest.value = true
            intent.removeExtra(Notifier.EXTRA_OPEN_REPORT)
        }
    }
}
