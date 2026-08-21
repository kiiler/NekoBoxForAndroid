package libcore

import (
	"errors"
	"sync"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
)

var _ tun.DefaultInterfaceMonitor = (*platformDefaultInterfaceMonitor)(nil)
var _ InterfaceUpdateListener = (*platformDefaultInterfaceMonitor)(nil)

type defaultInterfaceMonitorController interface {
	StartDefaultInterfaceMonitor(monitorID int64, listener InterfaceUpdateListener) error
	CloseDefaultInterfaceMonitor(monitorID int64) error
}

type platformInterfaceState interface {
	UpdateInterfaces() error
	NetworkInterfaces() []adapter.NetworkInterface
}

type platformDefaultInterfaceMonitor struct {
	access           sync.Mutex
	id               int64
	network          platformInterfaceState
	platform         defaultInterfaceMonitorController
	defaultInterface *control.Interface
	callbacks        list.List[tun.DefaultInterfaceUpdateCallback]
	myInterfaces     []string
	logger           logger.Logger
	started          bool
	closed           bool
}

var nextPlatformDefaultInterfaceMonitorID atomic.Int64

func (m *platformDefaultInterfaceMonitor) setNetwork(network platformInterfaceState) {
	m.access.Lock()
	m.network = network
	m.access.Unlock()
}

func newPlatformDefaultInterfaceMonitor(network platformInterfaceState, platform defaultInterfaceMonitorController) *platformDefaultInterfaceMonitor {
	return &platformDefaultInterfaceMonitor{
		network:  network,
		platform: platform,
		id:       nextPlatformDefaultInterfaceMonitorID.Add(1),
	}
}

func (m *platformDefaultInterfaceMonitor) Start() error {
	m.access.Lock()
	if m.closed || m.started {
		m.access.Unlock()
		return nil
	}
	m.started = true
	platform := m.platform
	m.access.Unlock()
	if platform != nil {
		err := platform.StartDefaultInterfaceMonitor(m.id, m)
		if err != nil {
			cleanupErr := platform.CloseDefaultInterfaceMonitor(m.id)
			m.access.Lock()
			m.started = cleanupErr != nil
			m.access.Unlock()
			return errors.Join(err, cleanupErr)
		}
		return err
	}
	return nil
}

func (m *platformDefaultInterfaceMonitor) Close() error {
	m.access.Lock()
	if m.closed {
		m.access.Unlock()
		return nil
	}
	m.closed = true
	started := m.started
	m.started = false
	platform := m.platform
	m.access.Unlock()
	if started && platform != nil {
		return platform.CloseDefaultInterfaceMonitor(m.id)
	}
	return nil
}

func (m *platformDefaultInterfaceMonitor) DefaultInterface() *control.Interface {
	m.access.Lock()
	defer m.access.Unlock()
	if m.defaultInterface == nil {
		return nil
	}
	defaultInterface := *m.defaultInterface
	return &defaultInterface
}

func (m *platformDefaultInterfaceMonitor) OverrideAndroidVPN() bool {
	return false
}

func (m *platformDefaultInterfaceMonitor) AndroidVPNEnabled() bool {
	return false
}

func (m *platformDefaultInterfaceMonitor) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	m.access.Lock()
	defer m.access.Unlock()
	return m.callbacks.PushBack(callback)
}

func (m *platformDefaultInterfaceMonitor) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	if element == nil {
		return
	}
	m.access.Lock()
	defer m.access.Unlock()
	m.callbacks.Remove(element)
}

func (m *platformDefaultInterfaceMonitor) RegisterMyInterface(interfaceName string) {
	if interfaceName == "" {
		return
	}
	m.access.Lock()
	defer m.access.Unlock()
	if !common.Contains(m.myInterfaces, interfaceName) {
		m.myInterfaces = append(m.myInterfaces, interfaceName)
	}
}

func (m *platformDefaultInterfaceMonitor) MyInterface() string {
	m.access.Lock()
	defer m.access.Unlock()
	if len(m.myInterfaces) == 0 {
		return ""
	}
	return m.myInterfaces[0]
}

func (m *platformDefaultInterfaceMonitor) MyInterfaces() []string {
	m.access.Lock()
	defer m.access.Unlock()
	return append([]string(nil), m.myInterfaces...)
}

func (m *platformDefaultInterfaceMonitor) UpdateDefaultInterface(interfaceName string, interfaceIndex int32, _ bool, _ bool, forceUpdate bool) {
	done := make(chan struct{})
	go func() {
		m.updateDefaultInterface(interfaceName, interfaceIndex, forceUpdate)
		close(done)
	}()
	<-done
}

func (m *platformDefaultInterfaceMonitor) updateDefaultInterface(interfaceName string, interfaceIndex int32, forceUpdate bool) {
	m.access.Lock()
	if m.closed {
		m.access.Unlock()
		return
	}
	network := m.network
	monitorLogger := m.logger
	m.access.Unlock()

	if network != nil {
		if err := network.UpdateInterfaces(); err != nil {
			if monitorLogger != nil {
				monitorLogger.Error("update Android network interfaces: ", err)
			}
			return
		}
	}

	var defaultInterface *control.Interface
	if interfaceName != "" && interfaceIndex >= 0 {
		defaultInterface = &control.Interface{
			Name:  interfaceName,
			Index: int(interfaceIndex),
		}
		if network != nil {
			var resolvedInterface *control.Interface
			interfaces := network.NetworkInterfaces()
			for index := range interfaces {
				if interfaces[index].Index == int(interfaceIndex) {
					resolved := interfaces[index].Interface
					resolvedInterface = &resolved
					break
				}
			}
			if resolvedInterface == nil {
				for index := range interfaces {
					if interfaces[index].Name == interfaceName {
						resolved := interfaces[index].Interface
						resolvedInterface = &resolved
						break
					}
				}
			}
			if resolvedInterface == nil {
				if monitorLogger != nil {
					monitorLogger.Error("find Android default interface ", interfaceName, " in platform snapshot")
				}
				return
			}
			defaultInterface = resolvedInterface
		}
	}

	m.access.Lock()
	if m.closed {
		m.access.Unlock()
		return
	}
	if defaultInterface != nil && common.Contains(m.myInterfaces, defaultInterface.Name) {
		defaultInterface = nil
	}
	sameInterface := samePlatformInterface(m.defaultInterface, defaultInterface)
	if defaultInterface != nil {
		defaultInterfaceCopy := *defaultInterface
		m.defaultInterface = &defaultInterfaceCopy
		defaultInterface = &defaultInterfaceCopy
	} else {
		m.defaultInterface = nil
	}
	if sameInterface && !forceUpdate {
		m.access.Unlock()
		return
	}
	callbacks := m.callbacks.Array()
	m.access.Unlock()

	for _, callback := range callbacks {
		callback(defaultInterface, 0)
	}
}

func samePlatformInterface(left *control.Interface, right *control.Interface) bool {
	if left == nil || right == nil {
		return left == nil && right == nil
	}
	return left.Index == right.Index && left.Name == right.Name
}
