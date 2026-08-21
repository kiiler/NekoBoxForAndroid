package libcore

import (
	"context"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

func TestTailscaleRuntimeConfigDecodes(t *testing.T) {
	ctx := box.Context(
		context.Background(),
		nekoboxAndroidInboundRegistry(),
		nekoboxAndroidOutboundRegistry(),
		nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(nil),
		nekoboxAndroidServiceRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	config := []byte(`{
		"dns": {
			"servers": [
				{"type":"https","tag":"tailscale-bootstrap","server":"1.1.1.1","detour":"proxy"},
				{"type":"tailscale","tag":"tailscale-dns","endpoint":"tailscale-in"}
			],
			"rules": [
				{"domain_suffix":["tailscale.com","tailscale.io"],"server":"tailscale-bootstrap"},
				{"domain_suffix":["ts.net"],"server":"tailscale-dns"}
			]
		},
		"endpoints": [{
			"type":"tailscale",
			"tag":"tailscale-in",
			"state_directory":"/tmp/tailscale",
			"accept_routes":true,
			"domain_resolver":{"server":"tailscale-bootstrap","strategy":"ipv4_only"}
		}],
		"outbounds": [
			{"type":"selector","tag":"proxy","outbounds":["direct"]},
			{"type":"direct","tag":"direct"}
		],
		"route": {"rules":[
			{"domain_suffix":["ts.net"],"outbound":"tailscale-in"},
			{"ip_cidr":["100.64.0.0/10","fd7a:115c:a1e0::/48"],"outbound":"tailscale-in"},
			{"ip_is_private":true,"outbound":"direct"}
		],"final":"proxy"}
	}`)
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, config); err != nil {
		t.Fatal(err)
	}
}

func TestMigratedNekoBoxInboundConfigDecodes(t *testing.T) {
	ctx := box.Context(
		context.Background(),
		nekoboxAndroidInboundRegistry(),
		nekoboxAndroidOutboundRegistry(),
		nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(nil),
		nekoboxAndroidServiceRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	config := []byte(`{
		"inbounds": [
			{"type":"tun","tag":"tun-in","stack":"mixed","address":["172.19.0.1/28","fdfe:dcba:9876::1/126"],"mtu":9000},
			{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":2080}
		],
		"outbounds": [{"type":"direct","tag":"direct"}],
		"route": {"rules":[
			{"inbound":"tun-in","action":"resolve","strategy":"prefer_ipv4"},
			{"inbound":"tun-in","action":"sniff"},
			{"inbound":"mixed-in","action":"sniff"},
			{"outbound":"direct"}
		],"final":"direct"}
	}`)
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, config); err != nil {
		t.Fatal(err)
	}
}
