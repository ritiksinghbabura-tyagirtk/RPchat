from datetime import datetime
from app.extensions import db

class Conversation(db.Model):
    __tablename__ = "conversations"
    __table_args__ = (
        db.UniqueConstraint("user_low", "user_high", name="uq_conversation_pair"),
    )

    id = db.Column(db.String(36), primary_key=True)
    user_low = db.Column(
        db.String(36), db.ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, index=True
    )
    user_high = db.Column(
        db.String(36), db.ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, index=True
    )
    created_at = db.Column(db.DateTime, nullable=False, default=datetime.utcnow)

class ChatMessage(db.Model):
    __tablename__ = "chat_messages"

    id = db.Column(db.Integer, primary_key=True)
    conversation_id = db.Column(
        db.String(36), db.ForeignKey("conversations.id", ondelete="CASCADE"),
        nullable=False, index=True
    )
    sender_id = db.Column(
        db.String(36), db.ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, index=True
    )
    ciphertext = db.Column(db.Text, nullable=False)
    nonce = db.Column(db.String(128), nullable=False)
    created_at = db.Column(db.DateTime, nullable=False, default=datetime.utcnow)
