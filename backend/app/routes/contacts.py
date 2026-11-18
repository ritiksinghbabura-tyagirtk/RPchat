from flask import Blueprint, g, jsonify, request
from sqlalchemy import or_
from sqlalchemy.exc import IntegrityError
from app.extensions import db
from app.models import User, ContactRequest, UserIdentity
from app.routes.auth import require_auth

contacts_bp = Blueprint("contacts", __name__, url_prefix="/api/v1/contacts")

def fail(message, status=400):
    return jsonify({"ok": False, "error": message}), status

def user_data(user):
    identity = UserIdentity.query.filter_by(user_id=user.id).first()
    return {
        "user_id": user.public_id,
        "username": user.username,
        "fingerprint": identity.fingerprint if identity else None,
    }

@contacts_bp.get("/search")
@require_auth
def search_contact():
    public_id = request.args.get("id", "").strip().upper()
    if not public_id or len(public_id) > 40:
        return fail("Provide a valid RTK ID")

    user = User.query.filter_by(public_id=public_id, status="active").first()
    if not user:
        return fail("User not found", 404)
    if user.id == g.current_user.id:
        return fail("This is your own account", 400)

    return jsonify({"ok": True, "user": user_data(user)})

@contacts_bp.post("/request")
@require_auth
def send_request():
    data = request.get_json(silent=True) or {}
    public_id = data.get("user_id", "")
    if not isinstance(public_id, str):
        return fail("Target RTK ID is required")

    target = User.query.filter_by(
        public_id=public_id.strip().upper(), status="active"
    ).first()
    if not target:
        return fail("User not found", 404)
    if target.id == g.current_user.id:
        return fail("You cannot add yourself")

    reverse = ContactRequest.query.filter_by(
        requester_id=target.id, target_id=g.current_user.id, status="pending"
    ).first()
    if reverse:
        return fail("This user has already requested you. Accept their request.")

    existing = ContactRequest.query.filter_by(
        requester_id=g.current_user.id, target_id=target.id
    ).first()

    if existing and existing.status in ("pending", "accepted"):
        return fail("Request already exists", 409)

    if existing:
        existing.status = "pending"
    else:
        existing = ContactRequest(
            requester_id=g.current_user.id, target_id=target.id, status="pending"
        )
        db.session.add(existing)

    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        return fail("Request already exists", 409)

    return jsonify({"ok": True, "request_id": existing.id, "status": "pending"}), 201

@contacts_bp.get("")
@require_auth
def list_contacts():
    me = g.current_user.id
    rows = ContactRequest.query.filter(
        or_(ContactRequest.requester_id == me, ContactRequest.target_id == me)
    ).order_by(ContactRequest.updated_at.desc()).all()

    result = []
    for row in rows:
        other_id = row.target_id if row.requester_id == me else row.requester_id
        other = db.session.get(User, other_id)
        if other:
            result.append({
                "request_id": row.id,
                "status": row.status,
                "direction": "outgoing" if row.requester_id == me else "incoming",
                "user": user_data(other),
            })

    return jsonify({"ok": True, "contacts": result})

@contacts_bp.post("/accept")
@require_auth
def accept_request():
    data = request.get_json(silent=True) or {}
    request_id = data.get("request_id")
    row = db.session.get(ContactRequest, request_id) if isinstance(request_id, int) else None

    if not row or row.target_id != g.current_user.id or row.status != "pending":
        return fail("Incoming request not found", 404)

    row.status = "accepted"
    db.session.commit()
    return jsonify({"ok": True, "status": "accepted"})

@contacts_bp.post("/reject")
@require_auth
def reject_request():
    data = request.get_json(silent=True) or {}
    request_id = data.get("request_id")
    row = db.session.get(ContactRequest, request_id) if isinstance(request_id, int) else None

    if not row or row.target_id != g.current_user.id or row.status != "pending":
        return fail("Incoming request not found", 404)

    row.status = "rejected"
    db.session.commit()
    return jsonify({"ok": True, "status": "rejected"})
