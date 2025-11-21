package com.sandbox.ftptransfer.service
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
                
                for (document in documents) {
                    if (document.isFile && !processedFiles.contains(document.uri.toString())) {
                        
                        // Create temporary file object for filtering
                        val tempFile = File(cacheDir, document.name ?: "unknown")
                        
                        // AUTO DETECT FILTERING
                        if (config.shouldTransferFile(tempFile)) {
                            // Wait for file stability
                            delay(config.monitoringSettings.getDelayMillis().coerceAtMost(2000L))
                            
                            if (isDocumentReady(document)) {
                                AppNotificationManager.notifyStatus(this@FileMonitorService, document.name.hashCode(), "📁 File Detected", "New file: ${document.name}")
                                processedFiles.add(document.uri.toString())
                                scope.launch {
                                    sendDocumentToReceiver(document, config)
                                }
                                transferredCount++
                            }
                        } else {
                            filteredCount++
                            Log.d(TAG, "AutoDetect filtered: ${document.name} in ${config.folderName}")
                        }
                    }
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
                
                for (file in files) {
                    if (file.isFile && !processedFiles.contains(file.absolutePath)) {
                        
                        // AUTO DETECT FILTERING - CORE FEATURE
                        if (config.shouldTransferFile(file)) {
                            // Wait for file to be completely written
                            delay(config.monitoringSettings.getDelayMillis().coerceAtMost(2000L))
                            
                            if (isFileReady(file)) {
                                AppNotificationManager.notifyStatus(this@FileMonitorService, file.name.hashCode(), "📁 File Detected", "New file: ${file.name}")
                                processedFiles.add(file.absolutePath)
                                scope.launch {
                                    sendFileToReceiver(file, config)
                                }
                                transferredCount++
                            }
                        } else {
                            filteredCount++
                            Log.d(TAG, "AutoDetect filtered: ${file.name} in ${config.folderName}")
                        }
                    }
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
                
                for (file in files) {
                    if (file.isFile && !processedFiles.contains(file.absolutePath)) {
                        
                        // AUTO DETECT FILTERING - CORE FEATURE
                        if (config.shouldTransferFile(file)) {
                            // Wait for file to be completely written
                            delay(config.monitoringSettings.getDelayMillis().coerceAtMost(2000L))
                            
                            if (isFileReady(file)) {
                                AppNotificationManager.notifyStatus(this@FileMonitorService, file.name.hashCode(), "📁 File Detected", "New file: ${file.name}")
                                processedFiles.add(file.absolutePath)
                                scope.launch {
                                    sendFileToReceiver(file, config)
                                }
                                transferredCount++
                            }
                        } else {
                            filteredCount++
                            Log.d(TAG, "AutoDetect filtered: ${file.name} in ${config.folderName}")
                        }
                    }
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

    private suspend fun isDocumentReady(document: androidx.documentfile.provider.DocumentFile): Boolean {
        return try {
            // For DocumentFile, we assume it's ready immediately since we can't easily check size changes
            delay(1000) // Wait 1 second for file stability
            document.exists() && document.length() > 0
        } catch (e: Exception) {
            false
        }
    }
        return try {

    private suspend fun isDocumentReady(document: androidx.documentfile.provider.DocumentFile): Boolean {
        return try {
            // For DocumentFile, we assume it's ready immediately since we can't easily check size changes
            delay(1000) // Wait 1 second for file stability
            document.exists() && document.length() > 0
        } catch (e: Exception) {
            false
        }
    }
            val initialSize = file.length()

    private suspend fun isDocumentReady(document: androidx.documentfile.provider.DocumentFile): Boolean {
        return try {
            // For DocumentFile, we assume it's ready immediately since we can't easily check size changes
            delay(1000) // Wait 1 second for file stability
            document.exists() && document.length() > 0
        } catch (e: Exception) {
            false
        }
    }
            delay(1000) // Fixed 1 second untuk file stability check

    private suspend fun isDocumentReady(document: androidx.documentfile.provider.DocumentFile): Boolean {
        return try {
            // For DocumentFile, we assume it's ready immediately since we can't easily check size changes
            delay(1000) // Wait 1 second for file stability
            document.exists() && document.length() > 0
        } catch (e: Exception) {
            false
        }
    }
            val finalSize = file.length()

    private suspend fun isDocumentReady(document: androidx.documentfile.provider.DocumentFile): Boolean {
        return try {
            // For DocumentFile, we assume it's ready immediately since we can't easily check size changes
            delay(1000) // Wait 1 second for file stability
            document.exists() && document.length() > 0
        } catch (e: Exception) {
            false
        }
    }
            initialSize == finalSize && initialSize > 0

    private suspend fun isDocumentReady(document: androidx.documentfile.provider.DocumentFile): Boolean {
        return try {
            // For DocumentFile, we assume it's ready immediately since we can't easily check size changes
            delay(1000) // Wait 1 second for file stability
            document.exists() && document.length() > 0
        } catch (e: Exception) {
            false
        }
    }
        } catch (e: Exception) {

    private suspend fun isDocumentReady(document: androidx.documentfile.provider.DocumentFile): Boolean {
        return try {
            // For DocumentFile, we assume it's ready immediately since we can't easily check size changes
            delay(1000) // Wait 1 second for file stability
            document.exists() && document.length() > 0
        } catch (e: Exception) {
            false
        }
    }
            false

    private suspend fun isDocumentReady(document: androidx.documentfile.provider.DocumentFile): Boolean {
        return try {
            // For DocumentFile, we assume it's ready immediately since we can't easily check size changes
            delay(1000) // Wait 1 second for file stability
            document.exists() && document.length() > 0
        } catch (e: Exception) {
            false
        }
    }
        }

    private suspend fun isDocumentReady(document: androidx.documentfile.provider.DocumentFile): Boolean {
        return try {
            // For DocumentFile, we assume it's ready immediately since we can't easily check size changes
            delay(1000) // Wait 1 second for file stability
            document.exists() && document.length() > 0
        } catch (e: Exception) {
            false
        }
    }
    }

    private suspend fun isDocumentReady(document: androidx.documentfile.provider.DocumentFile): Boolean {
        return try {
            // For DocumentFile, we assume it's ready immediately since we can't easily check size changes
            delay(1000) // Wait 1 second for file stability
            document.exists() && document.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun sendFileToReceiver(file: File, config: FolderMonitorConfig) {

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
        Log.d(TAG, "Attempting to send file: ${file.name} to port: ${config.targetPort}")

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }


    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
        AppNotificationManager.notifyStatus(this, file.name.hashCode(), "📤 Sending File", "Sending ${file.name} to port ${config.targetPort}")

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
        // FIX: Gunakan connectToServer bukan connectToPort

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
        val socket = PortManager.connectToServer(config.targetPort)

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }


    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
        if (socket == null) {

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
            processedFiles.remove(file.absolutePath)

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
            return

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
        }

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }


    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
        try {

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
            socket.use { sock ->

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                val outputStream = DataOutputStream(sock.getOutputStream())

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                val inputStream = DataInputStream(sock.getInputStream())

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }


    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                // Send file metadata

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                outputStream.writeUTF(file.name)

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                outputStream.writeLong(file.length())

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                outputStream.writeUTF(config.fileAction.toString())

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }


    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                // Send file data

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                val fileInputStream = FileInputStream(file)

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                val buffer = ByteArray(8192)

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                var read: Int

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }


    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                fileInputStream.use { fis ->

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                    while (fis.read(buffer).also { read = it } != -1) {

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                        outputStream.write(buffer, 0, read)

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                    }

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                }

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }


    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                outputStream.flush()

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }


    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                // Wait for response

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                val success = inputStream.readBoolean()

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                val message = inputStream.readUTF()

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                        AppNotificationManager.notifyStatus(this, file.name.hashCode(), "✅ Transfer Complete", "File sent: ${file.name}")

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }


    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                if (success) {

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                    Log.d(TAG, "File sent successfully: ${file.name} - $message")

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }


    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                        if (config.fileAction == FileAction.MOVE && file.exists()) {

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                            file.delete()

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                            Log.d(TAG, "Source file deleted after move: ${file.name}")

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                        } else {

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                            // No action needed

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                        }

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }


    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                } else {

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                    Log.e(TAG, "File transfer failed: ${file.name} - $message")

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                    processedFiles.remove(file.absolutePath) // Retry later

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
                }

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
            }

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
        } catch (e: Exception) {

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
            Log.e(TAG, "Error sending file ${file.name}: ${e.message}")

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
            processedFiles.remove(file.absolutePath)

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
        }

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
        }
    }
    }

    private suspend fun sendDocumentToReceiver(document: androidx.documentfile.provider.DocumentFile, config: FolderMonitorConfig) {
        Log.d(TAG, "Attempting to send document: ${document.name} to port: ${config.targetPort}")

        AppNotificationManager.notifyStatus(this, document.name.hashCode(), "📤 Sending File", "Sending ${document.name} to port ${config.targetPort}")
        

        val socket = PortManager.connectToServer(config.targetPort)
        

        if (socket == null) {
            Log.e(TAG, "Failed to connect to server on port ${config.targetPort}")
            processedFiles.remove(document.uri.toString())
            return
        }
        

        try {
            socket.use { sock ->
                val outputStream = DataOutputStream(sock.getOutputStream())
                val inputStream = DataInputStream(sock.getInputStream())
                

                // Send file metadata
                outputStream.writeUTF(document.name ?: "unknown_file")
                outputStream.writeLong(document.length())
                outputStream.writeUTF(config.fileAction.toString())
                

                // Send file data from DocumentFile
                val inputStream = contentResolver.openInputStream(document.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                

                outputStream.flush()
                

                // Wait for response
                val success = inputStream.readBoolean()
                val message = inputStream.readUTF()
                AppNotificationManager.notifyStatus(this, document.name.hashCode(), "✅ Transfer Complete", "File sent: ${document.name}")
                

                if (success) {
                    Log.d(TAG, "Document sent successfully: ${document.name} - $message")
                    

                    if (config.fileAction == FileAction.MOVE) {
                        val deleted = document.delete()
                        Log.d(TAG, "Source document deleted after move: ${document.name}, deleted=$deleted")
                    }
                    

                } else {
                    Log.e(TAG, "Document transfer failed: ${document.name} - $message")
                    processedFiles.remove(document.uri.toString()) // Retry later
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending document ${document.name}: ${e.message}")
            processedFiles.remove(document.uri.toString())
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
}