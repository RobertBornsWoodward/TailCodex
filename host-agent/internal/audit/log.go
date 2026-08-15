package audit

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type Entry struct {
	Timestamp   time.Time         `json:"timestamp"`
	RequestID   string            `json:"requestId,omitempty"`
	OperationID string            `json:"operationId,omitempty"`
	Actor       string            `json:"actor"`
	Action      string            `json:"action"`
	RiskLevel   string            `json:"riskLevel"`
	Outcome     string            `json:"outcome"`
	Metadata    map[string]string `json:"metadata,omitempty"`
}

type Logger struct {
	path string
	mu   sync.Mutex
	now  func() time.Time
}

func New(path string) *Logger { return &Logger{path: path, now: time.Now} }

func (l *Logger) Append(entry Entry) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if err := os.MkdirAll(filepath.Dir(l.path), 0o700); err != nil {
		return err
	}
	entry.Timestamp = l.now().UTC()
	entry.Metadata = redact(entry.Metadata)
	file, err := os.OpenFile(l.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	defer file.Close()
	return json.NewEncoder(file).Encode(entry)
}

// Recent returns a bounded tail of already-redacted metadata audit entries.
// It never reads arbitrary files and does not expose the logger's backing path.
func (l *Logger) Recent(limit int) ([]Entry, error) {
	if limit <= 0 || limit > 100 {
		return nil, errors.New("audit summary limit must be between 1 and 100")
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	file, err := os.Open(l.path)
	if errors.Is(err, os.ErrNotExist) {
		return []Entry{}, nil
	}
	if err != nil {
		return nil, err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return nil, err
	}
	const maximumScanBytes int64 = 16 * 1024 * 1024
	start := info.Size() - maximumScanBytes
	if start < 0 {
		start = 0
	}
	if _, err := file.Seek(start, io.SeekStart); err != nil {
		return nil, err
	}

	entries := make([]Entry, 0, limit)
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 16*1024), 256*1024)
	if start > 0 {
		// The seek may land inside a JSON line; discard only that partial record.
		scanner.Scan()
	}
	for scanner.Scan() {
		var entry Entry
		if err := json.Unmarshal(scanner.Bytes(), &entry); err != nil {
			continue
		}
		entry.Metadata = redact(entry.Metadata)
		if len(entries) == limit {
			copy(entries, entries[1:])
			entries[len(entries)-1] = entry
		} else {
			entries = append(entries, entry)
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	for left, right := 0, len(entries)-1; left < right; left, right = left+1, right-1 {
		entries[left], entries[right] = entries[right], entries[left]
	}
	return entries, nil
}

func redact(metadata map[string]string) map[string]string {
	if len(metadata) == 0 {
		return nil
	}
	result := make(map[string]string, len(metadata))
	for key, value := range metadata {
		lower := strings.ToLower(key)
		if strings.Contains(lower, "token") || strings.Contains(lower, "credential") ||
			strings.Contains(lower, "authorization") || strings.Contains(lower, "password") ||
			strings.Contains(lower, "terminal") || strings.Contains(lower, "input") {
			result[key] = "[redacted]"
			continue
		}
		if len(value) > 512 {
			value = value[:512]
		}
		result[key] = value
	}
	return result
}
