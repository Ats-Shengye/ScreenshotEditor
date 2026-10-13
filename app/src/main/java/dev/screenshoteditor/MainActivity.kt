package dev.screenshoteditor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.screenshoteditor.capture.CaptureService
import dev.screenshoteditor.capture.ServiceLauncher
import dev.screenshoteditor.databinding.ActivityMainBinding
import dev.screenshoteditor.ui.SettingsActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding

    /** Permission request triggered by the start button: starts the service once granted. */
    private val permissionForStartLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCaptureService()
        } else {
            Toast.makeText(this, R.string.message_notification_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Permission request shown on first launch. It only asks; starting the service is left to the
     * user's explicit tap on the start button.
     */
    private val initialPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) Log.i(TAG, "initial notification permission request denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        observeServiceState()
        // Only on a fresh start; a recreation (e.g. rotation) must not show the dialog again.
        if (savedInstanceState == null && needsNotificationPermission()) {
            initialPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupViews() {
        binding.btnStartService.setOnClickListener {
            if (needsNotificationPermission()) {
                permissionForStartLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startCaptureService()
            }
        }
        binding.btnStopService.setOnClickListener { stopCaptureService() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /**
     * Renders the service state from [CaptureService.running] rather than polling after a fixed
     * delay; stopService() returns before the service is destroyed, so an immediate read showed
     * "running" after stopping.
     */
    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptureService.running.collect { renderServiceState(it) }
            }
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun startCaptureService() {
        try {
            ServiceLauncher.startCaptureService(this)
            Toast.makeText(this, R.string.message_service_started, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.w(TAG, "startCaptureService: permission denied", e)
            Toast.makeText(this, R.string.message_service_start_failed, Toast.LENGTH_SHORT).show()
        } catch (e: IllegalStateException) {
            // Android 12+ でバックグラウンドからのForeground Service起動が制限される場合
            Log.w(TAG, "startCaptureService: illegal state (background start restriction?)", e)
            Toast.makeText(this, R.string.message_service_start_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopCaptureService() {
        stopService(Intent(this, CaptureService::class.java))
        Toast.makeText(this, R.string.message_service_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun renderServiceState(isRunning: Boolean) {
        binding.tvServiceStatus.setText(
            if (isRunning) R.string.service_status_running else R.string.service_status_stopped
        )
        binding.btnStartService.isEnabled = !isRunning
        binding.btnStopService.isEnabled = isRunning
    }
}
