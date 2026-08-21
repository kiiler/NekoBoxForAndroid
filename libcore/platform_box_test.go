package libcore

import (
	"errors"
	"net"
	"net/netip"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
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
	_, err := parsePlatformNetworkInterfaces(`[{"index":1,"mtu":1500,"name":"wlan0","addresses":["invalid"],"up":true}]`)
	if err == nil {
		t.Fatal("expected invalid prefix error")
	}
}

func TestApplyPlatformTunNameRegistersActualAndroidInterface(t *testing.T) {
	monitor := newPlatformDefaultInterfaceMonitor(nil, nil)
	options := &tun.Options{InterfaceMonitor: monitor}
	var resolvedFD int

	if err := applyPlatformTunName(options, 77, func(fd int) (string, error) {
		resolvedFD = fd
		return "tun42", nil
	}); err != nil {
		t.Fatal(err)
	}
	if resolvedFD != 77 {
		t.Fatalf("unexpected TUN fd passed to resolver: %d", resolvedFD)
	}
	if options.Name != "tun42" {
		t.Fatalf("actual TUN name was not written back: %q", options.Name)
	}
	if got := monitor.MyInterfaces(); len(got) != 1 || got[0] != "tun42" {
		t.Fatalf("actual TUN interface was not registered: %v", got)
	}
}

func TestParsePlatformNetworkInterfacesKeepsOnlyUsableUnderlyingNetworks(t *testing.T) {
	interfaces, err := parsePlatformNetworkInterfaces(`[
		{"index":12,"mtu":1500,"name":"wlan0","addresses":["192.0.2.10/24"],"up":true,"running":true,"type":0,"underlying":true},
		{"index":1,"mtu":65536,"name":"lo","addresses":["127.0.0.1/8"],"up":true,"running":true,"loopback":true,"type":3,"underlying":false},
		{"index":13,"mtu":1500,"name":"rmnet_data0","addresses":["2001:db8::10/64"],"up":false,"running":false,"type":1,"underlying":true},
		{"index":14,"mtu":9000,"name":"tun0","addresses":["172.19.0.1/28","fdfe:dcba:9876::1/126"],"up":true,"running":true,"point_to_point":true,"type":3,"vpn":true,"underlying":false},
		{"index":15,"mtu":1280,"name":"tun9","addresses":["10.8.0.2/24"],"up":true,"running":true,"point_to_point":true,"type":3,"vpn":true,"underlying":false}
	]`)
	if err != nil {
		t.Fatal(err)
	}
	if len(interfaces) != 1 {
		t.Fatalf("expected only the usable underlying interface, got %+v", interfaces)
	}
	if interfaces[0].Name != "wlan0" {
		t.Fatalf("unexpected retained interface: %+v", interfaces[0].Interface)
	}
}

func TestPlatformDefaultInterfaceMonitorPublishesChanges(t *testing.T) {
	monitor := newPlatformDefaultInterfaceMonitor(nil, nil)
	var updates []*control.Interface
	element := monitor.RegisterCallback(func(defaultInterface *control.Interface, _ int) {
		updates = append(updates, defaultInterface)
	})
	defer monitor.UnregisterCallback(element)

	monitor.UpdateDefaultInterface("wlan0", 12, false, false, false)
	got := monitor.DefaultInterface()
	if got == nil || got.Name != "wlan0" || got.Index != 12 {
		t.Fatalf("unexpected default interface: %+v", got)
	}
	if len(updates) != 1 || updates[0] == nil || updates[0].Name != "wlan0" {
		t.Fatalf("unexpected callbacks: %+v", updates)
	}

	monitor.UpdateDefaultInterface("wlan0", 12, false, false, false)
	if len(updates) != 1 {
		t.Fatalf("unchanged interface emitted another callback: %+v", updates)
	}

	monitor.UpdateDefaultInterface("", -1, false, false, false)
	if monitor.DefaultInterface() != nil {
		t.Fatalf("expected no default interface, got %+v", monitor.DefaultInterface())
	}
	if len(updates) != 2 || updates[1] != nil {
		t.Fatalf("missing no-route callback: %+v", updates)
	}
}

type testDefaultInterfaceMonitorController struct {
	started  int
	closed   int
	startID  int64
	closeID  int64
	startErr error
}

type testPlatformInterfaceState struct {
	updates int
	finder  *control.DefaultInterfaceFinder
	current control.Interface
}

type testCountingFallbackFinder struct {
	*control.DefaultInterfaceFinder
	lookups int
}

func (f *testCountingFallbackFinder) ByIndex(index int) (*control.Interface, error) {
	f.lookups++
	return f.DefaultInterfaceFinder.ByIndex(index)
}

func (f *testCountingFallbackFinder) ByName(name string) (*control.Interface, error) {
	f.lookups++
	return f.DefaultInterfaceFinder.ByName(name)
}

type testPlatformSnapshotMissState struct {
	finder *testCountingFallbackFinder
}

func (s *testPlatformSnapshotMissState) UpdateInterfaces() error {
	return nil
}

