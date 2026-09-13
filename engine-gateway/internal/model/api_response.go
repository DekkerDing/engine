package model

import "time"

type ApiResponse struct {
	Code      int         `json:"code"`
	Message   string      `json:"message"`
	Data      interface{} `json:"data"`
	Timestamp string      `json:"timestamp"`
}

func Ok(data interface{}) ApiResponse {
	return ApiResponse{
		Code:      0,
		Message:   "ok",
		Data:      data,
		Timestamp: time.Now().UTC().Format(time.RFC3339),
	}
}

func Error(code int, message string) ApiResponse {
	return ApiResponse{
		Code:      code,
		Message:   message,
		Data:      nil,
		Timestamp: time.Now().UTC().Format(time.RFC3339),
	}
}