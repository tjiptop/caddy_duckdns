using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
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
        private Button btnSave;
        private Button btnToggle;
        private Label lblStatus;
        private TextBox txtLogs;
        private NotifyIcon trayIcon;
        private ContextMenuStrip trayMenu;

        private Process caddyProcess;
        private bool isRunning = false;
        private readonly string configFile = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "caddy_proxy_config.json");
        private readonly string caddyExe = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "caddy.exe");

        [STAThread]
        public static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }

        public MainForm()
        {
            InitializeComponent();
            LoadConfig();
            DetectLocalIp();
        }

        private void InitializeComponent()
        {
            this.Text = "Caddy HTTPS Reverse Proxy (Windows)";
            this.Size = new Size(680, 720);
            this.MinimumSize = new Size(600, 650);
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
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 280)); // Config Box
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
            y += 44;

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

            Button btnService = new Button {
                Text = "⚙️ Pasang Auto-Start (Windows Service)",
                Location = new Point(180, y),
                Size = new Size(420, 30),
                BackColor = Color.FromArgb(40, 40, 40),
                ForeColor = Color.FromArgb(0, 230, 118),
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 8.5F)
            };
            btnService.FlatAppearance.BorderSize = 0;
            btnService.Click += (s, e) => {
                SaveConfig();
                string bat = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Install-Service.bat");
                if (File.Exists(bat)) {
                    Process.Start(new ProcessStartInfo {
                        FileName = bat,
                        UseShellExecute = true,
                        Verb = "runas"
                    });
                } else {
                    MessageBox.Show("File Install-Service.bat tidak ditemukan!", "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            };
            card.Controls.Add(btnService);
            y += 36;

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

        private void DetectLocalIp()
        {
            Task.Run(() => {
                var ipList = GetAllLocalIPv4();
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

        private List<IpInfo> GetAllLocalIPv4()
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
            string caddyConfig = "{\n    admin off\n    auto_https disable_redirects\n}\n\n" +
                                 domain + ":" + listenPort + " {\n" +
                                 "    tls {\n" +
                                 "        dns duckdns " + token + "\n" +
                                 "        resolvers 8.8.8.8 8.8.4.4\n" +
                                 "    }\n" +
                                 "    reverse_proxy " + host + ":" + port + "\n" +
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
                caddyProcess.OutputDataReceived += (s, e) => { if (e.Data != null) AppendLog("[Caddy] " + e.Data); };
                caddyProcess.ErrorDataReceived += (s, e) => { if (e.Data != null) AppendLog("[Caddy Log] " + e.Data); };

                caddyProcess.Start();
                caddyProcess.BeginOutputReadLine();
                caddyProcess.BeginErrorReadLine();

                isRunning = true;
                btnToggle.Text = "Stop Proxy";
                btnToggle.BackColor = Color.FromArgb(244, 67, 54);
                lblStatus.Text = "Status: Running (HTTPS :" + listenPort + " -> :" + port + ")";
                lblStatus.ForeColor = Color.FromArgb(76, 175, 80);
                AppendLog("Caddy aktif! Buka browser: https://" + domain + ":" + listenPort);
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
                              "  \"manualIp\": \"" + GetSelectedIp().Replace("\"", "\\\"") + "\"\n" +
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
                    txtDomain.Text = ExtractJsonValue(content, "domain", "tjipto.duckdns.org");
                    txtToken.Text = ExtractJsonValue(content, "token", "");
                    txtBackendHost.Text = ExtractJsonValue(content, "host", "127.0.0.1");
                    txtBackendPort.Text = ExtractJsonValue(content, "port", "8090");
                    txtListenPort.Text = ExtractJsonValue(content, "listenPort", "8443");
                    string savedIp = ExtractJsonValue(content, "manualIp", "");
                    if (!string.IsNullOrEmpty(savedIp))
                    {
                        cmbManualIp.Text = savedIp;
                    }
                }
            }
            catch { }
        }

        private string ExtractJsonValue(string json, string key, string def)
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
    }
}
