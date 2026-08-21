//go:build darwin

package libcore

import "golang.org/x/sys/unix"

func getPlatformTunName(fd int) (string, error) {
	return unix.GetsockoptString(
		fd,
		2, // SYSPROTO_CONTROL
		2, // UTUN_OPT_IFNAME
	)
}
