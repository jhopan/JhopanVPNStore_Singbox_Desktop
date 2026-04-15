package assets

import (
_ "embed"

"fyne.io/fyne/v2"
)

//go:embed icon.png
var iconBytes []byte

// IconData is the application icon.
var IconData = &fyne.StaticResource{
StaticName:    "icon.png",
StaticContent: iconBytes,
}

// TrayIconData returns the tray icon.
func TrayIconData() fyne.Resource {
return IconData
}

// LoadIcon returns the app icon resource.
func LoadIcon() fyne.Resource {
return IconData
}
