import base64
import hashlib
import secrets
import uuid

from datetime import datetime, timedelta

from flask import Blueprint, request, jsonify, current_app
from sqlalchemy import update
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PublicKey
)
from cryptography.exceptions import InvalidSignature

from app.extensions import db
from app.models import User, UserIdentity, UserSession, RecoveryChallenge
from app.routes.auth import password_hasher


recovery_bp = Blueprint(
    "recovery",
    __name__,
    url_prefix="/api/v1/recovery"
)


def response_error(message, status):
    response = jsonify({"ok": False, "error": message})
    response.status_code = status
    response.headers["Cache-Control"] = "no-store"
    return response


def response_ok(data, status=200):
    response = jsonify({"ok": True, **data})
    response.status_code = status
    response.headers["Cache-Control"] = "no-store"
    return response


@recovery_bp.post("/challenge")
def issue_challenge():
    data = request.get_json(silent=True)

    if not isinstance(data, dict):
        return response_error("JSON body required", 400)

    public_id = data.get("user_id")

    if not isinstance(public_id, str) or len(public_id) > 32:
        return response_error("Invalid recovery request", 400)

    user = User.query.filter_by(public_id=public_id.strip().upper()).first()

    if user is None or user.status != "active":
        return response_error("Account or recovery identity not found", 404)

    identity = UserIdentity.query.filter_by(user_id=user.id).first()

    if identity is None:
        return response_error("Recovery identity is not registered", 404)

    now = datetime.utcnow()

    # Database-backed per-account limit: maximum 5 challenges per hour.
    recent = RecoveryChallenge.query.filter(
        RecoveryChallenge.user_id == user.id,
        RecoveryChallenge.created_at >= now - timedelta(hours=1)
    ).count()

    if recent >= 5:
        return response_error("Too many recovery attempts. Try later.", 429)

    raw = secrets.token_bytes(32)
    encoded = base64.b64encode(raw).decode("ascii")
    challenge_id = str(uuid.uuid4())

    record = RecoveryChallenge(
        id=challenge_id,
        user_id=user.id,
        challenge_hash=hashlib.sha256(raw).hexdigest(),
        expires_at=now + timedelta(minutes=5)
    )

    try:
        db.session.add(record)
        db.session.commit()
    except Exception:
        db.session.rollback()
        current_app.logger.exception("Recovery challenge creation failed")
        return response_error("Could not start recovery", 500)

    return response_ok({
        "challenge_id": challenge_id,
        "challenge": encoded,
        "expires_in_seconds": 300
    })


@recovery_bp.post("/verify")
def verify_recovery():
    data = request.get_json(silent=True)

    if not isinstance(data, dict):
        return response_error("JSON body required", 400)

    challenge_id = data.get("challenge_id")
    challenge_text = data.get("challenge")
    signature_text = data.get("signature")
    new_password = data.get("new_password")

    if not all(isinstance(x, str) for x in (
        challenge_id, challenge_text, signature_text, new_password
    )):
        return response_error("Invalid recovery request", 400)

    if len(new_password) < 12 or len(new_password.encode("utf-8")) > 1024:
        return response_error("Password must be at least 12 characters", 400)

    if len(challenge_id) > 64 or len(challenge_text) > 128 or len(signature_text) > 256:
        return response_error("Invalid recovery request", 400)

    try:
        raw_challenge = base64.b64decode(challenge_text, validate=True)
        signature = base64.b64decode(signature_text, validate=True)
    except Exception:
        return response_error("Invalid recovery proof", 400)

    if len(raw_challenge) != 32 or len(signature) != 64:
        return response_error("Invalid recovery proof", 400)

    record = RecoveryChallenge.query.filter_by(id=challenge_id).first()

    if record is None:
        return response_error("Recovery challenge expired or invalid", 400)

    now = datetime.utcnow()

    if record.consumed_at is not None or record.expires_at <= now:
        return response_error("Recovery challenge expired or already used", 400)

    supplied_hash = hashlib.sha256(raw_challenge).hexdigest()

    if not secrets.compare_digest(record.challenge_hash, supplied_hash):
        return response_error("Invalid recovery proof", 400)

    user = User.query.filter_by(id=record.user_id, status="active").first()
    identity = UserIdentity.query.filter_by(user_id=record.user_id).first()

    if user is None or identity is None:
        return response_error("Recovery identity not found", 404)

    try:
        public_key_bytes = base64.b64decode(
            identity.signing_public_key, validate=True
        )
        public_key = Ed25519PublicKey.from_public_bytes(public_key_bytes)
        public_key.verify(signature, raw_challenge)
    except (ValueError, InvalidSignature, Exception):
        return response_error("Recovery proof is incorrect", 401)

    try:
        # Atomic one-time challenge consumption prevents replay/races.
        result = db.session.execute(
            update(RecoveryChallenge)
            .where(
                RecoveryChallenge.id == challenge_id,
                RecoveryChallenge.consumed_at.is_(None),
                RecoveryChallenge.expires_at > now
            )
            .values(consumed_at=now)
        )

        if result.rowcount != 1:
            db.session.rollback()
            return response_error("Recovery challenge already used", 409)

        user.password_hash = password_hasher.hash(new_password)

        # Revoke every previous session for this account.
        UserSession.query.filter(
            UserSession.user_id == user.id,
            UserSession.revoked_at.is_(None)
        ).update(
            {"revoked_at": now},
            synchronize_session=False
        )

        db.session.commit()

    except Exception:
        db.session.rollback()
        current_app.logger.exception("Account recovery update failed")
        return response_error("Recovery could not be completed", 500)

    return response_ok({
        "message": "Password updated. All previous sessions were revoked.",
        "user": {
            "user_id": user.public_id,
            "username": user.username
        }
    })
