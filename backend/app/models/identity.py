from datetime import datetime
from app.extensions import db

class UserIdentity(db.Model):
    __tablename__ = "user_identities"

    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(
        db.String(36), db.ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, unique=True, index=True
    )
    signing_public_key = db.Column(db.String(128), nullable=False)
    exchange_public_key = db.Column(db.String(128), nullable=False)
    fingerprint = db.Column(db.String(64), nullable=False, unique=True)
    created_at = db.Column(db.DateTime, nullable=False, default=datetime.utcnow)
