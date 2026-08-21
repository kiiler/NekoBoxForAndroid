package libcore

import (
	"encoding/json"
	"fmt"
	"libcore/procfs"
	"log"
	"net"
	"net/netip"
	"strings"
	"sync"
	"syscall"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	N "github.com/sagernet/sing/common/network"
)

var _ adapter.PlatformInterface = (*boxPlatformInterfaceWrapper)(nil)

type boxPlatformInterfaceWrapper struct {
	access         sync.Mutex
	pendingMonitor *platformDefaultInterfaceMonitor
}

type platformTunNameResolver func(fd int) (string, error)

func newBoxPlatformInterface() *boxPlatformInterfaceWrapper {
	return new(boxPlatformInterfaceWrapper)
}

func (w *boxPlatformInterfaceWrapper) ReadWIFIState() adapter.WIFIState {
	state := strings.Split(intfBox.WIFIState(), ",")
	return adapter.WIFIState{
		SSID:  state[0],
		BSSID: state[1],
	}
}

func (w *boxPlatformInterfaceWrapper) Initialize(n adapter.NetworkManager) error {
	w.bindNetwork(n)
	return nil
}

func (w *boxPlatformInterfaceWrapper) bindNetwork(n platformInterfaceState) {
	w.access.Lock()
	monitor := w.pendingMonitor
	w.pendingMonitor = nil
	w.access.Unlock()
	if monitor != nil {
		monitor.setNetwork(n)
	}
}

func (w *boxPlatformInterfaceWrapper) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) AutoDetectInterfaceControl(fd int) error {
	// call protect_path
	if !isBgProcess {
		_ = sendFdToProtect(fd, "protect_path")
		return nil
	}
	// bg process call VPNService
	return intfBox.AutoDetectInterfaceControl(int32(fd))
}

func (w *boxPlatformInterfaceWrapper) UsePlatformInterface() bool { return true }

func (w *boxPlatformInterfaceWrapper) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	if len(options.IncludeUID) > 0 || len(options.ExcludeUID) > 0 {
		return nil, E.New("android: unsupported uid options")
	}
	if len(options.IncludeAndroidUser) > 0 {
		return nil, E.New("android: unsupported android_user option")
	}
	a, _ := json.Marshal(options)
	b, _ := json.Marshal(platformOptions)
	tunFd, err := intfBox.OpenTun(string(a), string(b))
	if err != nil {
		return nil, fmt.Errorf("intfBox.OpenTun: %v", err)
	}
	// Do you want to close it?
	tunFd, err = syscall.Dup(tunFd)
	if err != nil {
		return nil, fmt.Errorf("syscall.Dup: %v", err)
	}
	if err = applyPlatformTunName(options, tunFd, getPlatformTunName); err != nil {
		_ = syscall.Close(tunFd)
		return nil, err
	}
	//
	options.FileDescriptor = int(tunFd)
	tunInterface, err := tun.New(*options)
	if err != nil {
		_ = syscall.Close(tunFd)
		return nil, err
	}
	return tunInterface, nil
}

func applyPlatformTunName(options *tun.Options, tunFD int, resolveName platformTunNameResolver) error {
	interfaceName, err := resolveName(tunFD)
	if err != nil {
		return fmt.Errorf("query Android TUN interface name: %w", err)
	}
	if interfaceName == "" {
		return fmt.Errorf("query Android TUN interface name: empty name")
	}
	options.Name = interfaceName
	if options.InterfaceMonitor != nil {
		options.InterfaceMonitor.RegisterMyInterface(interfaceName)
	}
	return nil
}

func (w *boxPlatformInterfaceWrapper) CloseTun() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformDefaultInterfaceMonitor() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) CreateDefaultInterfaceMonitor(l logger.Logger) tun.DefaultInterfaceMonitor {
	monitor := newPlatformDefaultInterfaceMonitor(nil, intfBox)
	monitor.logger = l
	w.access.Lock()
	w.pendingMonitor = monitor
	w.access.Unlock()
	return monitor
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNetworkInterfaces() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return parsePlatformNetworkInterfaces(intfBox.NetworkInterfaces())
}

type platformNetworkInterface struct {
	Index           int      `json:"index"`
	MTU             int      `json:"mtu"`
	Name            string   `json:"name"`
	HardwareAddress string   `json:"hardware_address"`
	Addresses       []string `json:"addresses"`
	Up              bool     `json:"up"`
	Running         bool     `json:"running"`
	Broadcast       bool     `json:"broadcast"`
	Loopback        bool     `json:"loopback"`
	PointToPoint    bool     `json:"point_to_point"`
	Multicast       bool     `json:"multicast"`
	Type            uint8    `json:"type"`
	DNSServers      []string `json:"dns_servers"`
	Expensive       bool     `json:"expensive"`
	Constrained     bool     `json:"constrained"`
	Underlying      *bool    `json:"underlying"`
	VPN             bool     `json:"vpn"`
}

