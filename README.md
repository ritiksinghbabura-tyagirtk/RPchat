# RPchat

**Private identity. Direct communication. Privacy-focused messaging.**

RPchat is an Android-first, self-hostable private messaging project. It uses a username/password account for sign-in and assigns each account a public RTK ID. Users can find one another by RTK ID, exchange contact requests, and start one-to-one conversations after a request is accepted.

The repository contains an Android client written in Kotlin, a Flask API, SQLAlchemy data models, and client-side identity/message cryptography.

> **Project status:** Active development. This README describes the source code inspected in the supplied project snapshot. It is not a claim that every feature has passed runtime, integration, penetration, or independent cryptographic testing. Review the limitations and security notes before using real or sensitive data.

---

## Contents

- [Project goals](#project-goals)
- [Current implementation status](#current-implementation-status)
- [Features](#features)
- [Architecture](#architecture)
- [Technology stack](#technology-stack)
- [Repository structure](#repository-structure)
- [How account and identity setup works](#how-account-and-identity-setup-works)
- [How contacts work](#how-contacts-work)
- [How messaging works](#how-messaging-works)
- [Cryptography and security design](#cryptography-and-security-design)
- [Backend API reference](#backend-api-reference)
- [Requirements](#requirements)
- [Backend setup](#backend-setup)
- [Android build](#android-build)
- [Configuration](#configuration)
- [Database](#database)
- [Testing and verification](#testing-and-verification)
- [Known limitations and planned work](#known-limitations-and-planned-work)
- [Security reporting](#security-reporting)
- [Contributing](#contributing)
- [License](#license)

---

## Project goals

RPchat is designed around the following product goals:

- Account discovery through a public RTK ID.
- No phone-number or email field in the current registration API.
- No KYC workflow in the current account-registration implementation.
- User-controlled identity keys generated on the Android client.
- Private one-to-one conversations between accepted contacts.
- Encrypted message payloads stored by the backend.
- A backend that can be hosted by the project operator.
- A small initial feature scope rather than public rooms or group chat.

These are project goals and properties of the inspected implementation. They should not be interpreted as a guarantee of anonymity, complete metadata privacy, or protection against a compromised device or server.

## Current implementation status

The table below distinguishes source-code presence from verified production readiness.

| Area | Status from source inspection | Notes |
|---|---|---|
| Android application shell | Implemented | Launcher activity, authentication activity, main activity and chat activity are present. |
| Login | Implemented | Username/password login against Flask API. |
| Registration | Implemented | Username and password; server creates an RTK ID and session. |
| Password hashing | Implemented | Backend uses Argon2id through `argon2-cffi`. |
| Session authentication | Implemented | Bearer token; server stores a SHA-256 token hash. |
| Local session storage | Implemented | Android Keystore-backed AES-GCM encrypted preference value. |
| RTK identity keys | Implemented | Client derives Ed25519 and X25519 key material from a 12-word phrase. |
| Local identity vault | Implemented | Identity seeds are encrypted using Android Keystore AES-GCM. |
| Recovery phrase confirmation | Implemented | Registration asks the user to confirm five randomly selected words. |
| Account recovery | Implemented in source | Challenge signing, password update and session revocation flow are present; runtime integration still needs testing. |
| RTK ID lookup | Implemented | Search endpoint accepts an exact RTK ID. |
| Contact requests | Implemented | Send, list, accept and reject operations. |
| QR generation | Implemented | Identity QR and RTK ID contact QR helpers are present; scanner UI is also present. |
| One-to-one conversation API | Implemented | A conversation requires an accepted contact. |
| Encrypted message transport | Implemented in source | Client encrypts before sending; API stores ciphertext and nonce. |
| Message history | Implemented | HTTP endpoint returns up to 50 messages per request. |
| Message refresh | Implemented | Chat screen uses periodic HTTP polling. |
| WebSocket messaging | Not verified / not implemented in inspected routes | Flask-SocketIO is a dependency, but no chat event handlers were found in the supplied source. |
| Group chat / public rooms | Not implemented | Outside the current one-to-one scope. |
| Image and file messaging | Not implemented | No media upload flow was found. |
| Voice/video calling | Not implemented | No calling implementation was found. |
| Admin panel | Not assessable from supplied audit ZIP | No admin-panel source files were included in that ZIP. |
| Production deployment | Not assessable from supplied audit ZIP | Deployment source files were not included in that ZIP. |
| Automated test suite | Not found in supplied audit ZIP | Manual and automated verification are still required. |

**Important:** “Implemented” means corresponding source code and API paths exist. It does not mean that the feature has been fully tested on multiple devices or is production-ready.

---

## Features

### 1. Account registration and login

The Android authentication screen supports:

- Create account using a username and password.
- Login using the same credentials.
- A minimum 12-character password requirement at registration and recovery.
- Account creation through the Flask API.
- A server-generated public RTK ID.
- A session token returned after successful registration or login.

The backend validates usernames against this pattern:

- 3 to 24 characters.
- Letters, numbers and underscores.
- Username uniqueness is case-insensitive.

The current registration API does not request a phone number or email address.

### 2. RTK public identity

An RTK ID is generated by the backend in this format:

`RTK-` followed by 16 uppercase hexadecimal characters.

The Android client creates identity key material locally and registers only the public keys with the backend:

- Ed25519 signing public key.
- X25519 exchange public key.
- SHA-256 identity fingerprint.

The backend prevents duplicate identity records and duplicate fingerprints.

### 3. Contact discovery and requests

Users can:

- Search for an account using its exact RTK ID.
- View the matching username, RTK ID and available identity fingerprint.
- Send a contact request.
- View incoming and outgoing requests.
- Accept or reject incoming requests.
- View accepted contacts.

A chat can be created only after the contact relationship has been accepted.

### 4. QR identity

The Android source contains QR generation helpers for:

- An identity payload containing the public RTK ID, username, public keys and fingerprint.
- A contact QR containing only the public RTK ID.

The contact QR helper explicitly avoids including session tokens or private keys. The app also includes QR scanning integration through ZXing.

### 5. One-to-one messaging

The Android chat screen includes:

- Recent conversation list.
- Start/open conversation using an accepted contact's RTK ID.
- Message composer.
- Outgoing and incoming message bubbles.
- Message timestamps.
- Message history loading.
- Periodic refresh while the chat activity is active.

Messages are encrypted on the client before they are submitted to the message API. The backend stores the ciphertext and nonce rather than receiving the intended plaintext from the normal send flow.

### 6. Account recovery

The recovery implementation uses the locally entered 12-word phrase to derive the identity keys again.

The flow in the inspected source is:

1. User enters their RTK ID.
2. Client requests a recovery challenge.
3. Server creates a random challenge with a five-minute expiry.
4. Client derives the identity from the phrase locally.
5. Client signs the challenge using the derived Ed25519 private key.
6. Client submits the challenge ID, challenge, signature and new password.
7. Server verifies the signature against the registered public signing key.
8. Server consumes the challenge, updates the password and revokes existing sessions.
9. Client stores the restored identity in its local encrypted identity vault.

The recovery phrase itself is not included in the recovery API request.

---

## Architecture

```text
┌──────────────────────────────────────────────┐
│               Android Client                 │
│                                              │
│  AuthActivity   MainActivity   ChatActivity  │
│       │              │              │        │
│       └──────────────┴──────────────┘        │
│                      │                       │
│                 AuthApi HTTP                 │
│                      │                       │
│       ┌──────────────┴──────────────┐        │
│       │                             │        │
│  IdentityCrypto                 ChatCrypto   │
│  IdentityVault                  SessionStore │
└──────────────────────┬───────────────────────┘
                       │ HTTPS in release
                       │ HTTP may be used in debug
┌──────────────────────▼───────────────────────┐
│                  Flask API                   │
│                                              │
│  Authentication   Identity   Contacts        │
│  Recovery         Chats      Health          │
│                      │                       │
│                SQLAlchemy ORM                 │
└──────────────────────┬───────────────────────┘
                       │
             SQLite / PostgreSQL
```

### Main backend modules

| Module | Responsibility |
|---|---|
| `routes/auth.py` | Registration, login, current user and logout. |
| `routes/identity.py` | Register and retrieve public identity keys. |
| `routes/contacts.py` | Search, request, list, accept and reject contacts. |
| `routes/chats.py` | Create conversations and send/list encrypted messages. |
| `routes/recovery.py` | Issue and verify account recovery challenges. |
| `models/user.py` | User accounts and public RTK IDs. |
| `models/session.py` | Hashed session tokens and expiry/revocation data. |
| `models/identity.py` | Public signing and exchange keys and fingerprints. |
| `models/contact.py` | Contact request relationships and states. |
| `models/chat.py` | Conversations and encrypted message records. |
| `models/recovery.py` | Recovery challenge lifecycle. |

### Realtime behavior

The backend requirements include Flask-SocketIO, and the application initializes a Socket.IO extension. However, the inspected route source does not define message event handlers. The Android chat code uses HTTP requests and periodic polling to refresh messages.

Therefore, this version should be described as **HTTP-based message synchronization**, not as a completed WebSocket realtime messaging system.

---

## Technology stack

| Component | Technology visible in source |
|---|---|
| Android language | Kotlin |
| Android UI | XML layouts and programmatic Android views |
| Android namespace | `com.rtk.privatechat` |
| Compile SDK | 35 |
| Target SDK | 35 |
| Minimum SDK | 31 |
| Java/Kotlin bytecode target | 17 |
| Backend | Python, Flask |
| ORM | Flask-SQLAlchemy / SQLAlchemy |
| Database migrations | Flask-Migrate |
| Development database default | SQLite |
| Configurable database | PostgreSQL through psycopg |
| Password hashing | Argon2id |
| Client cryptography | Bouncy Castle and Java Cryptography APIs |
| QR | ZXing |
| Session and identity local protection | Android Keystore, AES-GCM |
| API transport | HTTP JSON |
| Socket dependency | Flask-SocketIO (no chat event handlers found in inspected source) |

Key Android dependencies include:

- AndroidX Core KTX 1.15.0
- AndroidX AppCompat 1.7.0
- Material Components 1.12.0
- ZXing Core 3.5.3
- JourneyApps ZXing Embedded 4.3.0
- Bouncy Castle `bcprov-jdk18on` 1.80

Backend dependencies are listed in `backend/requirements.txt`.

---

## Repository structure

The main project structure is:

```text
RPchat/
├── android_app/
│   ├── app/
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   ├── java/com/rtk/privatechat/
│   │   │   │   │   ├── AuthActivity.kt
│   │   │   │   │   ├── MainActivity.kt
│   │   │   │   │   ├── ChatActivity.kt
│   │   │   │   │   ├── UiWindow.kt
│   │   │   │   │   ├── data/
│   │   │   │   │   │   ├── AuthApi.kt
│   │   │   │   │   │   └── SessionStore.kt
│   │   │   │   │   └── security/
│   │   │   │   │       ├── ChatCrypto.kt
│   │   │   │   │       ├── IdentityCrypto.kt
│   │   │   │   │       ├── IdentityQr.kt
│   │   │   │   │       └── IdentityVault.kt
│   │   │   │   ├── res/
│   │   │   │   └── assets/
│   │   │   │       └── bip39_english.txt
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   └── settings.gradle.kts
├── backend/
│   ├── app/
│   │   ├── models/
│   │   ├── routes/
│   │   ├── __init__.py
│   │   ├── config.py
│   │   └── extensions.py
│   ├── requirements.txt
│   └── run.py
├── docs/
│   ├── ARCHITECTURE.md
│   └── SECURITY.md
├── admin_panel/       # Directory in project; inspect its current contents separately
├── deployment/        # Directory in project; inspect its current contents separately
├── .gitignore
├── LICENSE
└── README.md
```

The audit ZIP used to prepare this README did not contain files from `admin_panel/` or `deployment/`. Their actual implementation and setup cannot be documented from that snapshot.

---

## How account and identity setup works

### Registration

The client sends a JSON request to:

`POST /api/v1/auth/register`

The server:

1. Validates the username and password.
2. Hashes the password using Argon2id.
3. Generates a UUID internal user ID.
4. Generates a public RTK ID.
5. Creates a session and returns the raw session token once.

After registration, the Android app creates the recovery phrase and derives identity keys locally. It then registers the public keys with the identity endpoint.

### Local identity storage

The Android `IdentityVault`:

- Generates or loads an AES key through Android Keystore.
- Encrypts the identity seed data using AES/GCM/NoPadding.
- Stores the encrypted blob in private SharedPreferences.
- Reconstructs the public keys from the stored seeds when loading.
- Checks that the reconstructed public keys match the stored public-key values.

The private signing and exchange seeds are not sent to the identity registration endpoint.

### Session storage

`SessionStore` encrypts the session token using an Android Keystore AES key and AES-GCM before storing it in private SharedPreferences.

The backend stores a SHA-256 hash of the session token, not the raw token. Authenticated requests use:

`Authorization: Bearer <session_token>`

The configured session lifetime is 30 days. Logout revokes the current session.

---

## How contacts work

The contact workflow is:

```text
Search RTK ID
     │
     ▼
Send contact request
     │
     ▼
Recipient sees incoming request
     │
     ├── Reject ──► Request marked rejected
     │
     └── Accept ──► Contact marked accepted
                         │
                         ▼
                 One-to-one chat allowed
```

The identity lookup endpoint allows the authenticated user to retrieve their own identity or the public identity of an accepted contact. Private identity seeds are not returned by this endpoint.

---

## How messaging works

### Sending

The Android client:

1. Loads its identity material from the local identity vault.
2. Requests the accepted contact's public identity.
3. Derives a shared secret using X25519.
4. Derives an AES key using HMAC-SHA256-based extract/expand operations with the conversation ID as salt/context.
5. Encrypts the message using AES-256-GCM with a fresh 12-byte nonce.
6. Signs the message envelope using Ed25519.
7. Sends the encoded encrypted envelope and nonce to the Flask API.

### Receiving

The client:

1. Fetches message records from the conversation endpoint.
2. Loads the peer's public identity and its own local identity material.
3. Verifies the sender's Ed25519 signature.
4. Derives the shared key.
5. Decrypts the AES-GCM payload.
6. Displays the plaintext in the local chat UI.

### Server-side message storage

The chat message model stores:

- Conversation ID.
- Sender user ID.
- Ciphertext.
- Nonce.
- Creation timestamp.

The message API validates that the caller belongs to the conversation and checks that ciphertext and nonce are Base64 values with expected basic sizes.

The backend does not perform the client-side message decryption in the inspected message routes.

---

## Cryptography and security design

This section describes the algorithms found in source. It is not a cryptographic audit or a certification.

### Identity keys

The Android identity implementation uses:

- A 12-word mnemonic generated from 128 bits of random entropy and a four-bit SHA-256 checksum.
- PBKDF2-HMAC-SHA512 to derive a seed from the mnemonic.
- HMAC-SHA256 labels to derive separate 32-byte signing and exchange seeds.
- Ed25519 for signatures.
- X25519 for key agreement.

The mnemonic implementation uses the bundled 2048-word English BIP39 word list. The source should receive independent compatibility and test-vector verification before claiming full BIP39 interoperability.

### Message encryption

The current message encryption implementation uses:

- Static X25519 identity keys for shared-secret agreement.
- HMAC-SHA256-based key derivation.
- AES-256-GCM for message confidentiality and integrity.
- A fresh 12-byte nonce for each encryption operation.
- Ed25519 signatures over the conversation ID, nonce and encrypted content.

### Important cryptographic limitations

The current implementation is an initial encrypted-message transport, not a modern ratcheting protocol.

It does **not** implement:

- Signal Protocol or a Signal-style double ratchet.
- Forward secrecy through per-message ephemeral key evolution.
- Automatic key rotation.
- A complete multi-device key-management protocol.
- A verified key-change notification and recovery workflow.
- Independent third-party cryptographic review.

Static identity-key agreement means compromise of long-term identity keys can have consequences for messages encrypted with the same keys. Do not claim forward secrecy.

The chat UI includes a legacy fallback that attempts to Base64-decode a message if authenticated decryption fails. That fallback should be reviewed and removed or tightly constrained before production use, because displaying undeciphered payloads as legacy text can confuse users about whether a message was securely authenticated.

### Transport security

The Android API client enforces HTTPS for non-debug builds. Debug builds can use HTTP for local development.

Production deployments must use HTTPS with valid certificate configuration. Do not expose the Flask development server directly to the public internet.

### Additional security work required

Before production use, perform:

- Independent cryptographic protocol review.
- API authorization and abuse testing.
- Rate-limit review for registration, login, contact search and messaging.
- TLS and deployment review.
- Database backup and access-control review.
- Android release build and device testing.
- Recovery and key-loss scenario testing.
- Log review to ensure credentials, tokens, recovery phrases and plaintext messages are not logged.
- Review of key verification UX and fingerprint comparison.

---

## Backend API reference

Base path:

`/api/v1`

All request and response bodies use JSON unless otherwise noted. Authenticated endpoints require a bearer token.

### Health

| Method | Endpoint | Authentication | Purpose |
|---|---|---|---|
| GET | `/health` | No | Returns service name, status and version information. |

The current health response includes `messaging: "not_enabled_yet"` even though chat routes exist. Treat this field as an outdated or conservative status marker that should be reconciled in a later backend change.

### Authentication

| Method | Endpoint | Authentication | Purpose |
|---|---|---|---|
| POST | `/auth/register` | No | Create account and session. |
| POST | `/auth/login` | No | Authenticate and create session. |
| GET | `/auth/me` | Yes | Return current account information. |
| POST | `/auth/logout` | Yes | Revoke current session. |

Example registration body:

```json
{
  "username": "example_user",
  "password": "use-a-long-unique-password"
}
```

### Identity

| Method | Endpoint | Authentication | Purpose |
|---|---|---|---|
| POST | `/identity/create` | Yes | Register public signing and exchange keys. |
| GET | `/identity/me` | Yes | Return the current user's public identity. |
| GET | `/identity/by-user/<public_id>` | Yes | Return self or accepted-contact public identity. |

Example identity body:

```json
{
  "signing_public_key": "BASE64_PUBLIC_SIGNING_KEY",
  "exchange_public_key": "BASE64_PUBLIC_EXCHANGE_KEY"
}
```

The server requires each decoded public key to be 32 bytes.

### Contacts

| Method | Endpoint | Authentication | Purpose |
|---|---|---|---|
| GET | `/contacts/search?id=<RTK_ID>` | Yes | Search by exact RTK ID. |
| POST | `/contacts/request` | Yes | Send a contact request. |
| GET | `/contacts` | Yes | List incoming, outgoing and accepted relationships. |
| POST | `/contacts/accept` | Yes | Accept an incoming request. |
| POST | `/contacts/reject` | Yes | Reject an incoming request. |

Example request body:

```json
{
  "user_id": "RTK-0123456789ABCDEF"
}
```

Accept/reject body:

```json
{
  "request_id": 123
}
```

### Chats

| Method | Endpoint | Authentication | Purpose |
|---|---|---|---|
| GET | `/chats` | Yes | List conversations for the current user. |
| POST | `/chats` | Yes | Create or retrieve a one-to-one conversation with an accepted contact. |
| GET | `/chats/<conversation_id>/messages` | Yes | Fetch up to 50 messages; optional `before_id` pagination parameter. |
| POST | `/chats/<conversation_id>/messages` | Yes | Store an encrypted message payload. |

Example create-chat body:

```json
{
  "user_id": "RTK-0123456789ABCDEF"
}
```

Example encrypted message body:

```json
{
  "ciphertext": "BASE64_ENCODED_ENCRYPTED_ENVELOPE",
  "nonce": "BASE64_ENCODED_12_BYTE_NONCE"
}
```

The chat API checks conversation membership before returning or accepting messages.

### Recovery

| Method | Endpoint | Authentication | Purpose |
|---|---|---|---|
| POST | `/recovery/challenge` | No bearer token | Issue a time-limited recovery challenge for an RTK ID. |
| POST | `/recovery/verify` | No bearer token | Verify an Ed25519 proof and set a new password. |

Example challenge request:

```json
{
  "user_id": "RTK-0123456789ABCDEF"
}
```

The verification body contains the challenge ID, challenge, signature and new password. The mnemonic phrase itself is not sent.

The backend limits challenge creation to five per account per hour in the inspected implementation. Challenges expire after five minutes and are designed for one-time use.

---

## Requirements

### Android

- Android SDK with API 35 platform/build tools.
- JDK 17 compatible toolchain.
- Gradle compatible with the Android Gradle Plugin configured by the project.
- Android device or emulator running Android 12 / API 31 or newer.
- Camera permission for QR scanning.
- Network access to the configured backend.

The supplied project snapshot does not include a Gradle wrapper. Use an installed compatible Gradle command or add and verify a wrapper as a separate repository maintenance task.

### Backend

- Python 3.11+ recommended; use a Python version supported by all pinned dependency releases in `requirements.txt`.
- pip.
- SQLite for local development, or PostgreSQL for a server deployment.
- A securely generated `FLASK_SECRET_KEY`.
- Network access between the Android client and backend.

The exact Python version used for the original development environment is not established by this source ZIP.

---

## Backend setup

From the repository root:

```bash
cd backend

python3 -m venv .venv
source .venv/bin/activate

python -m pip install --upgrade pip
pip install -r requirements.txt
```

Create a local `.env` file inside `backend/`:

```env
FLASK_SECRET_KEY=replace-with-a-long-random-secret
DATABASE_URL=sqlite:///rtk_private_dev.db
```

Generate a secret value securely. Do not commit `.env` to Git.

Start the development backend:

```bash
python run.py
```

The current `run.py` binds to:

```text
127.0.0.1:8789
```

This is suitable for local development from the same environment. It is not a public production server configuration.

Health endpoint:

```text
http://127.0.0.1:8789/api/v1/health
```

For a different Android device, configure a reachable private LAN address or deployed HTTPS domain. `127.0.0.1` on a physical Android device refers to that Android device itself, not another computer or phone hosting the backend.

---

## Android build

The Android project is in `android_app/`.

Use an installed compatible Gradle version:

```bash
cd android_app
gradle assembleDebug
```

The expected debug APK location is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The Android application uses:

- Application ID: `com.rtk.privatechat`
- Debug application ID suffix: `.debug`
- Version name: `1.0.0`
- Version code: `1`
- Minimum SDK: `31`
- Target SDK: `35`
- Compile SDK: `35`

### Configure the API base URL

The Android Gradle build reads the `RTK_API_BASE_URL` project property. If it is not supplied, the source defaults to:

```text
http://127.0.0.1:8789
```

For local development, set a reachable address when building:

```bash
gradle assembleDebug -PRTK_API_BASE_URL=http://YOUR_SERVER_LAN_IP:8789
```

For a release build, use HTTPS:

```bash
gradle assembleRelease -PRTK_API_BASE_URL=https://YOUR_DOMAIN
```

The API client rejects non-HTTPS base URLs in non-debug builds.

Replace the example address with your own reachable backend address. Do not put private server credentials in Gradle properties or source code.

---

## Configuration

### Backend environment variables

| Variable | Purpose | Default |
|---|---|---|
| `FLASK_SECRET_KEY` | Flask signing/session secret; required by app startup. | No default; application exits if missing. |
| `DATABASE_URL` | SQLAlchemy database connection URL. | `sqlite:///rtk_private_dev.db` |

### Android build property

| Property | Purpose | Default |
|---|---|---|
| `RTK_API_BASE_URL` | Backend base URL embedded in `BuildConfig`. | `http://127.0.0.1:8789` |

The default local URL is for development only. A production release must use HTTPS.

---

## Database

The SQLAlchemy models define these tables:

| Table | Purpose |
|---|---|
| `users` | Internal user ID, public RTK ID, username, password hash and account status. |
| `user_sessions` | Hashed bearer token, expiry and revocation timestamp. |
| `user_identities` | Public Ed25519/X25519 keys and identity fingerprint. |
| `contact_requests` | Contact request direction and status. |
| `conversations` | One-to-one participant pair. |
| `chat_messages` | Encrypted message payload, nonce, sender and timestamp. |
| `recovery_challenges` | Hashed challenge, expiry and consumption timestamp. |

The application calls `db.create_all()` during app creation. Flask-Migrate is included in the dependencies, but a migration history was not included in the supplied audit ZIP. For a long-lived deployment, create and maintain explicit database migrations rather than relying on `create_all()` for schema evolution.

Back up production databases securely. Database backups may contain account metadata, contact relationships, public identity keys and encrypted message payloads.

---

## Testing and verification

The supplied audit ZIP did not contain an automated test suite. Before publishing a release, verify at least the following workflows.

### Account tests

- [ ] Register with a valid username and password.
- [ ] Reject invalid usernames.
- [ ] Reject passwords shorter than 12 characters.
- [ ] Reject duplicate usernames regardless of letter case.
- [ ] Login with valid credentials.
- [ ] Reject invalid credentials.
- [ ] Confirm session expiry and logout revocation.
- [ ] Confirm `/auth/me` rejects missing, expired and revoked tokens.

### Identity and recovery tests

- [ ] Generate and validate a 12-word phrase.
- [ ] Confirm the five-word registration check.
- [ ] Confirm private seeds remain local.
- [ ] Register public identity keys.
- [ ] Reject invalid public-key lengths and duplicate identity keys.
- [ ] Restore the same identity from the same phrase.
- [ ] Reject an invalid recovery phrase.
- [ ] Reject expired, incorrect and reused recovery challenges.
- [ ] Confirm recovery revokes all existing sessions.

### Contact and messaging tests

- [ ] Search an existing RTK ID.
- [ ] Reject searching for the current user's own ID.
- [ ] Send and receive a contact request.
- [ ] Accept and reject incoming requests.
- [ ] Prevent chat creation before acceptance.
- [ ] Create a conversation between accepted contacts.
- [ ] Prevent non-participants from reading or writing messages.
- [ ] Send and decrypt messages in both directions.
- [ ] Reject tampered ciphertext, nonce and signature.
- [ ] Verify message history and pagination.
- [ ] Verify behavior when a contact changes or loses identity keys.

### Release checks

- [ ] Build a debug APK.
- [ ] Build a release APK with an HTTPS API URL.
- [ ] Test on at least two physical Android devices.
- [ ] Test API connectivity over the intended network.
- [ ] Review Android logs and backend logs for sensitive data.
- [ ] Test database backup and restore.
- [ ] Complete an independent security review before real-world deployment.

A successful compile is not proof that login, recovery, encryption, authorization or messaging works correctly at runtime.

---

## Known limitations and planned work

The following items should be treated as future engineering work unless separately implemented and verified.

### Messaging and cryptography

- Replace static-key message encryption with a reviewed protocol that provides forward secrecy and key evolution.
- Add explicit key-change handling and a robust identity verification experience.
- Remove or safely migrate the legacy Base64 plaintext fallback.
- Add message delivery/read states if required.
- Add robust pagination and synchronization behavior.
- Decide whether to implement real-time WebSocket events or keep HTTP polling.
- Add attachment encryption and upload handling before image/file messaging.

### Account and identity

- Test the complete recovery flow across fresh installs and multiple devices.
- Define a secure identity migration and key-rotation process.
- Add clear warnings for users who lose their recovery phrase.
- Review username/RTK ID enumeration and abuse controls.
- Add broader rate limits and abuse prevention for unauthenticated endpoints.

### Backend and operations

- Add automated API and model tests.
- Add explicit database migrations.
- Configure production HTTPS, reverse proxy and process supervision.
- Review CORS, request limits, logging, monitoring and backup policy as deployment architecture evolves.
- Add operational documentation after the actual `deployment/` source is audited.
- Audit admin-panel permissions and functionality after its source is included.

### Product scope

The inspected source is focused on private one-to-one communication. Public rooms, group chats, media attachments, voice calls and video calls were not present in the supplied source snapshot.

---

## Security reporting

If you discover a security issue, do not publish exploit details or private user data in a public issue.

Contact the project maintainer privately through a verified project-maintainer channel. Include:

- A concise description of the issue.
- Affected component and version/commit.
- Reproduction steps that do not expose real user data.
- Potential impact.
- A suggested mitigation, if known.

Do not submit real recovery phrases, private keys, passwords, session tokens or production database contents.

---

## Contributing

Contributions should preserve the project's privacy and security goals.

Before submitting a change:

1. Explain the problem being solved.
2. Keep API and database changes documented.
3. Add tests for new behavior and security-sensitive paths.
4. Do not commit `.env`, credentials, private keys, recovery phrases, databases or user data.
5. Avoid claiming a security property unless the implementation and tests support it.
6. Build the Android app and run backend checks relevant to the change.
7. Update this README and the architecture/security documents when behavior changes.

---

## License

See [`LICENSE`](LICENSE) for the license text and terms that apply to this repository. Review the file before redistributing or incorporating the project into another product.

---

**RPchat — Private identity. Direct communication.**
