import hashlib
import re
import secrets
import uuid

from datetime import datetime, timedelta
from functools import wraps

from argon2 import PasswordHasher, Type
from argon2.exceptions import (
    VerifyMismatchError,
    VerificationError,
    InvalidHashError,
)
from flask import Blueprint, current_app, g, jsonify, request
from sqlalchemy.exc import IntegrityError

from app.extensions import db
from app.models import User, UserSession


auth_bp = Blueprint("auth", __name__, url_prefix="/api/v1/auth")

password_hasher = PasswordHasher(
    time_cost=3,
    memory_cost=65536,
    parallelism=2,
    hash_len=32,
    salt_len=16,
    type=Type.ID,
)

USERNAME_PATTERN = re.compile(r"^[a-zA-Z0-9_]{3,24}$")
SESSION_DAYS = 30


def error(message, status):
    response = jsonify({"ok": False, "error": message})
    response.status_code = status
    response.headers["Cache-Control"] = "no-store"
    return response


def success(data, status=200):
    response = jsonify({"ok": True, **data})
    response.status_code = status
    response.headers["Cache-Control"] = "no-store"
    return response


def create_session(user):
    raw_token = secrets.token_urlsafe(32)
    token_hash = hashlib.sha256(raw_token.encode("utf-8")).hexdigest()

    session = UserSession(
        user_id=user.id,
        token_hash=token_hash,
        expires_at=datetime.utcnow() + timedelta(days=SESSION_DAYS),
    )

    db.session.add(session)
    return raw_token


def require_auth(function):
    @wraps(function)
    def wrapper(*args, **kwargs):
        header = request.headers.get("Authorization", "")
        parts = header.split()

        if len(parts) != 2 or parts[0].lower() != "bearer":
            return error("Authentication required", 401)

        raw_token = parts[1]

        if len(raw_token) < 32 or len(raw_token) > 200:
            return error("Invalid or expired session", 401)

        token_hash = hashlib.sha256(raw_token.encode("utf-8")).hexdigest()

        session = UserSession.query.filter_by(
            token_hash=token_hash
        ).first()

        now = datetime.utcnow()

        if (
            session is None
            or session.revoked_at is not None
            or session.expires_at <= now
            or session.user is None
            or session.user.status != "active"
        ):
            return error("Invalid or expired session", 401)

        g.current_user = session.user
        g.current_session = session

        return function(*args, **kwargs)

    return wrapper


@auth_bp.post("/register")
def register():
    data = request.get_json(silent=True)

    if not isinstance(data, dict):
        return error("JSON request body required", 400)

    username = data.get("username")
    password = data.get("password")

    if not isinstance(username, str) or not isinstance(password, str):
        return error("Username and password are required", 400)

    username = username.strip()
    normalized = username.lower()

    if not USERNAME_PATTERN.fullmatch(username):
        return error(
            "Username must be 3-24 characters: letters, numbers or underscore",
            400
        )

    if len(password) < 12:
        return error("Password must contain at least 12 characters", 400)

    if len(password.encode("utf-8")) > 1024:
        return error("Password is too long", 400)

    if User.query.filter_by(username_normalized=normalized).first():
        return error("Username is unavailable", 409)

    user = User(
        id=str(uuid.uuid4()),
        public_id="RTK-" + secrets.token_hex(8).upper(),
        username=username,
        username_normalized=normalized,
        password_hash=password_hasher.hash(password),
    )

    try:
        db.session.add(user)
        db.session.flush()

        raw_token = create_session(user)
        db.session.commit()

    except IntegrityError:
        db.session.rollback()
        return error("Username is unavailable; please try another", 409)

    except Exception:
        db.session.rollback()
        current_app.logger.exception("Registration failed")
        return error("Registration could not be completed", 500)

    return success({
        "message": "Account created",
        "user": {
            "user_id": user.public_id,
            "username": user.username,
        },
        "session_token": raw_token,
        "expires_in_days": SESSION_DAYS,
    }, 201)


@auth_bp.post("/login")
def login():
    data = request.get_json(silent=True)

    if not isinstance(data, dict):
        return error("JSON request body required", 400)

    username = data.get("username")
    password = data.get("password")

    if (
        not isinstance(username, str)
        or not isinstance(password, str)
        or len(username) > 100
        or len(password.encode("utf-8")) > 1024
    ):
        return error("Invalid username or password", 401)

    user = User.query.filter_by(
        username_normalized=username.strip().lower()
    ).first()

    valid = False

    if user is not None and user.status == "active":
        try:
            valid = password_hasher.verify(user.password_hash, password)
        except (VerifyMismatchError, VerificationError, InvalidHashError):
            valid = False

    if not valid:
        return error("Invalid username or password", 401)

    try:
        raw_token = create_session(user)
        db.session.commit()
    except Exception:
        db.session.rollback()
        current_app.logger.exception("Login session creation failed")
        return error("Login could not be completed", 500)

    return success({
        "message": "Login successful",
        "user": {
            "user_id": user.public_id,
            "username": user.username,
        },
        "session_token": raw_token,
        "expires_in_days": SESSION_DAYS,
    })


@auth_bp.get("/me")
@require_auth
def me():
    user = g.current_user

    return success({
        "user": {
            "user_id": user.public_id,
            "username": user.username,
            "created_at": user.created_at.isoformat() + "Z",
        }
    })


@auth_bp.post("/logout")
@require_auth
def logout():
    session = g.current_session
    session.revoked_at = datetime.utcnow()
    db.session.commit()

    return success({"message": "Logged out"})
