package store

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"strings"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

func storeErr(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.ErrNotFound
	}
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23505" {
		return domain.ErrConflict
	}
	return err
}
func (p *Postgres) tx(ctx context.Context, f func(pgx.Tx) error) error {
	if p == nil || p.pool == nil {
		return errors.New("postgres pool is not initialized")
	}
	t, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = t.Rollback(ctx) }()
	if err = f(t); err != nil {
		return err
	}
	return t.Commit(ctx)
}
func scanUser(row pgx.Row) (domain.User, error) {
	var u domain.User
	err := row.Scan(&u.ID, &u.Username, &u.Phone, &u.Email, &u.Role, &u.CreatedAt)
	u.Email = publicEmail(u.Email)
	return u, storeErr(err)
}

func publicEmail(email string) string {
	if strings.HasPrefix(email, "phone-") && strings.HasSuffix(email, "@reserved.invalid") {
		return ""
	}
	return email
}
func scanEnt(row pgx.Row) (domain.Entitlement, error) {
	var e domain.Entitlement
	err := row.Scan(&e.ID, &e.UserID, &e.Kind, &e.StartsAt, &e.ExpiresAt)
	return e, storeErr(err)
}

func registerTx(ctx context.Context, t pgx.Tx, x domain.RegisterParams) (domain.User, domain.Entitlement, error) {
	var u domain.User
	var e domain.Entitlement
	err := t.QueryRow(ctx, `INSERT INTO users(email,username,phone,password_hash) VALUES($1,NULLIF($2,''),NULLIF($3,''),$4) RETURNING id,COALESCE(username,''),COALESCE(phone,''),email,role,created_at`, x.Email, x.Username, x.Phone, x.PasswordHash).Scan(&u.ID, &u.Username, &u.Phone, &u.Email, &u.Role, &u.CreatedAt)
	if err != nil {
		return u, e, storeErr(err)
	}
	err = t.QueryRow(ctx, `INSERT INTO entitlements(user_id,kind,starts_at,expires_at) VALUES($1,'trial',$2::timestamptz,$2::timestamptz+interval '3 days') RETURNING id,user_id,kind,starts_at,expires_at`, u.ID, x.Now.UTC()).Scan(&e.ID, &e.UserID, &e.Kind, &e.StartsAt, &e.ExpiresAt)
	return u, e, storeErr(err)
}

func (p *Postgres) Register(ctx context.Context, x domain.RegisterParams) (domain.User, domain.Entitlement, error) {
	var u domain.User
	var e domain.Entitlement
	err := p.tx(ctx, func(t pgx.Tx) error {
		var err error
		u, e, err = registerTx(ctx, t, x)
		return err
	})
	return u, e, err
}

func scanRegistrationVerification(row pgx.Row) (domain.RegistrationVerification, error) {
	var verification domain.RegistrationVerification
	err := row.Scan(&verification.ID, &verification.Username, &verification.Email, &verification.PasswordHash, &verification.CodeHash, &verification.CodeSalt, &verification.ExpiresAt, &verification.AttemptCount, &verification.SentAt, &verification.CreatedAt, &verification.UpdatedAt)
	return verification, storeErr(err)
}

func updateRegistrationRateLimit(ctx context.Context, t pgx.Tx, keyType string, keyHash []byte, maximum int, now time.Time) error {
	var count int
	err := t.QueryRow(ctx, `INSERT INTO email_verification_rate_limits(key_type,key_hash,window_started_at,request_count,updated_at)
		VALUES($1,$2,$3,1,$3)
		ON CONFLICT(key_type,key_hash) DO UPDATE SET
			window_started_at=CASE WHEN email_verification_rate_limits.window_started_at <= EXCLUDED.window_started_at-interval '1 hour' THEN EXCLUDED.window_started_at ELSE email_verification_rate_limits.window_started_at END,
			request_count=CASE WHEN email_verification_rate_limits.window_started_at <= EXCLUDED.window_started_at-interval '1 hour' THEN 1 ELSE email_verification_rate_limits.request_count+1 END,
			updated_at=EXCLUDED.updated_at
		RETURNING request_count`, keyType, keyHash, now.UTC()).Scan(&count)
	if err != nil {
		return storeErr(err)
	}
	if count > maximum {
		return domain.ErrRegistrationVerificationFailed
	}
	return nil
}