func parsePlatformNetworkInterfaces(raw string) ([]adapter.NetworkInterface, error) {
	var source []platformNetworkInterface
	if err := json.Unmarshal([]byte(raw), &source); err != nil {
		return nil, fmt.Errorf("decode Android network interfaces: %w", err)
	}
	interfaces := make([]adapter.NetworkInterface, 0, len(source))
	for _, sourceInterface := range source {
		if !sourceInterface.Up || sourceInterface.Loopback || sourceInterface.VPN ||
			(sourceInterface.Underlying != nil && !*sourceInterface.Underlying) {
			continue
		}
		addresses := make([]netip.Prefix, 0, len(sourceInterface.Addresses))
		ownTunnel := false
		for _, address := range sourceInterface.Addresses {
			prefix, err := netip.ParsePrefix(address)
			if err != nil {
				return nil, fmt.Errorf("decode Android network interface %s address %q: %w", sourceInterface.Name, address, err)
			}
			if isNekoTunnelAddress(prefix.Addr()) {
				ownTunnel = true
			}
			addresses = append(addresses, prefix)
		}
		if ownTunnel {
			continue
		}
		var hardwareAddress net.HardwareAddr
		if sourceInterface.HardwareAddress != "" {
			parsedAddress, err := net.ParseMAC(sourceInterface.HardwareAddress)
			if err != nil {
				return nil, fmt.Errorf("decode Android network interface %s hardware address: %w", sourceInterface.Name, err)
			}
			hardwareAddress = parsedAddress
		}
		var flags net.Flags
		if sourceInterface.Up {
			flags |= net.FlagUp
		}
		if sourceInterface.Running {
			flags |= net.FlagRunning
		}
		if sourceInterface.Broadcast {
			flags |= net.FlagBroadcast
		}
		if sourceInterface.Loopback {
			flags |= net.FlagLoopback
		}
		if sourceInterface.PointToPoint {
			flags |= net.FlagPointToPoint
		}
		if sourceInterface.Multicast {
			flags |= net.FlagMulticast
		}
		interfaceType := C.InterfaceType(sourceInterface.Type)
		if interfaceType > C.InterfaceTypeOther {
			interfaceType = C.InterfaceTypeOther
		}
		interfaces = append(interfaces, adapter.NetworkInterface{
			Interface: control.Interface{
				Index:        sourceInterface.Index,
				MTU:          sourceInterface.MTU,
				Name:         sourceInterface.Name,
				HardwareAddr: hardwareAddress,
				Flags:        flags,
				Addresses:    addresses,
			},
			Type:        interfaceType,
			DNSServers:  sourceInterface.DNSServers,
			Expensive:   sourceInterface.Expensive,
			Constrained: sourceInterface.Constrained,
		})
	}
	return interfaces, nil
}

func isNekoTunnelAddress(address netip.Addr) bool {
	return address == netip.MustParseAddr("172.19.0.1") ||
		address == netip.MustParseAddr("fdfe:dcba:9876::1")
}

func (w *boxPlatformInterfaceWrapper) NetworkExtensionIncludeAllNetworks() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNotification() bool { return true }

func (w *boxPlatformInterfaceWrapper) SendNotification(notification *adapter.Notification) error {
	return intfBox.SendNotification(notification.Identifier, notification.Title, notification.Body, notification.OpenURL)
}

func (s *boxPlatformInterfaceWrapper) SystemCertificates() []string {
	return nil
}

// Android not using

func (w *boxPlatformInterfaceWrapper) UnderNetworkExtension() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) ClearDNSCache() {
}

func (w *boxPlatformInterfaceWrapper) RequestPermissionForWIFIState() error { return nil }

func (w *boxPlatformInterfaceWrapper) UsePlatformWIFIMonitor() bool { return false }

func (w *boxPlatformInterfaceWrapper) UsePlatformConnectionOwnerFinder() bool { return true }

func (w *boxPlatformInterfaceWrapper) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	var uid int32
	if useProcfs {
		sourceAddress, _ := netip.ParseAddr(request.SourceAddress)
		destinationAddress, _ := netip.ParseAddr(request.DestinationAddress)
		source := netip.AddrPortFrom(sourceAddress, uint16(request.SourcePort))
		destination := netip.AddrPortFrom(destinationAddress, uint16(request.DestinationPort))
		var network string
		switch request.IpProtocol {
		case syscall.IPPROTO_TCP:
			network = N.NetworkTCP
		case syscall.IPPROTO_UDP:
			network = N.NetworkUDP
		default:
			return nil, E.New("unknown protocol: ", request.IpProtocol)
		}
		uid = procfs.ResolveSocketByProcSearch(network, source, destination)
		if uid == -1 {
			return nil, E.New("procfs: not found")
		}
	} else {
		var err error
		uid, err = intfBox.FindConnectionOwner(
			request.IpProtocol,
			request.SourceAddress,
			request.SourcePort,
			request.DestinationAddress,
			request.DestinationPort,
		)
		if err != nil {
			return nil, err
		}
	}
	packageName, _ := intfBox.PackageNameByUid(uid)
	return &adapter.ConnectionOwner{UserId: uid, AndroidPackageNames: []string{packageName}}, nil
}

func (w *boxPlatformInterfaceWrapper) MyInterfaceAddress() []netip.Addr { return nil }

// io.Writer

var disableSingBoxLog = false

func (w *boxPlatformInterfaceWrapper) Write(p []byte) (n int, err error) {
	// use neko_log
	if !disableSingBoxLog {
		log.Print(string(p))
	}
	return len(p), nil
}

// 日志

type boxPlatformLogWriterWrapper struct {
}

var boxPlatformLogWriter sblog.PlatformWriter = &boxPlatformLogWriterWrapper{}

func (w *boxPlatformLogWriterWrapper) DisableColors() bool { return true }

func (w *boxPlatformLogWriterWrapper) WriteMessage(level uint8, message string) {
	if !strings.HasSuffix(message, "\n") {
		message += "\n"
	}
	neko_log.LogWriter.Write([]byte(message))
}
