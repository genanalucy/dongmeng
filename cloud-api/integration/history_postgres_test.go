//go:build integration

package integration_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/dngmeng/cloud-api/internal/historycrypto"
	"github.com/dngmeng/cloud-api/internal/migrate"
	"github.com/dngmeng/cloud-api/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// TestEncryptedHistoryPersistence exercises the owner-scoped, tombstone-final
// translation-history foundation against isolated PostgreSQL. It never logs
// key material, plaintext, or ciphertext.
func TestEncryptedHistoryPersistence(t *testing.T) {
	dsn := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: dsn, Directory: repositoryMigrationDirectory(t), Schema: "public"}); err != nil {
		t.Fatal("apply history test migrations")
	}
	db, err := store.Open(ctx, dsn)
	if err != nil {
		t.Fatal("open isolated history test database")
	}
	t.Cleanup(db.Close)
	raw, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal("open history fixture pool")
	}
	t.Cleanup(raw.Close)

	now := time.Now().UTC().Truncate(time.Microsecond)
	ownerID, otherID, sessionCappedID, turnCappedID := insertHistoryUser(t, ctx, raw, "history-owner"), insertHistoryUser(t, ctx, raw, "history-other"), insertHistoryUser(t, ctx, raw, "history-session-capped"), insertHistoryUser(t, ctx, raw, "history-turn-capped")
	t.Cleanup(func() {
		cleanupContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := raw.Exec(cleanupContext, `DELETE FROM users WHERE id = ANY($1)`, []uuid.UUID{ownerID, otherID, sessionCappedID, turnCappedID}); err != nil {
			t.Error("cleanup history fixture users")
		}
	})

	rootKey := make([]byte, historycrypto.MinRootKeyBytes)
	if _, err := rand.Read(rootKey); err != nil {
		t.Fatal("generate history root key")
	}
	cipher, err := historycrypto.NewCipher(rootKey, 1)
	if err != nil {
		t.Fatalf("construct history cipher: %v", err)
	}

	session, err := db.CreateHistorySession(ctx, domain.CreateHistorySessionParams{ID: uuid.New(), UserID: ownerID, Now: now})
	if err != nil {
		t.Fatalf("create history session: %v", err)
	}
	if session.UserID != ownerID || session.DeletedAt != nil || !session.CreatedAt.Equal(now) {
		t.Fatalf("unexpected created session: %+v", session)
	}

	t.Run("create is idempotent per UUID", func(t *testing.T) {
		retried, err := db.CreateHistorySession(ctx, domain.CreateHistorySessionParams{ID: session.ID, UserID: ownerID, Now: now.Add(time.Minute)})
		if err != nil {
			t.Fatalf("retry create: %v", err)
		}
		if retried.ID != session.ID || !retried.CreatedAt.Equal(session.CreatedAt) {
			t.Fatalf("idempotent retry changed the session: %+v", retried)
		}
	})

	t.Run("reads are owner scoped", func(t *testing.T) {
		if _, err := db.HistorySessionByID(ctx, ownerID, session.ID); err != nil {
			t.Fatalf("owner read: %v", err)
		}
		if _, err := db.HistorySessionByID(ctx, otherID, session.ID); !errors.Is(err, domain.ErrNotFound) {
			t.Fatalf("foreign read error = %v, want ErrNotFound", err)
		}
		if _, err := db.CreateHistorySession(ctx, domain.CreateHistorySessionParams{ID: session.ID, UserID: otherID, Now: now}); !errors.Is(err, domain.ErrNotFound) {
			t.Fatalf("foreign idempotent create error = %v, want ErrNotFound", err)
		}
	})

	plaintexts := [][]byte{
		[]byte("第一轮：欢迎使用同传历史。"),
		[]byte("Second turn: completed translation text."),
	}
	turnIDs := make([]uuid.UUID, 0, len(plaintexts))
	for index, plaintext := range plaintexts {
		turnID := uuid.New()
		nonce, ciphertext, err := cipher.SealTurn(ownerID, session.ID, turnID, plaintext)
		if err != nil {
			t.Fatalf("seal turn %d: %v", index, err)
		}
		created, err := db.AppendHistoryTurn(ctx, domain.AppendHistoryTurnParams{ID: turnID, UserID: ownerID, SessionID: session.ID, KeyVersion: 1, Nonce: nonce, Ciphertext: ciphertext, Now: now.Add(time.Duration(index) * time.Second)})
		if err != nil {
			t.Fatalf("append turn %d: %v", index, err)
		}
		turnIDs = append(turnIDs, turnID)
		if !created.Live() || created.KeyVersion != 1 {
			t.Fatalf("unexpected created turn: %+v", created)
		}
	}

	t.Run("append is idempotent per UUID", func(t *testing.T) {
		nonce, ciphertext, err := cipher.SealTurn(ownerID, session.ID, turnIDs[0], plaintexts[0])
		if err != nil {
			t.Fatalf("seal replay: %v", err)
		}
		retried, err := db.AppendHistoryTurn(ctx, domain.AppendHistoryTurnParams{ID: turnIDs[0], UserID: ownerID, SessionID: session.ID, KeyVersion: 1, Nonce: nonce, Ciphertext: ciphertext, Now: now.Add(time.Hour)})
		if err != nil {
			t.Fatalf("replay append: %v", err)
		}
		if retried.ID != turnIDs[0] || !retried.CreatedAt.Equal(now) {
			t.Fatalf("idempotent replay changed the turn: %+v", retried)
		}
	})

	t.Run("append rejects missing or foreign sessions", func(t *testing.T) {
		nonce, ciphertext, err := cipher.SealTurn(ownerID, uuid.New(), uuid.New(), []byte("orphan"))
		if err != nil {
			t.Fatalf("seal orphan: %v", err)
		}
		if _, err := db.AppendHistoryTurn(ctx, domain.AppendHistoryTurnParams{ID: uuid.New(), UserID: ownerID, SessionID: uuid.New(), KeyVersion: 1, Nonce: nonce, Ciphertext: ciphertext, Now: now}); !errors.Is(err, domain.ErrNotFound) {
			t.Fatalf("missing session append error = %v, want ErrNotFound", err)
		}
		if _, err := db.AppendHistoryTurn(ctx, domain.AppendHistoryTurnParams{ID: uuid.New(), UserID: otherID, SessionID: session.ID, KeyVersion: 1, Nonce: nonce, Ciphertext: ciphertext, Now: now}); !errors.Is(err, domain.ErrNotFound) {
			t.Fatalf("foreign session append error = %v, want ErrNotFound", err)
		}
	})

	t.Run("listed turns decrypt for the owning binding only", func(t *testing.T) {
		turns, err := db.ListHistoryTurns(ctx, ownerID, session.ID, 10, 0)
		if err != nil {
			t.Fatalf("list turns: %v", err)
		}
		if len(turns) != len(plaintexts) {
			t.Fatalf("listed %d turns, want %d", len(turns), len(plaintexts))
		}
		// Listing is newest first; decrypt each turn with its stored version.
		for index, turn := range turns {
			opened, err := cipher.OpenTurn(ownerID, session.ID, turn.ID, turn.KeyVersion, turn.Nonce, turn.Ciphertext)
			if err != nil {
				t.Fatalf("open listed turn %d: %v", index, err)
			}
			want := plaintexts[len(plaintexts)-1-index]
			if !bytes.Equal(opened, want) {
				t.Fatalf("turn %d plaintext mismatch", index)
			}
			// Wrong session binding must fail authentication.
			if _, err := cipher.OpenTurn(ownerID, uuid.New(), turn.ID, turn.KeyVersion, turn.Nonce, turn.Ciphertext); !errors.Is(err, historycrypto.ErrCrypto) {
				t.Fatalf("wrong-binding open error = %v, want ErrCrypto", err)
			}
		}
		if _, err := db.ListHistoryTurns(ctx, otherID, session.ID, 10, 0); !errors.Is(err, domain.ErrNotFound) {
			t.Fatalf("foreign list turns error = %v, want ErrNotFound", err)
		}
	})

	t.Run("session listing is owner scoped and live only", func(t *testing.T) {
		otherSession, err := db.CreateHistorySession(ctx, domain.CreateHistorySessionParams{ID: uuid.New(), UserID: ownerID, Now: now.Add(time.Minute)})
		if err != nil {
			t.Fatalf("create second session: %v", err)
		}
		owned, err := db.ListHistorySessions(ctx, ownerID, 10, 0)
		if err != nil {
			t.Fatalf("list sessions: %v", err)
		}
		if len(owned) != 2 || owned[0].ID != otherSession.ID || owned[1].ID != session.ID {
			t.Fatalf("unexpected session listing: %+v", owned)
		}
		foreign, err := db.ListHistorySessions(ctx, otherID, 10, 0)
		if err != nil || len(foreign) != 0 {
			t.Fatalf("foreign listing = %+v, %v", foreign, err)
		}
	})

	deletedAt := now.Add(2 * time.Hour)
	t.Run("delete tombstones the session, clears ciphertext, and wins", func(t *testing.T) {
		if err := db.DeleteHistorySession(ctx, ownerID, session.ID, deletedAt); err != nil {
			t.Fatalf("delete session: %v", err)
		}
		var tombstoneAt time.Time
		var liveCiphertext, liveNonce int
		if err := raw.QueryRow(ctx, `SELECT deleted_at FROM history_sessions WHERE id=$1`, session.ID).Scan(&tombstoneAt); err != nil || !tombstoneAt.Equal(deletedAt) {
			t.Fatalf("session tombstone = %v, %v", tombstoneAt, err)
		}
		if err := raw.QueryRow(ctx, `SELECT count(*) FILTER (WHERE ciphertext IS NOT NULL OR nonce IS NOT NULL), count(*) FILTER (WHERE deleted_at IS NULL) FROM history_turns WHERE session_id=$1`, session.ID).Scan(&liveCiphertext, &liveNonce); err != nil {
			t.Fatalf("inspect cleared turns: %v", err)
		}
		if liveCiphertext != 0 || liveNonce != 0 {
			t.Fatalf("ciphertext-bearing=%d live=%d turns remain after delete", liveCiphertext, liveNonce)
		}
		if _, err := db.HistorySessionByID(ctx, ownerID, session.ID); !errors.Is(err, domain.ErrNotFound) {
			t.Fatalf("deleted session read error = %v, want ErrNotFound", err)
		}
		if _, err := db.ListHistoryTurns(ctx, ownerID, session.ID, 10, 0); !errors.Is(err, domain.ErrNotFound) {
			t.Fatalf("deleted session turn listing error = %v, want ErrNotFound", err)
		}
		// Tombstone wins: no re-creation, no resurrection appends.
		if _, err := db.CreateHistorySession(ctx, domain.CreateHistorySessionParams{ID: session.ID, UserID: ownerID, Now: deletedAt.Add(time.Minute)}); !errors.Is(err, domain.ErrConflict) {
			t.Fatalf("deleted session re-create error = %v, want ErrConflict", err)
		}
		nonce, ciphertext, err := cipher.SealTurn(ownerID, session.ID, turnIDs[0], []byte("resurrect"))
		if err != nil {
			t.Fatalf("seal resurrection: %v", err)
		}
		if _, err := db.AppendHistoryTurn(ctx, domain.AppendHistoryTurnParams{ID: turnIDs[0], UserID: ownerID, SessionID: session.ID, KeyVersion: 1, Nonce: nonce, Ciphertext: ciphertext, Now: deletedAt.Add(time.Minute)}); !errors.Is(err, domain.ErrConflict) {
			t.Fatalf("tombstoned turn replay error = %v, want ErrConflict", err)
		}
		freshNonce, freshCiphertext, err := cipher.SealTurn(ownerID, session.ID, uuid.New(), []byte("fresh turn"))
		if err != nil {
			t.Fatalf("seal fresh turn: %v", err)
		}
		if _, err := db.AppendHistoryTurn(ctx, domain.AppendHistoryTurnParams{ID: uuid.New(), UserID: ownerID, SessionID: session.ID, KeyVersion: 1, Nonce: freshNonce, Ciphertext: freshCiphertext, Now: deletedAt.Add(time.Minute)}); !errors.Is(err, domain.ErrNotFound) {
			t.Fatalf("append after delete error = %v, want ErrNotFound", err)
		}
		// Deleting again stays idempotent and preserves the original tombstone.
		if err := db.DeleteHistorySession(ctx, ownerID, session.ID, deletedAt.Add(time.Hour)); err != nil {
			t.Fatalf("idempotent delete: %v", err)
		}
		if err := raw.QueryRow(ctx, `SELECT deleted_at FROM history_sessions WHERE id=$1`, session.ID).Scan(&tombstoneAt); err != nil || !tombstoneAt.Equal(deletedAt) {
			t.Fatalf("tombstone timestamp moved: %v, %v", tombstoneAt, err)
		}
	})

	t.Run("live session cap rejects the 1001st session", func(t *testing.T) {
		if _, err := raw.Exec(ctx, `INSERT INTO history_sessions(id,user_id,created_at) SELECT gen_random_uuid(),$1,now() FROM generate_series(1,$2)`, sessionCappedID, domain.HistoryMaxLiveSessions); err != nil {
			t.Fatalf("seed live sessions: %v", err)
		}
		if _, err := db.CreateHistorySession(ctx, domain.CreateHistorySessionParams{ID: uuid.New(), UserID: sessionCappedID, Now: now}); !errors.Is(err, domain.ErrHistoryLimitExceeded) {
			t.Fatalf("capped session create error = %v, want ErrHistoryLimitExceeded", err)
		}
	})

	t.Run("live turn cap rejects the 10001st turn", func(t *testing.T) {
		cappedSession, err := db.CreateHistorySession(ctx, domain.CreateHistorySessionParams{ID: uuid.New(), UserID: turnCappedID, Now: now})
		if err != nil {
			t.Fatalf("create capped turn session: %v", err)
		}
		bulkNonce := make([]byte, 12)
		if _, err := rand.Read(bulkNonce); err != nil {
			t.Fatal("generate bulk nonce")
		}
		if _, err := raw.Exec(ctx, `INSERT INTO history_turns(id,user_id,session_id,key_version,nonce,ciphertext,created_at) SELECT gen_random_uuid(),$1,$2,1,$3,$4,now() FROM generate_series(1,$5)`, turnCappedID, cappedSession.ID, bulkNonce, bytes.Repeat([]byte{0x42}, 64), domain.HistoryMaxLiveTurns); err != nil {
			t.Fatalf("seed live turns: %v", err)
		}
		nonce, ciphertext, err := cipher.SealTurn(turnCappedID, cappedSession.ID, uuid.New(), []byte("over cap"))
		if err != nil {
			t.Fatalf("seal over-cap turn: %v", err)
		}
		if _, err := db.AppendHistoryTurn(ctx, domain.AppendHistoryTurnParams{ID: uuid.New(), UserID: turnCappedID, SessionID: cappedSession.ID, KeyVersion: 1, Nonce: nonce, Ciphertext: ciphertext, Now: now}); !errors.Is(err, domain.ErrHistoryLimitExceeded) {
			t.Fatalf("capped turn append error = %v, want ErrHistoryLimitExceeded", err)
		}
	})

	t.Run("invalid inputs are rejected before storage", func(t *testing.T) {
		if _, err := db.CreateHistorySession(ctx, domain.CreateHistorySessionParams{ID: uuid.Nil, UserID: ownerID, Now: now}); !errors.Is(err, domain.ErrInvalid) {
			t.Fatalf("nil-id create error = %v, want ErrInvalid", err)
		}
		nonce, ciphertext, err := cipher.SealTurn(ownerID, session.ID, uuid.New(), []byte("shape"))
		if err != nil {
			t.Fatalf("seal shape turn: %v", err)
		}
		if _, err := db.AppendHistoryTurn(ctx, domain.AppendHistoryTurnParams{ID: uuid.New(), UserID: ownerID, SessionID: session.ID, KeyVersion: 0, Nonce: nonce, Ciphertext: ciphertext, Now: now}); !errors.Is(err, domain.ErrInvalid) {
			t.Fatalf("zero-version append error = %v, want ErrInvalid", err)
		}
		if _, err := db.AppendHistoryTurn(ctx, domain.AppendHistoryTurnParams{ID: uuid.New(), UserID: ownerID, SessionID: session.ID, KeyVersion: 1, Nonce: []byte("short"), Ciphertext: ciphertext, Now: now}); !errors.Is(err, domain.ErrInvalid) {
			t.Fatalf("short-nonce append error = %v, want ErrInvalid", err)
		}
	})
}

func insertHistoryUser(t *testing.T, ctx context.Context, raw *pgxpool.Pool, label string) uuid.UUID {
	t.Helper()
	hash, err := auth.HashPassword(label + "-history-fixture-password")
	if err != nil {
		t.Fatalf("hash %s fixture password: %v", label, err)
	}
	var id uuid.UUID
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,password_hash) VALUES($1,$2) RETURNING id`, label+"-"+uuid.NewString()[:8]+"@history.test", hash).Scan(&id); err != nil {
		t.Fatalf("insert %s fixture user: %v", label, err)
	}
	return id
}
