; =====================================================================
;   Inno Setup Script untuk Caddy HTTPS Reverse Proxy (Windows)
;   Mendukung setup parameter GUI, membaca konfigurasi setup_config.json,
;   membuka port firewall, mengecualikan real-time scanner Defender,
;   dan memasang NSSM service.
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
Source: "..\windows\Install-Service.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\windows\Uninstall-Service.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "setup_config.example.json"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Caddy HTTPS Proxy"; Filename: "{app}\CaddyProxy.exe"
Name: "{group}\Pasang Service NSSM"; Filename: "{app}\Install-Service.bat"
Name: "{group}\Hapus Service NSSM"; Filename: "{app}\Uninstall-Service.bat"
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

function GetJsonValue(JsonStr, Key, DefaultVal: String): String;
var
  SearchKey, ValStr: String;
  P, ColonPos, StartPos, EndPos: Integer;
begin
  Result := DefaultVal;
  SearchKey := '"' + Key + '"';
  P := Pos(SearchKey, JsonStr);
  if P > 0 then
  begin
    ColonPos := P + Length(SearchKey);
    while (ColonPos <= Length(JsonStr)) and (JsonStr[ColonPos] <> ':') do
      ColonPos := ColonPos + 1;
    if (ColonPos <= Length(JsonStr)) and (JsonStr[ColonPos] = ':') then
    begin
      StartPos := ColonPos + 1;
      while (StartPos <= Length(JsonStr)) and ((JsonStr[StartPos] = ' ') or (JsonStr[StartPos] = #9) or (JsonStr[StartPos] = #13) or (JsonStr[StartPos] = #10)) do
        StartPos := StartPos + 1;
      if StartPos <= Length(JsonStr) then
      begin
        if JsonStr[StartPos] = '"' then
        begin
          StartPos := StartPos + 1;
          EndPos := StartPos;
          while (EndPos <= Length(JsonStr)) and (JsonStr[EndPos] <> '"') do
            EndPos := EndPos + 1;
          Result := Copy(JsonStr, StartPos, EndPos - StartPos);
        end
        else
        begin
          EndPos := StartPos;
          while (EndPos <= Length(JsonStr)) and (JsonStr[EndPos] <> ',') and (JsonStr[EndPos] <> '}') and (JsonStr[EndPos] <> #13) and (JsonStr[EndPos] <> #10) do
            EndPos := EndPos + 1;
          ValStr := Trim(Copy(JsonStr, StartPos, EndPos - StartPos));
          if ValStr <> '' then
            Result := ValStr;
        end;
      end;
    end;
  end;
end;

function GetJsonField(JsonStr, Key1, Key2, DefaultVal: String): String;
var
  Val: String;
begin
  Val := GetJsonValue(JsonStr, Key1, '');
  if (Val = '') and (Key2 <> '') then
    Val := GetJsonValue(JsonStr, Key2, '');
  if Val = '' then
    Result := DefaultVal
  else
    Result := Val;
end;

function GetJsonBoolField(JsonStr, Key1, Key2: String; DefaultVal: Boolean): Boolean;
var
  ValStr: String;
begin
  ValStr := LowerCase(GetJsonField(JsonStr, Key1, Key2, ''));
  if Pos('true', ValStr) > 0 then
    Result := True
  else if Pos('false', ValStr) > 0 then
    Result := False
  else
    Result := DefaultVal;
end;

procedure InitializeWizard;
var
  lbl: TLabel;
  y: Integer;
  ConfigSrcFile, ConfigJson: String;
  ConfigJsonAnsi: AnsiString;
  DefDomain, DefToken, DefHost, DefPort, DefListenPort, DefManualIp: String;
  DefDisableLog: Boolean;
begin
  // Nilai default: kosongkan domain, token, manual IP
  DefDomain := '';
  DefToken := '';
  DefHost := '127.0.0.1';
  DefPort := '8090';
  DefListenPort := '443';
  DefManualIp := '';
  DefDisableLog := True;

  // Hanya baca file setting jika ada di folder installer ({src})
  ConfigSrcFile := ExpandConstant('{src}\setup_config.json');
  if not FileExists(ConfigSrcFile) then
    ConfigSrcFile := ExpandConstant('{src}\caddy_setup_config.json');
  if not FileExists(ConfigSrcFile) then
    ConfigSrcFile := ExpandConstant('{src}\caddy_proxy_config.json');

  if FileExists(ConfigSrcFile) then
  begin
    if LoadStringFromFile(ConfigSrcFile, ConfigJsonAnsi) then
    begin
      ConfigJson := String(ConfigJsonAnsi);
      DefDomain := GetJsonField(ConfigJson, 'domain', '', DefDomain);
      DefToken := GetJsonField(ConfigJson, 'token', '', DefToken);
      DefHost := GetJsonField(ConfigJson, 'backend_host', 'host', DefHost);
      DefPort := GetJsonField(ConfigJson, 'backend_port', 'port', DefPort);
      DefListenPort := GetJsonField(ConfigJson, 'listen_port', 'listenPort', DefListenPort);
      DefManualIp := GetJsonField(ConfigJson, 'manual_ip', 'manualIp', DefManualIp);
      DefDisableLog := GetJsonBoolField(ConfigJson, 'disable_log', 'disableLog', DefDisableLog);
    end;
  end;

  ConfigPage := CreateCustomPage(wpSelectDir,
    'Pengaturan Parameter Caddy & DuckDNS',
    'Tentukan domain, token DuckDNS, port backend, dan opsi Windows Service:');

  y := 8;

  // Domain DuckDNS
  lbl := TLabel.Create(ConfigPage);
  lbl.Parent := ConfigPage.Surface;
  lbl.ShowAccelChar := False;
  lbl.Caption := 'DuckDNS Domain (contoh: yourname.duckdns.org):';
  lbl.Left := 0;
  lbl.Top := y;
  edDomain := TNewEdit.Create(ConfigPage);
  edDomain.Parent := ConfigPage.Surface;
  edDomain.Left := 0;
  edDomain.Top := y + 16;
  edDomain.Width := 400;
  edDomain.Text := DefDomain;
  y := y + 46;

  // Token DuckDNS
  lbl := TLabel.Create(ConfigPage);
  lbl.Parent := ConfigPage.Surface;
  lbl.ShowAccelChar := False;
  lbl.Caption := 'DuckDNS Token:';
  lbl.Left := 0;
  lbl.Top := y;
  edToken := TNewEdit.Create(ConfigPage);
  edToken.Parent := ConfigPage.Surface;
  edToken.Left := 0;
  edToken.Top := y + 16;
  edToken.Width := 400;
  edToken.PasswordChar := '*';
  edToken.Text := DefToken;
  y := y + 46;

  // Host & Port Backend
  lbl := TLabel.Create(ConfigPage);
  lbl.Parent := ConfigPage.Surface;
  lbl.ShowAccelChar := False;
  lbl.Caption := 'Backend Target Host && Backend Target Port:';
  lbl.Left := 0;
  lbl.Top := y;
  edHost := TNewEdit.Create(ConfigPage);
  edHost.Parent := ConfigPage.Surface;
  edHost.Left := 0;
  edHost.Top := y + 16;
  edHost.Width := 240;
  edHost.Text := DefHost;

  edPort := TNewEdit.Create(ConfigPage);
  edPort.Parent := ConfigPage.Surface;
  edPort.Left := 250;
  edPort.Top := y + 16;
  edPort.Width := 150;
  edPort.Text := DefPort;
  y := y + 46;

  // HTTPS Listen Port & Hotspot IP Override
  lbl := TLabel.Create(ConfigPage);
  lbl.Parent := ConfigPage.Surface;
  lbl.ShowAccelChar := False;
  lbl.Caption := 'HTTPS Listen Port && Hotspot IP Override (Opsional):';
  lbl.Left := 0;
  lbl.Top := y;
  edListenPort := TNewEdit.Create(ConfigPage);
  edListenPort.Parent := ConfigPage.Surface;
  edListenPort.Left := 0;
  edListenPort.Top := y + 16;
  edListenPort.Width := 120;
  edListenPort.Text := DefListenPort;

  edManualIp := TNewEdit.Create(ConfigPage);
  edManualIp.Parent := ConfigPage.Surface;
  edManualIp.Left := 130;
  edManualIp.Top := y + 16;
  edManualIp.Width := 270;
  edManualIp.Text := DefManualIp;
  y := y + 46;

  // Checkbox Matikan Log
  chkDisableLog := TNewCheckBox.Create(ConfigPage);
  chkDisableLog.Parent := ConfigPage.Surface;
  chkDisableLog.Left := 0;
  chkDisableLog.Top := y;
  chkDisableLog.Width := 400;
  chkDisableLog.Caption := 'Matikan Log Akses (Disable Logging - Sangat disarankan untuk performa server)';
  chkDisableLog.Checked := DefDisableLog;
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

      if (Trim(edDomain.Text) <> '') and (Trim(edToken.Text) <> '') then
      begin
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
      end
      else
      begin
        // Jika domain/token belum diisi, pasang service tanpa auto-start
        Exec(NssmExe, 'install CaddyProxy "' + AppPath + '\CaddyProxy.exe" -service', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
        Exec(NssmExe, 'set CaddyProxy AppDirectory "' + AppPath + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
        Exec(NssmExe, 'set CaddyProxy DisplayName "Caddy DuckDNS HTTPS Proxy"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
        Exec(NssmExe, 'set CaddyProxy Description "Reverse proxy HTTPS otomatis untuk DuckDNS menggunakan Caddy Server"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
        Exec(NssmExe, 'set CaddyProxy Start SERVICE_DEMAND_START', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
        Exec(NssmExe, 'set CaddyProxy AppStdout "' + AppPath + '\service.log"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
        Exec(NssmExe, 'set CaddyProxy AppStderr "' + AppPath + '\service_error.log"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      end;
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
