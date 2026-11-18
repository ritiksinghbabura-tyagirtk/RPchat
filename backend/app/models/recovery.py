from datetime import datetime
from app.extensions import db


class RecoveryChallenge(db.Model):
    __tablename__ = "recovery_challenges"

    id = db.Column(db.String(36), primary_key=True)
    user_id = db.Column(
        db.String(36),
        db.ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True
    )
    challenge_hash = db.Column(db.String(64), nullable=False)
    created_at = db.Column(
        db.DateTime, nullable=False, default=datetime.utcnow
    )
    expires_at = db.Column(db.DateTime, nullable=False, index=True)
    consumed_at = db.Column(db.DateTime, nullable=True)
