// Package mail provides local SMTP delivery adapters.
package mail

import (
	"context"
	"errors"
	"fmt"
	"net"
	stdmail "net/mail"
	"net/smtp"
	"strconv"
	"time"
)

// SMTPConfig describes the loopback-only SMTP submission endpoint.
type SMTPConfig struct {
	Host           string
	Port           int
	From           string
	ConnectTimeout time.Duration
	SendTimeout    time.Duration
}

// SMTPTransport submits a fully rendered message. It permits sender tests to
// exercise delivery behavior without opening a network connection.
type SMTPTransport interface {
	Send(context.Context, SMTPConfig, string, string, []byte) error
}

// SMTPRegistrationCodeSender delivers the fixed registration verification
// message through a loopback-only SMTP transport.
type SMTPRegistrationCodeSender struct {
	config    SMTPConfig
	transport SMTPTransport
}

func NewSMTPRegistrationCodeSender(config SMTPConfig, transport SMTPTransport) (*SMTPRegistrationCodeSender, error) {
	if err := validateConfig(config); err != nil {
		return nil, err
	}
	if transport == nil {
		transport = networkSMTPTransport{}
	}
	return &SMTPRegistrationCodeSender{config: config, transport: transport}, nil
}

func (s *SMTPRegistrationCodeSender) SendRegistrationCode(ctx context.Context, email, code string, _ time.Time) error {
	if s == nil {
		return errors.New("SMTP registration sender is not initialized")
	}
	recipient, err := parseMailbox(email)
	if err != nil {
		return err
	}
	if !isSixDigitCode(code) {
		return errors.New("registration verification code must be six ASCII digits")
	}
	message := registrationCodeMessage(s.config.From, recipient, code)
	sendContext, cancel := context.WithTimeout(ctx, s.config.SendTimeout)
	defer cancel()
	if err := s.transport.Send(sendContext, s.config, s.config.From, recipient, message); err != nil {
		return fmt.Errorf("submit registration verification email: %w", err)
	}
	return nil
}

func validateConfig(config SMTPConfig) error {
	if config.Host != "localhost" && config.Host != "127.0.0.1" {
		return errors.New("SMTP host must be localhost or 127.0.0.1")
	}
	if config.Port < 1 || config.Port > 65535 {
		return errors.New("SMTP port must be between 1 and 65535")
	}
	if _, err := parseMailbox(config.From); err != nil {
		return errors.New("SMTP sender address is invalid")
	}
	if config.ConnectTimeout <= 0 {
		return errors.New("SMTP connect timeout must be positive")
	}
	if config.SendTimeout <= 0 {
		return errors.New("SMTP send timeout must be positive")
	}
	return nil
}

func parseMailbox(value string) (string, error) {
	parsed, err := stdmail.ParseAddress(value)
	if err != nil || parsed.Address != value {
		return "", errors.New("email address is invalid")
	}
	return parsed.Address, nil
}

func isSixDigitCode(code string) bool {
	if len(code) != 6 {
		return false
	}
	for i := range code {
		if code[i] < '0' || code[i] > '9' {
			return false
		}
	}
	return true
}

func registrationCodeMessage(from, to, code string) []byte {
	return []byte("From: " + from + "\r\n" +
		"To: " + to + "\r\n" +
		"Subject: Verba 邮箱验证码\r\n" +
		"MIME-Version: 1.0\r\n" +
		"Content-Type: text/plain; charset=utf-8\r\n" +
		"\r\n" +
		"Verba 邮箱验证码：" + code + "\r\n" +
		"该验证码 10 分钟内有效。\r\n")
}

type networkSMTPTransport struct{}

func (networkSMTPTransport) Send(ctx context.Context, config SMTPConfig, from, to string, message []byte) error {
	connectContext, cancel := context.WithTimeout(ctx, config.ConnectTimeout)
	defer cancel()
	connection, err := (&net.Dialer{}).DialContext(connectContext, "tcp", net.JoinHostPort(config.Host, strconv.Itoa(config.Port)))
	if err != nil {
		return err
	}
	defer connection.Close()
	if deadline, ok := ctx.Deadline(); ok {
		if err := connection.SetDeadline(deadline); err != nil {
			return err
		}
	}
	client, err := smtp.NewClient(connection, config.Host)
	if err != nil {
		return err
	}
	defer client.Quit()
	if err := client.Mail(from); err != nil {
		return err
	}
	if err := client.Rcpt(to); err != nil {
		return err
	}
	writer, err := client.Data()
	if err != nil {
		return err
	}
	if _, err := writer.Write(message); err != nil {
		return err
	}
	return writer.Close()
}

var _ interface {
	SendRegistrationCode(context.Context, string, string, time.Time) error
} = (*SMTPRegistrationCodeSender)(nil)
