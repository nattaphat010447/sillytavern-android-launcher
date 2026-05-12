package com.standroid.launcher.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.standroid.launcher.databinding.ActivityAdvancedSettingsBinding
import com.standroid.launcher.util.AppPrefs

/**
 * Advanced Settings screen — accessible from SettingsActivity.
 *
 * Currently exposes:
 *  • Auto-Update on Startup toggle (AppPrefs.autoUpdateOnStartup)
 */
class AdvancedSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdvancedSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvancedSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialise toggle from saved preference
        binding.switchAutoUpdate.isChecked = AppPrefs.autoUpdateOnStartup

        // Persist immediately on change
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.autoUpdateOnStartup = isChecked
        }
    }
}
