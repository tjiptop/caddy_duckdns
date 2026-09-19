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
        private CheckBox chkDisableLog;
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

        [System.Runtime.InteropServices.DllImport("kernel32.dll")]
        private static extern bool AttachConsole(int dwProcessId);

        [STAThread]
        public static void Main(string[] args)
        {
            try
            {
                ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072 | (SecurityProtocolType)768 | SecurityProtocolType.Tls | SecurityProtocolType.Ssl3;
            }
            catch { }

            if (args != null && args.Length > 0 && !HasGuiFlag(args))
            {
                RunServiceMode(args);
                return;
            }

            try
            {
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm());
            }
            catch (Exception ex)
            {
                File.WriteAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "crash.log"), ex.ToString());
            }
        }

        private static bool HasGuiFlag(string[] args)
        {
            if (args == null) return false;
            for (int i = 0; i < args.Length; i++)
            {
                string s = args[i].TrimStart('-', '/').ToLowerInvariant();
                if (s == "gui") return true;
            }
            return false;
        }

        private static void RunServiceMode(string[] args)
        {
            try
            {
                AttachConsole(-1);
            }
            catch { }

            string baseDir = AppDomain.CurrentDomain.BaseDirectory;
            string configFile = Path.Combine(baseDir, "caddy_proxy_config.json");
            string caddyExe = Path.Combine(baseDir, "caddy.exe");
            string caddyfilePath = Path.Combine(baseDir, "Caddyfile");

            string domain = "";
            string token = "";
            string host = "127.0.0.1";
            string port = "8090";
            string listenPort = "443";
            string manualIp = "";
            bool disableLog = true;

            // 1. Load default from config file if exists (or setup_config.json)
            string cfgToRead = File.Exists(configFile) ? configFile : (File.Exists(Path.Combine(baseDir, "setup_config.json")) ? Path.Combine(baseDir, "setup_config.json") : null);
            if (cfgToRead != null)
            {
                try
                {
                    string content = File.ReadAllText(cfgToRead);
                    domain = ExtractJsonValue(content, "domain", domain);
                    token = ExtractJsonValue(content, "token", token);
                    host = ExtractJsonValue(content, "backend_host", ExtractJsonValue(content, "host", host));
                    port = ExtractJsonValue(content, "backend_port", ExtractJsonValue(content, "port", port));
                    listenPort = ExtractJsonValue(content, "listen_port", ExtractJsonValue(content, "listenPort", listenPort));
                    manualIp = ExtractJsonValue(content, "manual_ip", ExtractJsonValue(content, "manualIp", manualIp));
                    disableLog = ExtractJsonBool(content, "disable_log", ExtractJsonBool(content, "disableLog", disableLog));
                }
                catch { }
            }

            // 2. Override with CLI args (supports Android parameter naming & standard flags)
            for (int i = 0; i < args.Length; i++)
            {
                string key = args[i].TrimStart('-', '/').ToLowerInvariant().Replace("_", "").Replace("-", "");
                if ((key == "domain" || key == "d") && i + 1 < args.Length) domain = args[++i];
                else if ((key == "token" || key == "t") && i + 1 < args.Length) token = args[++i];
                else if ((key == "backendhost" || key == "host" || key == "h") && i + 1 < args.Length) host = args[++i];
                else if ((key == "backendport" || key == "port" || key == "p") && i + 1 < args.Length) port = args[++i];
                else if ((key == "listenport" || key == "listen" || key == "l") && i + 1 < args.Length) listenPort = args[++i];
                else if ((key == "manualip" || key == "ip") && i + 1 < args.Length) manualIp = args[++i];
                else if (key == "disablelog") disableLog = true;
                else if (key == "enablelog") disableLog = false;
            }

            Action<string> serviceLog = delegate(string msg) {
                string line = string.Format("[{0:yyyy-MM-dd HH:mm:ss}] {1}", DateTime.Now, msg);
                Console.WriteLine(line);
                try
                {
                    File.AppendAllText(Path.Combine(baseDir, "service.log"), line + Environment.NewLine);
                }
                catch { }
            };

            serviceLog("=== Caddy HTTPS Proxy Service Runner Memulai ===");
            serviceLog("Domain: " + domain + ", Port: " + listenPort + " -> " + host + ":" + port);

            if (string.IsNullOrEmpty(domain))
            {
                serviceLog("PERINGATAN: DuckDNS Domain belum dikonfigurasi! Buka CaddyProxy.exe atau konfigurasi caddy_proxy_config.json.");
                return;
            }

            // 3. Resolve target IP
            string targetIp = manualIp;
            if (string.IsNullOrEmpty(targetIp))
            {
                List<IpInfo> ips = GetAllLocalIPv4();
                if (ips.Count > 0)
                {
                    targetIp = ips[0].IP;
                }
                else
                {
                    targetIp = "127.0.0.1";
                }
            }
            serviceLog("Target IP lokal: " + targetIp);

            // 4. Update DuckDNS
            if (!string.IsNullOrEmpty(token))
            {
                serviceLog("Mengupdate DuckDNS (" + domain + " -> " + targetIp + ")...");
                try
                {
                    string sub = domain.Replace(".duckdns.org", "").TrimEnd('.');
                    string u = "https://www.duckdns.org/update?domains=" + sub + "&token=" + token + "&ip=" + targetIp;
                    using (WebClient wc = new WebClient())
                    {
                        wc.Proxy = null;
                        string resp = wc.DownloadString(u);
                        if (resp.Contains("OK"))
                        {
                            serviceLog("DuckDNS berhasil diupdate: " + sub + " -> " + targetIp);
                        }
                        else
                        {
                            serviceLog("DuckDNS respon: " + resp);
                        }
                    }
                }
                catch (Exception ex)
                {
                    serviceLog("Peringatan Update DuckDNS: " + ex.Message);
                }
            }
            else
            {
                serviceLog("Peringatan: DuckDNS Token belum diisi!");
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
            serviceLog("Caddyfile berhasil dibuat di: " + caddyfilePath);

            // 6. Jalankan caddy.exe
            if (!File.Exists(caddyExe))
            {
                serviceLog("ERROR: caddy.exe tidak ditemukan di: " + caddyExe);
                Environment.Exit(1);
                return;
            }

            Process caddyProc = null;
            object syncLock = new object();
            bool hasExited = false;

            Action killCaddy = delegate {
                lock (syncLock)
                {
                    if (hasExited) return;
                    hasExited = true;
                }
                serviceLog("Menghentikan Caddy process...");
                try
                {
                    if (caddyProc != null && !caddyProc.HasExited)
                    {
                        caddyProc.Kill();
                        caddyProc.WaitForExit(3000);
                    }
                }
                catch { }
                serviceLog("Caddy process berhenti.");
            };

            Console.CancelKeyPress += delegate(object s, ConsoleCancelEventArgs e) {
                e.Cancel = true;
                killCaddy();
            };

            AppDomain.CurrentDomain.ProcessExit += delegate(object s, EventArgs e) {
                killCaddy();
            };

            try
            {
                ProcessStartInfo psi = new ProcessStartInfo {
                    FileName = caddyExe,
                    Arguments = "run --config \"" + caddyfilePath + "\"",
                    WorkingDirectory = baseDir,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true
                };

                caddyProc = new Process { StartInfo = psi };
                caddyProc.OutputDataReceived += delegate(object s, DataReceivedEventArgs e) {
                    if (e.Data != null)
                    {
                        if (!disableLog || e.Data.Contains("\"level\":\"error\"") || e.Data.Contains("ERROR"))
                            serviceLog("[Caddy] " + e.Data);
                    }
                };
                caddyProc.ErrorDataReceived += delegate(object s, DataReceivedEventArgs e) {
                    if (e.Data != null)
                    {
                        if (!disableLog || e.Data.Contains("\"level\":\"error\"") || e.Data.Contains("ERROR"))
                            serviceLog("[Caddy Log] " + e.Data);
                    }
                };

                caddyProc.Start();
                caddyProc.BeginOutputReadLine();
                caddyProc.BeginErrorReadLine();

                serviceLog("Caddy berhasil dijalankan (PID: " + caddyProc.Id + ")");
                serviceLog("HTTPS aktif di https://" + domain + (listenPort == "443" ? "" : ":" + listenPort));
                caddyProc.WaitForExit();

                int code = caddyProc.ExitCode;
                serviceLog("Caddy berhenti dengan exit code: " + code);
                Environment.Exit(code);
            }
            catch (Exception ex)
            {
                serviceLog("ERROR Fatal: " + ex.Message);
                Environment.Exit(1);
            }
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
                Text = "IP Hotspot/WLAN: Mendeteksi...",
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
            txtDomain = new TextBox { Text = "", Location = new Point(180, y), Size = new Size(420, 26), BackColor = Color.FromArgb(45, 45, 45), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle };
            card.Controls.Add(lDomain); card.Controls.Add(txtDomain);
            y += 36;

            // Token
            Label lToken = new Label { Text = "DuckDNS Token:", ForeColor = Color.LightGray, Location = new Point(12, y), Size = new Size(160, 22) };
            txtToken = new TextBox { UseSystemPasswordChar = true, Location = new Point(180, y), Size = new Size(420, 26), BackColor = Color.FromArgb(45, 45, 45), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle };
            card.Controls.Add(lToken); card.Controls.Add(txtToken);
            y += 36;

            // Backend Host & Port
            Label lHost = new Label { Text = "Backend Target Host & Port:", ForeColor = Color.LightGray, Location = new Point(12, y), Size = new Size(160, 22) };
            txtBackendHost = new TextBox { Text = "127.0.0.1", Location = new Point(180, y), Size = new Size(240, 26), BackColor = Color.FromArgb(45, 45, 45), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle };
            txtBackendPort = new TextBox { Text = "8090", Location = new Point(430, y), Size = new Size(170, 26), BackColor = Color.FromArgb(45, 45, 45), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle };
            card.Controls.Add(lHost); card.Controls.Add(txtBackendHost); card.Controls.Add(txtBackendPort);
            y += 36;

            // Listen Port & Manual IP Dropdown
            Label lListen = new Label { Text = "HTTPS Port & Override IP:", UseMnemonic = false, ForeColor = Color.LightGray, Location = new Point(12, y), Size = new Size(160, 22) };
            txtListenPort = new TextBox { Text = "443", Location = new Point(180, y), Size = new Size(80, 26), BackColor = Color.FromArgb(45, 45, 45), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle };

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

            // Checkbox Matikan Log (Default: Checked / Disabled)
            chkDisableLog = new CheckBox {
                Text = "Matikan Log Akses (Disable Logging)",
                Location = new Point(180, y),
                Size = new Size(350, 24),
                ForeColor = Color.FromArgb(220, 220, 220),
                Font = new Font("Segoe UI", 9F),
                Checked = true
            };
            tt.SetToolTip(chkDisableLog, "Jika dicentang, Caddy tidak mencetak request log ke console (menghemat resource CPU/RAM & server lebih cepat)");
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

            Button btnFixFirewall = new Button {
                Text = "🛡️ Buka Port Firewall",
                Location = new Point(180, y),
                Size = new Size(205, 30),
                BackColor = Color.FromArgb(40, 40, 40),
                ForeColor = Color.FromArgb(255, 179, 0),
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 8.2F)
            };
            btnFixFirewall.FlatAppearance.BorderSize = 0;
            btnFixFirewall.Click += (s, e) => {
                string bat = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Fix-Firewall.bat");
                if (File.Exists(bat)) {
                    Process.Start(new ProcessStartInfo {
                        FileName = bat,
                        UseShellExecute = true,
                        Verb = "runas"
                    });
                } else {
                    MessageBox.Show("File Fix-Firewall.bat tidak ditemukan!", "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            };
            card.Controls.Add(btnFixFirewall);

            Button btnService = new Button {
                Text = "⚙️ Pasang Windows Service",
                Location = new Point(395, y),
                Size = new Size(205, 30),
                BackColor = Color.FromArgb(40, 40, 40),
                ForeColor = Color.FromArgb(0, 230, 118),
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 8.2F)
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

        public static List<IpInfo> GetAllLocalIPv4()
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
            bool dnsOk = await UpdateDuckDns(domain, token, targetIp, AppendLog);
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

        public static async Task<bool> UpdateDuckDns(string fullDomain, string token, string ip, Action<string> logAction = null)
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
                        if (logAction != null) logAction("DuckDNS berhasil diupdate: " + subdomain + " -> " + ip);
                        return true;
                    }
                    else
                    {
                        if (logAction != null) logAction("DuckDNS respon: " + resp);
                        return false;
                    }
                }
            }
            catch (Exception ex)
            {
                if (logAction != null) logAction("DuckDNS update error: " + ex.Message);
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
                string bHost = txtBackendHost.Text.Replace("\"", "\\\"");
                string bPort = txtBackendPort.Text.Replace("\"", "\\\"");
                string lPort = txtListenPort.Text.Replace("\"", "\\\"");
                string mIp = GetSelectedIp().Replace("\"", "\\\"");
                string dLog = chkDisableLog.Checked ? "true" : "false";

                string json = "{\n" +
                              "  \"domain\": \"" + txtDomain.Text.Replace("\"", "\\\"") + "\",\n" +
                              "  \"token\": \"" + txtToken.Text.Replace("\"", "\\\"") + "\",\n" +
                              "  \"backend_host\": \"" + bHost + "\",\n" +
                              "  \"backend_port\": \"" + bPort + "\",\n" +
                              "  \"listen_port\": \"" + lPort + "\",\n" +
                              "  \"manual_ip\": \"" + mIp + "\",\n" +
                              "  \"disable_log\": " + dLog + ",\n" +
                              "  \"host\": \"" + bHost + "\",\n" +
                              "  \"port\": \"" + bPort + "\",\n" +
                              "  \"listenPort\": \"" + lPort + "\",\n" +
                              "  \"manualIp\": \"" + mIp + "\",\n" +
                              "  \"disableLog\": " + dLog + "\n" +
                              "}";
                File.WriteAllText(configFile, json);
            }
            catch { }
        }

        private void LoadConfig()
        {
            try
            {
                string baseDir = AppDomain.CurrentDomain.BaseDirectory;
                string setupCfg = Path.Combine(baseDir, "setup_config.json");
                string cfg = File.Exists(configFile) ? configFile : (File.Exists(setupCfg) ? setupCfg : null);
                if (cfg != null)
                {
                    string content = File.ReadAllText(cfg);
                    txtDomain.Text = ExtractJsonValue(content, "domain", "");
                    txtToken.Text = ExtractJsonValue(content, "token", "");
                    txtBackendHost.Text = ExtractJsonValue(content, "backend_host", ExtractJsonValue(content, "host", "127.0.0.1"));
                    txtBackendPort.Text = ExtractJsonValue(content, "backend_port", ExtractJsonValue(content, "port", "8090"));
                    txtListenPort.Text = ExtractJsonValue(content, "listen_port", ExtractJsonValue(content, "listenPort", "443"));
                    string savedIp = ExtractJsonValue(content, "manual_ip", ExtractJsonValue(content, "manualIp", ""));
                    if (!string.IsNullOrEmpty(savedIp))
                    {
                        cmbManualIp.Text = savedIp;
                    }
                    chkDisableLog.Checked = ExtractJsonBool(content, "disable_log", ExtractJsonBool(content, "disableLog", true));
                }
            }
            catch { }
        }

        public static bool ExtractJsonBool(string json, string key, bool def)
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

        public static string ExtractJsonValue(string json, string key, string def)
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
