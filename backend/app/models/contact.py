from datetime import datetime
from app.extensions import db

class ContactRequest(db.Model):
    __tablename__ = "contact_requests"
    __table_args__ = (
        db.UniqueConstraint("requester_id", "target_id", name="uq_contact_pair"),
    )

    id = db.Column(db.Integer, primary_key=True)
    requester_id = db.Column(
        db.String(36), db.ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, index=True
    )
    target_id = db.Column(
        db.String(36), db.ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, index=True
    )
    status = db.Column(db.String(16), nullable=False, default="pending")
    created_at = db.Column(db.DateTime, nullable=False, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, nullable=False, default=datetime.utcnow,
                           onupdate=datetime.utcnow)
