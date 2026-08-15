// Package integrations defines the compile-time adapter registry used from I5.
package integrations

import (
	"errors"
	"sort"
	"sync"
)

type Descriptor struct {
	ID                   string   `json:"id"`
	Version              string   `json:"version"`
	RiskLevel            string   `json:"riskLevel"`
	Mutability           string   `json:"mutability"`
	TimeoutClass         string   `json:"timeoutClass"`
	InputSchema          string   `json:"inputSchema"`
	OutputSchema         string   `json:"outputSchema"`
	PresentationHint     string   `json:"presentationHint"`
	RequiredCapabilities []string `json:"requiredCapabilities"`
}

type Adapter interface {
	Descriptor() Descriptor
}

type Registry struct {
	mu       sync.RWMutex
	adapters map[string]Adapter
}

func NewRegistry(adapters ...Adapter) (*Registry, error) {
	registry := &Registry{adapters: map[string]Adapter{}}
	for _, adapter := range adapters {
		if adapter == nil || adapter.Descriptor().ID == "" {
			return nil, errors.New("integration adapter requires an id")
		}
		id := adapter.Descriptor().ID
		if _, exists := registry.adapters[id]; exists {
			return nil, errors.New("duplicate integration adapter: " + id)
		}
		registry.adapters[id] = adapter
	}
	return registry, nil
}

func (r *Registry) Descriptors() []Descriptor {
	r.mu.RLock()
	defer r.mu.RUnlock()
	descriptors := make([]Descriptor, 0, len(r.adapters))
	for _, adapter := range r.adapters {
		descriptors = append(descriptors, adapter.Descriptor())
	}
	sort.Slice(descriptors, func(i, j int) bool { return descriptors[i].ID < descriptors[j].ID })
	return descriptors
}
