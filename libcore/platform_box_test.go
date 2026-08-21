package libcore

import (
	"net"
	"testing"

	C "github.com/sagernet/sing-box/constant"
)

func TestParsePlatformNetworkInterfaces(t *testing.T) {
	interfaces, err := parsePlatformNetworkInterfaces(`[{"index":12,"mtu":1500,"name":"wlan0","hardware_address":"02:00:00:00:00:00","addresses":["192.0.2.10/24","2001:db8::10/64"],"up":true,"running":true,"multicast":true,"type":0,"dns_servers":["192.0.2.1"],"expensive":false}]`)
	if err != nil {
		t.Fatal(err)
	}
	if len(interfaces) != 1 {
		t.Fatalf("expected 1 interface, got %d", len(interfaces))
	}
	got := interfaces[0]
	if got.Index != 12 || got.MTU != 1500 || got.Name != "wlan0" {
		t.Fatalf("unexpected interface identity: %+v", got.Interface)
	}
	if got.HardwareAddr.String() != "02:00:00:00:00:00" {
		t.Fatalf("unexpected hardware address: %s", got.HardwareAddr)
	}
	if len(got.Addresses) != 2 || got.Addresses[0].String() != "192.0.2.10/24" || got.Addresses[1].String() != "2001:db8::10/64" {
		t.Fatalf("unexpected addresses: %v", got.Addresses)
	}
	if got.Flags&(net.FlagUp|net.FlagRunning|net.FlagMulticast) != net.FlagUp|net.FlagRunning|net.FlagMulticast {
		t.Fatalf("unexpected flags: %v", got.Flags)
	}
	if got.Type != C.InterfaceTypeWIFI || len(got.DNSServers) != 1 || got.DNSServers[0] != "192.0.2.1" || got.Expensive {
		t.Fatalf("unexpected metadata: %+v", got)
	}
}

func TestParsePlatformNetworkInterfacesRejectsInvalidPrefix(t *testing.T) {
	_, err := parsePlatformNetworkInterfaces(`[{"index":1,"mtu":1500,"name":"wlan0","addresses":["invalid"]}]`)
	if err == nil {
		t.Fatal("expected invalid prefix error")
	}
}
