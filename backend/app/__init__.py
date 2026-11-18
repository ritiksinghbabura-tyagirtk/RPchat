import os

from dotenv import load_dotenv
from flask import Flask, jsonify

from .config import Config
from .extensions import db, migrate, socketio


def create_app():
    load_dotenv()

    app = Flask(__name__)
    app.config.from_object(Config)

    # Load secrets after dotenv, without printing them.
    app.config["SECRET_KEY"] = os.environ.get("FLASK_SECRET_KEY")
    app.config["SQLALCHEMY_DATABASE_URI"] = os.environ.get(
        "DATABASE_URL",
        app.config["SQLALCHEMY_DATABASE_URI"]
    )

    if not app.config.get("SECRET_KEY"):
        raise RuntimeError("FLASK_SECRET_KEY is required")

    db.init_app(app)
    migrate.init_app(app, db)
    socketio.init_app(app)

    # Import models before create_all so SQLAlchemy knows every table.
    from . import models
    from .routes.auth import auth_bp

    app.register_blueprint(auth_bp)
    from .routes.identity import identity_bp
    from .routes.contacts import contacts_bp
    from .routes.chats import chats_bp
    from .routes.recovery import recovery_bp
    app.register_blueprint(identity_bp)
    app.register_blueprint(contacts_bp)
    app.register_blueprint(chats_bp)
    app.register_blueprint(recovery_bp)


    @app.get("/api/v1/health")
    def health():
        return jsonify({
            "service": "RTK PRIVATE",
            "status": "ok",
            "version": "1.0.0",
            "messaging": "not_enabled_yet",
            "account_system": "enabled",
        })

    with app.app_context():
        db.create_all()

    return app
