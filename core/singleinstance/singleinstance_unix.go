//go:build !windows

package singleinstance

import (
	"fmt"
	"os"
	"path/filepath"
	"syscall"
)

type Instance struct {
	lockFile *os.File
	lockPath string
}

// New creates a new single instance lock using file lock (flock)
func New(appName string) (*Instance, error) {
	// Use /tmp on Unix-like systems
	lockPath := filepath.Join(os.TempDir(), appName+".lock")

	// Open or create lock file
	lockFile, err := os.OpenFile(lockPath, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, fmt.Errorf("failed to open lock file: %w", err)
	}

	// Try to acquire exclusive lock (LOCK_EX | LOCK_NB = non-blocking)
	err = syscall.Flock(int(lockFile.Fd()), syscall.LOCK_EX|syscall.LOCK_NB)
	if err != nil {
		lockFile.Close()
		return nil, fmt.Errorf("another instance is already running")
	}

	return &Instance{
		lockFile: lockFile,
		lockPath: lockPath,
	}, nil
}

// Release releases the file lock
func (i *Instance) Release() error {
	if i.lockFile != nil {
		syscall.Flock(int(i.lockFile.Fd()), syscall.LOCK_UN)
		i.lockFile.Close()
		os.Remove(i.lockPath)
		i.lockFile = nil
	}
	return nil
}