func (p *Postgres) RequestRegistrationVerification(ctx context.Context, x domain.CreateRegistrationVerificationParams) (domain.RegistrationVerification, error) {
	var verification domain.RegistrationVerification
	now := x.Now.UTC()
	if x.Username == "" || x.Email == "" || x.PasswordHash == "" || len(x.CodeHash) == 0 || len(x.CodeSalt) == 0 || len(x.EmailRateLimitKey) == 0 || len(x.IPRateLimitKey) == 0 || now.IsZero() || !x.ExpiresAt.After(now) {
		return verification, domain.ErrInvalid
	}
	err := p.tx(ctx, func(t pgx.Tx) error {
		// The advisory lock covers the no-row case, where SELECT FOR UPDATE alone
		// cannot serialize concurrent first requests for the same email.
		if _, err := t.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, x.Email); err != nil {
			return storeErr(err)
		}
		if err := updateRegistrationRateLimit(ctx, t, "email", x.EmailRateLimitKey, 5, now); err != nil {
			return err
		}
		if err := updateRegistrationRateLimit(ctx, t, "ip", x.IPRateLimitKey, 20, now); err != nil {
			return err
		}
		var sentAt time.Time
		err := t.QueryRow(ctx, `SELECT sent_at FROM registration_verifications WHERE email=$1 FOR UPDATE`, x.Email).Scan(&sentAt)
		if err == nil && sentAt.Add(time.Minute).After(now) {
			return domain.ErrRegistrationVerificationFailed
		}
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return storeErr(err)
		}
		var exists bool
		if err := t.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM users WHERE (email=$1 OR username=$2) AND disabled_at IS NULL)`, x.Email, x.Username).Scan(&exists); err != nil {
			return storeErr(err)
		}
		if exists {
			return domain.ErrConflict
		}
		created, err := scanRegistrationVerification(t.QueryRow(ctx, `INSERT INTO registration_verifications(username,email,password_hash,code_hash,code_salt,expires_at,attempt_count,sent_at,created_at,updated_at)
			VALUES($1,$2,$3,$4,$5,$6,0,$7,$7,$7)
			ON CONFLICT(email) DO UPDATE SET username=EXCLUDED.username,password_hash=EXCLUDED.password_hash,code_hash=EXCLUDED.code_hash,code_salt=EXCLUDED.code_salt,expires_at=EXCLUDED.expires_at,attempt_count=0,sent_at=EXCLUDED.sent_at,updated_at=EXCLUDED.updated_at
			RETURNING id,username,email,password_hash,code_hash,code_salt,expires_at,attempt_count,sent_at,created_at,updated_at`, x.Username, x.Email, x.PasswordHash, x.CodeHash, x.CodeSalt, x.ExpiresAt.UTC(), now))
		verification = created
		return err
	})
	return verification, err
}

func verificationCodeMatches(pepper, salt, expected []byte, code string) bool {
	if len(pepper) == 0 || len(salt) == 0 || len(expected) == 0 {
		return false
	}
	mac := hmac.New(sha256.New, pepper)
	_, _ = mac.Write(salt)
	_, _ = mac.Write([]byte(code))
	return subtle.ConstantTimeCompare(expected, mac.Sum(nil)) == 1
}

func (p *Postgres) ConfirmRegistrationVerification(ctx context.Context, x domain.ConfirmRegistrationVerificationParams) (domain.RegisterParams, error) {
	var registration domain.RegisterParams
	now := x.Now.UTC()
	if x.Email == "" || x.Code == "" || len(x.CodePepper) == 0 || now.IsZero() {
		return registration, domain.ErrRegistrationVerificationFailed
	}
	failed := false
	err := p.tx(ctx, func(t pgx.Tx) error {
		verification, err := scanRegistrationVerification(t.QueryRow(ctx, `SELECT id,username,email,password_hash,code_hash,code_salt,expires_at,attempt_count,sent_at,created_at,updated_at FROM registration_verifications WHERE email=$1 FOR UPDATE`, x.Email))
		if err != nil {
			failed = true
			return nil
		}
		if !verification.ExpiresAt.After(now) || verification.AttemptCount >= 5 {
			if _, err := t.Exec(ctx, `DELETE FROM registration_verifications WHERE id=$1`, verification.ID); err != nil {
				return storeErr(err)
			}
			failed = true
			return nil
		}
		if err := t.QueryRow(ctx, `UPDATE registration_verifications SET attempt_count=attempt_count+1,updated_at=$2 WHERE id=$1 RETURNING attempt_count`, verification.ID, now).Scan(&verification.AttemptCount); err != nil {
			return storeErr(err)
		}
		if !verificationCodeMatches(x.CodePepper, verification.CodeSalt, verification.CodeHash, x.Code) {
			if verification.AttemptCount >= 5 {
				if _, err := t.Exec(ctx, `DELETE FROM registration_verifications WHERE id=$1`, verification.ID); err != nil {
					return storeErr(err)
				}
			}
			failed = true
			return nil
		}
		registration = domain.RegisterParams{Username: verification.Username, Email: verification.Email, PasswordHash: verification.PasswordHash, Now: now}
		if _, _, err := registerTx(ctx, t, registration); err != nil {
			return err
		}
		if _, err := t.Exec(ctx, `DELETE FROM registration_verifications WHERE id=$1`, verification.ID); err != nil {
			return storeErr(err)
		}
		return nil
	})
	if err != nil {
		return domain.RegisterParams{}, err
	}
	if failed {
		return domain.RegisterParams{}, domain.ErrRegistrationVerificationFailed
	}
	return registration, nil
}

func (p *Postgres) InvalidateRegistrationVerification(ctx context.Context, email string, now time.Time) error {
	if email == "" || now.IsZero() {
		return domain.ErrInvalid
	}
	_, err := p.pool.Exec(ctx, `DELETE FROM registration_verifications WHERE email=$1`, email)
	return storeErr(err)
}
func (p *Postgres) UserByEmail(ctx context.Context, email string) (domain.User, string, error) {
	var u domain.User
	var hash string
	err := p.pool.QueryRow(ctx, `SELECT id,COALESCE(username,''),COALESCE(phone,''),email,role,created_at,password_hash FROM users WHERE email=$1 AND disabled_at IS NULL`, email).Scan(&u.ID, &u.Username, &u.Phone, &u.Email, &u.Role, &u.CreatedAt, &hash)
	u.Email = publicEmail(u.Email)
	return u, hash, storeErr(err)
}
func (p *Postgres) UserByPhone(ctx context.Context, phone string) (domain.User, string, error) {
	var u domain.User
	var hash string
	err := p.pool.QueryRow(ctx, `SELECT id,COALESCE(username,''),phone,role,created_at,password_hash FROM users WHERE phone=$1 AND disabled_at IS NULL`, phone).Scan(&u.ID, &u.Username, &u.Phone, &u.Role, &u.CreatedAt, &hash)
	return u, hash, storeErr(err)
}
func (p *Postgres) UserByUsername(ctx context.Context, username string) (domain.User, string, error) {
	var u domain.User
	var hash string
	err := p.pool.QueryRow(ctx, `SELECT id,username,COALESCE(phone,''),email,role,created_at,password_hash FROM users WHERE username=$1 AND disabled_at IS NULL`, username).Scan(&u.ID, &u.Username, &u.Phone, &u.Email, &u.Role, &u.CreatedAt, &hash)
	u.Email = publicEmail(u.Email)
	return u, hash, storeErr(err)
}
func (p *Postgres) UserByID(ctx context.Context, id uuid.UUID) (domain.User, error) {
	return scanUser(p.pool.QueryRow(ctx, `SELECT id,COALESCE(username,''),COALESCE(phone,''),email,role,created_at FROM users WHERE id=$1`, id))
}
func (p *Postgres) ActiveEntitlement(ctx context.Context, id uuid.UUID, now time.Time) (domain.Entitlement, error) {
	return scanEnt(p.pool.QueryRow(ctx, `SELECT id,user_id,kind,starts_at,expires_at FROM entitlements WHERE user_id=$1 AND revoked_at IS NULL AND starts_at<=$2 AND expires_at>$2 ORDER BY expires_at DESC,id DESC LIMIT 1`, id, now.UTC()))
}
func (p *Postgres) CreateRefreshToken(ctx context.Context, x domain.CreateRefreshParams) (domain.RefreshToken, error) {
	var r domain.RefreshToken
	err := p.pool.QueryRow(ctx, `INSERT INTO refresh_tokens(user_id,family_id,token_hash,expires_at) VALUES($1,$2,$3,$4) RETURNING id,user_id,family_id,token_hash,expires_at,revoked_at,replaced_by_id`, x.UserID, x.FamilyID, x.Hash, x.ExpiresAt.UTC()).Scan(&r.ID, &r.UserID, &r.FamilyID, &r.TokenHash, &r.ExpiresAt, &r.RevokedAt, &r.ReplacedByID)
	return r, storeErr(err)
}
func (p *Postgres) RotateRefreshToken(ctx context.Context, oldHash, newHash []byte, now, expires time.Time) (domain.RefreshToken, domain.RefreshToken, error) {
	var old, next domain.RefreshToken
	replayed := false
	err := p.tx(ctx, func(t pgx.Tx) error {
		err := t.QueryRow(ctx, `SELECT id,user_id,family_id,token_hash,expires_at,revoked_at,replaced_by_id FROM refresh_tokens WHERE token_hash=$1 FOR UPDATE`, oldHash).Scan(&old.ID, &old.UserID, &old.FamilyID, &old.TokenHash, &old.ExpiresAt, &old.RevokedAt, &old.ReplacedByID)
		if err != nil {
			return storeErr(err)
		}
		if old.RevokedAt != nil || !old.ExpiresAt.After(now) {
			if _, err := t.Exec(ctx, `UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,$2) WHERE family_id=$1`, old.FamilyID, now.UTC()); err != nil {
				return err
			}
			replayed = true
			return nil // commit revocation; return the denial after commit.
		}
		err = t.QueryRow(ctx, `INSERT INTO refresh_tokens(user_id,family_id,token_hash,expires_at) VALUES($1,$2,$3,$4) RETURNING id,user_id,family_id,token_hash,expires_at,revoked_at,replaced_by_id`, old.UserID, old.FamilyID, newHash, expires.UTC()).Scan(&next.ID, &next.UserID, &next.FamilyID, &next.TokenHash, &next.ExpiresAt, &next.RevokedAt, &next.ReplacedByID)
		if err != nil {
			return storeErr(err)
		}
		_, err = t.Exec(ctx, `UPDATE refresh_tokens SET revoked_at=$2,replaced_by_id=$3 WHERE id=$1`, old.ID, now.UTC(), next.ID)
		old.RevokedAt = &now
		old.ReplacedByID = &next.ID
		return storeErr(err)
	})
	if err == nil && replayed {
		return old, domain.RefreshToken{}, domain.ErrUnauthorized
	}
	return old, next, err
}
func (p *Postgres) RevokeRefreshToken(ctx context.Context, hash []byte, now time.Time) error {
	return p.tx(ctx, func(t pgx.Tx) error {
		var family uuid.UUID
		err := t.QueryRow(ctx, `SELECT family_id FROM refresh_tokens WHERE token_hash=$1 FOR UPDATE`, hash).Scan(&family)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		_, err = t.Exec(ctx, `UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,$2) WHERE family_id=$1`, family, now.UTC())
		return err
	})
}
func (p *Postgres) RedeemCode(ctx context.Context, user uuid.UUID, hash []byte, now time.Time) (domain.Entitlement, error) {
	var e domain.Entitlement
	err := p.tx(ctx, func(t pgx.Tx) error {
		var batch uuid.UUID
		var days int
		err := t.QueryRow(ctx, `UPDATE redemption_codes SET redeemed_by=$2,redeemed_at=GREATEST($3::timestamptz,created_at) WHERE code_hash=$1 AND redeemed_at IS NULL RETURNING batch_id`, hash, user, now.UTC()).Scan(&batch)
		if errors.Is(err, pgx.ErrNoRows) {
			return domain.ErrConflict
		}
		if err != nil {
			return err
		}
		if err = t.QueryRow(ctx, `SELECT duration_days FROM code_batches WHERE id=$1`, batch).Scan(&days); err != nil {
			return err
		}
		err = t.QueryRow(ctx, `WITH locked AS (SELECT id FROM users WHERE id=$1 FOR UPDATE), start AS (SELECT GREATEST($2::timestamptz,COALESCE((SELECT max(expires_at) FROM entitlements WHERE user_id=$1 AND revoked_at IS NULL),$2::timestamptz)) AS at) INSERT INTO entitlements(user_id,kind,starts_at,expires_at) SELECT $1,'package',at,at+interval '365 days' FROM start RETURNING id,user_id,kind,starts_at,expires_at`, user, now.UTC()).Scan(&e.ID, &e.UserID, &e.Kind, &e.StartsAt, &e.ExpiresAt)
		return storeErr(err)
	})
	return e, err
}
func (p *Postgres) CreateSession(ctx context.Context, s domain.TranslationSession, now time.Time) error {
	return p.tx(ctx, func(t pgx.Tx) error {
		// Transaction-scoped advisory locking serializes the read/count/insert
		// sequence per user without imposing a global session lock.
		if _, err := t.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1::text, 0))`, s.UserID); err != nil {
			return err
		}
		var n int
		if err := t.QueryRow(ctx, `SELECT count(*) FROM translation_sessions WHERE user_id=$1 AND expires_at>$2 AND ended_at IS NULL AND revoked_at IS NULL`, s.UserID, now.UTC()).Scan(&n); err != nil {
			return err
		}
		if n > 0 {
			return domain.ErrConflict
		}
		if _, err := t.Exec(ctx, `INSERT INTO user_devices(user_id,install_id,last_seen_at) VALUES($1,$2,$3) ON CONFLICT(user_id,install_id) DO UPDATE SET last_seen_at=EXCLUDED.last_seen_at`, s.UserID, s.InstallID, now.UTC()); err != nil {
			return err
		}
		tag, err := t.Exec(ctx, `INSERT INTO translation_sessions(id,user_id,entitlement_id,install_id,jti,expires_at) SELECT $1,$2,$3,$4,$5,$6 WHERE EXISTS(SELECT 1 FROM entitlements WHERE id=$3 AND user_id=$2 AND revoked_at IS NULL AND starts_at<=$7 AND expires_at>$7)`, s.ID, s.UserID, s.EntitlementID, s.InstallID, s.JTI, s.ExpiresAt.UTC(), now.UTC())
		if err != nil {
			return storeErr(err)
		}
		if tag.RowsAffected() != 1 {
			return domain.ErrNoEntitlement
		}
		return nil
	})
}
func (p *Postgres) CreateUsageRecord(ctx context.Context, x domain.CreateUsageParams) error {
	tag, err := p.pool.Exec(ctx, `INSERT INTO usage_records(user_id,session_id,audio_seconds,characters,created_at) VALUES($1,$2,$3,$4,$5)`, x.UserID, x.SessionID, x.AudioSeconds, x.Characters, x.Now.UTC())
	if err != nil {
		return storeErr(err)
	}
	if tag.RowsAffected() != 1 {
		return domain.ErrNotFound
	}
	return nil
}
func (p *Postgres) CreateFeedbackConsent(ctx context.Context, user uuid.UUID, granted bool, now time.Time) (domain.FeedbackConsent, error) {
	var c domain.FeedbackConsent
	err := p.pool.QueryRow(ctx, `INSERT INTO feedback_consents(user_id,granted,created_at) VALUES($1,$2,$3) RETURNING id,user_id,granted,created_at`, user, granted, now.UTC()).Scan(&c.ID, &c.UserID, &c.Granted, &c.CreatedAt)
	return c, storeErr(err)
}
func (p *Postgres) CreateFeedbackArtifact(ctx context.Context, x domain.CreateArtifactParams) (domain.FeedbackArtifact, error) {
	var a domain.FeedbackArtifact
	err := p.pool.QueryRow(ctx, `INSERT INTO feedback_artifacts(user_id,consent_id,object_key,expires_at,created_at) VALUES($1,$2,$3,$4,$5) RETURNING id,user_id,consent_id,object_key,expires_at,created_at`, x.UserID, x.ConsentID, x.ObjectKey, x.ExpiresAt.UTC(), x.Now.UTC()).Scan(&a.ID, &a.UserID, &a.ConsentID, &a.ObjectKey, &a.ExpiresAt, &a.CreatedAt)
	return a, storeErr(err)
}
func (p *Postgres) FeedbackArtifact(ctx context.Context, user, id uuid.UUID) (domain.FeedbackArtifact, error) {
	var a domain.FeedbackArtifact
	err := p.pool.QueryRow(ctx, `SELECT id,user_id,consent_id,object_key,expires_at,created_at FROM feedback_artifacts WHERE id=$1 AND user_id=$2`, id, user).Scan(&a.ID, &a.UserID, &a.ConsentID, &a.ObjectKey, &a.ExpiresAt, &a.CreatedAt)
	return a, storeErr(err)
}
func (p *Postgres) ListUsers(ctx context.Context, search string, limit, offset int) ([]domain.User, error) {
	rows, err := p.query(ctx, `SELECT id,COALESCE(username,''),COALESCE(phone,''),email,role,created_at FROM users WHERE $1='' OR email ILIKE '%' || $1 || '%' ORDER BY created_at DESC,id DESC LIMIT $2 OFFSET $3`, search, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []domain.User{}
	for rows.Next() {
		u, e := scanUser(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, u)
	}
	return out, rows.Err()
}
func (p *Postgres) ListTranslationSessions(ctx context.Context, user uuid.UUID, limit, offset int) ([]domain.TranslationSession, error) {
	rows, err := p.pool.Query(ctx, `SELECT id,user_id,entitlement_id,install_id,jti,expires_at FROM translation_sessions WHERE user_id=$1 ORDER BY expires_at DESC,id DESC LIMIT $2 OFFSET $3`, user, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []domain.TranslationSession{}
	for rows.Next() {
		var v domain.TranslationSession
		if err := rows.Scan(&v.ID, &v.UserID, &v.EntitlementID, &v.InstallID, &v.JTI, &v.ExpiresAt); err != nil {
			return nil, storeErr(err)
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func (p *Postgres) ListUsageRecords(ctx context.Context, user uuid.UUID, limit, offset int) ([]domain.UsageRecord, error) {
	rows, err := p.pool.Query(ctx, `SELECT id,user_id,session_id,audio_seconds,characters,created_at FROM usage_records WHERE user_id=$1 ORDER BY created_at DESC,id DESC LIMIT $2 OFFSET $3`, user, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []domain.UsageRecord{}
	for rows.Next() {
		var v domain.UsageRecord
		if err := rows.Scan(&v.ID, &v.UserID, &v.SessionID, &v.AudioSeconds, &v.Characters, &v.CreatedAt); err != nil {
			return nil, storeErr(err)
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func (p *Postgres) AccountOverview(ctx context.Context, user uuid.UUID) (domain.AccountOverview, error) {
	accountUser, err := p.UserByID(ctx, user)
	if err != nil {
		return domain.AccountOverview{}, err
	}
	var summary domain.UsageSummary
	if err := p.pool.QueryRow(ctx, `SELECT COALESCE(sum(audio_seconds), 0), count(*), max(created_at) FROM usage_records WHERE user_id=$1`, user).Scan(&summary.AudioSeconds, &summary.SessionCount, &summary.LastUsedAt); err != nil {
		return domain.AccountOverview{}, storeErr(err)
	}
	var entitlement domain.Entitlement
	err = p.pool.QueryRow(ctx, `SELECT id,user_id,kind,starts_at,expires_at FROM entitlements WHERE user_id=$1 AND revoked_at IS NULL ORDER BY expires_at DESC,id DESC LIMIT 1`, user).Scan(&entitlement.ID, &entitlement.UserID, &entitlement.Kind, &entitlement.StartsAt, &entitlement.ExpiresAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.AccountOverview{User: accountUser, Usage: summary}, nil
	}
	if err != nil {
		return domain.AccountOverview{}, storeErr(err)
	}
	return domain.AccountOverview{User: accountUser, Entitlement: &entitlement, Usage: summary}, nil
}

func (p *Postgres) ListAccountUsage(ctx context.Context, user uuid.UUID, limit, offset int) ([]domain.AccountUsage, error) {
	rows, err := p.pool.Query(ctx, `SELECT s.id,s.created_at,s.ended_at,COALESCE(u.audio_seconds,0) FROM translation_sessions s LEFT JOIN usage_records u ON u.session_id=s.id AND u.user_id=s.user_id WHERE s.user_id=$1 ORDER BY s.created_at DESC,s.id DESC LIMIT $2 OFFSET $3`, user, limit, offset)
	if err != nil {
		return nil, storeErr(err)
	}
	defer rows.Close()
	out := []domain.AccountUsage{}
	for rows.Next() {
		var value domain.AccountUsage
		if err := rows.Scan(&value.SessionID, &value.StartedAt, &value.EndedAt, &value.DurationSeconds); err != nil {
			return nil, storeErr(err)
		}
		out = append(out, value)
	}
	return out, storeErr(rows.Err())
}

func (p *Postgres) AccountIdentity(ctx context.Context, userID uuid.UUID) (domain.AccountIdentity, error) {
	var profile domain.AccountIdentity
	var phone string
	if err := p.pool.QueryRow(ctx, `SELECT COALESCE(username,''),email,COALESCE(phone,'') FROM users WHERE id=$1 AND disabled_at IS NULL`, userID).Scan(&profile.Username, &profile.Email, &phone); err != nil {
		return domain.AccountIdentity{}, storeErr(err)
	}
	if profile.Username == "" {
		profile.Username = "旧版用户"
	}
	if len(phone) == len("+8613800138000") && strings.HasPrefix(phone, "+86") {
		profile.MaskedPhone = phone[:6] + "****" + phone[len(phone)-4:]
	}
	return profile, nil
}

func (p *Postgres) UpdateIdentity(ctx context.Context, input domain.UpdateIdentityParams) (domain.User, error) {
	var user domain.User
	err := p.tx(ctx, func(t pgx.Tx) error {
		var hash, username, phone, existingEmail string
		if err := t.QueryRow(ctx, `SELECT password_hash,COALESCE(username,''),COALESCE(phone,''),email FROM users WHERE id=$1 AND disabled_at IS NULL FOR UPDATE`, input.UserID).Scan(&hash, &username, &phone, &existingEmail); err != nil {
			return storeErr(err)
		}
		valid, err := auth.VerifyPassword(hash, input.CurrentPassword)
		if err != nil || !valid {
			return domain.ErrUnauthorized
		}
		// A legacy account is one created before both optional identity columns
		// existed. Its first completion can add username and phone but must retain
		// the pre-existing email login identity.
		email := input.Email
		if username == "" && phone == "" {
			email = existingEmail
		}
		return storeErr(t.QueryRow(ctx, `UPDATE users SET username=$2,email=$3,phone=$4 WHERE id=$1 RETURNING id,username,phone,email,role,created_at`, input.UserID, input.Username, email, input.Phone).Scan(&user.ID, &user.Username, &user.Phone, &user.Email, &user.Role, &user.CreatedAt))
	})
	user.Email = publicEmail(user.Email)
	return user, err
}

func (p *Postgres) ListDevices(ctx context.Context, user uuid.UUID) ([]domain.Device, error) {
	rows, err := p.pool.Query(ctx, `SELECT id,install_id,last_seen_at,created_at FROM user_devices WHERE user_id=$1 ORDER BY last_seen_at DESC,id DESC`, user)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []domain.Device{}
	for rows.Next() {
		var d domain.Device
		if err := rows.Scan(&d.ID, &d.InstallID, &d.LastSeenAt, &d.CreatedAt); err != nil {
			return nil, storeErr(err)
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func (p *Postgres) ListAuditLogs(ctx context.Context, limit, offset int) ([]domain.AuditLog, error) {
	rows, err := p.pool.Query(ctx, `SELECT id,admin_id,action,target_type,target_id,metadata,created_at FROM audit_logs ORDER BY created_at DESC,id DESC LIMIT $1 OFFSET $2`, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	logs := []domain.AuditLog{}
	for rows.Next() {
		var value domain.AuditLog
		if err := rows.Scan(&value.ID, &value.AdminID, &value.Action, &value.TargetType, &value.TargetID, &value.Metadata, &value.CreatedAt); err != nil {
			return nil, storeErr(err)
		}
		logs = append(logs, value)
	}
	return logs, rows.Err()
}
