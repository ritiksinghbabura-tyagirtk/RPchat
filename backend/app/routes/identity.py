import base64
import hashlib
from flask import Blueprint, g, jsonify, request
from app.extensions import db
from app.models import UserIdentity
from app.routes.auth import require_auth

identity_bp = Blueprint("identity", __name__, url_prefix="/api/v1/identity")

def fail(message, status=400):
    return jsonify({"ok": False, "error": message}), status

@identity_bp.post("/create")
@require_auth
def create_identity():
    data = request.get_json(silent=True) or {}
    signing = data.get("signing_public_key")
    exchange = data.get("exchange_public_key")

    if not isinstance(signing, str) or not isinstance(exchange, str):
        return fail("Both public keys are required")

    try:
        signing_bytes = base64.b64decode(signing, validate=True)
        exchange_bytes = base64.b64decode(exchange, validate=True)
    except Exception:
        return fail("Public keys must be valid Base64")

    if len(signing_bytes) != 32 or len(exchange_bytes) != 32:
        return fail("Public keys must each contain 32 bytes")

    existing = UserIdentity.query.filter_by(user_id=g.current_user.id).first()
    if existing:
        return fail("Identity already exists", 409)

    fingerprint = hashlib.sha256(
        signing_bytes + exchange_bytes
    ).hexdigest()

    if UserIdentity.query.filter_by(fingerprint=fingerprint).first():
        return fail("This identity key is already registered", 409)

    identity = UserIdentity(
        user_id=g.current_user.id,
        signing_public_key=signing,
        exchange_public_key=exchange,
        fingerprint=fingerprint,
    )
    db.session.add(identity)
    db.session.commit()

    return jsonify({
        "ok": True,
        "identity": {
            "user_id": g.current_user.public_id,
            "username": g.current_user.username,
            "signing_public_key": signing,
            "exchange_public_key": exchange,
            "fingerprint": fingerprint,
            "created_at": identity.created_at.isoformat() + "Z",
        }
    }), 201

@identity_bp.get("/me")
@require_auth
def my_identity():
    identity = UserIdentity.query.filter_by(user_id=g.current_user.id).first()
    if not identity:
        return fail("Identity has not been created", 404)

    return jsonify({
        "ok": True,
        "identity": {
            "user_id": g.current_user.public_id,
            "username": g.current_user.username,
            "signing_public_key": identity.signing_public_key,
            "exchange_public_key": identity.exchange_public_key,
            "fingerprint": identity.fingerprint,
            "created_at": identity.created_at.isoformat() + "Z",
        }
    })
