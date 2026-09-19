package com.caddy.proxy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var tvDetectedIp: TextView
    private lateinit var etDomain: TextInputEditText
    private lateinit var etToken: TextInputEditText
    private lateinit var etBackendHost: TextInputEditText
    private lateinit var etBackendPort: TextInputEditText
    private lateinit var etListenPort: TextInputEditText
    private lateinit var etManualIp: AutoCompleteTextView
    private lateinit var btnSave: Button
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLogServerLink: TextView
    private lateinit var btnCopyLog: Button
    private lateinit var btnClearLog: Button
    private lateinit var tvLogs: TextView

    private val logBuffer = StringBuilder()
    private var detectedIps: List<IpInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("caddy_proxy_prefs", Context.MODE_PRIVATE)

        initViews()
        loadSavedConfig()
        requestPermissionsIfNeeded()
        detectLocalIp()
        setupListeners()
        LogServer.start { CaddyService.getLogs() }
    }

    private fun initViews() {
        tvDetectedIp = findViewById(R.id.tvDetectedIp)
        etDomain = findViewById(R.id.etDomain)
        etToken = findViewById(R.id.etToken)
        etBackendHost = findViewById(R.id.etBackendHost)
        etBackendPort = findViewById(R.id.etBackendPort)
        etListenPort = findViewById(R.id.etListenPort)
        etManualIp = findViewById(R.id.etManualIp)
        btnSave = findViewById(R.id.btnSave)
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvLogServerLink = findViewById(R.id.tvLogServerLink)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        btnClearLog = findViewById(R.id.btnClearLog)
        tvLogs = findViewById(R.id.tvLogs)
    }

    private fun loadSavedConfig() {
        etDomain.setText(prefs.getString("domain", "tjipto.duckdns.org"))
        etToken.setText(prefs.getString("token", ""))
        etBackendHost.setText(prefs.getString("backend_host", "127.0.0.1"))
        etBackendPort.setText(prefs.getString("backend_port", "8090"))
        etListenPort.setText(prefs.getString("listen_port", "8443"))
        etManualIp.setText(prefs.getString("manual_ip", ""))
        updateUiState(CaddyService.isRunning, if (CaddyService.isRunning) "Running" else "Stopped")

        val existingLogs = CaddyService.getLogs()
        if (existingLogs.isNotBlank()) {
            logBuffer.setLength(0)
            logBuffer.append(existingLogs)
            tvLogs.text = existingLogs
        }
    }

    private fun saveConfig() {
        prefs.edit()
            .putString("domain", etDomain.text?.toString()?.trim())
            .putString("token", etToken.text?.toString()?.trim())
            .putString("backend_host", etBackendHost.text?.toString()?.trim())
            .putString("backend_port", etBackendPort.text?.toString()?.trim())
            .putString("listen_port", etListenPort.text?.toString()?.trim())
            .putString("manual_ip", etManualIp.text?.toString()?.trim())
            .apply()
    }

    private fun detectLocalIp() {
        CoroutineScope(Dispatchers.IO).launch {
            val ip = NetworkHelper.getLocalIpAddress()
            val allIps = NetworkHelper.getAllLocalIPv4()
            withContext(Dispatchers.Main) {
                detectedIps = allIps
                tvDetectedIp.text = "IP Hotspot/WLAN: $ip (Tap untuk refresh)"
                tvLogServerLink.text = "HTTP Log URL: http://$ip:8088/logs"

                val dropdownItems = mutableListOf<String>()
                dropdownItems.add("(Auto-detect IP)")
                for (item in allIps) {
                    dropdownItems.add(item.getDisplayText())
                }

                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, dropdownItems)
                etManualIp.setAdapter(adapter)

                etManualIp.setOnItemClickListener { _, _, position, _ ->
                    if (position == 0) {
                        etManualIp.setText("", false)
                    } else {
                        val chosen = allIps.getOrNull(position - 1)
                        if (chosen != null) {
                            etManualIp.setText(chosen.ip, false)
                        }
                    }
                }

                etManualIp.setOnClickListener {
                    etManualIp.showDropDown()
                }
            }
        }
    }

    private fun setupListeners() {
        tvDetectedIp.setOnClickListener {
            detectLocalIp()
            Toast.makeText(this, "Memindai ulang IP adapter jaringan...", Toast.LENGTH_SHORT).show()
        }

        tvLogServerLink.setOnClickListener {
            val linkText = tvLogServerLink.text.toString().removePrefix("HTTP Log URL: ").trim()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Log URL", linkText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "URL Log disalin: $linkText", Toast.LENGTH_SHORT).show()
        }

        btnCopyLog.setOnClickListener {
            val logs = CaddyService.getLogs().ifBlank { logBuffer.toString() }
            if (logs.isBlank()) {
                Toast.makeText(this, "Log masih kosong", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Caddy Logs", logs)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Log disalin ke clipboard! Siap di-paste", Toast.LENGTH_LONG).show()
            }
        }

        btnClearLog.setOnClickListener {
            logBuffer.setLength(0)
            CaddyService.clearLogs()
            tvLogs.text = ""
            Toast.makeText(this, "Log dibersihkan", Toast.LENGTH_SHORT).show()
        }

        btnSave.setOnClickListener {
            saveConfig()
            Toast.makeText(this, getString(R.string.saved_toast), Toast.LENGTH_SHORT).show()
        }

        btnToggle.setOnClickListener {
            if (CaddyService.isRunning) {
                stopProxy()
            } else {
                startProxy()
            }
        }

        CaddyService.logListener = { message ->
            runOnUiThread {
                logBuffer.append(message).append("\n")
                // Keep max 100 lines
                val lines = logBuffer.lines()
                if (lines.size > 120) {
                    val trimmed = lines.takeLast(100).joinToString("\n")
                    logBuffer.setLength(0)
                    logBuffer.append(trimmed).append("\n")
                }
                tvLogs.text = logBuffer.toString()
            }
        }

        CaddyService.statusListener = { running, text ->
            runOnUiThread {
                updateUiState(running, text)
            }
        }
    }

    private fun startProxy() {
        val domain = etDomain.text?.toString()?.trim() ?: ""
        val token = etToken.text?.toString()?.trim() ?: ""
        val backendHost = etBackendHost.text?.toString()?.trim() ?: "127.0.0.1"
        val backendPort = etBackendPort.text?.toString()?.trim() ?: "8090"
        val listenPort = etListenPort.text?.toString()?.trim() ?: "8443"
        val manualIp = etManualIp.text?.toString()?.trim() ?: ""

        if (domain.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "Domain and DuckDNS Token are required!", Toast.LENGTH_SHORT).show()
            return
        }

        saveConfig()

        val intent = Intent(this, CaddyService::class.java).apply {
            action = CaddyService.ACTION_START
            putExtra(CaddyService.EXTRA_DOMAIN, domain)
            putExtra(CaddyService.EXTRA_TOKEN, token)
            putExtra(CaddyService.EXTRA_BACKEND_HOST, backendHost)
            putExtra(CaddyService.EXTRA_BACKEND_PORT, backendPort)
            putExtra(CaddyService.EXTRA_LISTEN_PORT, listenPort)
            putExtra(CaddyService.EXTRA_MANUAL_IP, manualIp)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopProxy() {
        val intent = Intent(this, CaddyService::class.java).apply {
            action = CaddyService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateUiState(running: Boolean, statusText: String) {
        if (running) {
            btnToggle.text = getString(R.string.btn_stop)
            btnToggle.setBackgroundColor(Color.parseColor("#F44336"))
            tvStatus.text = "Status: $statusText"
            tvStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            btnToggle.text = getString(R.string.btn_start)
            btnToggle.setBackgroundColor(Color.parseColor("#1E88E5"))
            tvStatus.text = "Status: $statusText"
            tvStatus.setTextColor(Color.parseColor("#F44336"))
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }
}
