package config

import (
	"os"
	"time"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Server   ServerConfig   `yaml:"server"`
	Upstream UpstreamConfig `yaml:"upstream"`
	Health   HealthConfig   `yaml:"health"`
	Static   StaticConfig   `yaml:"static"`
	Logging  LoggingConfig  `yaml:"logging"`
}

type ServerConfig struct {
	Port int `yaml:"port"`
}

type UpstreamConfig struct {
	BaseURL        string        `yaml:"base_url"`
	ConnectTimeout time.Duration `yaml:"connect_timeout"`
	ReadTimeout    time.Duration `yaml:"read_timeout"`
	MaxBodyBytes   int64         `yaml:"max_body_bytes"`
}

type HealthConfig struct {
	ProbeInterval    time.Duration `yaml:"probe_interval"`
	FailureThreshold int           `yaml:"failure_threshold"`
}

type StaticConfig struct {
	Dir string `yaml:"dir"`
}

type LoggingConfig struct {
	Level  string `yaml:"level"`
	Format string `yaml:"format"`
}

func DefaultConfig() *Config {
	return &Config{
		Server: ServerConfig{Port: 8090},
		Upstream: UpstreamConfig{
			BaseURL:        "http://127.0.0.1:8081",
			ConnectTimeout: 2 * time.Second,
			ReadTimeout:    60 * time.Second,
			MaxBodyBytes:   57671680,
		},
		Health: HealthConfig{
			ProbeInterval:    10 * time.Second,
			FailureThreshold: 3,
		},
		Static: StaticConfig{Dir: "./static"},
		Logging: LoggingConfig{
			Level:  "info",
			Format: "text",
		},
	}
}

func Load(path string) (*Config, error) {
	cfg := DefaultConfig()
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return cfg, nil
		}
		return nil, err
	}
	if err := yaml.Unmarshal(data, cfg); err != nil {
		return nil, err
	}
	return cfg, nil
}