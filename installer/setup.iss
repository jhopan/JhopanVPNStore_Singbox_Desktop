; Jhopan VPN Desktop Installer Script
; INNO Setup v6.0+
; Compile: iscc.exe setup.iss

[Setup]
AppName=JhopanStoreVPN
AppVersion=1.0.0
AppPublisher=Jhopan
AppPublisherURL=https://github.com/jhopan
WizardStyle=modern
DefaultDirName={autopf}\JhopanStoreVPN
DefaultGroupName=JhopanStoreVPN
OutputDir=..\dist
OutputBaseFilename=JhopanStoreVPN_Installer_v1.0.0
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64
SetupIconFile=..\assets\icon.ico
UninstallDisplayIcon={app}\JhopanStoreVPN.exe

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; Application Executable
Source: "..\dist\jhopan-vpn-windows-amd64.exe"; DestDir: "{app}"; DestName: "JhopanStoreVPN.exe"; Flags: ignoreversion
; Core / Dependencies
Source: "..\dist\sing-box.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\dist\wintun.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\assets\icon.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\JhopanStoreVPN"; Filename: "{app}\JhopanStoreVPN.exe"; IconFilename: "{app}\icon.ico"
Name: "{autodesktop}\JhopanStoreVPN"; Filename: "{app}\JhopanStoreVPN.exe"; Tasks: desktopicon; IconFilename: "{app}\icon.ico"

[Run]
Filename: "{app}\JhopanStoreVPN.exe"; Description: "{cm:LaunchProgram,JhopanStoreVPN}"; Flags: nowait postinstall skipifsilent runascurrentuser

[UninstallDelete]
Type: filesandordirs; Name: "{app}"
Type: filesandordirs; Name: "{localappdata}\com.jhopanstorevpn.app"
Type: filesandordirs; Name: "{userappdata}\com.jhopanstorevpn.app"
Type: filesandordirs; Name: "{userappdata}\fyne\com.jhopanstorevpn.app"
