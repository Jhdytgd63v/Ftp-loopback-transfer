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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sandbox.ftptransfer.model.PortFolderConfig
import com.sandbox.ftptransfer.model.ReceiverSettings
import com.google.gson.Gson
import java.io.File

class ReceiverConfigActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnAddMapping: Button
    private lateinit var btnSave: Button
    private lateinit var btnBack: Button
    
    private val adapter = PortConfigAdapter()
    private val configs = mutableListOf<PortFolderConfig>()
    private var selectedConfigIndex = -1
    
    private val settingsFile = "receiver_settings.json"
    private val FOLDER_PICKER_REQUEST = 1001
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver_config)
        
        initViews()
        loadSettings()
        setupClickListeners()
    }
    
    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        btnAddMapping = findViewById(R.id.btnAddMapping)
        btnSave = findViewById(R.id.btnSave)
        btnBack = findViewById(R.id.btnBack)
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        adapter.onFolderSelectListener = { index ->
            selectedConfigIndex = index
            openFolderPicker()
        }
        
        adapter.onPortChangeListener = { index, newPort ->
            configs[index] = configs[index].copy(port = newPort)
        }
    }
    
    private fun loadSettings() {
        try {
            val file = File(filesDir, settingsFile)
            if (file.exists()) {
                val json = file.readText()
                val settings = Gson().fromJson(json, ReceiverSettings::class.java)
                
                configs.clear()
                configs.addAll(settings.getPortConfigs())
            } else {
                // Default configs
                configs.clear()
                configs.addAll(ReceiverSettings.defaultMappings().map { (port, folderName) ->
                    PortFolderConfig(port, "/$folderName/", folderName)
                })
            }
            adapter.submitList(configs.toList())
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading settings", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupClickListeners() {
        btnAddMapping.setOnClickListener {
            val newPort = (configs.maxByOrNull { it.port }?.port ?: 5151) + 1
            val newConfig = PortFolderConfig(
                port = newPort,
                folderPath = "/NewFolder/",
                folderName = "NewFolder"
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
            val folderName = getFolderNameFromUri(uri)
            val folderPath = uri.toString()
            
            configs[selectedConfigIndex] = configs[selectedConfigIndex].copy(
                folderPath = folderPath,
                folderName = folderName
            )
            
            adapter.submitList(configs.toList())
            Toast.makeText(this, "Folder selected: $folderName", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error selecting folder", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun getFolderNameFromUri(uri: Uri): String {
        return uri.lastPathSegment ?: "Unknown Folder"
    }
    
    private fun saveSettings() {
        val timestamp = System.currentTimeMillis()
        try {
            val portMappings = configs.associate { it.port to it.folderName }
            val settings = ReceiverSettings(portMappings = portMappings)
            
            val json = Gson().toJson(settings)
            File(filesDir, settingsFile).writeText(json)
            
            // Save individual folder paths
            configs.forEach { config ->
                val configFile = File(filesDir, "port_${config.port}_config.json")
                val configJson = Gson().toJson(config)
                configFile.writeText(configJson)
            }
            
            // Restart receiver service to apply new port-folder mappings immediately
            val intent = Intent(this, com.sandbox.ftptransfer.service.LoopbackServer::class.java)
            startService(intent)
            
            Toast.makeText(this, "Receiver settings saved (${configs.size} ports). Service restarted @ $timestamp", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving settings", Toast.LENGTH_SHORT).show()
        }
    }
}

class PortConfigAdapter : RecyclerView.Adapter<PortConfigAdapter.ViewHolder>() {
    
    private var configs: List<PortFolderConfig> = emptyList()
    var onFolderSelectListener: ((Int) -> Unit)? = null
    var onPortChangeListener: ((Int, Int) -> Unit)? = null
    
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvPort: TextView = itemView.findViewById(R.id.tvPort)
        val etPort: EditText = itemView.findViewById(R.id.etPort)
        val tvFolder: TextView = itemView.findViewById(R.id.tvFolder)
        val btnSelectFolder: Button = itemView.findViewById(R.id.btnSelectFolder)
        val switchEnabled: Switch = itemView.findViewById(R.id.switchEnabled)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_port_config, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = configs[position]
        
        holder.tvPort.text = "Port:"
        holder.etPort.setText(config.port.toString())
        holder.tvFolder.text = "Folder: ${config.folderName}"
        holder.switchEnabled.isChecked = config.enabled
        
        holder.etPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val newPort = holder.etPort.text.toString().toIntOrNull() ?: config.port
                onPortChangeListener?.invoke(position, newPort)
            }
        }
        
        holder.btnSelectFolder.setOnClickListener {
            onFolderSelectListener?.invoke(position)
        }
    }
    
    override fun getItemCount(): Int = configs.size
    
    fun submitList(newConfigs: List<PortFolderConfig>) {
        configs = newConfigs
        notifyDataSetChanged()
    }
}
