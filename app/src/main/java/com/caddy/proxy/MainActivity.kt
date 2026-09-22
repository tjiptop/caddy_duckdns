package com.caddy.proxy

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.Filter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var tvDetectedIp: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetails: TextView
    private lateinit var btnToggle: Button

    // Lightweight Stats
    private lateinit var tvUptime: TextView
    private lateinit var tvMemory: TextView
    private lateinit var tvCertStatusMini: TextView
    private var statsJob: Job? = null

    // Client Download Portal Views
    private lateinit var tvPortalUrl: TextView
    private lateinit var btnCopyPortalLink: Button
    private lateinit var btnSharePortalLink: Button

    // Log Switch & Container
    private lateinit var swShowLogs: SwitchCompat
    private lateinit var layoutLogs: LinearLayout
    private lateinit var btnCopyLog: Button
    private lateinit var btnClearLog: Button
    private lateinit var tvLogs: TextView

    // Accordion & Settings Input Fields
    private lateinit var btnToggleSettings: LinearLayout
    private lateinit var tvSettingsExpandIndicator: TextView
    private lateinit var layoutSettings: LinearLayout
    private lateinit var etDomain: TextInputEditText
    private lateinit var etToken: TextInputEditText
    private lateinit var etBackendHost: TextInputEditText
    private lateinit var etBackendPort: TextInputEditText
    private lateinit var etListenPort: TextInputEditText
    private lateinit var etManualIp: AutoCompleteTextView
    private lateinit var cbDisableLog: MaterialCheckBox
    private lateinit var cbEnablePortal: MaterialCheckBox
    private lateinit var btnSave: Button

    // SSL Certificate Management Views inside settings
    private lateinit var tvCertStatus: TextView
    private lateinit var btnPasteCert: Button
    private lateinit var btnImportCert: Button

    private val logBuffer = StringBuilder()
    private var detectedIps: List<IpInfo> = emptyList()

    private val certFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { handleCertFilePicked(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("caddy_proxy_prefs", Context.MODE_PRIVATE)

        initViews()
        loadSavedConfig()
        requestPermissionsIfNeeded()
        detectLocalIp()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateCertStatus()
        manageCertServer()
        startStatsLoop()
    }

    override fun onPause() {
        super.onPause()
        stopStatsLoop()
    }

    private fun initViews() {
        tvDetectedIp = findViewById(R.id.tvDetectedIp)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDetails = findViewById(R.id.tvStatusDetails)
        btnToggle = findViewById(R.id.btnToggle)

        // Stats
        tvUptime = findViewById(R.id.tvUptime)
        tvMemory = findViewById(R.id.tvMemory)
        tvCertStatusMini = findViewById(R.id.tvCertStatusMini)

        // Portal views
        tvPortalUrl = findViewById(R.id.tvPortalUrl)
        btnCopyPortalLink = findViewById(R.id.btnCopyPortalLink)
        btnSharePortalLink = findViewById(R.id.btnSharePortalLink)

        // Log views
        swShowLogs = findViewById(R.id.swShowLogs)
        layoutLogs = findViewById(R.id.layoutLogs)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        btnClearLog = findViewById(R.id.btnClearLog)
        tvLogs = findViewById(R.id.tvLogs)

        // Settings Accordion & Inputs
        btnToggleSettings = findViewById(R.id.btnToggleSettings)
        tvSettingsExpandIndicator = findViewById(R.id.tvSettingsExpandIndicator)
        layoutSettings = findViewById(R.id.layoutSettings)
        etDomain = findViewById(R.id.etDomain)
        etToken = findViewById(R.id.etToken)
        etBackendHost = findViewById(R.id.etBackendHost)
        etBackendPort = findViewById(R.id.etBackendPort)
        etListenPort = findViewById(R.id.etListenPort)
        etManualIp = findViewById(R.id.etManualIp)
        cbDisableLog = findViewById(R.id.cbDisableLog)
        cbEnablePortal = findViewById(R.id.cbEnablePortal)
        btnSave = findViewById(R.id.btnSave)

        // SSL views
        tvCertStatus = findViewById(R.id.tvCertStatus)
        btnPasteCert = findViewById(R.id.btnPasteCert)
        btnImportCert = findViewById(R.id.btnImportCert)
    }

    private fun loadSavedConfig() {
        try {
            etDomain.setText(prefs.getString("domain", ""))
            etToken.setText(prefs.getString("token", ""))
            etBackendHost.setText(prefs.getString("backend_host", "127.0.0.1"))
            etBackendPort.setText(prefs.getString("backend_port", "8090"))
            etListenPort.setText(prefs.getString("listen_port", "8443"))
            etManualIp.setText(prefs.getString("manual_ip", ""))
            cbDisableLog.isChecked = prefs.getBoolean("disable_log", true)
            cbEnablePortal.isChecked = prefs.getBoolean("enable_portal", true)

            val showLogs = prefs.getBoolean("show_logs", false)
            swShowLogs.isChecked = showLogs
            layoutLogs.visibility = if (showLogs) View.VISIBLE else View.GONE

            updateUiState(CaddyService.isRunning, if (CaddyService.isRunning) "Aktif" else "Nonaktif")
            updateCertStatus()
            updatePortalUrl()
            manageCertServer()

            val existingLogs = CaddyService.getLogs()
            if (existingLogs.isNotBlank()) {
                logBuffer.setLength(0)
                logBuffer.append(existingLogs)
                tvLogs.text = existingLogs
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            .putBoolean("disable_log", cbDisableLog.isChecked)
            .putBoolean("enable_portal", cbEnablePortal.isChecked)
            .apply()

        updateUiState(CaddyService.isRunning, if (CaddyService.isRunning) "Aktif" else "Nonaktif")
    }

    private fun detectLocalIp() {
        CoroutineScope(Dispatchers.IO).launch {
            val ip = NetworkHelper.getLocalIpAddress()
            val allIps = NetworkHelper.getAllLocalIPv4()
            withContext(Dispatchers.Main) {
                detectedIps = allIps
                tvDetectedIp.text = "IP Hotspot/WLAN: $ip (Tap untuk refresh)"
                updatePortalUrl(ip)

                val dropdownItems = mutableListOf<String>()
                dropdownItems.add("(Auto-detect IP)")
                for (item in allIps) {
                    dropdownItems.add(item.getDisplayText())
                }

                val adapter = IpDropdownAdapter(this@MainActivity, dropdownItems)
                etManualIp.setAdapter(adapter)
                etManualIp.setDropDownBackgroundResource(R.drawable.bg_dropdown_popup)

                etManualIp.setOnItemClickListener { _, _, position, _ ->
                    if (position == 0) {
                        etManualIp.setText("", false)
                    } else {
                        val chosen = allIps.getOrNull(position - 1)
                        if (chosen != null) {
                            etManualIp.setText(chosen.ip, false)
                        }
                    }
                    updatePortalUrl()
                }

                etManualIp.setOnClickListener {
                    etManualIp.showDropDown()
                }

                etManualIp.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        etManualIp.showDropDown()
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        tvDetectedIp.setOnClickListener {
            detectLocalIp()
            Toast.makeText(this, "Memindai ulang IP adapter jaringan...", Toast.LENGTH_SHORT).show()
        }

        // Accordion Toggle Settings
        btnToggleSettings.setOnClickListener {
            if (layoutSettings.visibility == View.VISIBLE) {
                layoutSettings.visibility = View.GONE
                tvSettingsExpandIndicator.text = getString(R.string.expand_settings)
            } else {
                layoutSettings.visibility = View.VISIBLE
                tvSettingsExpandIndicator.text = getString(R.string.collapse_settings)
            }
        }

        // Show/Hide Logs Switch
        swShowLogs.setOnCheckedChangeListener { _, isChecked ->
            layoutLogs.visibility = if (isChecked) View.VISIBLE else View.GONE
            prefs.edit().putBoolean("show_logs", isChecked).apply()
        }

        btnCopyLog.setOnClickListener {
            val logs = CaddyService.getLogs().ifBlank { logBuffer.toString() }
            if (logs.isBlank()) {
                Toast.makeText(this, "Log masih kosong", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Caddy Logs", logs)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.copied_toast), Toast.LENGTH_LONG).show()
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
            updateCertStatus()
            Toast.makeText(this, getString(R.string.saved_toast), Toast.LENGTH_SHORT).show()
        }

        btnToggle.setOnClickListener {
            if (CaddyService.isRunning) {
                stopProxy()
            } else {
                startProxy()
            }
        }

        // SSL listeners
        btnPasteCert.setOnClickListener {
            showPasteCertDialog()
        }

        btnImportCert.setOnClickListener {
            certFileLauncher.launch(arrayOf("*/*"))
        }

        // Portal listeners
        btnCopyPortalLink.setOnClickListener {
            copyPortalLink()
        }

        btnSharePortalLink.setOnClickListener {
            sharePortalLink()
        }

        cbEnablePortal.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enable_portal", isChecked).apply()
            manageCertServer()
        }

        etDomain.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCertStatus()
                updateUiState(CaddyService.isRunning, if (CaddyService.isRunning) "Aktif" else "Nonaktif")
            }
        })

        CaddyService.logListener = { message ->
            runOnUiThread {
                logBuffer.append(message).append("\n")
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

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                updateStats()
                delay(1000)
            }
        }
    }

    private fun stopStatsLoop() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun updateStats() {
        try {
            // 1. Uptime
            val isRunning = CaddyService.isRunning
            val startTime = CaddyService.startTime
            if (isRunning && startTime > 0L) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                val h = elapsed / 3600
                val m = (elapsed % 3600) / 60
                val s = elapsed % 60
                tvUptime.text = String.format("%02d:%02d:%02d", h, m, s)
            } else {
                tvUptime.text = "00:00:00"
            }

            // 2. RAM Usage
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            tvMemory.text = "$usedMb MB"

            // 3. Mini SSL Status
            val domain = getCleanDomain()
            val token = etToken.text?.toString()?.trim() ?: ""
            val savedCrt = if (domain.isNotBlank()) prefs.getString("saved_cert_$domain", null) else null
            val certFile = if (domain.isNotBlank()) File(File(filesDir, "certs"), "$domain.crt") else null
            val hasCert = (!savedCrt.isNullOrBlank()) || (certFile != null && certFile.exists() && certFile.length() > 0L)
            
            if (hasCert) {
                tvCertStatusMini.text = "Let's Encrypt"
                tvCertStatusMini.setTextColor(Color.parseColor("#4CAF50"))
            } else if (CaddyService.isRunning) {
                if (token.isNotBlank()) {
                    tvCertStatusMini.text = "Meminta SSL..."
                    tvCertStatusMini.setTextColor(Color.parseColor("#FFD54F"))
                } else {
                    tvCertStatusMini.text = "Internal CA"
                    tvCertStatusMini.setTextColor(Color.parseColor("#4FC3F7"))
                }
            } else {
                tvCertStatusMini.text = "Belum Ada"
                tvCertStatusMini.setTextColor(Color.parseColor("#FFB300"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getCleanDomain(): String {
        val d = etDomain.text?.toString()?.trim()?.replace("dnsduck.org", "duckdns.org") ?: ""
        return if (d.isNotBlank() && !d.contains(".")) "$d.duckdns.org" else d
    }

    private fun updateCertStatus() {
        val domain = getCleanDomain()
        val token = etToken.text?.toString()?.trim() ?: ""
        if (domain.isBlank()) {
            tvCertStatus.text = "Masukkan domain DuckDNS terlebih dahulu"
            tvCertStatus.setTextColor(Color.parseColor("#9E9E9E"))
            return
        }

        val savedCrt = prefs.getString("saved_cert_$domain", null)
        val certFile = File(File(filesDir, "certs"), "$domain.crt")
        val keyFile = File(File(filesDir, "certs"), "$domain.key")

        val hasCert = (!savedCrt.isNullOrBlank()) ||
                (certFile.exists() && certFile.length() > 0L && keyFile.exists() && keyFile.length() > 0L)

        if (hasCert) {
            val size = if (certFile.exists()) certFile.length() else savedCrt?.length?.toLong() ?: 0L
            tvCertStatus.text = "✅ Sertifikat Terpasang: $domain ($size bytes)"
            tvCertStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else if (CaddyService.isRunning && token.isNotBlank()) {
            tvCertStatus.text = "⏳ Sedang meminta sertifikat Let's Encrypt resmi via DNS-01..."
            tvCertStatus.setTextColor(Color.parseColor("#FFD54F"))
        } else if (token.isBlank()) {
            tvCertStatus.text = "ℹ️ Mode Internal CA (Isi Token DuckDNS untuk SSL resmi Let's Encrypt)"
            tvCertStatus.setTextColor(Color.parseColor("#81D4FA"))
        } else {
            tvCertStatus.text = "⚠️ Belum ada sertifikat lokal untuk $domain"
            tvCertStatus.setTextColor(Color.parseColor("#FF9800"))
        }
    }

    private fun updatePortalUrl(ip: String? = null) {
        val manual = etManualIp.text?.toString()?.trim() ?: ""
        val targetIp = if (manual.isNotBlank()) {
            manual
        } else if (!ip.isNullOrBlank() && ip != "127.0.0.1") {
            ip
        } else {
            NetworkHelper.getLocalIpAddress().ifBlank { "127.0.0.1" }
        }
        tvPortalUrl.text = "http://$targetIp:8080/"
    }

    private fun copyPortalLink() {
        val url = tvPortalUrl.text.toString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Portal Sertifikat", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Link portal disalin: $url", Toast.LENGTH_SHORT).show()
    }

    private fun sharePortalLink() {
        val url = tvPortalUrl.text.toString()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Download Sertifikat SSL")
            putExtra(Intent.EXTRA_TEXT, "Buka link ini di browser Anda untuk mengunduh sertifikat SSL: $url")
        }
        startActivity(Intent.createChooser(intent, "Bagikan Link Portal Sertifikat"))
    }

    private fun manageCertServer() {
        val enable = prefs.getBoolean("enable_portal", true)
        if (enable) {
            CertServerManager.start(this, onLog = { msg ->
                CaddyService.logListener?.invoke(msg)
            }, onCertReceived = { domain, _, _ ->
                runOnUiThread {
                    if (etDomain.text.isNullOrBlank()) {
                        etDomain.setText(domain)
                    }
                    updateCertStatus()
                    Toast.makeText(this, "Sertifikat untuk $domain berhasil diterima dan aktif!", Toast.LENGTH_LONG).show()
                    if (CaddyService.isRunning) {
                        stopProxy()
                        btnToggle.postDelayed({ startProxy() }, 1000)
                    }
                }
            })
        } else {
            CertServerManager.stop()
        }
    }

    private fun showPasteCertDialog() {
        val domain = getCleanDomain()
        val dialogView = layoutInflater.inflate(R.layout.dialog_paste_cert, null)
        val dDomain = dialogView.findViewById<TextInputEditText>(R.id.dialogDomain)
        val dCrt = dialogView.findViewById<TextInputEditText>(R.id.dialogCrt)
        val dKey = dialogView.findViewById<TextInputEditText>(R.id.dialogKey)

        dDomain.setText(domain)
        dCrt.setText(prefs.getString("saved_cert_$domain", ""))
        dKey.setText(prefs.getString("saved_key_$domain", ""))

        MaterialAlertDialogBuilder(this, R.style.Theme_CaddyProxy_Dialog)
            .setView(dialogView)
            .setPositiveButton("Simpan") { _, _ ->
                val inputDomain = dDomain.text?.toString()?.trim()?.replace("dnsduck.org", "duckdns.org") ?: ""
                val cleanDomain = if (inputDomain.isNotBlank() && !inputDomain.contains(".")) "$inputDomain.duckdns.org" else inputDomain
                val crt = dCrt.text?.toString()?.trim() ?: ""
                val key = dKey.text?.toString()?.trim() ?: ""

                if (cleanDomain.isBlank() || crt.isBlank() || key.isBlank()) {
                    Toast.makeText(this, "Domain, CRT, dan KEY tidak boleh kosong!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                prefs.edit()
                    .putString("domain", cleanDomain)
                    .putString("saved_cert_$cleanDomain", crt)
                    .putString("saved_key_$cleanDomain", key)
                    .apply()

                val certsDir = File(filesDir, "certs").apply { mkdirs() }
                File(certsDir, "$cleanDomain.crt").writeText(crt)
                File(certsDir, "$cleanDomain.key").writeText(key)

                etDomain.setText(cleanDomain)
                updateCertStatus()
                Toast.makeText(this, "Sertifikat SSL berhasil disimpan secara permanen!", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun handleCertFilePicked(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val text = input.bufferedReader().readText()
                val domain = getCleanDomain().ifBlank { "absenku.duckdns.org" }
                val certsDir = File(filesDir, "certs").apply { mkdirs() }

                if (text.contains("BEGIN CERTIFICATE")) {
                    prefs.edit().putString("saved_cert_$domain", text).apply()
                    File(certsDir, "$domain.crt").writeText(text)
                    updateCertStatus()
                    Toast.makeText(this, "File sertifikat (.crt) berhasil diimpor untuk $domain!", Toast.LENGTH_LONG).show()
                } else if (text.contains("BEGIN PRIVATE KEY") || text.contains("BEGIN RSA PRIVATE KEY") || text.contains("BEGIN EC PRIVATE KEY")) {
                    prefs.edit().putString("saved_key_$domain", text).apply()
                    File(certsDir, "$domain.key").writeText(text)
                    updateCertStatus()
                    Toast.makeText(this, "File private key (.key) berhasil diimpor untuk $domain!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Format file tidak dikenali sebagai sertifikat (.crt) atau private key (.key)", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membaca file: ${e.message}", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Domain dan DuckDNS Token wajib diisi pada Pengaturan!", Toast.LENGTH_LONG).show()
            // Automatically open settings if empty
            layoutSettings.visibility = View.VISIBLE
            tvSettingsExpandIndicator.text = getString(R.string.collapse_settings)
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
            putExtra(CaddyService.EXTRA_DISABLE_LOG, cbDisableLog.isChecked)
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
        val domain = getCleanDomain().ifBlank { "absenku.duckdns.org" }
        val listenPort = etListenPort.text?.toString()?.trim()?.ifBlank { "8443" } ?: "8443"
        val backendHost = etBackendHost.text?.toString()?.trim()?.ifBlank { "127.0.0.1" } ?: "127.0.0.1"
        val backendPort = etBackendPort.text?.toString()?.trim()?.ifBlank { "8090" } ?: "8090"

        tvStatusDetails.text = "https://$domain:$listenPort → $backendHost:$backendPort"

        if (running) {
            btnToggle.text = getString(R.string.btn_stop)
            btnToggle.setBackgroundColor(Color.parseColor("#EF4444"))
            tvStatus.text = "🟢 Status: $statusText"
            tvStatus.setTextColor(Color.parseColor("#22C55E"))
        } else {
            btnToggle.text = getString(R.string.btn_start)
            btnToggle.setBackgroundColor(Color.parseColor("#1E88E5"))
            tvStatus.text = "⚪ Status: $statusText"
            tvStatus.setTextColor(Color.parseColor("#9CA3AF"))
        }
        updateCertStatus()
        updateStats()
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

class IpDropdownAdapter(
    context: Context,
    items: List<String>
) : ArrayAdapter<String>(context, R.layout.item_dropdown, items) {

    private val allItems = ArrayList(items)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent) as TextView
        view.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getDropDownView(position, convertView, parent) as TextView
        view.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        return view
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                return FilterResults().apply {
                    values = allItems
                    count = allItems.size
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                clear()
                if (results != null && results.count > 0) {
                    addAll(results.values as List<String>)
                }
                notifyDataSetChanged()
            }

            override fun convertResultToString(resultValue: Any?): CharSequence {
                return resultValue?.toString() ?: ""
            }
        }
    }
}

