package com.mostafa.phonecleaner

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.mostafa.phonecleaner.ui.AppRoot
import com.mostafa.phonecleaner.ui.CleanerTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CleanerTheme {
                AppRoot()
            }
        }
    }
}
