//go:build windows

package singleinstance

import (
	"fmt"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	kernel32         = windows.NewLazySystemDLL("kernel32.dll")
	procCreateMutex  = kernel32.NewProc("CreateMutexW")
	procReleaseMutex = kernel32.NewProc("ReleaseMutex")
	procCloseHandle  = kernel32.NewProc("CloseHandle")
)

const (
	ERROR_ALREADY_EXISTS = 183
)

type Instance struct {
	mutexHandle windows.Handle
}

// New creates a new single instance lock using Windows named mutex
func New(appName string) (*Instance, error) {
	mutexName, err := syscall.UTF16PtrFromString("Global\\" + appName)
	if err != nil {
		return nil, fmt.Errorf("failed to convert mutex name: %w", err)
	}

	ret, _, err := procCreateMutex.Call(
		0,
		0,
		uintptr(unsafe.Pointer(mutexName)),
	)

	if ret == 0 {
		return nil, fmt.Errorf("failed to create mutex: %w", err)
	}

	handle := windows.Handle(ret)

	// Check if mutex already exists (another instance is running)
	if err == syscall.Errno(ERROR_ALREADY_EXISTS) {
		procCloseHandle.Call(uintptr(handle))
		return nil, fmt.Errorf("another instance is already running")
	}

	return &Instance{
		mutexHandle: handle,
	}, nil
}

// Release releases the mutex and allows other instances to run
func (i *Instance) Release() error {
	if i.mutexHandle != 0 {
		procReleaseMutex.Call(uintptr(i.mutexHandle))
		procCloseHandle.Call(uintptr(i.mutexHandle))
		i.mutexHandle = 0
	}
	return nil
}
