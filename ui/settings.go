package ui

import (
	"strconv"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/widget"
)

// SettingsData holds all settings values.
type SettingsData struct {
	// Connection
	Path string
	SNI  string
	Host string

	// DNS
	DNS1 string
	DNS2 string

	// Behavior
	SystemTray    bool
	AutoReconnect bool
	AllowInsecure bool

	// Ping
	PingURL      string
	PingEnabled  bool
	PingDelaySec int
	ReconnectSec int
	ReconnectTry int
}

// DefaultSettings returns the default settings.
func DefaultSettings() SettingsData {
	return SettingsData{
		Path:          "/vless",
		SNI:           "",
		Host:          "biznet.vpnstore28.my.id",
		DNS1:          "8.8.8.8",
		DNS2:          "8.8.4.4",
		SystemTray:    true,
		AutoReconnect: true,
		AllowInsecure: true,
		PingURL:       "http://cp.cloudflare.com/generate_204",
		PingEnabled:   true,
		PingDelaySec:  2,
		ReconnectSec:  3,
		ReconnectTry:  5,
	}
}

// SettingsPage holds the settings UI and current values.
type SettingsPage struct {
	PathEntry    *widget.Entry
	SNIEntry     *widget.Entry
	HostEntry    *widget.Entry
	DNS1Entry    *widget.Entry
	DNS2Entry    *widget.Entry
	PingURLEntry *widget.Entry
	PingDelay    *widget.Entry
	ReconnectMS  *widget.Entry
	ReconnectTry *widget.Entry

	SystemTrayCheck    *widget.Check
	AutoReconnectCheck *widget.Check
	AllowInsecureCheck *widget.Check
	PingEnabledCheck   *widget.Check

	Container    *fyne.Container
	LogoResource fyne.Resource // set by caller
}

