package com.sandbox.ftptransfer.model

data class AutoDetectSettings(
    val enabled: Boolean = true,
    val allowedExtensions: Set<String> = setOf("jpg", "jpeg", "png", "gif", "mp4", "mov", "avi", "pdf", "txt"),
    val maxFileSize: Long = 100 * 1024 * 1024, // 100MB default
    val ignoreHiddenFiles: Boolean = true,
    val customPatterns: Set<String> = emptySet()
) {
    fun shouldTransfer(file: File): Boolean {
        if (!enabled) return true // Jika auto detect disabled, transfer semua file
        
        // Check hidden files
        if (ignoreHiddenFiles && file.name.startsWith(".")) {
            return false
        }
        
        // Check file size
        if (file.length() > maxFileSize) {
            return false
        }
        
        // Check file extension
        val extension = file.extension.lowercase()
        if (extension in allowedExtensions) {
            return true
        }
        
        // Check custom patterns (jika ada)
        if (customPatterns.any { pattern ->
            file.name.contains(pattern, ignoreCase = true)
        }) {
            return true
        }
        
        return false
    }
    
    fun getDisplayText(): String {
        if (!enabled) return "All files"
        return "${allowedExtensions.size} file types (${allowedExtensions.take(3).joinToString()})"
    }
    
    companion object {
        fun mediaOnly(): AutoDetectSettings {
            return AutoDetectSettings(
                enabled = true,
                allowedExtensions = setOf("jpg", "jpeg", "png", "gif", "mp4", "mov", "avi"),
                maxFileSize = 500 * 1024 * 1024 // 500MB untuk media files
            )
        }
        
        fun documentsOnly(): AutoDetectSettings {
            return AutoDetectSettings(
                enabled = true, 
                allowedExtensions = setOf("pdf", "doc", "docx", "txt", "xls", "xlsx"),
                maxFileSize = 50 * 1024 * 1024 // 50MB untuk documents
            )
        }
    }
}
