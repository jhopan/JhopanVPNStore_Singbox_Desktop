package singbox

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sync"
	"syscall"
	"time"
)

// Process manages the sing-box subprocess lifecycle.
type Process struct {
	mu      sync.Mutex
	cmd     *exec.Cmd
	running bool
	onCrash func() // callback when sing-box crashes unexpectedly
}

// NewProcess creates a new sing-box process manager.
func NewProcess(onCrash func()) *Process {
	return &Process{onCrash: onCrash}
}

// IsRunning returns whether sing-box is currently running.
func (p *Process) IsRunning() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.running
}

// Start launches sing-box with the given config JSON bytes.
func (p *Process) Start(configJSON []byte) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.running {
		return fmt.Errorf("sing-box is already running")
	}

	// Write config to temp file
	configDir, err := os.MkdirTemp("", "jhovpn_singbox")
	if err != nil {
		return fmt.Errorf("failed to create temp dir: %w", err)
	}
	configPath := filepath.Join(configDir, "config.json")
	if err := os.WriteFile(configPath, configJSON, 0644); err != nil {
		os.RemoveAll(configDir)
		return fmt.Errorf("failed to write config: %w", err)
	}

	// Find sing-box binary
	bin := findBinary()
	if bin == "" {
		os.RemoveAll(configDir)
		return fmt.Errorf("sing-box binary not found. Place sing-box binary next to the application")
	}

	logFile, _ := os.OpenFile("singbox_error.log", os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
	p.cmd = exec.Command(bin, "run", "-c", configPath)
	setProcAttr(p.cmd)
	p.cmd.Stdout = logFile
	p.cmd.Stderr = logFile

	if err := p.cmd.Start(); err != nil {
		os.RemoveAll(configDir)
		return fmt.Errorf("failed to start sing-box: %w", err)
	}

	p.running = true

	// Monitor process in background
	go func() {
		p.cmd.Wait() //nolint:errcheck
		p.mu.Lock()
		wasRunning := p.running
		p.running = false
		p.mu.Unlock()

		// Clean up temp config
		os.RemoveAll(configDir)

		// Invoke callback for unexpected exit
		if wasRunning && p.onCrash != nil {
			p.onCrash()
		}
	}()

	return nil
}

// Stop gracefully stops the sing-box process with timeout before force killing.
func (p *Process) Stop() error {
	p.mu.Lock()
	if !p.running || p.cmd == nil || p.cmd.Process == nil {
		p.running = false
		p.mu.Unlock()
		return nil
	}
	p.running = false
	cmd := p.cmd
	p.mu.Unlock()

	gracefulTimeout := time.Duration(3) * time.Second
	log.Printf("[Singbox] Graceful shutdown requested (timeout: %v)", gracefulTimeout)
	
	var sig os.Signal = syscall.SIGTERM
	if runtime.GOOS == "windows" {
		sig = os.Interrupt // Windows: Ctrl+C equivalent
	}
	
	if err := cmd.Process.Signal(sig); err != nil {
		log.Printf("[Singbox] Graceful signal failed: %v, forcing kill", err)
		return cmd.Process.Kill()
	}
	
	done := make(chan error, 1)
	go func() {
		done <- cmd.Wait()
	}()
	
	select {
	case <-time.After(gracefulTimeout):
		log.Printf("[Singbox] Graceful shutdown timeout, force killing")
		if err := cmd.Process.Kill(); err != nil {
			log.Printf("[Singbox] Force kill failed: %v", err)
			return err
		}
		<-done
		return nil
	case err := <-done:
		if err != nil {
			log.Printf("[Singbox] Process exit error: %v", err)
		}
		return nil
	}
}

func findBinary() string {
	names := []string{"sing-box"}
	if runtime.GOOS == "windows" {
		names = []string{"sing-box.exe"}
	}

	if exePath, err := os.Executable(); err == nil {
		dir := filepath.Dir(exePath)
		for _, name := range names {
			p := filepath.Join(dir, name)
			if _, err := os.Stat(p); err == nil {
				return p
			}
		}
	}

	if cwd, err := os.Getwd(); err == nil {
		for _, name := range names {
			p := filepath.Join(cwd, name)
			if _, err := os.Stat(p); err == nil {
				return p
			}
		}
	}

	for _, name := range names {
		if p, err := exec.LookPath(name); err == nil {
			return p
		}
	}

	return ""
}

func HasBinary() bool {
	return findBinary() != ""
}
