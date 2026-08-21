package libcore

import (
	"encoding/json"
	"errors"
	"fmt"
	"libcore/procfs"
	"log"
	"net/netip"
	"strings"
	"syscall"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/adapter"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	N "github.com/sagernet/sing/common/network"
)

var boxPlatformInterfaceInstance adapter.PlatformInterface = &boxPlatformInterfaceWrapper{}

type boxPlatformInterfaceWrapper struct{}

func (w *boxPlatformInterfaceWrapper) ReadWIFIState() adapter.WIFIState {
	state := strings.Split(intfBox.WIFIState(), ",")
	return adapter.WIFIState{
		SSID:  state[0],
		BSSID: state[1],
	}
}

func (w *boxPlatformInterfaceWrapper) Initialize(n adapter.NetworkManager) error {
	return nil
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
	//
	options.FileDescriptor = int(tunFd)
	return tun.New(*options)
}

func (w *boxPlatformInterfaceWrapper) CloseTun() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformDefaultInterfaceMonitor() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) CreateDefaultInterfaceMonitor(l logger.Logger) tun.DefaultInterfaceMonitor {
	return &interfaceMonitorStub{}
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNetworkInterfaces() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return nil, errors.New("wtf")
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
