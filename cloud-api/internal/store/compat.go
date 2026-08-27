package store

import (
	"context"
	"github.com/dngmeng/cloud-api/internal/domain"
	"time"
)

func (p *Postgres) CreateTranslationSession(ctx context.Context, s domain.TranslationSession) error {
	return p.CreateSession(ctx, s, time.Now().UTC())
}
