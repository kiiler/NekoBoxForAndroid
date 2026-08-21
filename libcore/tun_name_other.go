//go:build !(darwin || linux)

package libcore

import "os"

func getPlatformTunName(fd int) (string, error) {
	return "", os.ErrInvalid
}
