package vless

import (
	"fmt"
	"net/url"
	"strings"
)

// Config holds parsed VLESS connection parameters.
type Config struct {
	Address string // domain:port
	UUID    string
	Path    string // ws path, default /ws
	SNI     string // TLS server name
	Host    string // host header
}

// DefaultConfig returns a Config with sensible defaults.
func DefaultConfig() Config {
	return Config{
		Path: "/vless",
	}
}

// ParseURI parses a vless:// URI and returns a Config.
// Format: vless://uuid@host:port?type=ws&security=tls&path=/ws&sni=example.com&host=example.com#remark
func ParseURI(uri string) (Config, error) {
	uri = strings.TrimSpace(uri)
	if !strings.HasPrefix(uri, "vless://") {
		return Config{}, fmt.Errorf("invalid vless URI: must start with vless://")
	}

	// Remove fragment (remark)
	if idx := strings.Index(uri, "#"); idx != -1 {
		uri = uri[:idx]
	}

	// Replace vless:// with https:// for standard URL parsing
	fakeURL := "https://" + uri[len("vless://"):]
	u, err := url.Parse(fakeURL)
	if err != nil {
		return Config{}, fmt.Errorf("failed to parse vless URI: %w", err)
	}

	uuid := u.User.Username()
	if uuid == "" {
		return Config{}, fmt.Errorf("missing UUID in vless URI")
	}

	host := u.Hostname()
	port := u.Port()
	if host == "" {
		return Config{}, fmt.Errorf("missing host in vless URI")
	}
	if port == "" {
		port = "443"
	}

	q := u.Query()

	path := q.Get("path")
	if path == "" {
		path = "/ws"
	}

	sni := q.Get("sni")
	if sni == "" {
		sni = host
	}

	hostHeader := q.Get("host")
	if hostHeader == "" {
		hostHeader = sni
	}

	return Config{
		Address: fmt.Sprintf("%s:%s", host, port),
		UUID:    uuid,
		Path:    path,
		SNI:     sni,
		Host:    hostHeader,
	}, nil
}

// SplitAddress splits "domain:port" into domain and port.
func SplitAddress(address string) (domain, port string, err error) {
	idx := strings.LastIndex(address, ":")
	if idx == -1 {
		return "", "", fmt.Errorf("invalid address format, expected domain:port")
	}
	domain = address[:idx]
	port = address[idx+1:]
	if domain == "" || port == "" {
		return "", "", fmt.Errorf("invalid address: domain or port is empty")
	}
	return domain, port, nil
}
