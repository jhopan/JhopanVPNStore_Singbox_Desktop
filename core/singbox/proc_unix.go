//go:build !windows
// +build !windows

package singbox

import "os/exec"

func setProcAttr(cmd *exec.Cmd) {
	// No special attributes needed for Unix
}
