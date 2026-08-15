package api

import "time"

const (
	ProtocolVersion  = 1
	AgentVersion     = "0.1.1"
	MinClientVersion = "0.3.0"
)

type ErrorResponse struct {
	OK      bool   `json:"ok"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

type HelloResponse struct {
	OK               bool   `json:"ok"`
	ProtocolVersion  int    `json:"protocolVersion"`
	AgentVersion     string `json:"agentVersion"`
	MinClientVersion string `json:"minClientVersion"`
}

type PairRequest struct {
	Code     string `json:"code"`
	DeviceID string `json:"deviceId"`
	Name     string `json:"name"`
}

type PairResponse struct {
	OK         bool      `json:"ok"`
	DeviceID   string    `json:"deviceId"`
	Credential string    `json:"credential"`
	Grants     []string  `json:"grants"`
	PairedAt   time.Time `json:"pairedAt"`
}

type ActionRequest struct {
	RequestID string `json:"requestId"`
}

type DesktopActionRequest struct {
	RequestID string `json:"requestId"`
	AppID     string `json:"appId"`
}

type OperationAcceptedResponse struct {
	OK          bool   `json:"ok"`
	OperationID string `json:"operationId"`
	Status      string `json:"status"`
	Duplicate   bool   `json:"duplicate,omitempty"`
}
