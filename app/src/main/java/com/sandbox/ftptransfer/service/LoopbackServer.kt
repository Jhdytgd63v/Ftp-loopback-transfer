package com.sandbox.ftptransfer.service
import com.sandbox.ftptransfer.utils.AppNotificationManager

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.sandbox.ftptransfer.utils.PortManager
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class LoopbackServer : Service() {
    
    private val TAG = "LoopbackServer"
    private val isRunning = AtomicBoolean(false)
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "LoopbackServer created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning.getAndSet(true)) {
            startServer()
        }
        return START_STICKY
    }
    
    private fun startServer() {
        serverJob = scope.launch {
            Log.d(TAG, "Starting loopback server...")
            
            if (!PortManager.initializeServerSockets()) {
                Log.e(TAG, "Failed to initialize server sockets")
                return@launch
            }
            
            val ports = PortManager.getAvailablePorts()
            Log.d(TAG, "Server listening on ports: $ports")
            
            // Start listening on all ports
            val serverJobs = ports.map { port ->
                launch { startListeningOnPort(port) }
            }
            
            // Wait for all server jobs to complete
            serverJobs.joinAll()
        }
    }
    
    private suspend fun startListeningOnPort(port: Int) {
        val serverSocket = PortManager.getServerSocket(port) ?: run {
            Log.e(TAG, "Server socket not found for port $port")
            return
        }
        
        Log.d(TAG, "Listening on port $port")
        
        while (isRunning.get()) {
            try {
                val clientSocket = withTimeout(5000) {
                    serverSocket.accept()
                }
                
                // FIX: Use scope.launch instead of just launch
                scope.launch {
                    handleClientConnection(clientSocket, port)
                }
                
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Error accepting connection on port $port: ${e.message}")
                }
            }
        }
    }
    
    private suspend fun handleClientConnection(clientSocket: java.net.Socket, port: Int) {
        try {
            Log.d(TAG, "Client connected on port $port")
            
            val inputStream = clientSocket.getInputStream()
            val outputStream = clientSocket.getOutputStream()
            val dataInputStream = DataInputStream(inputStream)
            val dataOutputStream = DataOutputStream(outputStream)
            
            // Read file metadata
            val fileName = dataInputStream.readUTF()
            val fileSize = dataInputStream.readLong()
            val channel = dataInputStream.readInt()
            
            Log.d(TAG, "Receiving file: $fileName, size: $fileSize, channel: $channel")
            
            // Create received files directory
            val receivedDir = File(getExternalFilesDir(null), "received")
            if (!receivedDir.exists()) {
                receivedDir.mkdirs()
            }
            
            // Create channel-specific subdirectory
            val channelDir = File(receivedDir, "channel_$channel")
            if (!channelDir.exists()) {
                channelDir.mkdirs()
            }
            
            val outputFile = File(channelDir, fileName)
            val fileOutputStream = FileOutputStream(outputFile)
            
            // Receive file data
            val buffer = ByteArray(8192)
            var totalRead = 0L
            var read: Int
            
            while (totalRead < fileSize) {
                read = inputStream.read(buffer)
                if (read == -1) break
                fileOutputStream.write(buffer, 0, read)
                totalRead += read
            }
            
            fileOutputStream.close()
            
            // Send response
            dataOutputStream.writeBoolean(true)
            dataOutputStream.writeUTF("File received successfully: ${outputFile.absolutePath}")
            dataOutputStream.flush()
            
            AppNotificationManager.notifyStatus(this, fileName.hashCode(), "📥 File Received", "New file: $fileName in channel_$channel")
            Log.d(TAG, "File received successfully: ${outputFile.absolutePath}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client connection on port $port: ${e.message}")
            try {
                val dataOutputStream = DataOutputStream(clientSocket.getOutputStream())
                dataOutputStream.writeBoolean(false)
                dataOutputStream.writeUTF("Error: ${e.message}")
                dataOutputStream.flush()
            } catch (e2: Exception) {
                Log.e(TAG, "Error sending error response: ${e2.message}")
            }
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client socket: ${e.message}")
            }
        }
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Stopping loopback server...")
        isRunning.set(false)
        serverJob?.cancel()
        PortManager.closeAllSockets()
        scope.cancel()
        super.onDestroy()
    }
}
