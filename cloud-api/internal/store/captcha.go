package store

import (
	"context"
	"errors"
	"math"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// captchaWindowDuration mirrors the fixed one-hour window interval used by the
// captcha_rate_limits SQL so Retry-After values stay consistent with it.
const captchaWindowDuration = time.Hour

// updateWindowedRateLimit increments a fixed one-hour window bucket and
// returns the bucket's committed window start. The table and key-type names
// are compile-time constants, never caller input. Exceeding the maximum
// returns domain.ErrRateLimited (as domain.RateLimitedError when reached
// through chargeCaptchaWindow), which aborts the surrounding transaction and
// therefore does not persist the excess count for the legacy in-transaction
// callers.
func updateWindowedRateLimit(ctx context.Context, t pgx.Tx, table, keyType string, keyHash []byte, maximum int, now time.Time) (time.Time, error) {
	var count int
	var windowStart time.Time
	err := t.QueryRow(ctx, `INSERT INTO `+table+`(key_type,key_hash,window_started_at,request_count,updated_at)
		VALUES($1,$2,$3,1,$3)
		ON CONFLICT(key_type,key_hash) DO UPDATE SET
			window_started_at=CASE WHEN `+table+`.window_started_at <= EXCLUDED.window_started_at-interval '1 hour' THEN EXCLUDED.window_started_at ELSE `+table+`.window_started_at END,
			request_count=CASE WHEN `+table+`.window_started_at <= EXCLUDED.window_started_at-interval '1 hour' THEN 1 ELSE `+table+`.request_count+1 END,
			updated_at=EXCLUDED.updated_at
		RETURNING request_count,window_started_at`, keyType, keyHash, now.UTC()).Scan(&count, &windowStart)
	if err != nil {
		return time.Time{}, storeErr(err)
	}
	if count > maximum {
		return windowStart, domain.ErrRateLimited
	}
	return windowStart, nil
}

// captchaWindowRetryAfter converts a fixed-window start into the whole
// seconds a caller should wait before the window can admit another request.
// It is clamped to at least one second so Retry-After is always actionable.
func captchaWindowRetryAfter(windowStart, now time.Time) int {
	remaining := windowStart.Add(captchaWindowDuration).Sub(now)
	if remaining < time.Second {
		return 1
	}
	return int(math.Ceil(remaining.Seconds()))
}

// chargeCaptchaWindow commits exactly one increment of a per trusted client
// IP fixed window in its own transaction. Because the charge commits before
// any registration work starts, it survives later transaction rollbacks
// (including identity conflicts) and concurrent requests cannot bypass the
// limit: the INSERT ... ON CONFLICT increment is atomic per bucket row.
func (p *Postgres) chargeCaptchaWindow(ctx context.Context, keyType string, keyHash []byte, maximum int, now time.Time) error {
	now = now.UTC()
	if len(keyHash) == 0 || now.IsZero() {
		return domain.ErrInvalid
	}
	var windowStart time.Time
	err := p.tx(ctx, func(t pgx.Tx) error {
		if err := cleanupWindowedRateLimits(ctx, t, "captcha_rate_limits", now); err != nil {
			return err
		}
		var err error
		windowStart, err = updateWindowedRateLimit(ctx, t, "captcha_rate_limits", keyType, keyHash, maximum, now)
		return err
	})
	if errors.Is(err, domain.ErrRateLimited) {
		return domain.RateLimitedError{RetryAfterSeconds: captchaWindowRetryAfter(windowStart, now)}
	}
	return err
}

// ChargeCaptchaIssueWindow charges the per trusted client IP issue window.
func (p *Postgres) ChargeCaptchaIssueWindow(ctx context.Context, keyHash []byte, now time.Time) error {
	return p.chargeCaptchaWindow(ctx, "issue", keyHash, auth.CaptchaIssueIPPerHour, now)
}

// ChargeCaptchaRegisterWindow charges the per trusted client IP registration
// window for every validly formatted register request, independently of the
// registration outcome.
func (p *Postgres) ChargeCaptchaRegisterWindow(ctx context.Context, keyHash []byte, now time.Time) error {
	return p.chargeCaptchaWindow(ctx, "register", keyHash, auth.CaptchaRegisterIPPerHour, now)
}

func updateRegistrationRateLimit(ctx context.Context, t pgx.Tx, keyType string, keyHash []byte, maximum int, now time.Time) error {
	_, err := updateWindowedRateLimit(ctx, t, "email_verification_rate_limits", keyType, keyHash, maximum, now)
	if errors.Is(err, domain.ErrRateLimited) {
		return domain.ErrRegistrationVerificationFailed
	}
	return err
}

// cleanupWindowedRateLimits performs bounded, idempotent cleanup of expired
// fixed-window buckets before any bucket update in the same transaction.
func cleanupWindowedRateLimits(ctx context.Context, t pgx.Tx, table string, now time.Time) error {
	_, err := t.Exec(ctx, `DELETE FROM `+table+` WHERE (key_type,key_hash) IN (SELECT key_type,key_hash FROM `+table+` WHERE window_started_at <= $1::timestamptz - interval '1 hour' ORDER BY window_started_at LIMIT 100)`, now.UTC())
	return storeErr(err)
}

// cleanupExpiredRegistrationCaptchas bounds table growth through the same
// opportunistic maintenance pattern used by the legacy verification flow.
func cleanupExpiredRegistrationCaptchas(ctx context.Context, t pgx.Tx, now time.Time) error {
	_, err := t.Exec(ctx, `DELETE FROM registration_captchas WHERE id IN (SELECT id FROM registration_captchas WHERE expires_at <= $1::timestamptz ORDER BY expires_at,id LIMIT 100)`, now.UTC())
	return storeErr(err)
}

// CreateRegistrationCaptcha persists one challenge as its salted answer hash.
// The per-IP issue window is charged separately and committed by
// ChargeCaptchaIssueWindow before this call.
func (p *Postgres) CreateRegistrationCaptcha(ctx context.Context, x domain.CreateRegistrationCaptchaParams) (domain.RegistrationCaptcha, error) {
	now := x.Now.UTC()
	if len(x.AnswerHash) != 32 || len(x.AnswerSalt) < 16 || len(x.AnswerSalt) > 64 || now.IsZero() || !x.ExpiresAt.After(now) || x.ExpiresAt.After(now.Add(auth.CaptchaTTL)) {
		return domain.RegistrationCaptcha{}, domain.ErrInvalid
	}
	var captcha domain.RegistrationCaptcha
	err := p.tx(ctx, func(t pgx.Tx) error {
		if err := cleanupExpiredRegistrationCaptchas(ctx, t, now); err != nil {
			return err
		}
		return storeErr(t.QueryRow(ctx, `INSERT INTO registration_captchas(answer_hash,answer_salt,expires_at,attempt_count,created_at,updated_at)
			VALUES($1,$2,$3,0,$4,$4) RETURNING id,expires_at`, x.AnswerHash, x.AnswerSalt, x.ExpiresAt.UTC(), now).Scan(&captcha.ID, &captcha.ExpiresAt))
	})
	return captcha, err
}

// ReserveRegistrationCaptcha validates one captcha answer before any
// expensive password hashing is paid for, as the gate between the committed
// rate-window charge and the Argon2id hash. Only a wrong answer mutates
// state (the attempt increment, with exhaustion consuming the row), so a
// correct answer costs nothing and identity conflicts keep the captcha fully
// usable; consumption stays tied to the committed registration in
// RegisterWithCaptcha. Expiry and attempt exhaustion remain terminal.
func (p *Postgres) ReserveRegistrationCaptcha(ctx context.Context, x domain.ReserveRegistrationCaptchaParams) error {
	answer := strings.TrimSpace(x.CaptchaAnswer)
	now := x.Now.UTC()
	if x.CaptchaID == uuid.Nil || answer == "" || len(answer) > auth.CaptchaAnswerMaxBytes || !utf8.ValidString(answer) || len(x.AnswerPepper) == 0 || now.IsZero() {
		return domain.ErrInvalid
	}
	failed := false
	err := p.tx(ctx, func(t pgx.Tx) error {
		var answerHash, answerSalt []byte
		var expiresAt time.Time
		var attempts int
		err := t.QueryRow(ctx, `SELECT answer_hash,answer_salt,expires_at,attempt_count FROM registration_captchas WHERE id=$1 FOR UPDATE`, x.CaptchaID).Scan(&answerHash, &answerSalt, &expiresAt, &attempts)
		if errors.Is(err, pgx.ErrNoRows) {
			failed = true
			return nil
		}
		if err != nil {
			return storeErr(err)
		}
		if !expiresAt.After(now) || attempts >= auth.CaptchaMaxAttempts {
			if _, err := t.Exec(ctx, `DELETE FROM registration_captchas WHERE id=$1`, x.CaptchaID); err != nil {
				return storeErr(err)
			}
			failed = true
			return nil
		}
		if !auth.CaptchaAnswerMatches(x.AnswerPepper, answerSalt, answerHash, answer) {
			// A wrong answer burns one of the challenge's bounded attempts even
			// though the request never reaches password hashing; a correct
			// answer mutates nothing so retries and conflicts stay free.
			if err := t.QueryRow(ctx, `UPDATE registration_captchas SET attempt_count=attempt_count+1,updated_at=$2 WHERE id=$1 RETURNING attempt_count`, x.CaptchaID, now).Scan(&attempts); err != nil {
				return storeErr(err)
			}
			if attempts >= auth.CaptchaMaxAttempts {
				if _, err := t.Exec(ctx, `DELETE FROM registration_captchas WHERE id=$1`, x.CaptchaID); err != nil {
					return storeErr(err)
				}
			}
			failed = true
		}
		return nil
	})
	if err != nil {
		return err
	}
	if failed {
		return domain.ErrCaptchaFailed
	}
	return nil
}

// RegisterWithCaptcha re-verifies and consumes one captcha answer and
// atomically creates the formal user, password credential, and trial
// entitlement. The captcha row is consumed by a committed verification
// (success, expiry, or attempt exhaustion); a failed registration
// transaction, such as a username or email conflict, rolls the consumption
// back so the same challenge stays verifiable. The per trusted client IP
// registration window is charged independently through
// ChargeCaptchaRegisterWindow and is not part of this transaction.
func (p *Postgres) RegisterWithCaptcha(ctx context.Context, x domain.RegisterWithCaptchaParams) (domain.User, domain.Entitlement, error) {
	var user domain.User
	var trial domain.Entitlement
	username, err := domain.ParseUsername(x.Username)
	if err != nil {
		return user, trial, err
	}
	email, err := domain.ParseEmail(x.Email)
	if err != nil {
		return user, trial, err
	}
	answer := strings.TrimSpace(x.CaptchaAnswer)
	now := x.Now.UTC()
	if x.PasswordHash == "" || x.CaptchaID == uuid.Nil || answer == "" || len(answer) > auth.CaptchaAnswerMaxBytes || !utf8.ValidString(answer) || len(x.AnswerPepper) == 0 || now.IsZero() {
		return user, trial, domain.ErrInvalid
	}
	failed := false
	err = p.tx(ctx, func(t pgx.Tx) error {
		var answerHash, answerSalt []byte
		var expiresAt time.Time
		var attempts int
		err := t.QueryRow(ctx, `SELECT answer_hash,answer_salt,expires_at,attempt_count FROM registration_captchas WHERE id=$1 FOR UPDATE`, x.CaptchaID).Scan(&answerHash, &answerSalt, &expiresAt, &attempts)
		if errors.Is(err, pgx.ErrNoRows) {
			failed = true
			return nil
		}
		if err != nil {
			return storeErr(err)
		}
		if !expiresAt.After(now) || attempts >= auth.CaptchaMaxAttempts {
			if _, err := t.Exec(ctx, `DELETE FROM registration_captchas WHERE id=$1`, x.CaptchaID); err != nil {
				return storeErr(err)
			}
			failed = true
			return nil
		}
		if err := t.QueryRow(ctx, `UPDATE registration_captchas SET attempt_count=attempt_count+1,updated_at=$2 WHERE id=$1 RETURNING attempt_count`, x.CaptchaID, now).Scan(&attempts); err != nil {
			return storeErr(err)
		}
		if !auth.CaptchaAnswerMatches(x.AnswerPepper, answerSalt, answerHash, answer) {
			if attempts >= auth.CaptchaMaxAttempts {
				if _, err := t.Exec(ctx, `DELETE FROM registration_captchas WHERE id=$1`, x.CaptchaID); err != nil {
					return storeErr(err)
				}
			}
			failed = true
			return nil
		}
		if _, err := t.Exec(ctx, `DELETE FROM registration_captchas WHERE id=$1`, x.CaptchaID); err != nil {
			return storeErr(err)
		}
		user, trial, err = registerTx(ctx, t, domain.RegisterParams{Username: username.String(), Email: email.String(), PasswordHash: x.PasswordHash, Now: now})
		return err
	})
	if err != nil {
		return domain.User{}, domain.Entitlement{}, err
	}
	if failed {
		return domain.User{}, domain.Entitlement{}, domain.ErrCaptchaFailed
	}
	return user, trial, nil
}
