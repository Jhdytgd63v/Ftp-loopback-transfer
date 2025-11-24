package com.sandbox.ftptransfer

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.sandbox.ftptransfer.service.FileMonitorService
import com.sandbox.ftptransfer.service.LoopbackServer
import com.sandbox.ftptransfer.model.SenderSettings
import com.google.gson.Gson
import java.io.File

class MainActivity : AppCompatActivity() {
    
    private lateinit var switchMode: Switch
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnReceiverConfig: Button
    private lateinit var switchBackgroundService: Switch
    private lateinit var switchAutoShare: Switch
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    
    private var isReceiverMode = true
    private val settingsFile = "sender_settings.json"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        loadBackgroundServiceSetting()
        setupClickListeners()
        updateModeDisplay()
    }
    
    private fun initViews() {
        switchMode = findViewById(R.id.switchMode)
        btnStart = findViewById(R.id.btnStartService)
        btnStop = findViewById(R.id.btnStopService)
        btnReceiverConfig = findViewById(R.id.btnReceiverConfig)
        switchBackgroundService = findViewById(R.id.switchBackgroundService)
        switchAutoShare = findViewById(R.id.switchAutoShare)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
    }
    
    private fun loadBackgroundServiceSetting() {
        try {
            val file = File(filesDir, settingsFile)
            if (file.exists()) {
                val json = file.readText()
                val settings = Gson().fromJson(json, SenderSettings::class.java)
                switchBackgroundService.isChecked = settings.backgroundServiceEnabled
                switchAutoShare.isChecked = settings.autoShareEnabled
            }
        } catch (e: Exception) {
            // Use default setting if error
            switchBackgroundService.isChecked = false
        }
    }
    
    private fun saveBackgroundServiceSetting(enabled: Boolean) {
        try {
            val file = File(filesDir, settingsFile)
            val settings = if (file.exists()) {
                val json = file.readText()
                Gson().fromJson(json, SenderSettings::class.java).copy(
                    backgroundServiceEnabled = enabled
                )
            } else {
                SenderSettings(backgroundServiceEnabled = enabled)
            }
            
            val json = Gson().toJson(settings)
            file.writeText(json)
        } catch (e: Exception) {
            logMessage("Error saving background service setting")
        }
    }
    
    private fun saveAutoShareSetting(enabled: Boolean) {
        try {
            val file = File(filesDir, settingsFile)
            val settings = if (file.exists()) {
                val json = file.readText()
                Gson().fromJson(json, SenderSettings::class.java).copy(autoShareEnabled = enabled)
            } else {
                SenderSettings(autoShareEnabled = enabled)
            }
            file.writeText(Gson().toJson(settings))
        } catch (e: Exception) {
            logMessage("Error saving auto share setting")
        }
    }
    
    private fun setupClickListeners() {
        switchMode.setOnCheckedChangeListener { _, isChecked ->
            isReceiverMode = !isChecked
            updateModeDisplay()
        }
        
        btnStart.setOnClickListener {
            startServices()
        }
        
        btnStop.setOnClickListener {
            stopServices()
        }
        
        btnReceiverConfig.setOnClickListener {
            if (isReceiverMode) {
                val intent = Intent(this, ReceiverConfigActivity::class.java)
                startActivity(intent)
            } else {
                val intent = Intent(this, SenderConfigActivity::class.java)
                startActivity(intent)
            }
        }
        
        switchBackgroundService.setOnCheckedChangeListener { _, isChecked ->
            saveBackgroundServiceSetting(isChecked)
            if (isChecked) {
                logMessage("Background service enabled")
            } else {
                logMessage("Background service disabled")
                // Stop service if running
                stopServices()
            }
        }
    }
    
    private fun updateModeDisplay() {
        val modeText = if (isReceiverMode) "Receiver" else "Sender"
        switchMode.text = "$modeText Mode"
        tvStatus.text = "Status: Stopped - $modeText Mode"
        
        // Update config button text
        btnReceiverConfig.text = if (isReceiverMode) "Configure Receiver" else "Configure Sender"
        
        // Show/hide background service switch based on mode
        switchBackgroundService.visibility = if (isReceiverMode) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }
        switchAutoShare.visibility = if (isReceiverMode) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }
    }
    
    private fun startServices() {
        if (isReceiverMode) {
            // Start receiver (server)
            val intent = Intent(this, LoopbackServer::class.java)
            startService(intent)
            logMessage("Receiver service started")
        } else {
            // Start sender (file monitor)
            val intent = Intent(this, FileMonitorService::class.java)
            startService(intent)
            logMessage("Sender service started")
            
            // Show background service status
            if (switchBackgroundService.isChecked) {
                logMessage("Background service is enabled")
            }
            if (switchAutoShare.isChecked) {
                logMessage("Auto Share is enabled")
            }
        }
        tvStatus.text = "Status: Running - ${if (isReceiverMode) "Receiver" else "Sender"} Mode"
    }
    
    private fun stopServices() {
        if (isReceiverMode) {
            val intent = Intent(this, LoopbackServer::class.java)
            stopService(intent)
            logMessage("Receiver service stopped")
        } else {
            val intent = Intent(this, FileMonitorService::class.java)
            stopService(intent)
            logMessage("Sender service stopped")
        }
        tvStatus.text = "Status: Stopped - ${if (isReceiverMode) "Receiver" else "Sender"} Mode"
    }
    
    private fun logMessage(message: String) {
        runOnUiThread {
            val currentText = tvLog.text.toString()
            tvLog.text = "$currentText\n$message"
        }
    }
}
