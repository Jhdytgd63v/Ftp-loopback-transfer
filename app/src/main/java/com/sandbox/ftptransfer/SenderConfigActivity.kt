package com.sandbox.ftptransfer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sandbox.ftptransfer.model.FolderMonitorConfig
import com.sandbox.ftptransfer.model.FileAction
import com.sandbox.ftptransfer.model.MonitoringSettings
import com.sandbox.ftptransfer.model.SenderSettings
import com.sandbox.ftptransfer.model.AutoDetectSettings
import com.sandbox.ftptransfer.ui.AutoDetectDialog
import com.google.gson.Gson
import java.io.File

class SenderConfigActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnAddMapping: Button
    private lateinit var btnSave: Button
    private lateinit var btnBack: Button
    
    private val adapter = SenderConfigAdapter()
    private val configs = mutableListOf<FolderMonitorConfig>()
    private var selectedConfigIndex = -1
    
    private val settingsFile = "sender_settings.json"
    private val FOLDER_PICKER_REQUEST = 1002
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver_config)
        
        title = "Sender Configuration - Folder to Port Mapping"
        
        initViews()
        loadSettings()
        setupClickListeners()
    }
    
    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        btnAddMapping = findViewById(R.id.btnAddMapping)
        btnSave = findViewById(R.id.btnSave)
        btnBack = findViewById(R.id.btnBack)
        
        btnAddMapping.text = "Add New Folder Monitoring"
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        adapter.onFolderSelectListener = { index ->
            selectedConfigIndex = index
            openFolderPicker()
        }
        
        adapter.onPortChangeListener = { index, newPort ->
            configs[index] = configs[index].copy(targetPort = newPort)
        }
        
        adapter.onDelayChangeListener = { index, newDelay ->
            val currentSettings = configs[index].monitoringSettings
            configs[index] = configs[index].copy(
                monitoringSettings = currentSettings.copy(delaySeconds = newDelay)
            )
        }
        
        adapter.onActionChangeListener = { index, newAction ->
            configs[index] = configs[index].copy(fileAction = newAction)
        }
        
        adapter.onAutoDetectClickListener = { index ->
            selectedConfigIndex = index
            showAutoDetectDialog(configs[index].autoDetectSettings)
        }
        
        adapter.onConfigDeleteListener = { index ->
            configs.removeAt(index)
            adapter.submitList(configs.toList())
        }
    }
    
    private fun showAutoDetectDialog(currentSettings: AutoDetectSettings) {
        val dialog = AutoDetectDialog(this, currentSettings) { newSettings ->
            if (selectedConfigIndex != -1) {
                configs[selectedConfigIndex] = configs[selectedConfigIndex].copy(
                    autoDetectSettings = newSettings
                )
                adapter.submitList(configs.toList())
                Toast.makeText(this, "Auto Detect settings saved", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }
    
    private fun loadSettings() {
        try {
            val file = File(filesDir, settingsFile)
            if (file.exists()) {
                val json = file.readText()
                val settings = Gson().fromJson(json, SenderSettings::class.java)
                
                configs.clear()
                configs.addAll(settings.monitoredFolders)
            } else {
                configs.clear()
                configs.addAll(SenderSettings.defaultFolders())
            }
            adapter.submitList(configs.toList())
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading settings", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupClickListeners() {
        btnAddMapping.setOnClickListener {
            val newPort = (configs.maxByOrNull { it.targetPort }?.targetPort ?: 5151) + 1
            val newConfig = FolderMonitorConfig(
                folderPath = "/NewFolder/",
                folderName = "NewFolder",
                targetPort = newPort,
                fileAction = FileAction.COPY,
                monitoringSettings = MonitoringSettings(delaySeconds = 2),
                autoDetectSettings = AutoDetectSettings()
            )
            configs.add(newConfig)
            adapter.submitList(configs.toList())
        }
        
        btnSave.setOnClickListener {
            saveSettings()
        }
        
        btnBack.setOnClickListener {
            finish()
        }
    }
    
    private fun openFolderPicker() {
        if (selectedConfigIndex == -1) return
        
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, FOLDER_PICKER_REQUEST)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == FOLDER_PICKER_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                updateSelectedFolder(uri)
            }
        }
    }
    
    private fun updateSelectedFolder(uri: Uri) {
        if (selectedConfigIndex == -1) return
        
        try {
            contentResolver.takePersistableUriPermission(
                uri, 
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            
            val folderName = getFolderNameFromUri(uri)
            val folderPath = uri.toString()
            
            configs[selectedConfigIndex] = configs[selectedConfigIndex].copy(
                folderPath = folderPath,
                folderName = folderName
            )
            
            adapter.submitList(configs.toList())
            Toast.makeText(this, "Folder selected: $folderName", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error selecting folder: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun getFolderNameFromUri(uri: Uri): String {
        return DocumentFile.fromTreeUri(this, uri)?.name ?: "Unknown Folder"
    }
    
    private fun saveSettings() {
        try {
            val settings = SenderSettings(
                monitoredFolders = configs,
                backgroundServiceEnabled = true,
                adaptiveScanning = true
            )
            
            val json = Gson().toJson(settings)
            File(filesDir, settingsFile).writeText(json)
            
            Toast.makeText(this, "Sender settings saved! ${configs.size} folders configured", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

class SenderConfigAdapter : RecyclerView.Adapter<SenderConfigAdapter.ViewHolder>() {
    
    private var configs: List<FolderMonitorConfig> = emptyList()
    var onFolderSelectListener: ((Int) -> Unit)? = null
    var onPortChangeListener: ((Int, Int) -> Unit)? = null
    var onDelayChangeListener: ((Int, Int) -> Unit)? = null
    var onActionChangeListener: ((Int, FileAction) -> Unit)? = null
    var onAutoDetectClickListener: ((Int) -> Unit)? = null
    var onConfigDeleteListener: ((Int) -> Unit)? = null
    
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvFolderName: TextView = itemView.findViewById(R.id.tvFolder)
        val btnSelectFolder: Button = itemView.findViewById(R.id.btnSelectFolder)
        val etPort: EditText = itemView.findViewById(R.id.etPort)
        val etDelay: EditText = itemView.findViewById(R.id.etDelay)
        val spinnerAction: Spinner = itemView.findViewById(R.id.spinnerAction)
        val btnAutoDetect: Button = itemView.findViewById(R.id.btnAutoDetect)
        val switchEnabled: Switch = itemView.findViewById(R.id.switchEnabled)
        val btnDelete: Button = itemView.findViewById(R.id.btnDelete)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder_config, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = configs[position]
        
        holder.tvFolderName.text = config.folderName
        holder.etPort.setText(config.targetPort.toString())
        holder.etDelay.setText(config.monitoringSettings.delaySeconds.toString())
        holder.switchEnabled.isChecked = config.enabled
        
        // Update Auto Detect button text
        holder.btnAutoDetect.text = if (config.autoDetectSettings.enabled) 
            "Auto Detect: ON" else "Auto Detect: OFF"
        
        // Setup file action spinner
        val actions = FileAction.values().map { it.name }
        val adapter = ArrayAdapter(holder.itemView.context, android.R.layout.simple_spinner_item, actions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        holder.spinnerAction.adapter = adapter
        holder.spinnerAction.setSelection(config.fileAction.ordinal)
        
        holder.btnSelectFolder.setOnClickListener {
            onFolderSelectListener?.invoke(position)
        }
        
        holder.btnAutoDetect.setOnClickListener {
            onAutoDetectClickListener?.invoke(position)
        }
        
        holder.etPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val newPort = holder.etPort.text.toString().toIntOrNull() ?: config.targetPort
                onPortChangeListener?.invoke(position, newPort)
            }
        }
        
        holder.etDelay.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val newDelay = holder.etDelay.text.toString().toIntOrNull() ?: config.monitoringSettings.delaySeconds
                onDelayChangeListener?.invoke(position, newDelay.coerceIn(0, 60))
            }
        }
        
        holder.spinnerAction.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selectedAction = FileAction.values()[pos]
                onActionChangeListener?.invoke(position, selectedAction)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            onActionChangeListener?.invoke(position, if (isChecked) config.fileAction else FileAction.COPY)
        }
        
        holder.btnDelete.setOnClickListener {
            onConfigDeleteListener?.invoke(position)
        }
    }
    
    override fun getItemCount(): Int = configs.size
    
    fun submitList(newConfigs: List<FolderMonitorConfig>) {
        configs = newConfigs
        notifyDataSetChanged()
    }
}
