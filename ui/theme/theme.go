package theme

import (
	"image/color"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/theme"
)

// DarkTheme is a premium dark theme for JhopanStoreVPN.
type DarkTheme struct{}

var _ fyne.Theme = (*DarkTheme)(nil)

func (d *DarkTheme) Color(name fyne.ThemeColorName, variant fyne.ThemeVariant) color.Color {
	switch name {
	case theme.ColorNameBackground:
		return color.NRGBA{R: 18, G: 18, B: 20, A: 255} // Deep charcoal background
	case theme.ColorNameButton:
		return color.NRGBA{R: 35, G: 35, B: 40, A: 255} // Slightly lighter surface for buttons
	case theme.ColorNameForeground:
		return color.NRGBA{R: 230, G: 230, B: 235, A: 255} // Soft white text
	case theme.ColorNamePrimary:
		return color.NRGBA{R: 10, G: 132, B: 255, A: 255} // Deep Apple blue accent
	case theme.ColorNameInputBackground:
		return color.NRGBA{R: 28, G: 28, B: 32, A: 255} // Dark input field
	case theme.ColorNamePlaceHolder:
		return color.NRGBA{R: 120, G: 120, B: 130, A: 255} // Subdued text
	case theme.ColorNameDisabled:
		return color.NRGBA{R: 50, G: 50, B: 55, A: 255} // Disabled elements
	case theme.ColorNameOverlayBackground:
		return color.NRGBA{R: 24, G: 24, B: 28, A: 240} // Dialog background
	case theme.ColorNameSeparator:
		return color.NRGBA{R: 45, G: 45, B: 50, A: 255} // Subtle borders
	case theme.ColorNameInputBorder:
		return color.NRGBA{R: 45, G: 45, B: 50, A: 255} // Subtle border around inputs
	case theme.ColorNameShadow:
		return color.NRGBA{R: 0, G: 0, B: 0, A: 60} // Darker shadow
	}
	return theme.DefaultTheme().Color(name, theme.VariantDark)
}

func (d *DarkTheme) Font(style fyne.TextStyle) fyne.Resource {
	return theme.DefaultTheme().Font(style)
}

func (d *DarkTheme) Icon(name fyne.ThemeIconName) fyne.Resource {
	return theme.DefaultTheme().Icon(name)
}

func (d *DarkTheme) Size(name fyne.ThemeSizeName) float32 {
	switch name {
	case theme.SizeNameText:
		return 13
	case theme.SizeNamePadding:
		return 8
	case theme.SizeNameInnerPadding:
		return 6
	}
	return theme.DefaultTheme().Size(name)
}
