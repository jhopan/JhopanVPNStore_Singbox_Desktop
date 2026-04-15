package ui

import (
	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

func clamp(v, minV, maxV float32) float32 {
	if v < minV {
		return minV
	}
	if v > maxV {
		return maxV
	}
	return v
}

type adaptiveHeroLayout struct {
	logo *canvas.Image
}

type centeredColumnLayout struct {
	maxWidth float32
}

func (l *centeredColumnLayout) Layout(objects []fyne.CanvasObject, size fyne.Size) {
	if len(objects) == 0 {
		return
	}

	obj := objects[0]
	innerW := size.Width
	if l.maxWidth > 0 && innerW > l.maxWidth {
		innerW = l.maxWidth
	}

	x := (size.Width - innerW) / 2
	obj.Move(fyne.NewPos(x, 0))
	obj.Resize(fyne.NewSize(innerW, size.Height))
}

func (l *centeredColumnLayout) MinSize(objects []fyne.CanvasObject) fyne.Size {
	if len(objects) == 0 {
		return fyne.NewSize(280, 200)
	}
	child := objects[0].MinSize()
	if l.maxWidth > 0 && child.Width > l.maxWidth {
		child.Width = l.maxWidth
	}
	return child
}

func (l *adaptiveHeroLayout) Layout(objects []fyne.CanvasObject, size fyne.Size) {
	if len(objects) == 0 {
		return
	}

	logoObj := objects[0]
	logoSide := clamp(size.Width*0.28, 72, 132)
	if l.logo != nil {
		logoObj.Resize(fyne.NewSize(logoSide, logoSide))
		logoObj.Move(fyne.NewPos((size.Width-logoSide)/2, 0))
	}
}

func (l *adaptiveHeroLayout) MinSize(objects []fyne.CanvasObject) fyne.Size {
	return fyne.NewSize(220, 128)
}

type MainPage struct {
	AddressEntry *widget.Entry
	UUIDEntry    *widget.Entry

	ConnectBtn    *widget.Button
	DisconnectBtn *widget.Button

	StatusText  *canvas.Text
	PingLabel   *widget.Label
	ServerLabel *widget.Label

	Container fyne.CanvasObject
}

func NewMainPage(onConnect, onDisconnect, onSettings, onClipboard func(), logoResource fyne.Resource) *MainPage {
	mp := &MainPage{}

	mp.AddressEntry = widget.NewEntry()
	mp.AddressEntry.SetPlaceHolder("example.com:443")

	mp.UUIDEntry = widget.NewEntry()
	mp.UUIDEntry.SetPlaceHolder("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")

	mp.ConnectBtn = widget.NewButtonWithIcon(" Connect", theme.MediaPlayIcon(), onConnect)
	mp.ConnectBtn.Importance = widget.HighImportance

	mp.DisconnectBtn = widget.NewButtonWithIcon(" Disconnect", theme.MediaStopIcon(), onDisconnect)
	mp.DisconnectBtn.Importance = widget.MediumImportance
	mp.DisconnectBtn.Disable()

	mp.StatusText = canvas.NewText("", theme.ForegroundColor())
	mp.StatusText.TextSize = 22
	mp.StatusText.Alignment = fyne.TextAlignCenter
	mp.StatusText.TextStyle = fyne.TextStyle{Bold: true}

	mp.PingLabel = widget.NewLabel("Ping: -")
	mp.ServerLabel = widget.NewLabel("No Connection")

	clipboardBtn := widget.NewButtonWithIcon("", theme.ContentPasteIcon(), onClipboard)
	clipboardBtn.Importance = widget.LowImportance

	settingsBtn := widget.NewButtonWithIcon("", theme.SettingsIcon(), onSettings)
	settingsBtn.Importance = widget.LowImportance

	title := widget.NewLabelWithStyle("JhopanStoreVPN", fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	var titleBox fyne.CanvasObject = title
	if logoResource != nil {
		topIcon := canvas.NewImageFromResource(logoResource)
		topIcon.FillMode = canvas.ImageFillContain
		topIcon.SetMinSize(fyne.NewSize(20, 20))
		titleBox = container.NewHBox(topIcon, title)
	}

	topBar := container.NewHBox(
		titleBox,
		layout.NewSpacer(),
		clipboardBtn,
		settingsBtn,
	)

	var logoBox fyne.CanvasObject
	var logoImage *canvas.Image
	if logoResource != nil {
		logoImage = canvas.NewImageFromResource(logoResource)
		logoImage.FillMode = canvas.ImageFillContain
		logoBox = logoImage
	} else {
		logoBox = canvas.NewRectangle(theme.BackgroundColor())
	}

	buttonsRow := container.NewGridWithColumns(2,
		container.NewPadded(mp.ConnectBtn),
		container.NewPadded(mp.DisconnectBtn),
	)

	infoCard := widget.NewCard("Network Status", "", container.NewVBox(
		mp.ServerLabel,
		mp.PingLabel,
	))

	profileCard := widget.NewCard("Account Info", "", container.NewVBox(
		widget.NewLabelWithStyle("Server Address", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
		mp.AddressEntry,
		widget.NewLabelWithStyle("Account UUID", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
		mp.UUIDEntry,
	))

	heroLayout := &adaptiveHeroLayout{logo: logoImage}
	hero := container.New(heroLayout,
		logoBox,
	)

	contentStack := container.NewVBox(
		container.NewPadded(topBar),
		widget.NewSeparator(),
		hero,
		buttonsRow,
		profileCard,
		infoCard,
	)

	column := container.New(&centeredColumnLayout{maxWidth: 460}, container.NewPadded(contentStack))
	scroll := container.NewScroll(column)
	scroll.Direction = container.ScrollVerticalOnly
	mp.Container = container.NewPadded(scroll)

	return mp
}

func (mp *MainPage) SetConnected() {
	fyne.Do(func() {
		mp.ConnectBtn.Disable()
		mp.DisconnectBtn.Enable()
	})
}

func (mp *MainPage) SetServer(address string) {
	fyne.Do(func() {
		mp.ServerLabel.SetText("Server: " + address)
	})
}

func (mp *MainPage) SetDisconnected() {
	fyne.Do(func() {
		mp.ConnectBtn.Enable()
		mp.DisconnectBtn.Disable()
		mp.ServerLabel.SetText("No Connection")
		mp.PingLabel.SetText("Ping: -")
	})
}

func (mp *MainPage) SetConnecting() {
	fyne.Do(func() {
		mp.ConnectBtn.Disable()
		mp.DisconnectBtn.Disable()
		mp.ServerLabel.SetText("Connecting...")
		mp.PingLabel.SetText("Ping: -")
	})
}

func (mp *MainPage) SetStatus(text string) {
	fyne.Do(func() {
		mp.ServerLabel.SetText(text)
	})
}

func (mp *MainPage) SetPing(text string) {
	fyne.Do(func() {
		mp.PingLabel.SetText("Ping: " + text)
	})
}
