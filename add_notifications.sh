#!/bin/bash
echo "Menambahkan notification system..."

# 1. Tambahkan import di FileMonitorService
sed -i '1a\
import com.sandbox.ftptransfer.utils.AppNotificationManager' app/src/main/java/com/sandbox/ftptransfer/service/FileMonitorService.kt

# 2. Tambahkan import di LoopbackServer
sed -i '1a\
import com.sandbox.ftptransfer.utils.AppNotificationManager' app/src/main/java/com/sandbox/ftptransfer/service/LoopbackServer.kt

# 3. Notification saat file terdeteksi (setelah processedFiles.add)
sed -i '101a\
                            AppNotificationManager.notifyStatus(this@FileMonitorService, file.name.hashCode(), "📁 File Detected", "New file: ${file.name}")' app/src/main/java/com/sandbox/ftptransfer/service/FileMonitorService.kt

# 4. Notification saat mulai transfer (setelah connectToServer)
sed -i '140a\
        AppNotificationManager.notifyStatus(this, file.name.hashCode(), "📤 Sending File", "Sending ${file.name} to port ${config.targetPort}")' app/src/main/java/com/sandbox/ftptransfer/service/FileMonitorService.kt

# 5. Notification saat transfer sukses (setelah Log.d success)
sed -i '176a\
                        AppNotificationManager.notifyStatus(this, file.name.hashCode(), "✅ Transfer Complete", "File sent: ${file.name}")' app/src/main/java/com/sandbox/ftptransfer/service/FileMonitorService.kt

# 6. Notification saat file diterima (setelah Log.d received)
sed -i '133a\
            AppNotificationManager.notifyStatus(this, fileName.hashCode(), "📥 File Received", "New file: $fileName in channel_$channel")' app/src/main/java/com/sandbox/ftptransfer/service/LoopbackServer.kt

echo "Notification system added!"
echo "Verifying changes..."
grep -n "AppNotificationManager" app/src/main/java/com/sandbox/ftptransfer/service/FileMonitorService.kt app/src/main/java/com/sandbox/ftptransfer/service/LoopbackServer.kt