func (s *testPlatformSnapshotMissState) InterfaceFinder() control.InterfaceFinder {
	return s.finder
}

func (s *testPlatformSnapshotMissState) NetworkInterfaces() []adapter.NetworkInterface {
	return nil
}

func (s *testPlatformInterfaceState) UpdateInterfaces() error {
	s.updates++
	current := s.current
	if current.Name == "" {
		current = control.Interface{
			Index: 21,
			MTU:   1400,
			Name:  "rmnet_data0",
			Flags: net.FlagUp | net.FlagRunning,
		}
	}
	s.current = current
	s.finder.UpdateInterfaces([]control.Interface{current})
	return nil
}

func (s *testPlatformInterfaceState) NetworkInterfaces() []adapter.NetworkInterface {
	return []adapter.NetworkInterface{{Interface: s.current}}
}

func (s *testPlatformInterfaceState) InterfaceFinder() control.InterfaceFinder {
	return s.finder
}

func (c *testDefaultInterfaceMonitorController) StartDefaultInterfaceMonitor(monitorID int64, listener InterfaceUpdateListener) error {
	c.started++
	c.startID = monitorID
	listener.UpdateDefaultInterface("rmnet_data0", 21, true, false, false)
	return c.startErr
}

func (c *testDefaultInterfaceMonitorController) CloseDefaultInterfaceMonitor(monitorID int64) error {
	c.closed++
	c.closeID = monitorID
	return nil
}

func TestPlatformDefaultInterfaceMonitorUsesPlatformUpdates(t *testing.T) {
	controller := new(testDefaultInterfaceMonitorController)
	monitor := newPlatformDefaultInterfaceMonitor(nil, controller)

	if err := monitor.Start(); err != nil {
		t.Fatal(err)
	}
	got := monitor.DefaultInterface()
	if controller.started != 1 || got == nil || got.Name != "rmnet_data0" || got.Index != 21 {
		t.Fatalf("platform update was not applied: started=%d default=%+v", controller.started, got)
	}

	if err := monitor.Close(); err != nil {
		t.Fatal(err)
	}
	if controller.closed != 1 {
		t.Fatalf("platform monitor was not closed: %d", controller.closed)
	}
	if controller.startID <= 0 || controller.closeID != controller.startID {
		t.Fatalf("platform monitor ID was not stable: start=%d close=%d", controller.startID, controller.closeID)
	}
}

func TestPlatformDefaultInterfaceMonitorCleansPartialStartFailure(t *testing.T) {
	startErr := errors.New("platform start failed after registration")
	controller := &testDefaultInterfaceMonitorController{startErr: startErr}
	monitor := newPlatformDefaultInterfaceMonitor(nil, controller)

	if err := monitor.Start(); !errors.Is(err, startErr) {
		t.Fatalf("unexpected start error: %v", err)
	}
	if controller.started != 1 || controller.closed != 1 {
		t.Fatalf("partial platform registration was not cleaned: started=%d closed=%d", controller.started, controller.closed)
	}
	if controller.startID <= 0 || controller.closeID != controller.startID {
		t.Fatalf("cleanup used the wrong monitor ID: start=%d close=%d", controller.startID, controller.closeID)
	}

	if err := monitor.Close(); err != nil {
		t.Fatal(err)
	}
	if controller.closed != 1 {
		t.Fatalf("cleaned monitor was closed twice: %d", controller.closed)
	}
}

func TestPlatformDefaultInterfaceMonitorRefreshesInterfaceStateBeforePublishing(t *testing.T) {
	state := &testPlatformInterfaceState{finder: control.NewDefaultInterfaceFinder()}
	monitor := newPlatformDefaultInterfaceMonitor(state, nil)

	monitor.UpdateDefaultInterface("rmnet_data0", 21, true, false, false)
	got := monitor.DefaultInterface()
	if state.updates != 1 || got == nil || got.MTU != 1400 || got.Flags&net.FlagUp == 0 {
		t.Fatalf("interface state was not refreshed: updates=%d default=%+v", state.updates, got)
	}
}

func TestPlatformDefaultInterfaceMonitorDoesNotFallbackOutsidePlatformSnapshot(t *testing.T) {
	finder := &testCountingFallbackFinder{DefaultInterfaceFinder: control.NewDefaultInterfaceFinder()}
	state := &testPlatformSnapshotMissState{finder: finder}
	monitor := newPlatformDefaultInterfaceMonitor(state, nil)

	monitor.UpdateDefaultInterface("missing0", 987654, false, false, false)

	if finder.lookups != 0 {
		t.Fatalf("platform snapshot miss fell back to native interfaces: %d lookups", finder.lookups)
	}
	if monitor.DefaultInterface() != nil {
		t.Fatalf("missing platform interface was published: %+v", monitor.DefaultInterface())
	}
}

