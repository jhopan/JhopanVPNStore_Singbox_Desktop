package singbox

import (
	"os/exec"
	"syscall"
)

// setProcAttr hides the console window on Windows.
func setProcAttr(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
}
