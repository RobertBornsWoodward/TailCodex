package integrations

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
)

type RootGuard struct {
	roots []string
}

func NewRootGuard(configuredRoots []string) (*RootGuard, error) {
	guard := &RootGuard{}
	for _, root := range configuredRoots {
		absolute, err := filepath.Abs(root)
		if err != nil {
			return nil, err
		}
		resolved, err := filepath.EvalSymlinks(absolute)
		if err != nil {
			return nil, err
		}
		info, err := os.Stat(resolved)
		if err != nil || !info.IsDir() {
			return nil, errors.New("configured workspace root is not a directory")
		}
		guard.roots = append(guard.roots, filepath.Clean(resolved))
	}
	if len(guard.roots) == 0 {
		return nil, errors.New("at least one workspace root is required")
	}
	return guard, nil
}

// ResolveExisting accepts an absolute candidate only when its canonical path
// remains inside one configured root. EvalSymlinks prevents symbolic-link escape.
func (g *RootGuard) ResolveExisting(candidate string) (string, error) {
	if candidate == "" || strings.IndexByte(candidate, 0) >= 0 {
		return "", errors.New("invalid workspace path")
	}
	absolute, err := filepath.Abs(candidate)
	if err != nil {
		return "", err
	}
	resolved, err := filepath.EvalSymlinks(absolute)
	if err != nil {
		return "", err
	}
	resolved = filepath.Clean(resolved)
	for _, root := range g.roots {
		relative, err := filepath.Rel(root, resolved)
		if err == nil && relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
			return resolved, nil
		}
	}
	return "", errors.New("path escapes configured workspace roots")
}
