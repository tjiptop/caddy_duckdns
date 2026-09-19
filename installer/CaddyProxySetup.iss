; =====================================================================
;   Inno Setup Script untuk Caddy HTTPS Reverse Proxy (Windows)
;   Mendukung setup parameter GUI, membuka port firewall,
;   mengecualikan real-time scanner Defender, dan memasang NSSM service.
; =====================================================================

[Setup]
AppId={{9C8B5F34-12A4-4A8E-93BF-632D157A8901}
AppName=Caddy HTTPS Reverse Proxy
AppVersion=1.0.0
AppPublisher=Tjipto
AppPublisherURL=https://github.com/tjiptop/caddy_duckdns
DefaultDirName={autopf}\CaddyProxy
DefaultGroupName=Caddy HTTPS Proxy
DisableProgramGroupPage=yes
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=commandline
OutputDir=Output
OutputBaseFilename=CaddyProxy-Setup-Windows
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\windows\caddy.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\windows\CaddyProxy.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\windows\nssm.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\windows\Fix-Firewall.bat"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Caddy HTTPS Proxy"; Filename: "{app}\CaddyProxy.exe"
Name: "{group}\Buka Port Firewall"; Filename: "{app}\Fix-Firewall.bat"
Name: "{group}\Uninstall Caddy HTTPS Proxy"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Caddy HTTPS Proxy"; Filename: "{app}\CaddyProxy.exe"; Tasks: desktopicon

[Code]
var
  ConfigPage: TWizardPage;
  edDomain: TNewEdit;
  edToken: TNewEdit;
  edHost: TNewEdit;
  edPort: TNewEdit;
  edListenPort: TNewEdit;
  edManualIp: TNewEdit;
  chkDisableLog: TNewCheckBox;
  chkInstallService: TNewCheckBox;

procedure InitializeWizard;
var
  lbl: TLabel;
  y: Integer;
begin
  ConfigPage := CreateCustomPage(wpSelectDir,
    'Pengaturan Parameter Caddy & DuckDNS',
    'Tentukan domain, token DuckDNS, port backend, dan opsi Windows Service:');

  y := 8;

  // Domain DuckDNS
  lbl := TLabel.Create(ConfigPage);
  lbl.Parent := ConfigPage.Surface;
  lbl.Caption := 'DuckDNS Domain (contoh: absenku.duckdns.org):';
  lbl.Left := 0;
  lbl.Top := y;
  edDomain := TNewEdit.Create(ConfigPage);
  edDomain.Parent := ConfigPage.Surface;
  edDomain.Left := 0;
  edDomain.Top := y + 16;
  edDomain.Width := 400;
  edDomain.Text := 'absenku.duckdns.org';
  y := y + 46;

  // Token DuckDNS
  lbl := TLabel.Create(ConfigPage);
  lbl.Parent := ConfigPage.Surface;
  lbl.Caption := 'DuckDNS Token:';
  lbl.Left := 0;
  lbl.Top := y;
  edToken := TNewEdit.Create(ConfigPage);
  edToken.Parent := ConfigPage.Surface;
  edToken.Left := 0;
  edToken.Top := y + 16;
  edToken.Width := 400;
  edToken.PasswordChar := '*';
  edToken.Text := '4ecb6ff8-642b-44c4-b839-1a5bf1f189d4';
  y := y + 46;

  // Host & Port Backend
  lbl := TLabel.Create(ConfigPage);
  lbl.Parent := ConfigPage.Surface;
  lbl.Caption := 'Backend Target Host & Backend Target Port:';
  lbl.Left := 0;
  lbl.Top := y;
  edHost := TNewEdit.Create(ConfigPage);
  edHost.Parent := ConfigPage.Surface;
  edHost.Left := 0;
  edHost.Top := y + 16;
  edHost.Width := 240;
  edHost.Text := '127.0.0.1';

  edPort := TNewEdit.Create(ConfigPage);
  edPort.Parent := ConfigPage.Surface;
  edPort.Left := 250;
  edPort.Top := y + 16;
  edPort.Width := 150;
  edPort.Text := '8090';
  y := y + 46;

  // HTTPS Listen Port & Hotspot IP Override
  lbl := TLabel.Create(ConfigPage);
  lbl.Parent := ConfigPage.Surface;
  lbl.Caption := 'HTTPS Listen Port & Hotspot IP Override (Opsional):';
  lbl.Left := 0;
  lbl.Top := y;
  edListenPort := TNewEdit.Create(ConfigPage);
  edListenPort.Parent := ConfigPage.Surface;
  edListenPort.Left := 0;
  edListenPort.Top := y + 16;
  edListenPort.Width := 120;
  edListenPort.Text := '443';

  edManualIp := TNewEdit.Create(ConfigPage);
  edManualIp.Parent := ConfigPage.Surface;
  edManualIp.Left := 130;
  edManualIp.Top := y + 16;
  edManualIp.Width := 270;
  edManualIp.Text := '192.168.2.105';
  y := y + 46;

  // Checkbox Matikan Log
  chkDisableLog := TNewCheckBox.Create(ConfigPage);
  chkDisableLog.Parent := ConfigPage.Surface;
  chkDisableLog.Left := 0;
  chkDisableLog.Top := y;
  chkDisableLog.Width := 400;
  chkDisableLog.Caption := 'Matikan Log Akses (Disable Logging - Sangat disarankan untuk performa server)';
  chkDisableLog.Checked := True;
  y := y + 26;

  // Checkbox Pasang Service NSSM
  chkInstallService := TNewCheckBox.Create(ConfigPage);
  chkInstallService.Parent := ConfigPage.Surface;
  chkInstallService.Left := 0;
  chkInstallService.Top := y;
  chkInstallService.Width := 400;
  chkInstallService.Caption := 'Pasang sebagai Windows Service (Otomatis start saat boot via NSSM)';
  chkInstallService.Checked := True;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
  AppPath: String;
  JsonContent: String;
  ConfigFile: String;
  NssmExe: String;
  NssmArgs: String;
  PsCmd: String;
  DisableLogStr: String;
begin
  if CurStep = ssPostInstall then
  begin
    AppPath := ExpandConstant('{app}');
    NssmExe := AppPath + '\nssm.exe';
    ConfigFile := AppPath + '\caddy_proxy_config.json';

    // 1. Simpan konfigurasi ke caddy_proxy_config.json (Sinkron dengan format APK)
    if chkDisableLog.Checked then
      DisableLogStr := 'true'
    else
      DisableLogStr := 'false';

    JsonContent := '{\n' +
      '  "domain": "' + edDomain.Text + '",\n' +
      '  "token": "' + edToken.Text + '",\n' +
      '  "backend_host": "' + edHost.Text + '",\n' +
      '  "backend_port": "' + edPort.Text + '",\n' +
      '  "listen_port": "' + edListenPort.Text + '",\n' +
      '  "manual_ip": "' + edManualIp.Text + '",\n' +
      '  "disable_log": ' + DisableLogStr + ',\n' +
      '  "host": "' + edHost.Text + '",\n' +
      '  "port": "' + edPort.Text + '",\n' +
      '  "listenPort": "' + edListenPort.Text + '",\n' +
      '  "manualIp": "' + edManualIp.Text + '",\n' +
      '  "disableLog": ' + DisableLogStr + '\n' +
      '}';
    SaveStringToFile(ConfigFile, JsonContent, False);

    // 2. Konfigurasi Windows Defender (Pengecualian Real-Time Scanner)
    PsCmd := 'Add-MpPreference -ExclusionPath ''' + AppPath + ''' -ErrorAction SilentlyContinue; ' +
             'Add-MpPreference -ExclusionProcess ''' + AppPath + '\caddy.exe'' -ErrorAction SilentlyContinue; ' +
             'Add-MpPreference -ExclusionProcess ''' + AppPath + '\CaddyProxy.exe'' -ErrorAction SilentlyContinue; ' +
             'Add-MpPreference -ExclusionProcess ''caddy.exe'' -ErrorAction SilentlyContinue; ' +
             'Add-MpPreference -ExclusionProcess ''CaddyProxy.exe'' -ErrorAction SilentlyContinue';
    Exec('powershell.exe', '-NoProfile -ExecutionPolicy Bypass -Command "' + PsCmd + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

    // 3. Konfigurasi Windows Defender Firewall (Buka port 80, 443, 8443, 8090 untuk Profile Any)
    PsCmd := 'Set-NetFirewallRule -DisplayName ''Caddy*'' -Profile Any -ErrorAction SilentlyContinue; ' +
             'Remove-NetFirewallRule -DisplayName ''Caddy Server*'' -ErrorAction SilentlyContinue; ' +
             'New-NetFirewallRule -DisplayName ''Caddy Server Program'' -Direction Inbound -Program ''' + AppPath + '\caddy.exe'' -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null; ' +
             'New-NetFirewallRule -DisplayName ''Caddy Server Port 80'' -Direction Inbound -LocalPort 80 -Protocol TCP -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null; ' +
             'New-NetFirewallRule -DisplayName ''Caddy Server Port 443'' -Direction Inbound -LocalPort 443 -Protocol TCP -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null; ' +
             'New-NetFirewallRule -DisplayName ''Caddy Server Port 8443'' -Direction Inbound -LocalPort 8443 -Protocol TCP -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null; ' +
             'New-NetFirewallRule -DisplayName ''Caddy Server Port 8090'' -Direction Inbound -LocalPort 8090 -Protocol TCP -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null';
    Exec('powershell.exe', '-NoProfile -ExecutionPolicy Bypass -Command "' + PsCmd + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

    // 4. Pasang sebagai Windows Service via NSSM jika opsi dicentang
    if chkInstallService.Checked then
    begin
      // Hapus service lama jika ada
      Exec(NssmExe, 'stop CaddyProxy', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec(NssmExe, 'remove CaddyProxy confirm', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

      // Argumen untuk CaddyProxy.exe (format sinkron dengan parameter APK)
      NssmArgs := '-service -domain "' + edDomain.Text + '" -token "' + edToken.Text + '" -backend-host "' + edHost.Text + '" -backend-port "' + edPort.Text + '" -listen-port "' + edListenPort.Text + '"';
      if Trim(edManualIp.Text) <> '' then
        NssmArgs := NssmArgs + ' -manual-ip "' + edManualIp.Text + '"';
      if chkDisableLog.Checked then
        NssmArgs := NssmArgs + ' -disable-log';

      // Pasang service dengan NSSM
      Exec(NssmExe, 'install CaddyProxy "' + AppPath + '\CaddyProxy.exe" ' + NssmArgs, '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec(NssmExe, 'set CaddyProxy AppDirectory "' + AppPath + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec(NssmExe, 'set CaddyProxy DisplayName "Caddy DuckDNS HTTPS Proxy"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec(NssmExe, 'set CaddyProxy Description "Reverse proxy HTTPS otomatis untuk DuckDNS menggunakan Caddy Server"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec(NssmExe, 'set CaddyProxy Start SERVICE_AUTO_START', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec(NssmExe, 'set CaddyProxy AppStdout "' + AppPath + '\service.log"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec(NssmExe, 'set CaddyProxy AppStderr "' + AppPath + '\service_error.log"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec(NssmExe, 'set CaddyProxy AppRotateFiles 1', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec(NssmExe, 'set CaddyProxy AppRotateOnline 1', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec(NssmExe, 'set CaddyProxy AppRotateBytes 10485760', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

      // Jalankan service
      Exec(NssmExe, 'start CaddyProxy', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    end;
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  ResultCode: Integer;
  NssmExe: String;
  PsCmd: String;
begin
  if CurUninstallStep = usUninstall then
  begin
    NssmExe := ExpandConstant('{app}\nssm.exe');
    if FileExists(NssmExe) then
    begin
      Exec(NssmExe, 'stop CaddyProxy', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec(NssmExe, 'remove CaddyProxy confirm', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    end;

    // Bersihkan aturan firewall
    PsCmd := 'Remove-NetFirewallRule -DisplayName ''Caddy Server*'' -ErrorAction SilentlyContinue';
    Exec('powershell.exe', '-NoProfile -ExecutionPolicy Bypass -Command "' + PsCmd + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;
