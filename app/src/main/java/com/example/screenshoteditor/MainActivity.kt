package com.example.screenshoteditor

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.screenshoteditor.capture.CaptureService
import com.example.screenshoteditor.databinding.ActivityMainBinding
import com.example.screenshoteditor.ui.SettingsActivity

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCaptureService()
        } else {
            Toast.makeText(this, "通知権限が必要です", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupViews()
        checkNotificationPermission()
    }
    
    private fun setupViews() {
        binding.btnStartService.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) 
                    == PackageManager.PERMISSION_GRANTED) {
                    startCaptureService()
                } else {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                startCaptureService()
            }
        }
        
        binding.btnStopService.setOnClickListener {
            stopCaptureService()
        }
        
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        updateServiceStatus()
    }
    
    private fun checkNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } catch (e: Exception) {
        }
    }
    
    private fun startCaptureService() {
        try {
            val intent = Intent(this, CaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            binding.root.postDelayed({
                updateServiceStatus()
            }, 1000)
            Toast.makeText(this, "撮影サービスを開始しました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "サービス開始に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopCaptureService() {
        stopService(Intent(this, CaptureService::class.java))
        updateServiceStatus()
        Toast.makeText(this, "撮影サービスを停止しました", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateServiceStatus() {
        val isRunning = CaptureService.isRunning
        binding.tvServiceStatus.text = if (isRunning) {
            "サービス状態: 実行中"
        } else {
            "サービス状態: 停止中"
        }
        binding.btnStartService.isEnabled = !isRunning
        binding.btnStopService.isEnabled = isRunning
    }
    
    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }
}
