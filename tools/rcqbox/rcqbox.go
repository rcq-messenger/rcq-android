// SPDX-License-Identifier: GPL-3.0-or-later
//
// Built into app/libs/rcqbox.aar by tools/build-rcqbox.sh; see README.md here.

// Package rcqbox is a thin, gomobile-friendly wrapper around the sing-box
// core. It exposes just enough surface for the RCQ Android and iOS clients to
// run a VLESS+Reality outbound with a local mixed inbound, in-process, without
// a system VPN (VpnService / NetworkExtension).
package rcqbox

import (
	"context"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
)

// BoxService owns a single running sing-box instance.
type BoxService struct {
	instance *box.Box
	cancel   context.CancelFunc
}

// NewBoxService returns an unstarted service.
func NewBoxService() *BoxService {
	return &BoxService{}
}

// Start parses the sing-box JSON config and starts the instance. Calling
// Start on an already-running service is a no-op.
func (s *BoxService) Start(configJSON string) error {
	if s.instance != nil {
		return nil
	}
	ctx := include.Context(context.Background())
	ctx, cancel := context.WithCancel(ctx)

	options, err := json.UnmarshalExtendedContext[option.Options](ctx, []byte(configJSON))
	if err != nil {
		cancel()
		return err
	}

	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		cancel()
		return err
	}

	if err := instance.Start(); err != nil {
		instance.Close()
		cancel()
		return err
	}

	s.instance = instance
	s.cancel = cancel
	return nil
}

// Stop shuts the instance down. Safe to call when not running.
func (s *BoxService) Stop() error {
	if s.instance == nil {
		return nil
	}
	err := s.instance.Close()
	if s.cancel != nil {
		s.cancel()
	}
	s.instance = nil
	s.cancel = nil
	return err
}

// IsRunning reports whether an instance is currently active.
func (s *BoxService) IsRunning() bool {
	return s.instance != nil
}