func TestPlatformDefaultInterfaceMonitorRefreshesSnapshotWithoutDuplicateCallback(t *testing.T) {
	state := &testPlatformInterfaceState{
		finder: control.NewDefaultInterfaceFinder(),
		current: control.Interface{
			Index:     21,
			MTU:       1400,
			Name:      "rmnet_data0",
			Flags:     net.FlagUp | net.FlagRunning,
			Addresses: []netip.Prefix{netip.MustParsePrefix("192.0.2.10/24")},
		},
	}
	monitor := newPlatformDefaultInterfaceMonitor(state, nil)
	callbackCount := 0
	element := monitor.RegisterCallback(func(_ *control.Interface, _ int) {
		callbackCount++
	})
	defer monitor.UnregisterCallback(element)

	monitor.UpdateDefaultInterface("rmnet_data0", 21, true, false, false)
	state.current.MTU = 1280
	state.current.Addresses = []netip.Prefix{netip.MustParsePrefix("198.51.100.20/24")}
	monitor.UpdateDefaultInterface("rmnet_data0", 21, true, false, false)

	got := monitor.DefaultInterface()
	if got == nil || got.MTU != 1280 || len(got.Addresses) != 1 || got.Addresses[0].String() != "198.51.100.20/24" {
		t.Fatalf("same-index interface snapshot was not refreshed: %+v", got)
	}
	if callbackCount != 1 {
		t.Fatalf("same interface emitted a duplicate callback: %d", callbackCount)
	}
}

func TestPlatformDefaultInterfaceMonitorPublishesForcedSnapshotRefresh(t *testing.T) {
	state := &testPlatformInterfaceState{
		finder: control.NewDefaultInterfaceFinder(),
		current: control.Interface{
			Index:     21,
			MTU:       1400,
			Name:      "rmnet_data0",
			Flags:     net.FlagUp | net.FlagRunning,
			Addresses: []netip.Prefix{netip.MustParsePrefix("192.0.2.10/24")},
		},
	}
	monitor := newPlatformDefaultInterfaceMonitor(state, nil)
	callbackCount := 0
	element := monitor.RegisterCallback(func(_ *control.Interface, _ int) {
		callbackCount++
	})
	defer monitor.UnregisterCallback(element)

	monitor.UpdateDefaultInterface("rmnet_data0", 21, true, false, false)
	state.current.MTU = 1280
	state.current.Addresses = []netip.Prefix{netip.MustParsePrefix("198.51.100.20/24")}
	monitor.UpdateDefaultInterface("rmnet_data0", 21, true, false, true)

	got := monitor.DefaultInterface()
	if got == nil || got.MTU != 1280 || len(got.Addresses) != 1 || got.Addresses[0].String() != "198.51.100.20/24" {
		t.Fatalf("forced interface snapshot was not refreshed: %+v", got)
	}
	if callbackCount != 2 {
		t.Fatalf("forced refresh did not publish an update: %d", callbackCount)
	}
}

func TestPlatformDefaultInterfaceMonitorIgnoresLateUpdatesAfterClose(t *testing.T) {
	monitor := newPlatformDefaultInterfaceMonitor(nil, nil)
	monitor.UpdateDefaultInterface("wlan0", 12, false, false, false)
	if err := monitor.Close(); err != nil {
		t.Fatal(err)
	}

	monitor.UpdateDefaultInterface("rmnet_data0", 21, true, false, false)
	got := monitor.DefaultInterface()
	if got == nil || got.Name != "wlan0" {
		t.Fatalf("late update replaced closed monitor state: %+v", got)
	}
}

func TestNewBoxPlatformInterfacesDoNotShareNetworkBindings(t *testing.T) {
	firstPlatform := newBoxPlatformInterface()
	secondPlatform := newBoxPlatformInterface()
	if firstPlatform == secondPlatform {
		t.Fatal("new boxes received the same platform wrapper")
	}

	firstMonitor := firstPlatform.CreateDefaultInterfaceMonitor(nil).(*platformDefaultInterfaceMonitor)
	secondMonitor := secondPlatform.CreateDefaultInterfaceMonitor(nil).(*platformDefaultInterfaceMonitor)
	firstState := &testPlatformInterfaceState{
		finder: control.NewDefaultInterfaceFinder(),
		current: control.Interface{
			Index: 11,
			MTU:   1500,
			Name:  "wlan0",
			Flags: net.FlagUp | net.FlagRunning,
		},
	}
	secondState := &testPlatformInterfaceState{
		finder: control.NewDefaultInterfaceFinder(),
		current: control.Interface{
			Index: 21,
			MTU:   1400,
			Name:  "rmnet_data0",
			Flags: net.FlagUp | net.FlagRunning,
		},
	}

	secondPlatform.bindNetwork(secondState)
	firstPlatform.bindNetwork(firstState)
	firstMonitor.UpdateDefaultInterface("wlan0", 11, false, false, false)
	secondMonitor.UpdateDefaultInterface("rmnet_data0", 21, true, false, false)

	if got := firstMonitor.DefaultInterface(); got == nil || got.Name != "wlan0" || got.MTU != 1500 {
		t.Fatalf("first platform used the wrong network manager: %+v", got)
	}
	if got := secondMonitor.DefaultInterface(); got == nil || got.Name != "rmnet_data0" || got.MTU != 1400 {
		t.Fatalf("second platform used the wrong network manager: %+v", got)
	}
}
