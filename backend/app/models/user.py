from datetime import datetime
from app.extensions import db


class User(db.Model):
    __tablename__ = "users"

    id = db.Column(db.String(36), primary_key=True)
    public_id = db.Column(db.String(32), unique=True, nullable=False, index=True)
    username = db.Column(db.String(24), nullable=False)
    username_normalized = db.Column(
        db.String(24), unique=True, nullable=False, index=True
    )
    password_hash = db.Column(db.String(512), nullable=False)
    status = db.Column(db.String(20), nullable=False, default="active")
    created_at = db.Column(db.DateTime, nullable=False, default=datetime.utcnow)

    sessions = db.relationship(
        "UserSession",
        backref="user",
        lazy=True,
        cascade="all, delete-orphan"
    )
