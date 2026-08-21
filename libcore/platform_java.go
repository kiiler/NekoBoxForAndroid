package libcore

var intfBox BoxPlatformInterface
var intfNB4A NB4AInterface

var useProcfs bool
var isBgProcess bool

type NB4AInterface interface {
	UseOfficialAssets() bool
	Selector_OnProxySelected(selectorTag string, tag string)
}

type BoxPlatformInterface interface {
	AutoDetectInterfaceControl(fd int32) error
	OpenTun(singTunOptionsJson, tunPlatformOptionsJson string) (int, error)
	UseProcFS() bool
	FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (int32, error)
	PackageNameByUid(uid int32) (string, error)
	UIDByPackageName(packageName string) (int32, error)
	WIFIState() string
	NetworkInterfaces() string
	StartDefaultInterfaceMonitor(monitorID int64, listener InterfaceUpdateListener) error
	CloseDefaultInterfaceMonitor(monitorID int64) error
	SendNotification(identifier, title, body, openURL string) error
}

type InterfaceUpdateListener interface {
	UpdateDefaultInterface(interfaceName string, interfaceIndex int32, isExpensive bool, isConstrained bool, forceUpdate bool)
}
