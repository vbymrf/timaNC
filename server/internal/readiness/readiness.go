package readiness

import (
	"bufio"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"
)

type Check func(context.Context) error

type Result struct {
	Name  string `json:"name"`
	Ready bool   `json:"ready"`
	Error string `json:"error,omitempty"`
}

type Checker struct {
	timeout time.Duration
	checks  map[string]Check
}

func New(timeout time.Duration, checks map[string]Check) *Checker {
	return &Checker{timeout: timeout, checks: checks}
}

func (c *Checker) Check(ctx context.Context) (bool, []Result) {
	results := make([]Result, 0, len(c.checks))
	resultCh := make(chan Result, len(c.checks))
	var wg sync.WaitGroup

	for name, check := range c.checks {
		wg.Add(1)
		go func() {
			defer wg.Done()
			checkCtx, cancel := context.WithTimeout(ctx, c.timeout)
			defer cancel()
			err := check(checkCtx)
			result := Result{Name: name, Ready: err == nil}
			if err != nil {
				result.Error = err.Error()
			}
			resultCh <- result
		}()
	}
	wg.Wait()
	close(resultCh)

	ready := true
	for result := range resultCh {
		results = append(results, result)
		ready = ready && result.Ready
	}
	sort.Slice(results, func(i, j int) bool { return results[i].Name < results[j].Name })
	return ready, results
}

type Pinger interface {
	Ping(context.Context) error
}

func PostgreSQL(pinger Pinger) Check {
	return pinger.Ping
}

func Redis(rawURL string) (Check, error) {
	u, err := url.Parse(rawURL)
	if err != nil || (u.Scheme != "redis" && u.Scheme != "rediss") || u.Host == "" {
		return nil, errors.New("invalid redis URL")
	}
	address := u.Host
	if !strings.Contains(address, ":") {
		address += ":6379"
	}

	return func(ctx context.Context) error {
		dialer := net.Dialer{}
		var conn net.Conn
		if u.Scheme == "rediss" {
			tlsDialer := tls.Dialer{
				NetDialer: &dialer,
				Config: &tls.Config{
					MinVersion: tls.VersionTLS12,
					ServerName: u.Hostname(),
				},
			}
			conn, err = tlsDialer.DialContext(ctx, "tcp", address)
		} else {
			conn, err = dialer.DialContext(ctx, "tcp", address)
		}
		if err != nil {
			return err
		}
		defer conn.Close()
		if deadline, ok := ctx.Deadline(); ok {
			if err := conn.SetDeadline(deadline); err != nil {
				return err
			}
		}

		password, hasPassword := u.User.Password()
		if hasPassword {
			args := []string{"AUTH"}
			if username := u.User.Username(); username != "" {
				args = append(args, username)
			}
			args = append(args, password)
			if err := redisCommand(conn, args...); err != nil {
				return fmt.Errorf("auth: %w", err)
			}
		}
		return redisCommand(conn, "PING")
	}, nil
}

func redisCommand(conn net.Conn, args ...string) error {
	var command strings.Builder
	fmt.Fprintf(&command, "*%d\r\n", len(args))
	for _, arg := range args {
		fmt.Fprintf(&command, "$%d\r\n%s\r\n", len(arg), arg)
	}
	if _, err := io.WriteString(conn, command.String()); err != nil {
		return err
	}
	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil {
		return err
	}
	if strings.HasPrefix(line, "-") {
		return errors.New(strings.TrimSpace(strings.TrimPrefix(line, "-")))
	}
	if !strings.HasPrefix(line, "+") {
		return fmt.Errorf("unexpected response %q", strings.TrimSpace(line))
	}
	return nil
}

func MinIO(client *http.Client, endpoint string) (Check, error) {
	u, err := url.Parse(endpoint)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, errors.New("invalid MinIO endpoint")
	}
	u.Path = strings.TrimRight(u.Path, "/") + "/minio/health/ready"
	return func(ctx context.Context) error {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
		if err != nil {
			return err
		}
		resp, err := client.Do(req)
		if err != nil {
			return err
		}
		defer resp.Body.Close()
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			return fmt.Errorf("status %d", resp.StatusCode)
		}
		return nil
	}, nil
}
