package tray

import (
	"jhopan-vpn/assets"

	"fyne.io/fyne/v2"
)

// Callbacks for tray menu actions.
type Callbacks struct {
	OnShowHide   func()
	OnConnect    func()
	OnDisconnect func()
	OnExit       func()
}

// SetupTray configures the desktop system tray.
func SetupTray(app fyne.App, cb Callbacks) {
	if desk, ok := app.(interface {
		SetSystemTrayMenu(menu *fyne.Menu)
		SetSystemTrayIcon(icon fyne.Resource)
	}); ok {
		desk.SetSystemTrayIcon(assets.TrayIconData())
		menu := fyne.NewMenu("JhopanStoreVPN",
			fyne.NewMenuItem("Show/Hide", func() {
				if cb.OnShowHide != nil {
					cb.OnShowHide()
				}
			}),
			fyne.NewMenuItemSeparator(),
			fyne.NewMenuItem("Connect", func() {
				if cb.OnConnect != nil {
					cb.OnConnect()
				}
			}),
			fyne.NewMenuItem("Disconnect", func() {
				if cb.OnDisconnect != nil {
					cb.OnDisconnect()
				}
			}),
			fyne.NewMenuItemSeparator(),
			fyne.NewMenuItem("Exit", func() {
				if cb.OnExit != nil {
					cb.OnExit()
				}
			}),
		)
		desk.SetSystemTrayMenu(menu)
	}
}
