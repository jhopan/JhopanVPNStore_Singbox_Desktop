package main

import (
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"syscall"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/widget"

	"jhopan-vpn/assets"
	"jhopan-vpn/core/ping"
	"jhopan-vpn/core/singbox"
	"jhopan-vpn/core/singleinstance"
	"jhopan-vpn/core/vless"
	appUI "jhopan-vpn/ui"
	jhovpnTheme "jhopan-vpn/ui/theme"
	"jhopan-vpn/ui/tray"
)

func fitDialogSize(win fyne.Window, widthFactor, heightFactor, minW, minH float32) fyne.Size {
	sz := win.Canvas().Size()
	w := sz.Width * widthFactor
	h := sz.Height * heightFactor
	if w < minW {
		w = minW
	}
	if h < minH {
		h = minH
	}
	return fyne.NewSize(w, h)
}

func main() {
	// Single instance check - prevent multiple instances
	instance, err := singleinstance.New("JhopanStoreVPN")
	if err != nil {
		// Another instance is already running
		log.Printf("Another instance is already running. Exiting.")
		// Note: Cannot show dialog here as Fyne app is not initialized yet
		// User will see nothing (which is expected - instance already running in tray)
		os.Exit(0)
	}
	defer instance.Release()

	// Create Fyne app
	a := app.NewWithID("com.jhopanstorevpn.app")
	a.Settings().SetTheme(&jhovpnTheme.DarkTheme{})
	a.SetIcon(assets.IconData)

	w := a.NewWindow("JhopanStoreVPN")
	w.Resize(fyne.NewSize(390, 680))
	w.CenterOnScreen()

	// Load app logo/icon resource
	logoResource := assets.LoadIcon()

	// State
	var (
		singboxProc *singbox.Process
		pinger            *ping.Pinger
		connected         bool
		reconnectAttempts int
		connectMu         sync.Mutex
	)

	// Global cleanup function - ensures complete application shutdown
	cleanup := func() {
		log.Println("[JhopanStoreVPN] Starting cleanup sequence...")
		
		// 1. Stop ping monitoring
		if pinger != nil {
			log.Println("[JhopanStoreVPN] Stopping ping monitor...")
			pinger.Stop()
			pinger = nil
		}
		
		// 2. Stop Singbox process (with graceful shutdown)
		if singboxProc != nil {
			log.Println("[JhopanStoreVPN] Stopping Singbox process...")
			if err := singboxProc.Stop(); err != nil {
				log.Printf("[JhopanStoreVPN] Singbox stop error (non-fatal): %v", err)
			}
			singboxProc = nil
		}
		
		// 3. Give OS time to cleanup TUN and routes
		// Wait a bit longer to ensure TUN adapter is cleaned up
		time.Sleep(500 * time.Millisecond)
		
		log.Println("[JhopanStoreVPN] Cleanup complete, internet should be restored")
	}

	// Forward-declare UI
	var mainPage *appUI.MainPage
	settingsPage := appUI.NewSettingsPage()

	// ---- Handlers ----

	doDisconnect := func() {
		connectMu.Lock()
		connected = false
		connectMu.Unlock()

		if pinger != nil {
			pinger.Stop()
		}
		if singboxProc != nil {
			singboxProc.Stop()
		}

		if mainPage != nil {
			mainPage.SetDisconnected()
		}
		log.Println("[JhopanStoreVPN] Disconnected")
	}

	var doConnect func()

	onXrayCrash := func() {
		log.Println("[JhopanStoreVPN] singbox crashed")
		if pinger != nil {
			pinger.Stop()
		}

		connectMu.Lock()
		wasConnected := connected
		connected = false
		connectMu.Unlock()

		if mainPage != nil {
			mainPage.SetDisconnected()
			mainPage.SetStatus("singbox crashed!")
		}

		// Auto-reconnect if enabled
		if wasConnected && settingsPage.IsAutoReconnect() {
			go func() {
				delay := settingsPage.GetAutoReconnectDelay()
				maxAttempts := settingsPage.GetAutoReconnectMaxAttempts()

				connectMu.Lock()
				reconnectAttempts++
				attempt := reconnectAttempts
				connectMu.Unlock()

				if attempt > maxAttempts {
					log.Printf("[JhopanStoreVPN] Auto-reconnect max attempts reached (%d)", maxAttempts)
					if mainPage != nil {
						mainPage.SetStatus("Reconnect failed")
					}
					return
				}

				log.Printf("[JhopanStoreVPN] Auto-reconnecting in %s (attempt %d/%d)...", delay, attempt, maxAttempts)
				time.Sleep(delay)
				connectMu.Lock()
				if !connected {
					connectMu.Unlock()
					doConnect()
				} else {
					connectMu.Unlock()
				}
			}()
		}
	}

	doConnect = func() {
		if mainPage == nil {
			return
		}

		if !singbox.HasBinary() {
			dialog.ShowError(fmt.Errorf("singbox.exe tidak ditemukan.\nLetakkan file singbox.exe di folder yang sama dengan JhopanStoreVPN.exe"), w)
			mainPage.SetDisconnected()
			return
		}

		address := mainPage.AddressEntry.Text
		uuid := mainPage.UUIDEntry.Text

		if address == "" || uuid == "" {
			dialog.ShowError(fmt.Errorf("Address and UUID are required"), w)
			return
		}

		domain, _, err := vless.SplitAddress(address)
		if err != nil {
			dialog.ShowError(fmt.Errorf("invalid address: %w", err), w)
			return
		}

		path := settingsPage.GetPath()
		sni := settingsPage.GetSNI()
		if sni == "" {
			sni = domain
		}
		host := settingsPage.GetHost()
		if host == "" {
			host = sni
		}
		dns1, dns2 := settingsPage.GetDNS()
		allowInsecure := settingsPage.IsAllowInsecure()

		vc := vless.Config{
			Address: address,
			UUID:    uuid,
			Path:    path,
			SNI:     sni,
			Host:    host,
		}

		mainPage.SetConnecting()

		// Run connection in background so UI doesn't freeze
		go func() {
			// Generate Singbox config
			configJSON, err := singbox.GenerateConfig(vc, dns1, dns2, allowInsecure)
			if err != nil {
				mainPage.SetDisconnected()
				log.Printf("[JhopanStoreVPN] Config error: %v", err)
				return
			}

			// Start Singbox
			singboxProc = singbox.NewProcess(onXrayCrash)
			if err := singboxProc.Start(configJSON); err != nil {
				mainPage.SetDisconnected()
				log.Printf("[JhopanStoreVPN] Singbox start error: %v", err)
				return
			}

			// Wait for Singbox SOCKS5 port to be ready with timeout feedback (up to 10 seconds)
			mainPage.SetStatus("Waiting for singbox...")
			portReady := false
			for i := 0; i < 40; i++ {
				remaining := 10 - (i+1)/4 // Show countdown every 1 second (4 iterations = 1 sec)
				if i%4 == 0 && i > 0 {
					mainPage.SetStatus(fmt.Sprintf("Waiting (%ds)...", remaining))
				}
				
				conn, dialErr := net.DialTimeout("tcp", "127.0.0.1:10808", 250*time.Millisecond)
				if dialErr == nil {
					conn.Close()
					portReady = true
					break
				}
				time.Sleep(250 * time.Millisecond)
				if !singboxProc.IsRunning() {
					mainPage.SetDisconnected()
					mainPage.SetStatus("Singbox exited unexpectedly")
					return
				}
			}
			if !portReady {
				singboxProc.Stop()
				mainPage.SetDisconnected()
				mainPage.SetStatus("Singbox connection timeout (10s)")
				return
			}
			log.Println("[JhopanStoreVPN] Singbox SOCKS5 port 10808 is ready")

			// TUN is handled directly by Singbox inbound.
			// Singbox config already includes TUN adapter setup

			connectMu.Lock()
			connected = true
			reconnectAttempts = 0
			connectMu.Unlock()

			mainPage.SetConnected()
			mainPage.SetServer(address)
			log.Println("[JhopanStoreVPN] Connected to", address)

			if settingsPage.IsPingEnabled() {
				pingURL := settingsPage.GetPingURL()
				pingDelay := settingsPage.GetPingDelay()
				pinger = ping.NewPinger(pingURL, pingDelay, func(latency time.Duration, pingErr error) {
					if pingErr != nil {
						mainPage.SetPing("timeout")
					} else {
						mainPage.SetPing(fmt.Sprintf("%d ms", latency.Milliseconds()))
					}
				})
				pinger.Start()
			} else {
				mainPage.SetPing("off")
			}

		}()
	}

	// ---- Clipboard Import ----
	importClipboard := func() {
		clip := w.Clipboard()
		if clip == nil {
			dialog.ShowError(fmt.Errorf("clipboard not available"), w)
			return
		}
		content := clip.Content()
		if content == "" {
			dialog.ShowError(fmt.Errorf("clipboard is empty"), w)
			return
		}

		vc, err := vless.ParseURI(content)
		if err != nil {
			dialog.ShowError(fmt.Errorf("clipboard parse error:\n%w", err), w)
			return
		}

		mainPage.AddressEntry.SetText(vc.Address)
		mainPage.UUIDEntry.SetText(vc.UUID)
		settingsPage.PathEntry.SetText(vc.Path)
		settingsPage.SNIEntry.SetText(vc.SNI)
		settingsPage.HostEntry.SetText(vc.Host)

		dialog.ShowInformation("Imported", "VLESS config imported from clipboard.", w)
	}

	// ---- Open settings as popup ----
	openSettings := func() {
		// Logo header in settings
		var settingsHeader fyne.CanvasObject
		if logoResource != nil {
			logoImg := canvas.NewImageFromResource(logoResource)
			logoImg.FillMode = canvas.ImageFillContain
			logoImg.SetMinSize(fyne.NewSize(120, 70))
			settingsHeader = container.NewVBox(
				container.NewHBox(layout.NewSpacer(), logoImg, layout.NewSpacer()),
				widget.NewSeparator(),
			)
		} else {
			settingsHeader = container.NewVBox(
				widget.NewLabelWithStyle("JhopanStoreVPN", fyne.TextAlignCenter, fyne.TextStyle{Bold: true}),
				widget.NewSeparator(),
			)
		}

		settingsContent := container.NewBorder(settingsHeader, nil, nil, nil, container.NewPadded(settingsPage.Container))

		d := dialog.NewCustom("Settings", "Close", settingsContent, w)
		d.Resize(fitDialogSize(w, 0.92, 0.9, 360, 420))
		d.Show()
	}

	// ---- Build Main Page ----
	mainPage = appUI.NewMainPage(doConnect, doDisconnect, openSettings, importClipboard, logoResource)

	// ---- Load saved preferences ----
	prefs := a.Preferences()
	if v := prefs.String("address"); v != "" {
		mainPage.AddressEntry.SetText(v)
	}
	if v := prefs.String("uuid"); v != "" {
		mainPage.UUIDEntry.SetText(v)
	}
	if v := prefs.String("path"); v != "" {
		settingsPage.PathEntry.SetText(v)
	}
	if v := prefs.String("sni"); v != "" {
		settingsPage.SNIEntry.SetText(v)
	}
	if v := prefs.String("host"); v != "" {
		settingsPage.HostEntry.SetText(v)
	}
	if v := prefs.String("dns1"); v != "" {
		settingsPage.DNS1Entry.SetText(v)
	}
	if v := prefs.String("dns2"); v != "" {
		settingsPage.DNS2Entry.SetText(v)
	}
	if v := prefs.String("ping_url"); v != "" {
		settingsPage.PingURLEntry.SetText(v)
	}
	if prefs.StringWithFallback("ping_enabled_set", "no") == "yes" {
		settingsPage.PingEnabledCheck.SetChecked(prefs.Bool("ping_enabled"))
	}
	if v := prefs.String("ping_delay_s"); v != "" {
		settingsPage.PingDelay.SetText(v)
	} else if v := prefs.String("ping_delay_ms"); v != "" {
		if ms, err := strconv.Atoi(v); err == nil && ms > 0 {
			sec := ms / 1000
			if sec < 1 {
				sec = 1
			}
			settingsPage.PingDelay.SetText(strconv.Itoa(sec))
		}
	}
	if prefs.StringWithFallback("system_tray_set", "no") == "yes" {
		settingsPage.SystemTrayCheck.SetChecked(prefs.Bool("system_tray"))
	}
	if prefs.StringWithFallback("auto_reconnect_set", "no") == "yes" {
		settingsPage.AutoReconnectCheck.SetChecked(prefs.Bool("auto_reconnect"))
	}
	if prefs.StringWithFallback("allow_insecure_set", "no") == "yes" {
		settingsPage.AllowInsecureCheck.SetChecked(prefs.Bool("allow_insecure"))
	}
	if v := prefs.String("auto_reconnect_delay_s"); v != "" {
		settingsPage.ReconnectMS.SetText(v)
	} else if v := prefs.String("auto_reconnect_delay_ms"); v != "" {
		if ms, err := strconv.Atoi(v); err == nil && ms > 0 {
			sec := ms / 1000
			if sec < 1 {
				sec = 1
			}
			settingsPage.ReconnectMS.SetText(strconv.Itoa(sec))
		}
	}
	if v := prefs.String("auto_reconnect_attempts"); v != "" {
		settingsPage.ReconnectTry.SetText(v)
	}
	// ---- Auto-save on every field change ----
	savePrefs := func() {
		prefs.SetString("address", mainPage.AddressEntry.Text)
		prefs.SetString("uuid", mainPage.UUIDEntry.Text)
		prefs.SetString("path", settingsPage.PathEntry.Text)
		prefs.SetString("sni", settingsPage.SNIEntry.Text)
		prefs.SetString("host", settingsPage.HostEntry.Text)
		prefs.SetString("dns1", settingsPage.DNS1Entry.Text)
		prefs.SetString("dns2", settingsPage.DNS2Entry.Text)
		prefs.SetString("ping_url", settingsPage.PingURLEntry.Text)
		prefs.SetBool("ping_enabled", settingsPage.PingEnabledCheck.Checked)
		prefs.SetString("ping_enabled_set", "yes")
		prefs.SetString("ping_delay_s", settingsPage.PingDelay.Text)
		prefs.SetBool("system_tray", settingsPage.SystemTrayCheck.Checked)
		prefs.SetString("system_tray_set", "yes")
		prefs.SetBool("auto_reconnect", settingsPage.AutoReconnectCheck.Checked)
		prefs.SetString("auto_reconnect_set", "yes")
		prefs.SetString("auto_reconnect_delay_s", settingsPage.ReconnectMS.Text)
		prefs.SetString("auto_reconnect_attempts", settingsPage.ReconnectTry.Text)
		prefs.SetBool("allow_insecure", settingsPage.AllowInsecureCheck.Checked)
		prefs.SetString("allow_insecure_set", "yes")
		// Force sync to disk to prevent data loss
		log.Println("[JhopanStoreVPN] Preferences saved")
	}

	mainPage.AddressEntry.OnChanged = func(_ string) { savePrefs() }
	mainPage.UUIDEntry.OnChanged = func(_ string) { savePrefs() }
	settingsPage.PathEntry.OnChanged = func(_ string) { savePrefs() }
	settingsPage.SNIEntry.OnChanged = func(_ string) { savePrefs() }
	settingsPage.HostEntry.OnChanged = func(_ string) { savePrefs() }
	settingsPage.DNS1Entry.OnChanged = func(_ string) { savePrefs() }
	settingsPage.DNS2Entry.OnChanged = func(_ string) { savePrefs() }
	settingsPage.PingURLEntry.OnChanged = func(_ string) { savePrefs() }
	settingsPage.PingDelay.OnChanged = func(_ string) { savePrefs() }
	settingsPage.PingEnabledCheck.OnChanged = func(_ bool) { savePrefs() }
	settingsPage.SystemTrayCheck.OnChanged = func(_ bool) { savePrefs() }
	settingsPage.AutoReconnectCheck.OnChanged = func(_ bool) { savePrefs() }
	settingsPage.ReconnectMS.OnChanged = func(_ string) { savePrefs() }
	settingsPage.ReconnectTry.OnChanged = func(_ string) { savePrefs() }
	settingsPage.AllowInsecureCheck.OnChanged = func(_ bool) { savePrefs() }

	w.SetContent(mainPage.Container)

	// ---- System Tray ----
	visible := true
	tray.SetupTray(a, tray.Callbacks{
		OnShowHide: func() {
			if visible {
				w.Hide()
			} else {
				w.Show()
			}
			visible = !visible
		},
		OnConnect:    doConnect,
		OnDisconnect: doDisconnect,
		OnExit: func() {
			log.Println("[JhopanStoreVPN] Exit requested from tray, saving preferences and cleanup...")
			savePrefs()                        // Save before quitting
			time.Sleep(100 * time.Millisecond) // Give time for preferences to flush
			cleanup()                          // Ensure full application cleanup
			a.Quit()
		},
	})

	// Window close behavior depends on system tray toggle
	w.SetCloseIntercept(func() {
		log.Println("[JhopanStoreVPN] Window close intercepted, saving preferences...")
		savePrefs()
		time.Sleep(50 * time.Millisecond) // Give time for preferences to flush
		if settingsPage.IsSystemTray() {
			w.Hide()
			visible = false
		} else {
			log.Println("[JhopanStoreVPN] System tray disabled, doing full cleanup on close...")
			cleanup()
			a.Quit()
		}
	})

	w.SetOnClosed(func() {
		log.Println("[JhopanStoreVPN] Window closed callback")
		savePrefs()
		cleanup()
	})

	// ---- OS Signal Handler ----
	// Handle Ctrl+C (SIGINT) or Kill signals (SIGTERM) for clean shutdown
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		sig := <-sigChan
		log.Printf("[JhopanStoreVPN] Received OS signal: %v", sig)
		cleanup()
		log.Println("[JhopanStoreVPN] Exiting after signal...")
		os.Exit(0)
	}()

	_ = widget.NewLabel("v2.1.0")

	w.ShowAndRun()

	// Final cleanup before exit
	log.Println("[JhopanStoreVPN] Application exiting, performing final cleanup...")
	cleanup()
	log.Println("[JhopanStoreVPN] Application shutdown complete")
}
