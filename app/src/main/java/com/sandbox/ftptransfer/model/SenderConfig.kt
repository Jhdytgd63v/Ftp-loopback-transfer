package com.sandbox.ftptransfer.model

data class SenderConfig(
    val monitorFolder: String,
    val targetPort: Int
)

data class FolderMonitorConfig(
    val folderPath: String,
    val folderName: String,
    val targetPort: Int,
    val fileAction: FileAction = FileAction.COPY,
    val enabled: Boolean = true
) {
    fun getDisplayName(): String {
        return "$folderName → Port $targetPort ($fileAction)"
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
                    fileAction = FileAction.MOVE
                ),
                FolderMonitorConfig(
                    folderPath = "/Downloads/",
                    folderName = "Downloads",
                    targetPort = 5153,
                    fileAction = FileAction.COPY
                ),
                FolderMonitorConfig(
                    folderPath = "/DCIM/Camera/",
                    folderName = "Camera",
                    targetPort = 5154,
                    fileAction = FileAction.MOVE
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