// NewSettingsPage creates the settings UI with default values.
func NewSettingsPage() *SettingsPage {
	sp := &SettingsPage{}
	defaults := DefaultSettings()

	// -- Connection section --
	sp.PathEntry = widget.NewEntry()
	sp.PathEntry.SetText(defaults.Path)
	sp.PathEntry.SetPlaceHolder("/vless")

	sp.SNIEntry = widget.NewEntry()
	sp.SNIEntry.SetText("biznet.vpnstore28.my.id")
	sp.SNIEntry.SetPlaceHolder("biznet.vpnstore28.my.id")

	sp.HostEntry = widget.NewEntry()
	sp.HostEntry.SetText(defaults.Host)
	sp.HostEntry.SetPlaceHolder("biznet.vpnstore28.my.id")

	// -- DNS section --
	sp.DNS1Entry = widget.NewEntry()
	sp.DNS1Entry.SetText(defaults.DNS1)
	sp.DNS1Entry.SetPlaceHolder("8.8.8.8")

	sp.DNS2Entry = widget.NewEntry()
	sp.DNS2Entry.SetText(defaults.DNS2)
	sp.DNS2Entry.SetPlaceHolder("8.8.4.4")

	// -- Ping section --
	sp.PingURLEntry = widget.NewEntry()
	sp.PingURLEntry.SetText(defaults.PingURL)
	sp.PingURLEntry.SetPlaceHolder("http://cp.cloudflare.com/generate_204")

	sp.PingDelay = widget.NewEntry()
	sp.PingDelay.SetText(strconv.Itoa(defaults.PingDelaySec))
	sp.PingDelay.SetPlaceHolder("2")

	sp.PingEnabledCheck = widget.NewCheck("Enable HTTP Ping", nil)
	sp.PingEnabledCheck.SetChecked(defaults.PingEnabled)

	sp.ReconnectMS = widget.NewEntry()
	sp.ReconnectMS.SetText(strconv.Itoa(defaults.ReconnectSec))
	sp.ReconnectMS.SetPlaceHolder("3")

	sp.ReconnectTry = widget.NewEntry()
	sp.ReconnectTry.SetText(strconv.Itoa(defaults.ReconnectTry))
	sp.ReconnectTry.SetPlaceHolder("5")

	// -- Behavior toggles --
	sp.SystemTrayCheck = widget.NewCheck("Minimize to System Tray", nil)
	sp.SystemTrayCheck.SetChecked(defaults.SystemTray)

	sp.AutoReconnectCheck = widget.NewCheck("Auto Reconnect", nil)
	sp.AutoReconnectCheck.SetChecked(defaults.AutoReconnect)

	sp.AllowInsecureCheck = widget.NewCheck("Allow Insecure TLS (skip verify)", nil)
	sp.AllowInsecureCheck.SetChecked(defaults.AllowInsecure)

	// Layout
	connForm := container.New(layout.NewFormLayout(),
		widget.NewLabel("Path:"), sp.PathEntry,
		widget.NewLabel("SNI:"), sp.SNIEntry,
		widget.NewLabel("Host:"), sp.HostEntry,
	)
	connCard := widget.NewCard("Connection", "Server endpoint configuration", connForm)

	dnsForm := container.New(layout.NewFormLayout(),
		widget.NewLabel("DNS 1:"), sp.DNS1Entry,
		widget.NewLabel("DNS 2:"), sp.DNS2Entry,
	)
	dnsCard := widget.NewCard("DNS", "Resolver used by tunnel", dnsForm)

	pingForm := container.New(layout.NewFormLayout(),
		widget.NewLabel("Ping URL:"), sp.PingURLEntry,
		widget.NewLabel("Delay (s):"), sp.PingDelay,
	)
	pingBox := container.NewVBox(sp.PingEnabledCheck, pingForm)
	pingCard := widget.NewCard("HTTP Ping", "Health check monitoring", pingBox)

	reconnectForm := container.New(layout.NewFormLayout(),
		widget.NewLabel("Reconnect Delay (s):"), sp.ReconnectMS,
		widget.NewLabel("Reconnect Attempts:"), sp.ReconnectTry,
	)
	behaviorBox := container.NewVBox(
		sp.SystemTrayCheck,
		sp.AutoReconnectCheck,
		sp.AllowInsecureCheck,
		reconnectForm,
	)
	behaviorCard := widget.NewCard("Behavior", "Startup and reconnect behavior", behaviorBox)

	tabs := container.NewAppTabs(
		container.NewTabItem("Connection", container.NewPadded(connCard)),
		container.NewTabItem("DNS", container.NewPadded(dnsCard)),
		container.NewTabItem("Ping", container.NewPadded(pingCard)),
		container.NewTabItem("Behavior", container.NewPadded(behaviorCard)),
	)
	tabs.SetTabLocation(container.TabLocationTop)

	sp.Container = container.NewVBox(
		widget.NewLabelWithStyle("Pengaturan VPN", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
		widget.NewLabel("Pilih tab sesuai kategori pengaturan."),
		widget.NewSeparator(),
		tabs,
	)

	return sp
}

// GetPath returns path value (with default).
func (sp *SettingsPage) GetPath() string {
	if sp.PathEntry.Text == "" {
		return "/vless"
	}
	return sp.PathEntry.Text
}

// GetSNI returns the SNI value. Defaults to biznet.vpnstore28.my.id if empty.
func (sp *SettingsPage) GetSNI() string {
	if sp.SNIEntry.Text == "" {
		return "biznet.vpnstore28.my.id"
	}
	return sp.SNIEntry.Text
}

// GetHost returns the host header value.
func (sp *SettingsPage) GetHost() string {
	if sp.HostEntry.Text == "" {
		return "biznet.vpnstore28.my.id"
	}
	return sp.HostEntry.Text
}

// GetDNS returns DNS server values.
func (sp *SettingsPage) GetDNS() (string, string) {
	d1 := sp.DNS1Entry.Text
	d2 := sp.DNS2Entry.Text
	if d1 == "" {
		d1 = "8.8.8.8"
	}
	if d2 == "" {
		d2 = "8.8.4.4"
	}
	return d1, d2
}

// GetPingURL returns the ping URL.
func (sp *SettingsPage) GetPingURL() string {
	if sp.PingURLEntry.Text == "" {
		return "http://cp.cloudflare.com/generate_204"
	}
	return sp.PingURLEntry.Text
}

// IsPingEnabled returns whether HTTP ping monitoring is enabled.
func (sp *SettingsPage) IsPingEnabled() bool {
	return sp.PingEnabledCheck.Checked
}

// GetPingDelay returns ping delay in seconds with sane defaults.
func (sp *SettingsPage) GetPingDelay() time.Duration {
	v, err := strconv.Atoi(sp.PingDelay.Text)
	if err != nil || v < 1 {
		v = 2
	}
	if v > 60 {
		v = 60
	}
	return time.Duration(v) * time.Second
}

// IsSystemTray returns whether system tray is enabled.
func (sp *SettingsPage) IsSystemTray() bool {
	return sp.SystemTrayCheck.Checked
}

// IsAutoReconnect returns whether auto-reconnect is enabled.
func (sp *SettingsPage) IsAutoReconnect() bool {
	return sp.AutoReconnectCheck.Checked
}

// GetAutoReconnectDelay returns auto reconnect delay.
func (sp *SettingsPage) GetAutoReconnectDelay() time.Duration {
	v, err := strconv.Atoi(sp.ReconnectMS.Text)
	if err != nil || v < 1 {
		v = 3
	}
	if v > 120 {
		v = 120
	}
	return time.Duration(v) * time.Second
}

// GetAutoReconnectMaxAttempts returns max reconnect attempts.
func (sp *SettingsPage) GetAutoReconnectMaxAttempts() int {
	v, err := strconv.Atoi(sp.ReconnectTry.Text)
	if err != nil || v < 1 {
		return 5
	}
	if v > 100 {
		return 100
	}
	return v
}

// IsAllowInsecure returns whether to skip TLS certificate verification.
func (sp *SettingsPage) IsAllowInsecure() bool {
	return sp.AllowInsecureCheck.Checked
}

