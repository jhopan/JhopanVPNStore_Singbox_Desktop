package singbox

import (
	"encoding/json"
	"fmt"
	"runtime"
	"strconv"
	"strings"

	"jhopan-vpn/core/vless"
)

// GenerateConfig generates a fully functional Sing-box config supporting TUN, DNS, and VLESS Outbound.
func GenerateConfig(vc vless.Config, dns1, dns2 string, allowInsecure bool) ([]byte, error) {
	if dns1 == "" {
		dns1 = "8.8.8.8"
	}
	if dns2 == "" {
		dns2 = "8.8.4.4"
	}

	domain, portStr, err := vless.SplitAddress(vc.Address)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, fmt.Errorf("invalid port: %s", portStr)
	}

	isCloudflareWorkers := false
	workersDomains := []string{".workers.dev", ".pages.dev"}
	checkStrings := []string{vc.Host, vc.SNI}
	for _, s := range checkStrings {
		if s == "" {
			continue
		}
		sLower := strings.ToLower(s)
		for _, d := range workersDomains {
			if strings.HasSuffix(sLower, d) {
				isCloudflareWorkers = true
				break
			}
		}
	}

	wsHost := vc.Host
	if wsHost == "" {
		wsHost = vc.SNI
	}
	if wsHost == "" {
		wsHost = domain
	}

	serverName := vc.SNI
	if serverName == "" {
		serverName = domain
	}

	// DNS Setup
	// Always use TCP to ensure DNS queries pass through TCP-only VLESS proxies
	parsedDNS1 := "tcp://" + dns1
	parsedDNS2 := "tcp://" + dns2

	// TUN Settings adapted for cross-platform (Win/Lin/Mac)
	tunSettings := map[string]interface{}{
		"type":                     "tun",
		"tag":                      "tun-in",
		"inet4_address":            "172.19.0.1/30",
		"inet6_address":            "fdfe:dcba:9876::1/126",
		"mtu":                      1500,
		"auto_route":               true,
		"strict_route":             false,
		"stack":                    "system",
		"endpoint_independent_nat": true,
		"sniff":                    true,
	}

	switch runtime.GOOS {
	case "windows":
		tunSettings["interface_name"] = "JhopanVPN"
	case "linux":
		tunSettings["interface_name"] = "jhopan0"
		tunSettings["strict_route"] = true
	case "darwin":
		// MacOS: omit interface_name, macOS assigns utun dynamically
	}

	config := map[string]interface{}{
		"log": map[string]interface{}{
			"level": "error",
		},
		"dns": map[string]interface{}{
			"servers": []map[string]interface{}{
				{
					"tag":     "remote-dns",
					"address": parsedDNS1,
					"detour":  "proxy",
				},
				{
					"tag":     "remote-dns-fallback",
					"address": parsedDNS2,
					"detour":  "proxy",
				},
				{
					"tag":     "local-dns",
					"address": "local",
					"detour":  "direct",
				},
			},
			"rules": []map[string]interface{}{
				{
					"outbound":   []string{"any"},
					"server":     "local-dns",
					"disable_cache": false,
				},
			},
			"final":    "remote-dns",
			"strategy": "ipv4_only",
		},
		"inbounds": []interface{}{
			tunSettings,
			map[string]interface{}{
				"type":        "socks",
				"tag":         "socks-in",
				"listen":      "127.0.0.1",
				"listen_port": 10808,
				"sniff":       true,
			},
		},
		"outbounds": []interface{}{
			map[string]interface{}{
				"type":        "vless",
				"tag":         "proxy",
				"server":      domain,
				"server_port": port,
				"uuid":        vc.UUID,
				"tls": map[string]interface{}{
					"enabled":       true,
					"server_name":   serverName,
					"insecure":      allowInsecure,
					"utls":          map[string]interface{}{"enabled": true, "fingerprint": "chrome"},
				},
				"transport": map[string]interface{}{
					"type": "ws",
					"path": vc.Path,
					"headers": map[string]string{
						"Host": wsHost,
					},
				},
			},
			map[string]interface{}{
				"type": "direct",
				"tag":  "direct",
			},
			map[string]interface{}{
				"type": "dns",
				"tag":  "dns-out",
			},
			map[string]interface{}{
				"type": "block",
				"tag":  "block",
			},
		},
	}

	// Routing Setup
	routingRules := []map[string]interface{}{
		{
			"port":     []int{53},
			"outbound": "dns-out",
		},
	}

	if !isCloudflareWorkers {
		// Bypass local IPs from passing through the VPN
		routingRules = append(routingRules, map[string]interface{}{
			"ip_cidr": []string{
				"10.0.0.0/8",
				"172.16.0.0/12",
				"192.168.0.0/16",
				"127.0.0.0/8",
				"fc00::/7",
				"fe80::/10",
			},
			"outbound": "direct",
		})
	}

	config["route"] = map[string]interface{}{
		"rules":          routingRules,
		"final":          "proxy",
		"auto_detect_interface": true,
	}

	data, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return nil, fmt.Errorf("failed to marshal config: %w", err)
	}

	return data, nil
}
