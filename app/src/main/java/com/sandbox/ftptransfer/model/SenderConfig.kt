package com.sandbox.ftptransfer.model

import java.io.File

data class SenderConfig(
    val monitorFolder: String,
    val targetPort: Int
)

data class FolderMonitorConfig(
    val folderPath: String,
    val folderName: String,
    val targetPort: Int,
    val fileAction: FileAction = FileAction.COPY,
    val enabled: Boolean = true,
    val monitoringSettings: MonitoringSettings = MonitoringSettings.default(),
    val autoDetectSettings: AutoDetectSettings = AutoDetectSettings(), // AUTO DETECT INTEGRATION
    val autoShare: Boolean = false // NEW: enable automatic Android share dialog
) {
    fun getDisplayName(): String {
        val delayText = if (monitoringSettings.delaySeconds == 0) "real-time" else "${monitoringSettings.delaySeconds}s"
        val autoDetectText = if (autoDetectSettings.enabled) "Auto" else "All"
        return "$folderName → Port $targetPort ($fileAction) - Scan: $delayText - Filter: $autoDetectText"
    }
    
    fun shouldTransferFile(file: File): Boolean {
        return autoDetectSettings.shouldTransfer(file)
    }
}

enum class FileAction {
    COPY,    // File disalin, tetap ada di sumber
    MOVE     // File dipindah, dihapus dari sumber setelah sukses
}

data class SenderSettings(
    val monitoredFolders: List<FolderMonitorConfig> = defaultFolders(),
    val backgroundServiceEnabled: Boolean = false,
    val adaptiveScanning: Boolean = true
) {
    companion object {
        fun defaultFolders(): List<FolderMonitorConfig> {
            return listOf(
                FolderMonitorConfig(
                    folderPath = "/Pictures/Screenshots/",
                    folderName = "Screenshots",
                    targetPort = 5152,
                    fileAction = FileAction.MOVE,
                    monitoringSettings = MonitoringSettings(delaySeconds = 2),
                    autoDetectSettings = AutoDetectSettings.mediaOnly()
                ),
                FolderMonitorConfig(
                    folderPath = "/Downloads/",
                    folderName = "Downloads",
                    targetPort = 5153,
                    fileAction = FileAction.COPY,
                    monitoringSettings = MonitoringSettings(delaySeconds = 5),
                    autoDetectSettings = AutoDetectSettings() // Default: images, videos, pdf, txt
                ),
                FolderMonitorConfig(
                    folderPath = "/DCIM/Camera/",
                    folderName = "Camera",
                    targetPort = 5154,
                    fileAction = FileAction.MOVE,
                    monitoringSettings = MonitoringSettings(delaySeconds = 3),
                    autoDetectSettings = AutoDetectSettings.mediaOnly()
                )
            )
        }
    }

    fun getConfigForFolder(folderPath: String): FolderMonitorConfig? {
        return monitoredFolders.find { it.folderPath == folderPath }
    }

    fun getConfigForPort(port: Int): FolderMonitorConfig? {
        return monitoredFolders.find { it.targetPort == port }
    }
}
