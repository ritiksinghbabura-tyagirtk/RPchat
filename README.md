# RPchat

**Private Identity. Direct Communication. Privacy-Focused Messaging.**

RPchat is a privacy-focused, ID-based messaging project designed
to let users create a unique identity and communicate without
requiring traditional registration details.

## Privacy Goals

- No KYC requirement in the intended account model.
- No mobile number required.
- No email address required.
- Unique identity-based account architecture.
- Developer-managed private server infrastructure.
- Focus on protecting user data and account privacy.

## Project Components

| Component | Technology |
|---|---|
| Android application | Kotlin, XML |
| Backend | Python, Flask |
| Database | SQLite / PostgreSQL configuration |
| Realtime architecture | WebSocket (planned/integration dependent) |
| Admin panel | Flask, HTML, CSS, JavaScript |
| Deployment | Ubuntu, Nginx, systemd |

## Repository Structure

- `android_app/` - Android application source
- `backend/` - API, identity, authentication and models
- `admin_panel/` - Administration interface
- `deployment/` - Deployment configuration
- `docs/` - Architecture and security documentation

## Development Status

This repository contains an evolving development project.

Messaging features are under development. The previous
project documentation identifies messaging as not enabled
in Phase 1.

Do not assume production-ready messaging or end-to-end
encryption until the corresponding implementations and
security tests have been completed.

## Security

- Never commit private keys, server credentials or `.env` files.
- Never commit production databases or user data.
- Keep server secrets outside the repository.
- Review encryption and authentication implementations
  before production deployment.

## Development

Review the documentation in `docs/`.

Android build:

```bash
cd android_app
./gradlew assembleDebug
```

Backend dependencies:

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## License

See the LICENSE file if present. All rights and licensing
terms should be confirmed by the project owner.
