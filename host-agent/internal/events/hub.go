package events

import (
	"encoding/json"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

type Event struct {
	Sequence uint64    `json:"sequence"`
	Type     string    `json:"type"`
	At       time.Time `json:"at"`
	Payload  any       `json:"payload"`
}

type Hub struct {
	mu       sync.Mutex
	clients  map[*websocket.Conn]chan []byte
	sequence atomic.Uint64
	now      func() time.Time
	upgrader websocket.Upgrader
}

func New() *Hub {
	return &Hub{
		clients: map[*websocket.Conn]chan []byte{}, now: time.Now,
		upgrader: websocket.Upgrader{
			ReadBufferSize: 4096, WriteBufferSize: 4096,
			CheckOrigin: func(*http.Request) bool { return true },
		},
	}
}

func (h *Hub) Publish(eventType string, payload any) Event {
	event := Event{Sequence: h.sequence.Add(1), Type: eventType, At: h.now().UTC(), Payload: payload}
	data, err := json.Marshal(event)
	if err != nil {
		return event
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	for conn, queue := range h.clients {
		select {
		case queue <- data:
		default:
			close(queue)
			delete(h.clients, conn)
			_ = conn.Close()
		}
	}
	return event
}

func (h *Hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	conn, err := h.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	queue := make(chan []byte, 64)
	h.mu.Lock()
	h.clients[conn] = queue
	h.mu.Unlock()

	done := make(chan struct{})
	go func() {
		defer close(done)
		conn.SetReadLimit(4096)
		_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		conn.SetPongHandler(func(string) error {
			return conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		})
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	}()

	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	defer func() {
		h.mu.Lock()
		if current, ok := h.clients[conn]; ok && current == queue {
			delete(h.clients, conn)
			close(queue)
		}
		h.mu.Unlock()
		_ = conn.Close()
	}()
	for {
		select {
		case data, ok := <-queue:
			if !ok {
				return
			}
			_ = conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := conn.WriteMessage(websocket.TextMessage, data); err != nil {
				return
			}
		case <-ticker.C:
			_ = conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		case <-done:
			return
		case <-r.Context().Done():
			return
		}
	}
}
