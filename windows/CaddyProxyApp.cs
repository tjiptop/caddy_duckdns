using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Principal;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace CaddyProxyWindows
{
    public class IpInfo
    {
        public string IP { get; set; }
        public string InterfaceName { get; set; }

        public override string ToString()
        {
            return string.IsNullOrEmpty(InterfaceName) ? IP : string.Format("{0} ({1})", IP, InterfaceName);
        }
    }

    public class MainForm : Form
    {
        private Label lblTitle;
        private Label lblIp;
        private TextBox txtDomain;
        private TextBox txtToken;
        private TextBox txtBackendHost;
        private TextBox txtBackendPort;
        private TextBox txtListenPort;
        private ComboBox cmbManualIp;
        private Button btnRefreshIp;
        private CheckBox chkDisableLog;
        private Button btnSave;
        private Button btnToggle;
        private Button btnSetup;
        private Label lblStatus;
        private TextBox txtLogs;
        private NotifyIcon trayIcon;
        private ContextMenuStrip trayMenu;

        private Process caddyProcess;
        private bool isRunning = false;
        private readonly string configFile = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "caddy_proxy_config.json");
        private readonly string caddyExe = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "caddy.exe");

        [STAThread]
        public static void Main(string[] args)
        {
            try
            {
                if (args != null && args.Length > 0)
                {
                    string firstArg = args[0].ToLowerInvariant();
                    if (firstArg == "--service" || firstArg == "-service" || firstArg == "/service")
                    {
                        ServiceRunner.RunHeadless();
                        return;
                    }
                    if (firstArg == "--setup-worker")
                    {
                        SetupWorker.RunElevatedSetup(args);
                        return;
                    }
                    if (firstArg == "--uninstall-service")
                    {
                        SetupWorker.RunUninstallService();
                        return;
                    }
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm());
            }
            catch (Exception ex)
            {
                File.WriteAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "crash.log"), ex.ToString());
            }
        }

        public MainForm()
        {
            InitializeComponent();
            EnsureCaddyBinary(caddyExe, msg => AppendLog(msg));
            LoadConfig();
            DetectLocalIp();
        }

        private void InitializeComponent()
        {
            this.Text = "Caddy HTTPS Reverse Proxy (Windows)";
            this.Size = new Size(680, 730);
            this.MinimumSize = new Size(600, 660);
            this.StartPosition = FormStartPosition.CenterScreen;
            this.BackColor = Color.FromArgb(18, 18, 18);
            this.ForeColor = Color.White;
            this.Font = new Font("Segoe UI", 9.5F);

            // Tray Icon
            trayMenu = new ContextMenuStrip();
            trayMenu.Items.Add("Buka Tampilan", null, (s, e) => { this.Show(); this.WindowState = FormWindowState.Normal; });
            trayMenu.Items.Add("Keluar", null, (s, e) => { StopProxy(); Application.Exit(); });

            trayIcon = new NotifyIcon();
            trayIcon.Text = "Caddy HTTPS Proxy";
            trayIcon.Icon = SystemIcons.Shield;
            trayIcon.ContextMenuStrip = trayMenu;
            trayIcon.Visible = true;
            trayIcon.DoubleClick += (s, e) => { this.Show(); this.WindowState = FormWindowState.Normal; };

            // Main Panel
            TableLayoutPanel mainLayout = new TableLayoutPanel();
            mainLayout.Dock = DockStyle.Fill;
            mainLayout.Padding = new Padding(16);
            mainLayout.RowCount = 5;
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 40));  // Title
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));  // IP Label
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 345)); // Config Box
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));  // Log Header
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));  // Logs
            this.Controls.Add(mainLayout);

            // Title
            lblTitle = new Label {
                Text = "Caddy HTTPS Reverse Proxy (DuckDNS)",
                Font = new Font("Segoe UI", 14F, FontStyle.Bold),
                ForeColor = Color.White,
                Dock = DockStyle.Fill
            };
            mainLayout.Controls.Add(lblTitle, 0, 0);

            // IP Status
            lblIp = new Label {
                Text = "IP Lokal / Hotspot: Mendeteksi...",
                ForeColor = Color.FromArgb(0, 230, 118),
                Font = new Font("Segoe UI", 10F),
                Dock = DockStyle.Fill
            };
            mainLayout.Controls.Add(lblIp, 0, 1);

            // Config Panel (Card)
            Panel card = new Panel {
                BackColor = Color.FromArgb(30, 30, 30),
                Dock = DockStyle.Fill,
                Padding = new Padding(12)
            };
            mainLayout.Controls.Add(card, 0, 2);

            int y = 10;
            // Domain
            Label lDomain = new Label { Text = "DuckDNS Domain:", ForeColor = Color.LightGray, Location = new Point(12, y), Size = new Size(160, 22) };
            txtDomain = new TextBox { Text = "tjipto.duckdns.org", Location = new Point(180, y), Size = new Size(420, 26), BackColor = Color.FromArgb(45, 45, 45), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle };
            card.Controls.Add(lDomain); card.Controls.Add(txtDomain);
            y += 36;

            // Token
            Label lToken = new Label { Text = "DuckDNS Token:", ForeColor = Color.LightGray, Location = new Point(12, y), Size = new Size(160, 22) };
            txtToken = new TextBox { UseSystemPasswordChar = true, Location = new Point(180, y), Size = new Size(420, 26), BackColor = Color.FromArgb(45, 45, 45), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle };
            card.Controls.Add(lToken); card.Controls.Add(txtToken);
            y += 36;

            // Backend Host & Port
            Label lHost = new Label { Text = "Target Host & Port:", ForeColor = Color.LightGray, Location = new Point(12, y), Size = new Size(160, 22) };
            txtBackendHost = new TextBox { Text = "127.0.0.1", Location = new Point(180, y), Size = new Size(240, 26), BackColor = Color.FromArgb(45, 45, 45), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle };
            txtBackendPort = new TextBox { Text = "8090", Location = new Point(430, y), Size = new Size(170, 26), BackColor = Color.FromArgb(45, 45, 45), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle };
            card.Controls.Add(lHost); card.Controls.Add(txtBackendHost); card.Controls.Add(txtBackendPort);
            y += 36;

            // Listen Port & Manual IP Dropdown
            Label lListen = new Label { Text = "HTTPS Port & Custom IP:", UseMnemonic = false, ForeColor = Color.LightGray, Location = new Point(12, y), Size = new Size(160, 22) };
            txtListenPort = new TextBox { Text = "8443", Location = new Point(180, y), Size = new Size(80, 26), BackColor = Color.FromArgb(45, 45, 45), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle };

            cmbManualIp = new ComboBox {
                Location = new Point(268, y),
                Size = new Size(300, 26),
                BackColor = Color.FromArgb(45, 45, 45),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                DropDownStyle = ComboBoxStyle.DropDown
            };

            btnRefreshIp = new Button {
                Text = "↻",
                Location = new Point(572, y),
                Size = new Size(28, 26),
                BackColor = Color.FromArgb(60, 60, 60),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 10F, FontStyle.Bold)
            };
            btnRefreshIp.FlatAppearance.BorderSize = 0;
            ToolTip tt = new ToolTip();
            tt.SetToolTip(btnRefreshIp, "Pindai ulang semua IP adapter jaringan");
            btnRefreshIp.Click += (s, e) => { DetectLocalIp(); };

            card.Controls.Add(lListen);
            card.Controls.Add(txtListenPort);
            card.Controls.Add(cmbManualIp);
            card.Controls.Add(btnRefreshIp);
            y += 36;

            // Checkbox Matikan Log
            chkDisableLog = new CheckBox {
                Text = "Matikan Log Akses (Disable Logging)",
                Location = new Point(180, y),
                Size = new Size(350, 24),
                ForeColor = Color.FromArgb(220, 220, 220),
                Font = new Font("Segoe UI", 9F),
                Checked = true
            };
            tt.SetToolTip(chkDisableLog, "Jika dicentang, Caddy tidak mencetak request log ke console (menghemat CPU/RAM & server lebih cepat)");
            card.Controls.Add(chkDisableLog);
            y += 34;

            // Buttons: Save & Start/Stop
            btnSave = new Button {
                Text = "Simpan Pengaturan",
                Location = new Point(180, y),
                Size = new Size(180, 36),
                BackColor = Color.FromArgb(60, 60, 60),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat
            };
            btnSave.FlatAppearance.BorderSize = 0;
            btnSave.Click += (s, e) => {
                SaveConfig();
                MessageBox.Show("Pengaturan berhasil disimpan!", "Informasi", MessageBoxButtons.OK, MessageBoxIcon.Information);
            };

            btnToggle = new Button {
                Text = "Start Caddy Proxy",
                Location = new Point(370, y),
                Size = new Size(230, 36),
                BackColor = Color.FromArgb(30, 136, 229),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 10F, FontStyle.Bold)
            };
            btnToggle.FlatAppearance.BorderSize = 0;
            btnToggle.Click += async (s, e) => {
                if (isRunning) {
                    StopProxy();
                } else {
                    await StartProxy();
                }
            };
            card.Controls.Add(btnSave); card.Controls.Add(btnToggle);
            y += 44;

            // Button: Setup & Instalasi Server (All-in-One Setup Wizard)
            btnSetup = new Button {
                Text = "⚙️ Setup & Instalasi Server (Firewall, Defender, Service)",
                Location = new Point(180, y),
                Size = new Size(420, 34),
                BackColor = Color.FromArgb(0, 121, 107),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 9F, FontStyle.Bold)
            };
            btnSetup.FlatAppearance.BorderSize = 0;
            btnSetup.Click += (s, e) => {
                SaveConfig();
                using (SetupDialog dlg = new SetupDialog())
                {
                    dlg.ShowDialog(this);
                }
            };
            card.Controls.Add(btnSetup);
            y += 38;

            lblStatus = new Label {
                Text = "Status: Stopped",
                ForeColor = Color.FromArgb(244, 67, 54),
                Location = new Point(180, y),
                Size = new Size(420, 22),
                Font = new Font("Segoe UI", 9.5F, FontStyle.Bold)
            };
            card.Controls.Add(lblStatus);

            // Log Header
            Label lblLogHeader = new Label {
                Text = "Live Caddy Log Console:",
                ForeColor = Color.DarkGray,
                Dock = DockStyle.Fill
            };
            mainLayout.Controls.Add(lblLogHeader, 0, 3);

            // Logs
            txtLogs = new TextBox {
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Both,
                Dock = DockStyle.Fill,
                BackColor = Color.Black,
                ForeColor = Color.FromArgb(0, 255, 102),
                Font = new Font("Consolas", 9.5F)
            };
            mainLayout.Controls.Add(txtLogs, 0, 4);

            this.FormClosing += (s, e) => {
                StopProxy();
                trayIcon.Visible = false;
            };
        }

        public static bool EnsureCaddyBinary(string caddyPath, Action<string> logAction = null)
        {
            if (File.Exists(caddyPath))
            {
                try
                {
                    FileInfo fi = new FileInfo(caddyPath);
                    if (fi.Length > 10000000)
                    {
                        return true;
                    }
                }
                catch { }
            }

            try
            {
                if (logAction != null) logAction("Mengekstrak caddy.exe dari embedded resource...");
                Assembly asm = Assembly.GetExecutingAssembly();
                string resName = null;
                foreach (string name in asm.GetManifestResourceNames())
                {
                    if (name.EndsWith("caddy.exe.gz", StringComparison.OrdinalIgnoreCase))
                    {
                        resName = name;
                        break;
                    }
                }

                if (string.IsNullOrEmpty(resName))
                {
                    return File.Exists(caddyPath);
                }

                using (Stream resStream = asm.GetManifestResourceStream(resName))
                {
                    if (resStream == null) return false;
                    using (GZipStream gz = new GZipStream(resStream, CompressionMode.Decompress))
                    using (FileStream fs = new FileStream(caddyPath, FileMode.Create, FileAccess.Write, FileShare.None))
                    {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = gz.Read(buf, 0, buf.Length)) > 0)
                        {
                            fs.Write(buf, 0, r);
                        }
                    }
                }

                if (logAction != null) logAction("caddy.exe berhasil diekstrak dan siap digunakan.");
                return true;
            }
            catch (Exception ex)
            {
                if (logAction != null) logAction("Gagal mengekstrak caddy.exe: " + ex.Message);
                return File.Exists(caddyPath);
            }
        }

        private void DetectLocalIp()
        {
            Task.Run(() => {
                var ipList = GetAllLocalIPv4Static();
                this.Invoke(new Action(() => {
                    string currentVal = GetSelectedIp();
                    cmbManualIp.Items.Clear();

                    foreach (var item in ipList)
                    {
                        cmbManualIp.Items.Add(item);
                    }

                    int selectIdx = -1;
                    if (!string.IsNullOrEmpty(currentVal))
                    {
                        for (int i = 0; i < ipList.Count; i++)
                        {
                            if (ipList[i].IP.Equals(currentVal, StringComparison.OrdinalIgnoreCase))
                            {
                                selectIdx = i;
                                break;
                            }
                        }
                    }

                    if (selectIdx >= 0)
                    {
                        cmbManualIp.SelectedIndex = selectIdx;
                    }
                    else if (!string.IsNullOrEmpty(currentVal))
                    {
                        cmbManualIp.Text = currentVal;
                    }
                    else if (ipList.Count > 0)
                    {
                        cmbManualIp.SelectedIndex = 0;
                    }

                    string bestIp = GetSelectedIp();
                    lblIp.Text = "IP Terdeteksi: " + (string.IsNullOrEmpty(bestIp) ? "127.0.0.1" : bestIp) + " (" + ipList.Count + " adapter ditemukan)";
                }));
            });
        }

        public static List<IpInfo> GetAllLocalIPv4Static()
        {
            var list = new List<IpInfo>();
            try
            {
                foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                        continue;

                    foreach (UnicastIPAddressInformation ip in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (ip.Address.AddressFamily == AddressFamily.InterNetwork)
                        {
                            string ipStr = ip.Address.ToString();
                            if (!list.Exists(x => x.IP == ipStr))
                            {
                                list.Add(new IpInfo { IP = ipStr, InterfaceName = ni.Name });
                            }
                        }
                    }
                }
            }
            catch { }

            list.Sort((a, b) => {
                bool aLink = a.IP.StartsWith("169.254");
                bool bLink = b.IP.StartsWith("169.254");
                if (aLink != bLink) return aLink ? 1 : -1;
                return a.IP.CompareTo(b.IP);
            });

            return list;
        }

        private string GetSelectedIp()
        {
            IpInfo info = cmbManualIp.SelectedItem as IpInfo;
            if (info != null)
            {
                return info.IP;
            }

            string text = cmbManualIp.Text.Trim();
            if (string.IsNullOrEmpty(text)) return "";

            int parenIdx = text.IndexOf(" (");
            if (parenIdx > 0)
            {
                return text.Substring(0, parenIdx).Trim();
            }

            string[] parts = text.Split(new char[] { ' ', '-' }, StringSplitOptions.RemoveEmptyEntries);
            IPAddress dummy;
            if (parts.Length > 0 && IPAddress.TryParse(parts[0], out dummy))
            {
                return parts[0];
            }

            return text;
        }

        private async Task StartProxy()
        {
            string domain = txtDomain.Text.Trim();
            string token = txtToken.Text.Trim();
            string host = string.IsNullOrWhiteSpace(txtBackendHost.Text) ? "127.0.0.1" : txtBackendHost.Text.Trim();
            string port = string.IsNullOrWhiteSpace(txtBackendPort.Text) ? "8090" : txtBackendPort.Text.Trim();
            string listenPort = string.IsNullOrWhiteSpace(txtListenPort.Text) ? "8443" : txtListenPort.Text.Trim();
            string manualIp = GetSelectedIp();

            if (string.IsNullOrEmpty(domain) || string.IsNullOrEmpty(token))
            {
                MessageBox.Show("DuckDNS Domain dan Token wajib diisi!", "Peringatan", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            EnsureCaddyBinary(caddyExe, msg => AppendLog(msg));

            if (!File.Exists(caddyExe))
            {
                MessageBox.Show("caddy.exe tidak ditemukan di folder: " + caddyExe, "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }

            SaveConfig();

            AppendLog("--- Memulai Caddy HTTPS Proxy ---");
            string targetIp = string.IsNullOrEmpty(manualIp) ? "127.0.0.1" : manualIp;
            AppendLog("Target IP Lokal: " + targetIp);

            // Update DuckDNS
            AppendLog("Mengupdate DNS DuckDNS (" + domain + " -> " + targetIp + ")...");
            bool dnsOk = await UpdateDuckDns(domain, token, targetIp);
            if (!dnsOk)
            {
                AppendLog("Peringatan: Update DuckDNS gagal. Melanjutkan start proxy...");
            }

            // Generate Caddyfile
            string caddyfilePath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Caddyfile");
            string redirTarget = (listenPort == "443") ? "https://{host}{uri}" : ("https://{host}:" + listenPort + "{uri}");
            string logSection = chkDisableLog.Checked ? "" : "    log {\n        output stdout\n        format console\n    }\n";

            string caddyConfig = "{\n" +
                                 "    admin off\n" +
                                 "    auto_https disable_redirects\n" +
                                 "}\n\n" +
                                 "http://" + domain + " {\n" +
                                 "    redir " + redirTarget + " permanent\n" +
                                 "}\n\n" +
                                 domain + ":" + listenPort + " {\n" +
                                 "    tls {\n" +
                                 "        dns duckdns " + token + "\n" +
                                 "        resolvers 8.8.8.8 8.8.4.4\n" +
                                 "    }\n" +
                                 logSection +
                                 "    reverse_proxy " + host + ":" + port + " {\n" +
                                 "        header_up Host {host}\n" +
                                 "        header_up X-Real-IP {remote_host}\n" +
                                 "    }\n" +
                                 "}\n";
            File.WriteAllText(caddyfilePath, caddyConfig);
            AppendLog("Caddyfile dibuat di: " + caddyfilePath);

            try
            {
                ProcessStartInfo psi = new ProcessStartInfo {
                    FileName = caddyExe,
                    Arguments = "run --config \"" + caddyfilePath + "\"",
                    WorkingDirectory = AppDomain.CurrentDomain.BaseDirectory,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true
                };

                caddyProcess = new Process { StartInfo = psi };
                caddyProcess.OutputDataReceived += (s, e) => {
                    if (e.Data != null) {
                        if (!chkDisableLog.Checked || e.Data.Contains("\"level\":\"error\"") || e.Data.Contains("ERROR"))
                            AppendLog("[Caddy] " + e.Data);
                    }
                };
                caddyProcess.ErrorDataReceived += (s, e) => {
                    if (e.Data != null) {
                        if (!chkDisableLog.Checked || e.Data.Contains("\"level\":\"error\"") || e.Data.Contains("ERROR"))
                            AppendLog("[Caddy Log] " + e.Data);
                    }
                };

                caddyProcess.Start();
                caddyProcess.BeginOutputReadLine();
                caddyProcess.BeginErrorReadLine();

                isRunning = true;
                btnToggle.Text = "Stop Proxy";
                btnToggle.BackColor = Color.FromArgb(244, 67, 54);
                lblStatus.Text = "Status: Running (HTTPS :" + listenPort + " -> :" + port + ")";
                lblStatus.ForeColor = Color.FromArgb(76, 175, 80);
                string urlDisplay = "https://" + domain + (listenPort == "443" ? "" : ":" + listenPort);
                AppendLog("Caddy aktif! Buka browser: " + urlDisplay);
                if (chkDisableLog.Checked)
                {
                    AppendLog("ℹ️ Log akses dinonaktifkan (mode hemat resource). Hilangkan centang 'Matikan Log' jika ingin memantau traffic.");
                }
            }
            catch (Exception ex)
            {
                AppendLog("Gagal menjalankan Caddy: " + ex.Message);
                StopProxy();
            }
        }

        private void StopProxy()
        {
            try
            {
                if (caddyProcess != null && !caddyProcess.HasExited)
                {
                    caddyProcess.Kill();
                    caddyProcess.Dispose();
                }
            }
            catch { }
            finally
            {
                caddyProcess = null;
                isRunning = false;
                if (!this.IsDisposed)
                {
                    this.Invoke(new Action(() => {
                        btnToggle.Text = "Start Caddy Proxy";
                        btnToggle.BackColor = Color.FromArgb(30, 136, 229);
                        lblStatus.Text = "Status: Stopped";
                        lblStatus.ForeColor = Color.FromArgb(244, 67, 54);
                        AppendLog("Caddy telah dihentikan.");
                    }));
                }
            }
        }

        private async Task<bool> UpdateDuckDns(string fullDomain, string token, string ip)
        {
            try
            {
                string subdomain = fullDomain.Replace(".duckdns.org", "").TrimEnd('.');
                string url = "https://www.duckdns.org/update?domains=" + subdomain + "&token=" + token + "&ip=" + ip;
                using (HttpClient client = new HttpClient())
                {
                    client.Timeout = TimeSpan.FromSeconds(15);
                    string resp = await client.GetStringAsync(url);
                    if (resp.Contains("OK"))
                    {
                        AppendLog("DuckDNS berhasil diupdate: " + subdomain + " -> " + ip);
                        return true;
                    }
                    else
                    {
                        AppendLog("DuckDNS respon: " + resp);
                        return false;
                    }
                }
            }
            catch (Exception ex)
            {
                AppendLog("DuckDNS update error: " + ex.Message);
                return false;
            }
        }

        private void AppendLog(string message)
        {
            if (txtLogs.IsDisposed) return;
            if (txtLogs.InvokeRequired)
            {
                txtLogs.Invoke(new Action<string>(AppendLog), message);
                return;
            }
            txtLogs.AppendText(message + Environment.NewLine);
        }

        private void SaveConfig()
        {
            try
            {
                string json = "{\n" +
                              "  \"domain\": \"" + txtDomain.Text.Replace("\"", "\\\"") + "\",\n" +
                              "  \"token\": \"" + txtToken.Text.Replace("\"", "\\\"") + "\",\n" +
                              "  \"host\": \"" + txtBackendHost.Text.Replace("\"", "\\\"") + "\",\n" +
                              "  \"port\": \"" + txtBackendPort.Text.Replace("\"", "\\\"") + "\",\n" +
                              "  \"listenPort\": \"" + txtListenPort.Text.Replace("\"", "\\\"") + "\",\n" +
                              "  \"manualIp\": \"" + GetSelectedIp().Replace("\"", "\\\"") + "\",\n" +
                              "  \"disableLog\": " + (chkDisableLog.Checked ? "true" : "false") + "\n" +
                              "}";
                File.WriteAllText(configFile, json);
            }
            catch { }
        }

        private void LoadConfig()
        {
            try
            {
                if (File.Exists(configFile))
                {
                    string content = File.ReadAllText(configFile);
                    txtDomain.Text = ExtractJsonValueHelper(content, "domain", "tjipto.duckdns.org");
                    txtToken.Text = ExtractJsonValueHelper(content, "token", "");
                    txtBackendHost.Text = ExtractJsonValueHelper(content, "host", "127.0.0.1");
                    txtBackendPort.Text = ExtractJsonValueHelper(content, "port", "8090");
                    txtListenPort.Text = ExtractJsonValueHelper(content, "listenPort", "8443");
                    string savedIp = ExtractJsonValueHelper(content, "manualIp", "");
                    if (!string.IsNullOrEmpty(savedIp))
                    {
                        cmbManualIp.Text = savedIp;
                    }
                    chkDisableLog.Checked = ExtractJsonBoolHelper(content, "disableLog", true);
                }
            }
            catch { }
        }

        public static string ExtractJsonValueHelper(string json, string key, string def)
        {
            try
            {
                string search = "\"" + key + "\": \"";
                int idx = json.IndexOf(search);
                if (idx >= 0)
                {
                    int start = idx + search.Length;
                    int end = json.IndexOf("\"", start);
                    if (end > start)
                    {
                        return json.Substring(start, end - start);
                    }
                }
            }
            catch { }
            return def;
        }

        public static bool ExtractJsonBoolHelper(string json, string key, bool def)
        {
            try
            {
                string search = "\"" + key + "\":";
                int idx = json.IndexOf(search);
                if (idx >= 0)
                {
                    int start = idx + search.Length;
                    int end = json.IndexOfAny(new char[] { ',', '\n', '\r', '}' }, start);
                    if (end > start)
                    {
                        string val = json.Substring(start, end - start).Trim().ToLower();
                        if (val == "true") return true;
                        if (val == "false") return false;
                    }
                }
            }
            catch { }
            return def;
        }
    }

    public class SetupDialog : Form
    {
        private CheckBox chkFirewall;
        private CheckBox chkDefender;
        private CheckBox chkService;
        private CheckBox chkShortcut;
        private Button btnRunSetup;
        private Button btnUninstall;
        private Button btnClose;

        public SetupDialog()
        {
            InitializeComponent();
        }

        private void InitializeComponent()
        {
            this.Text = "Setup & Optimalisasi Caddy Server (Windows)";
            this.Size = new Size(580, 460);
            this.FormBorderStyle = FormBorderStyle.FixedDialog;
            this.StartPosition = FormStartPosition.CenterParent;
            this.MaximizeBox = false;
            this.MinimizeBox = false;
            this.BackColor = Color.FromArgb(24, 24, 24);
            this.ForeColor = Color.White;
            this.Font = new Font("Segoe UI", 9.5F);

            TableLayoutPanel layout = new TableLayoutPanel();
            layout.Dock = DockStyle.Fill;
            layout.Padding = new Padding(20);
            layout.RowCount = 5;
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 35));  // Title
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));  // Subtitle
            layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));  // Options Box
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 50));  // Run Button
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 40));  // Secondary Buttons
            this.Controls.Add(layout);

            Label lblTitle = new Label {
                Text = "⚙️ Setup & Optimalisasi Caddy Server",
                Font = new Font("Segoe UI", 13F, FontStyle.Bold),
                ForeColor = Color.White,
                Dock = DockStyle.Fill
            };
            layout.Controls.Add(lblTitle, 0, 0);

            Label lblSub = new Label {
                Text = "Otomatis konfigurasikan sistem agar server Caddy berjalan optimal dan aman.",
                ForeColor = Color.LightGray,
                Dock = DockStyle.Fill
            };
            layout.Controls.Add(lblSub, 0, 1);

            Panel box = new Panel {
                BackColor = Color.FromArgb(34, 34, 34),
                Dock = DockStyle.Fill,
                Padding = new Padding(16)
            };
            layout.Controls.Add(box, 0, 2);

            int y = 14;
            chkFirewall = new CheckBox {
                Text = "Buka Port Firewall (Port 80, 443, 8443, 8090) untuk Semua Jaringan",
                Location = new Point(14, y),
                Size = new Size(500, 24),
                Checked = true,
                ForeColor = Color.White,
                Font = new Font("Segoe UI", 9.5F, FontStyle.Bold)
            };
            Label lblFwDesc = new Label {
                Text = "Membuka port HTTP, HTTPS, & Backend di Windows Firewall (Private, Public, Domain).",
                Location = new Point(34, y + 24),
                Size = new Size(480, 18),
                ForeColor = Color.DarkGray,
                Font = new Font("Segoe UI", 8.2F)
            };
            box.Controls.Add(chkFirewall); box.Controls.Add(lblFwDesc);
            y += 48;

            chkDefender = new CheckBox {
                Text = "Optimalisasi Real-Time Scanner Windows Defender",
                Location = new Point(14, y),
                Size = new Size(500, 24),
                Checked = true,
                ForeColor = Color.White,
                Font = new Font("Segoe UI", 9.5F, FontStyle.Bold)
            };
            Label lblDefDesc = new Label {
                Text = "Mengecualikan folder instalasi & proses caddy.exe agar I/O jaringan tidak terhambat.",
                Location = new Point(34, y + 24),
                Size = new Size(480, 18),
                ForeColor = Color.DarkGray,
                Font = new Font("Segoe UI", 8.2F)
            };
            box.Controls.Add(chkDefender); box.Controls.Add(lblDefDesc);
            y += 48;

            chkService = new CheckBox {
                Text = "Pasang sebagai Windows Service (Autostart Saat Booting)",
                Location = new Point(14, y),
                Size = new Size(500, 24),
                Checked = true,
                ForeColor = Color.White,
                Font = new Font("Segoe UI", 9.5F, FontStyle.Bold)
            };
            Label lblSvcDesc = new Label {
                Text = "Server langsung menyala di background saat Windows boot tanpa perlu login.",
                Location = new Point(34, y + 24),
                Size = new Size(480, 18),
                ForeColor = Color.DarkGray,
                Font = new Font("Segoe UI", 8.2F)
            };
            box.Controls.Add(chkService); box.Controls.Add(lblSvcDesc);
            y += 48;

            chkShortcut = new CheckBox {
                Text = "Buat Shortcut di Desktop & Start Menu",
                Location = new Point(14, y),
                Size = new Size(500, 24),
                Checked = true,
                ForeColor = Color.White,
                Font = new Font("Segoe UI", 9.5F, FontStyle.Bold)
            };
            Label lblLnkDesc = new Label {
                Text = "Membuat ikon pintasan Caddy HTTPS Proxy untuk memudahkan konfigurasi.",
                Location = new Point(34, y + 24),
                Size = new Size(480, 18),
                ForeColor = Color.DarkGray,
                Font = new Font("Segoe UI", 8.2F)
            };
            box.Controls.Add(chkShortcut); box.Controls.Add(lblLnkDesc);

            // Primary Button: Run Setup
            btnRunSetup = new Button {
                Text = "🚀 Jalankan Setup & Optimalisasi Server",
                Dock = DockStyle.Fill,
                BackColor = Color.FromArgb(0, 150, 136),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 10.5F, FontStyle.Bold)
            };
            btnRunSetup.FlatAppearance.BorderSize = 0;
            btnRunSetup.Click += (s, e) => {
                ExecuteSetup();
            };
            layout.Controls.Add(btnRunSetup, 0, 3);

            // Secondary Buttons Panel
            Panel bottomPanel = new Panel { Dock = DockStyle.Fill };
            btnUninstall = new Button {
                Text = "🗑️ Copot Windows Service",
                Location = new Point(0, 4),
                Size = new Size(200, 32),
                BackColor = Color.FromArgb(50, 50, 50),
                ForeColor = Color.FromArgb(255, 138, 128),
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 8.5F)
            };
            btnUninstall.FlatAppearance.BorderSize = 0;
            btnUninstall.Click += (s, e) => {
                ExecuteUninstallService();
            };

            btnClose = new Button {
                Text = "Tutup",
                Location = new Point(430, 4),
                Size = new Size(100, 32),
                BackColor = Color.FromArgb(50, 50, 50),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                DialogResult = DialogResult.Cancel
            };
            btnClose.FlatAppearance.BorderSize = 0;
            bottomPanel.Controls.Add(btnUninstall);
            bottomPanel.Controls.Add(btnClose);
            layout.Controls.Add(bottomPanel, 0, 4);
        }

        private void ExecuteSetup()
        {
            List<string> workerArgs = new List<string>();
            workerArgs.Add("--setup-worker");
            if (!chkFirewall.Checked) workerArgs.Add("firewall=0");
            if (!chkDefender.Checked) workerArgs.Add("defender=0");
            if (!chkService.Checked) workerArgs.Add("service=0");
            if (!chkShortcut.Checked) workerArgs.Add("shortcut=0");

            if (SetupWorker.IsAdministrator())
            {
                SetupWorker.RunElevatedSetup(workerArgs.ToArray());
                this.Close();
            }
            else
            {
                try
                {
                    ProcessStartInfo psi = new ProcessStartInfo {
                        FileName = Process.GetCurrentProcess().MainModule.FileName,
                        Arguments = string.Join(" ", workerArgs.ToArray()),
                        Verb = "runas",
                        UseShellExecute = true
                    };
                    Process p = Process.Start(psi);
                    if (p != null)
                    {
                        p.WaitForExit();
                        this.Close();
                    }
                }
                catch (Exception ex)
                {
                    MessageBox.Show("Izin Administrator diperlukan untuk konfigurasi Firewall & Service:\n" + ex.Message, "Peringatan", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
            }
        }

        private void ExecuteUninstallService()
        {
            if (SetupWorker.IsAdministrator())
            {
                SetupWorker.RunUninstallService();
            }
            else
            {
                try
                {
                    ProcessStartInfo psi = new ProcessStartInfo {
                        FileName = Process.GetCurrentProcess().MainModule.FileName,
                        Arguments = "--uninstall-service",
                        Verb = "runas",
                        UseShellExecute = true
                    };
                    Process p = Process.Start(psi);
                    if (p != null)
                    {
                        p.WaitForExit();
                    }
                }
                catch (Exception ex)
                {
                    MessageBox.Show("Izin Administrator diperlukan untuk mencopot service:\n" + ex.Message, "Peringatan", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
            }
        }
    }

    public static class SetupWorker
    {
        public static bool IsAdministrator()
        {
            try
            {
                WindowsIdentity id = WindowsIdentity.GetCurrent();
                WindowsPrincipal principal = new WindowsPrincipal(id);
                return principal.IsInRole(WindowsBuiltInRole.Administrator);
            }
            catch { return false; }
        }

        public static void RunElevatedSetup(string[] args)
        {
            bool doFirewall = true;
            bool doDefender = true;
            bool doService = true;
            bool doShortcut = true;

            for (int i = 1; i < args.Length; i++)
            {
                string a = args[i].ToLowerInvariant();
                if (a == "firewall=0") doFirewall = false;
                if (a == "defender=0") doDefender = false;
                if (a == "service=0") doService = false;
                if (a == "shortcut=0") doShortcut = false;
            }

            string baseDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
            string exePath = Process.GetCurrentProcess().MainModule.FileName;
            string caddyPath = Path.Combine(baseDir, "caddy.exe");

            MainForm.EnsureCaddyBinary(caddyPath);

            StringBuilder report = new StringBuilder();
            report.AppendLine("=================================================");
            report.AppendLine("   HASIL SETUP & OPTIMALISASI CADDY SERVER       ");
            report.AppendLine("=================================================");
            report.AppendLine("Waktu: " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"));
            report.AppendLine("Folder: " + baseDir);
            report.AppendLine();

            // 1. Firewall
            if (doFirewall)
            {
                try
                {
                    RunPowerShell(
                        "Set-NetFirewallRule -DisplayName 'Caddy' -Profile Any -ErrorAction SilentlyContinue; " +
                        "Remove-NetFirewallRule -DisplayName 'Caddy Server*' -ErrorAction SilentlyContinue; " +
                        "New-NetFirewallRule -DisplayName 'Caddy Server Program' -Direction Inbound -Program '" + caddyPath + "' -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null; " +
                        "New-NetFirewallRule -DisplayName 'Caddy Server Port 80' -Direction Inbound -LocalPort 80 -Protocol TCP -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null; " +
                        "New-NetFirewallRule -DisplayName 'Caddy Server Port 443' -Direction Inbound -LocalPort 443 -Protocol TCP -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null; " +
                        "New-NetFirewallRule -DisplayName 'Caddy Server Port 8443' -Direction Inbound -LocalPort 8443 -Protocol TCP -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null; " +
                        "New-NetFirewallRule -DisplayName 'Caddy Server Port 8090' -Direction Inbound -LocalPort 8090 -Protocol TCP -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null;"
                    );
                    report.AppendLine("[OK] Windows Firewall: Port 80, 443, 8443, 8090 dan Program caddy.exe diizinkan penuh (Semua Profil: Private, Public, Domain).");
                }
                catch (Exception ex)
                {
                    report.AppendLine("[GAGAL] Windows Firewall: " + ex.Message);
                }
            }

            // 2. Windows Defender Exclusions
            if (doDefender)
            {
                try
                {
                    RunPowerShell(
                        "Add-MpPreference -ExclusionPath '" + baseDir + "' -ErrorAction SilentlyContinue; " +
                        "Add-MpPreference -ExclusionProcess 'caddy.exe' -ErrorAction SilentlyContinue; " +
                        "Add-MpPreference -ExclusionProcess 'CaddyProxy.exe' -ErrorAction SilentlyContinue;"
                    );
                    report.AppendLine("[OK] Windows Defender: Pengecualian Real-Time Scanner aktif untuk folder & proses caddy.");
                }
                catch (Exception ex)
                {
                    report.AppendLine("[GAGAL] Windows Defender: " + ex.Message);
                }
            }

            // 3. Windows Service (Task Scheduler ONSTART)
            if (doService)
            {
                try
                {
                    string taskCmd = "/Create /TN \"CaddyProxyService\" /TR \"\\\"" + exePath + "\\\" --service\" /SC ONSTART /RU \"SYSTEM\" /RL HIGHEST /F";
                    RunProcess("schtasks.exe", taskCmd);
                    report.AppendLine("[OK] Windows Service: Task Scheduler 'CaddyProxyService' berhasil dipasang (Autostart Booting tanpa login).");
                }
                catch (Exception ex)
                {
                    report.AppendLine("[GAGAL] Windows Service: " + ex.Message);
                }
            }

            // 4. Shortcuts
            if (doShortcut)
            {
                try
                {
                    CreateShortcut(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Caddy HTTPS Proxy", exePath);
                    string commonPrograms = Environment.GetFolderPath(Environment.SpecialFolder.CommonPrograms);
                    if (!string.IsNullOrEmpty(commonPrograms))
                    {
                        CreateShortcut(commonPrograms, "Caddy HTTPS Proxy", exePath);
                    }
                    report.AppendLine("[OK] Shortcut: Pintasan Desktop & Start Menu berhasil dibuat.");
                }
                catch (Exception ex)
                {
                    report.AppendLine("[GAGAL] Shortcut: " + ex.Message);
                }
            }

            report.AppendLine();
            report.AppendLine("Semua konfigurasi selesai diterapkan!");

            MessageBox.Show(report.ToString(), "Setup Caddy Server Berhasil", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        public static void RunUninstallService()
        {
            try
            {
                RunProcess("schtasks.exe", "/Delete /TN \"CaddyProxyService\" /F");
                MessageBox.Show("Windows Service 'CaddyProxyService' berhasil dicopot / dihapus.", "Informasi", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception ex)
            {
                MessageBox.Show("Gagal menghapus service: " + ex.Message, "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        public static void CreateShortcut(string folder, string name, string targetPath)
        {
            try
            {
                string linkPath = Path.Combine(folder, name + ".lnk");
                string psScript = "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('" + linkPath.Replace("'", "''") + "'); " +
                                  "$s.TargetPath = '" + targetPath.Replace("'", "''") + "'; " +
                                  "$s.WorkingDirectory = '" + Path.GetDirectoryName(targetPath).Replace("'", "''") + "'; " +
                                  "$s.Save();";
                RunPowerShell(psScript);
            }
            catch { }
        }

        public static void RunPowerShell(string script)
        {
            ProcessStartInfo psi = new ProcessStartInfo {
                FileName = "powershell.exe",
                Arguments = "-NoProfile -ExecutionPolicy Bypass -Command \"" + script.Replace("\"", "\\\"") + "\"",
                CreateNoWindow = true,
                UseShellExecute = false,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            using (Process p = Process.Start(psi))
            {
                if (p != null) p.WaitForExit();
            }
        }

        public static void RunProcess(string filename, string arguments)
        {
            ProcessStartInfo psi = new ProcessStartInfo {
                FileName = filename,
                Arguments = arguments,
                CreateNoWindow = true,
                UseShellExecute = false,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            using (Process p = Process.Start(psi))
            {
                if (p != null) p.WaitForExit();
            }
        }
    }

    public static class ServiceRunner
    {
        private static string logFile = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "service.log");

        private static void Log(string msg)
        {
            try
            {
                string line = string.Format("[{0:yyyy-MM-dd HH:mm:ss}] {1}", DateTime.Now, msg);
                File.AppendAllText(logFile, line + Environment.NewLine);
            }
            catch { }
        }

        public static void RunHeadless()
        {
            Log("=== Caddy Background Service Dimulai (System Boot) ===");
            string baseDir = AppDomain.CurrentDomain.BaseDirectory;
            string caddyExe = Path.Combine(baseDir, "caddy.exe");
            string configFile = Path.Combine(baseDir, "caddy_proxy_config.json");
            string caddyfilePath = Path.Combine(baseDir, "Caddyfile");

            // Ensure caddy.exe is extracted
            MainForm.EnsureCaddyBinary(caddyExe, msg => Log(msg));

            // 1. Wait for network / DNS up to 60 seconds
            Log("Menunggu koneksi jaringan dan DNS aktif...");
            bool connected = false;
            for (int i = 0; i < 30; i++)
            {
                try
                {
                    IPHostEntry entry = Dns.GetHostEntry("www.duckdns.org");
                    if (entry != null && entry.AddressList.Length > 0)
                    {
                        connected = true;
                        break;
                    }
                }
                catch { }
                System.Threading.Thread.Sleep(2000);
            }

            if (connected)
            {
                Log("Koneksi internet terdeteksi!");
            }
            else
            {
                Log("Peringatan: DNS belum responsif setelah 60 detik, melanjutkan start...");
            }

            // 2. Read config
            string domain = "tjipto.duckdns.org";
            string token = "";
            string host = "127.0.0.1";
            string port = "8090";
            string listenPort = "8443";
            string manualIp = "";
            bool disableLog = true;

            if (File.Exists(configFile))
            {
                try
                {
                    string json = File.ReadAllText(configFile);
                    domain = MainForm.ExtractJsonValueHelper(json, "domain", domain);
                    token = MainForm.ExtractJsonValueHelper(json, "token", token);
                    host = MainForm.ExtractJsonValueHelper(json, "host", host);
                    port = MainForm.ExtractJsonValueHelper(json, "port", port);
                    listenPort = MainForm.ExtractJsonValueHelper(json, "listenPort", listenPort);
                    manualIp = MainForm.ExtractJsonValueHelper(json, "manualIp", manualIp);
                    disableLog = MainForm.ExtractJsonBoolHelper(json, "disableLog", true);
                    Log("Konfigurasi dimuat dari " + configFile);
                }
                catch (Exception ex)
                {
                    Log("Gagal memuat config: " + ex.Message);
                }
            }

            // 3. Determine target IP
            string targetIp = manualIp;
            if (string.IsNullOrEmpty(targetIp))
            {
                var ips = MainForm.GetAllLocalIPv4Static();
                if (ips.Count > 0) targetIp = ips[0].IP;
                else targetIp = "127.0.0.1";
            }
            Log("Target IP Lokal: " + targetIp);

            // 4. Update DuckDNS
            if (!string.IsNullOrEmpty(token))
            {
                string subdomain = domain.Replace(".duckdns.org", "").TrimEnd('.');
                string url = "https://www.duckdns.org/update?domains=" + subdomain + "&token=" + token + "&ip=" + targetIp;
                try
                {
                    Log("Mengupdate DuckDNS: " + subdomain + " -> " + targetIp);
                    using (WebClient wc = new WebClient())
                    {
                        string resp = wc.DownloadString(url);
                        Log("Respon DuckDNS: " + resp);
                    }
                }
                catch (Exception ex)
                {
                    Log("Gagal update DuckDNS: " + ex.Message);
                }
            }
            else
            {
                Log("Peringatan: Token DuckDNS kosong.");
            }

            // 5. Generate Caddyfile
            string redirTarget = (listenPort == "443") ? "https://{host}{uri}" : ("https://{host}:" + listenPort + "{uri}");
            string logSection = disableLog ? "" : "    log {\n        output stdout\n        format console\n    }\n";

            string caddyConfig = "{\n" +
                                 "    admin off\n" +
                                 "    auto_https disable_redirects\n" +
                                 "}\n\n" +
                                 "http://" + domain + " {\n" +
                                 "    redir " + redirTarget + " permanent\n" +
                                 "}\n\n" +
                                 domain + ":" + listenPort + " {\n" +
                                 "    tls {\n" +
                                 "        dns duckdns " + token + "\n" +
                                 "        resolvers 8.8.8.8 8.8.4.4\n" +
                                 "    }\n" +
                                 logSection +
                                 "    reverse_proxy " + host + ":" + port + " {\n" +
                                 "        header_up Host {host}\n" +
                                 "        header_up X-Real-IP {remote_host}\n" +
                                 "    }\n" +
                                 "}\n";
            File.WriteAllText(caddyfilePath, caddyConfig);
            Log("Caddyfile berhasil dibuat.");

            // 6. Launch caddy.exe and wait
            if (File.Exists(caddyExe))
            {
                Log("Menjalankan caddy.exe run --config Caddyfile...");
                try
                {
                    ProcessStartInfo psi = new ProcessStartInfo {
                        FileName = caddyExe,
                        Arguments = "run --config \"" + caddyfilePath + "\"",
                        WorkingDirectory = baseDir,
                        UseShellExecute = false,
                        CreateNoWindow = true
                    };
                    using (Process proc = Process.Start(psi))
                    {
                        Log("Caddy berjalan dengan Process ID: " + proc.Id);
                        proc.WaitForExit();
                        Log("Caddy berhenti dengan exit code: " + proc.ExitCode);
                    }
                }
                catch (Exception ex)
                {
                    Log("ERROR menjalankan Caddy: " + ex.Message);
                }
            }
            else
            {
                Log("ERROR: caddy.exe tidak ditemukan di " + caddyExe);
            }
        }
    }
}
