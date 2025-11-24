package com.sandbox.ftptransfer.service
import androidx.documentfile.provider.DocumentFile
import com.sandbox.ftptransfer.utils.AppNotificationManager

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.sandbox.ftptransfer.utils.PortManager
import com.sandbox.ftptransfer.model.SenderSettings
import com.sandbox.ftptransfer.model.FolderMonitorConfig
import com.sandbox.ftptransfer.model.FileAction
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class FileMonitorService : Service() {

    private val TAG = "FileMonitorService"
    private val isRunning = AtomicBoolean(false)
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val processedFiles = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val lastScanTimes = ConcurrentHashMap<String, Long>() // Folder path -> Last scan time
    // Cache: path/uri -> Pair(size,lastModified)
    private val fileCache = mutableMapOf<String, Pair<Long, Long>>()
    // Track files already shared to avoid duplicate share dialogs
    private val sharedFiles = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private var autoShareEnabledGlobal = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FileMonitorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start foreground service untuk Android 8+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel("ftp_monitor_channel", "File Monitoring", android.app.NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            
            val notification = android.app.Notification.Builder(this, "ftp_monitor_channel")
                .setContentTitle("FTP File Monitor")
                .setContentText("Monitoring folders for new files")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .build()
            startForeground(1, notification)
        }
        if (!isRunning.getAndSet(true)) {
            startFileMonitoring()
        }
        return START_STICKY
    }

    private fun startFileMonitoring() {
        monitorJob = scope.launch {
            Log.d(TAG, "Starting file monitoring service...")

            while (isRunning.get()) {
                try {
                    val settings = loadSenderSettings()
                    autoShareEnabledGlobal = settings.autoShareEnabled
                    val enabledConfigs = settings.monitoredFolders.filter { it.enabled }

                    enabledConfigs.forEach { config ->
                        if (shouldScanFolder(config)) {
                            monitorConfiguredFolder(config)
                        }
                    }

                    // Global check interval (1 second untuk responsiveness)
                    delay(1000)

                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Error in file monitoring: ${e.message}")
                        delay(5000) // Wait longer if there's an error
                    }
                }
            }
        }
    }

    private fun shouldScanFolder(config: FolderMonitorConfig): Boolean {
        val now = System.currentTimeMillis()
        val lastScan = lastScanTimes[config.folderPath] ?: 0L
        val delayMs = config.monitoringSettings.getDelayMillis()
        
        return now - lastScan >= delayMs
    }

    private suspend fun monitorConfiguredFolder(config: FolderMonitorConfig) {
        try {
            // Handle both content URI and file paths
            val uri = android.net.Uri.parse(config.folderPath)
            
            if (uri.scheme == "content") {
                // Use DocumentFile for content URIs
                val documentDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@FileMonitorService, uri)
                if (documentDir == null || !documentDir.exists() || !documentDir.isDirectory) {
                    Log.w(TAG, "Document directory not found: ${config.folderPath}")
                    return
                }
                
                // Update last scan time
                lastScanTimes[config.folderPath] = System.currentTimeMillis()
                
                val documents = documentDir.listFiles()
                var transferredCount = 0
                var filteredCount = 0

                val currentUris = mutableSetOf<String>()
                for (document in documents) {
                    if (document.isFile) {
                        val key = document.uri.toString()
                        currentUris.add(key)
                        val size = document.length()
                        val lastMod = document.lastModified()
                        val cached = fileCache[key]
                        val isNew = cached == null
                        val isModified = cached != null && (cached.first != size || cached.second != lastMod)

                        if ((isNew || isModified) && !processedFiles.contains(key)) {
                            val tempFile = File(cacheDir, document.name ?: "unknown")
                            if (config.shouldTransferFile(tempFile)) {
                                delay(config.monitoringSettings.getDelayMillis().coerceAtMost(2000L))
                                if (isDocumentReady(document)) {
                                    AppNotificationManager.notifyStatus(this@FileMonitorService, document.name.hashCode(), if (isNew) "📁 New File" else "♻️ File Modified", document.name ?: "unknown")
                                    processedFiles.add(key)
                                    fileCache[key] = size to lastMod
                                    scope.launch { sendDocumentToReceiver(document, config) }
                                    if (autoShareEnabledGlobal && isNew && !sharedFiles.contains(key)) {
                                        sharedFiles.add(key)
                                        shareDocument(document)
                                    }
                                    transferredCount++
                                }
                            } else {
                                filteredCount++
                                Log.d(TAG, "AutoDetect filtered: ${document.name} in ${config.folderName}")
                            }
                        }
                    }
                }
                // Cleanup cache entries for deleted documents
                fileCache.keys.filter { it.startsWith(documentDir.uri.toString()) && it !in currentUris }.forEach { stale ->
                    fileCache.remove(stale)
                    processedFiles.remove(stale)
                }
                
                // Log statistics
                if (transferredCount > 0 || filteredCount > 0) {
                    Log.i(TAG, "AutoDetect [${config.folderName}]: Transferred: $transferredCount, Filtered: $filteredCount")
                }
            } else {
                // Use regular File for filesystem paths (fallback)
                val directory = File(config.folderPath)
                if (!directory.exists() || !directory.isDirectory) {
                    Log.w(TAG, "Directory not found: ${config.folderPath}")
                    return
                }
                
                // Update last scan time
                lastScanTimes[config.folderPath] = System.currentTimeMillis()
                
                val files = directory.listFiles() ?: return

                var transferredCount = 0
                var filteredCount = 0

                val currentPaths = mutableSetOf<String>()
                for (file in files) {
                    if (file.isFile) {
                        val key = file.absolutePath
                        currentPaths.add(key)
                        val size = file.length()
                        val lastMod = file.lastModified()
                        val cached = fileCache[key]
                        val isNew = cached == null
                        val isModified = cached != null && (cached.first != size || cached.second != lastMod)

                        if ((isNew || isModified) && !processedFiles.contains(key)) {
                            if (config.shouldTransferFile(file)) {
                                delay(config.monitoringSettings.getDelayMillis().coerceAtMost(2000L))
                                if (isFileReady(file)) {
                                    AppNotificationManager.notifyStatus(this@FileMonitorService, file.name.hashCode(), if (isNew) "📁 New File" else "♻️ File Modified", file.name)
                                    processedFiles.add(key)
                                    fileCache[key] = size to lastMod
                                    scope.launch { sendFileToReceiver(file, config) }
                                    if (autoShareEnabledGlobal && isNew && !sharedFiles.contains(key)) {
                                        sharedFiles.add(key)
                                        shareFile(file)
                                    }
                                    transferredCount++
                                }
                            } else {
                                filteredCount++
                                Log.d(TAG, "AutoDetect filtered: ${file.name} in ${config.folderName}")
                            }
                        }
                    }
                }
                // Cleanup deleted entries
                fileCache.keys.filter { it.startsWith(directory.absolutePath) && it !in currentPaths }.forEach { stale ->
                    fileCache.remove(stale)
                    processedFiles.remove(stale)
                    sharedFiles.remove(stale)
                }
                
                // Log auto detect statistics
                if (transferredCount > 0 || filteredCount > 0) {
                    Log.i(TAG, "AutoDetect [${config.folderName}]: Transferred: $transferredCount, Filtered: $filteredCount")
                }
            }
            
            // Clean up processed files set to prevent memory leak
            if (processedFiles.size > 1000) {
                processedFiles.clear()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error monitoring folder ${config.folderPath}: ${e.message}")
        }
    }

    private suspend fun isFileReady(file: File): Boolean {
        return try {
            val initialSize = file.length()
            delay(1000) // Fixed 1 second untuk file stability check
            val finalSize = file.length()
            initialSize == finalSize && initialSize > 0
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun sendFileToReceiver(file: File, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send file: ${file.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, file.name.hashCode(), "📤 Sending File", "Sending ${file.name} to port ${config.targetPort}")
        // FIX: Gunakan connectToServer bukan connectToPort
        val socket = PortManager.connectToServer(config.targetPort)

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(file.absolutePath)
            return
        }

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())

                // Send file metadata
                outputStream.writeUTF(file.name)
                outputStream.writeLong(file.length())
                outputStream.writeUTF(config.fileAction.toString())

                // Send file data
                val fileInputStream = FileInputStream(file)
                val buffer = ByteArray(8192)
                var read: Int

                fileInputStream.use { fis ->
                    while (fis.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                }

                outputStream.flush()

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                        AppNotificationManager.notifyStatus(this, file.name.hashCode(), "✅ Transfer Complete", "File sent: ${file.name}")

                if (success) {
                    Log.d(TAG, "File sent successfully: ${file.name} - $message")

                        if (config.fileAction == FileAction.MOVE && file.exists()) {
                            file.delete()
                            Log.d(TAG, "Source file deleted after move: ${file.name}")
                        } else {
                            // No action needed
                        }

                } else {
                    Log.e(TAG, "File transfer failed: ${file.name} - $message")
                    processedFiles.remove(file.absolutePath) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending file ${file.name}: ${e.message}")
            processedFiles.remove(file.absolutePath)
        }
    }

    private fun loadSenderSettings(): SenderSettings {
        return try {
            val settingsFile = File(filesDir, "sender_settings.json")
            if (settingsFile.exists()) {
                val json = settingsFile.readText()
                Gson().fromJson(json, SenderSettings::class.java)
            } else {
                SenderSettings() // Return default settings
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading sender settings: ${e.message}")
            SenderSettings() // Return default on error
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        monitorJob?.cancel()
        scope.cancel()
        Log.d(TAG, "FileMonitorService destroyed")
    }
    private suspend fun isDocumentReady(document: androidx.documentfile.provider.DocumentFile): Boolean {
        return try {
            // For DocumentFile, check if file exists and has content
            delay(1000) // Wait 1 second for file stability
            document.exists() && document.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        withContext(Dispatchers.IO) {
            try {
                Socket("127.0.0.1", config.targetPort).use { socket ->
                    DataOutputStream(socket.getOutputStream()).use { outputStream ->
                        DataInputStream(socket.getInputStream()).use { inputStream ->
                            // Send file name and size
                            outputStream.writeUTF(document.name ?: "unknown")
                            outputStream.writeLong(document.length())

                            // Send file content
                            this@FileMonitorService.contentResolver.openInputStream(document.uri)?.use { fileInputStream ->
                                fileInputStream.copyTo(outputStream)
                            }

                            // Get response
                            val success = inputStream.readBoolean()
                            val message = inputStream.readUTF()
                            AppNotificationManager.notifyStatus(this@FileMonitorService, (document.name ?: "unknown").hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")

                            if (success) {
                                Log.d(TAG, "Document sent successfully: ${document.name} - $message")

                                if (config.fileAction == FileAction.MOVE) {
                                    val deleted = document.delete()
                                    Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                                } else {
                                    // No action needed - file remains in source
                                }

                            } else {
                                Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                                processedFiles.remove(document.uri.toString()) // Retry later
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
                processedFiles.remove(document.uri.toString())
            }
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                applicationContext.packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = getMimeType(uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "Share ${file.name}"))
        } catch (e: Exception) {
            Log.e(TAG, "shareFile error: ${e.message}")
        }
    }

    private fun shareDocument(document: DocumentFile) {
        try {
            val uri = document.uri
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = document.type ?: getMimeType(uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "Share ${document.name}"))
        } catch (e: Exception) {
            Log.e(TAG, "shareDocument error: ${e.message}")
        }
    }

    private fun getMimeType(uri: android.net.Uri): String? {
        return try {
            contentResolver.getType(uri) ?: run {
                val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                if (extension.isNullOrBlank()) null else android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            }
        } catch (e: Exception) { null }
    }
}
