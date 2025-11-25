package com.sandbox.ftptransfer.model

import java.io.File

data class SenderConfig(
    val monitorFolder: String,

)

data class FolderMonitorConfig(
    val folderPath: String,
    val folderName: String,
    val enabled: Boolean = true,
    val monitoringSettings: MonitoringSettings = MonitoringSettings.default(),
    val autoDetectSettings: AutoDetectSettings = AutoDetectSettings(), // AUTO DETECT INTEGRATION
    val autoShare: Boolean = false // NEW: enable automatic Android share dialog
) {
    fun getDisplayName(): String {
        val delayText = if (monitoringSettings.delaySeconds == 0) "real-time" else "${monitoringSettings.delaySeconds}s"
        val autoDetectText = if (autoDetectSettings.enabled) "Auto" else "All"
        return "$folderName - Scan: $delayText - Filter: $autoDetectText"
    }
    
    fun shouldTransferFile(file: File): Boolean {
        return autoDetectSettings.shouldTransfer(file)
    }
}

// FileAction removed (COPY/MOVE no longer used in pure auto-share mode)

data class SenderSettings(
    val monitoredFolders: List<FolderMonitorConfig> = defaultFolders(),
    val backgroundServiceEnabled: Boolean = false,
    val adaptiveScanning: Boolean = true,
    val autoShareEnabled: Boolean = true // Global auto-share (default true)
) {
    companion object {
        fun defaultFolders(): List<FolderMonitorConfig> {
            return listOf(
                FolderMonitorConfig(
                    folderPath = "/Pictures/Screenshots/",
                    folderName = "Screenshots",
                    monitoringSettings = MonitoringSettings(delaySeconds = 2),
                    autoDetectSettings = AutoDetectSettings.mediaOnly()
                ),
                FolderMonitorConfig(
                    folderPath = "/Downloads/",
                    folderName = "Downloads",
                    monitoringSettings = MonitoringSettings(delaySeconds = 5),
                    autoDetectSettings = AutoDetectSettings() // Default: images, videos, pdf, txt
                ),
                FolderMonitorConfig(
                    folderPath = "/DCIM/Camera/",
                    folderName = "Camera",
                    monitoringSettings = MonitoringSettings(delaySeconds = 3),
                    autoDetectSettings = AutoDetectSettings.mediaOnly()
                )
            )
        }
    }

    fun getConfigForFolder(folderPath: String): FolderMonitorConfig? {
        return monitoredFolders.find { it.folderPath == folderPath }
    }

    // getConfigForPort removed: ports no longer used
}
