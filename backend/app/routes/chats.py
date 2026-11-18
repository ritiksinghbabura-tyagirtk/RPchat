import base64
import uuid
from flask import Blueprint, g, jsonify, request
from sqlalchemy import or_
from app.extensions import db
from app.models import User, ContactRequest, Conversation, ChatMessage
from app.routes.auth import require_auth

chats_bp = Blueprint("chats", __name__, url_prefix="/api/v1/chats")

def fail(message, status=400):
    return jsonify({"ok": False, "error": message}), status

def participant(conversation, user_id):
    return user_id in (conversation.user_low, conversation.user_high)

@chats_bp.get("")
@require_auth
def list_chats():
    me = g.current_user.id
    rows = Conversation.query.filter(
        or_(Conversation.user_low == me, Conversation.user_high == me)
    ).order_by(Conversation.created_at.desc()).all()

    result = []
    for chat in rows:
        other_id = chat.user_high if chat.user_low == me else chat.user_low
        other = db.session.get(User, other_id)
        last = ChatMessage.query.filter_by(
            conversation_id=chat.id
        ).order_by(ChatMessage.id.desc()).first()
        result.append({
            "conversation_id": chat.id,
            "contact": {
                "user_id": other.public_id,
                "username": other.username,
            } if other else None,
            "last_message_at": last.created_at.isoformat() + "Z" if last else None,
        })

    return jsonify({"ok": True, "chats": result})

@chats_bp.post("")
@require_auth
def create_chat():
    data = request.get_json(silent=True) or {}
    public_id = data.get("user_id", "")
    if not isinstance(public_id, str):
        return fail("Contact RTK ID is required")

    other = User.query.filter_by(
        public_id=public_id.strip().upper(), status="active"
    ).first()
    if not other or other.id == g.current_user.id:
        return fail("Valid contact not found", 404)

    a, b = sorted((g.current_user.id, other.id))
    accepted = ContactRequest.query.filter(
        ContactRequest.status == "accepted",
        or_(
            (ContactRequest.requester_id == a) & (ContactRequest.target_id == b),
            (ContactRequest.requester_id == b) & (ContactRequest.target_id == a),
        )
    ).first()
    if not accepted:
        return fail("Accept the contact request before chatting", 403)

    chat = Conversation.query.filter_by(user_low=a, user_high=b).first()
    if not chat:
        chat = Conversation(id=str(uuid.uuid4()), user_low=a, user_high=b)
        db.session.add(chat)
        db.session.commit()

    return jsonify({"ok": True, "conversation_id": chat.id}), 201

@chats_bp.get("/<conversation_id>/messages")
@require_auth
def get_messages(conversation_id):
    chat = db.session.get(Conversation, conversation_id)
    if not chat or not participant(chat, g.current_user.id):
        return fail("Conversation not found", 404)

    before_id = request.args.get("before_id", type=int)
    query = ChatMessage.query.filter_by(conversation_id=chat.id)
    if before_id:
        query = query.filter(ChatMessage.id < before_id)

    rows = query.order_by(ChatMessage.id.desc()).limit(50).all()
    rows.reverse()

    return jsonify({
        "ok": True,
        "messages": [{
            "id": m.id,
            "sender_id": db.session.get(User, m.sender_id).public_id,
            "ciphertext": m.ciphertext,
            "nonce": m.nonce,
            "created_at": m.created_at.isoformat() + "Z",
        } for m in rows]
    })

@chats_bp.post("/<conversation_id>/messages")
@require_auth
def send_message(conversation_id):
    chat = db.session.get(Conversation, conversation_id)
    if not chat or not participant(chat, g.current_user.id):
        return fail("Conversation not found", 404)

    data = request.get_json(silent=True) or {}
    ciphertext = data.get("ciphertext")
    nonce = data.get("nonce")

    if not isinstance(ciphertext, str) or not isinstance(nonce, str):
        return fail("Encrypted ciphertext and nonce are required")
    if len(ciphertext) > 20000 or len(nonce) > 128:
        return fail("Encrypted message is too large")

    try:
        cipher_bytes = base64.b64decode(ciphertext, validate=True)
        nonce_bytes = base64.b64decode(nonce, validate=True)
    except Exception:
        return fail("Ciphertext and nonce must be Base64")

    if not cipher_bytes or len(nonce_bytes) != 12:
        return fail("Invalid encrypted payload or nonce")

    message = ChatMessage(
        conversation_id=chat.id,
        sender_id=g.current_user.id,
        ciphertext=ciphertext,
        nonce=nonce,
    )
    db.session.add(message)
    db.session.commit()

    return jsonify({
        "ok": True,
        "message": {
            "id": message.id,
            "ciphertext": message.ciphertext,
            "nonce": message.nonce,
            "created_at": message.created_at.isoformat() + "Z",
        }
    }), 201
