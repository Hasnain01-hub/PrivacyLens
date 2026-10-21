# PrivacyLens

It covers the project structure, how the Android client works, how the backend is set up, and how to access the cloud server.

---

## Project Overview

The app is an Android privacy research tool that does two things:

1. **Image annotation** — intercepts the Instagram posting flow and asks the user to answer a short questionnaire about the image they are about to post.
2. **DM behaviour capture** — passively monitors Instagram Direct Messages via Android's Accessibility Service, extracts contact names and message metadata, encrypts everything on-device, and syncs it to a remote PostgreSQL database.

The original image annotation flow was built by Hampel and Tandon. The DM capture pipeline, encryption layer, REST sync, and contact classification survey were added on top of that.

---

## Android App

### Tech Stack

| Layer         | Library                                   |
| ------------- | ----------------------------------------- |
| Language      | Kotlin                                    |
| UI            | Jetpack Compose                           |
| Local DB      | Room                                      |
| Networking    | Retrofit 2 + OkHttp                       |
| Serialisation | GSON                                      |
| Crypto        | `javax.crypto` (AES-256-GCM, HMAC-SHA256) |
| Image loading | Coil                                      |
| Min API       | 26 (Android 8.0)                          |

Tested exclusively on **Instagram for Android v411.0.0.23.257**, English locale. The accessibility tree parsing will likely break on other Instagram versions — see the Fragility section below.

### Key Files

- **`InstagramAccessibilityService.kt`** — the core service. Handles all accessibility events, detects when the user is in DMs, extracts contact names and message metadata, deduplicates via SHA-256 fingerprints, and writes to Room.
- **`PvcAssistant.kt`** — the main `ComponentActivity`. Hosts all Compose screens: Welcome, Login, Register, image questionnaire flow, and the contact classification screen.
- **`ContactClassificationScreen.kt`** — the classification UI. Shows a scrollable list of contacts with five checkbox columns (Friend, Family, Colleague, Acquaintance, Ignore). Ignore is mutually exclusive with all other labels.
- **`RemoteApiClient.kt`** — Retrofit setup and data classes for the three REST endpoints (`/contacts`, `/messages`, `/contact-relations`). The base URL is hardcoded as `http://141.5.108.226:8080/` — update this if the server moves.

### Encryption Pipeline

Before any personal data leaves the device:

1. A 256-bit AES key is derived by SHA-256 hashing a stored passphrase.
2. Each contact name is pseudonymised via HMAC-SHA256(name, key) — this is what the server uses as its record key, so the server never sees plaintext names.
3. Contact names and message text are encrypted with AES-256-GCM (fresh 12-byte IV per operation). Ciphertext is stored as `Base64(IV || ciphertext || tag)`.
4. Message fingerprints (SHA-256 of contact + direction + content + date) are sent in cleartext — they contain no reversible information.

> **Known limitation:** The key derivation is a single SHA-256 pass over the passphrase, which is not suitable for production. A real deployment should replace this with PBKDF2 or Argon2 with a stored salt.

### Fragility / Things That Will Break

- **Instagram view IDs are undocumented and change with app updates.** IDs like `direct_text_message_text_view` or `row_thread_user_name` may be renamed or removed without notice. When the parser stops capturing data, this is the first thing to check. The service logs unrecognised nodes — check logcat filtered by `InstagramAccessibilityService`.
- **Timestamp parsing assumes English locale.** Formats like "Today 14:30", "Mon 14:30", and "March 15" are handled, but non-English devices will produce incorrect timestamps.
- **Message direction is heuristic.** The service looks for "you sent" / "sent you" in ancestor node content descriptions. As a fallback it uses screen-position (right-aligned = outgoing). This can misclassify on unusual screen sizes or when accessibility zoom is active.

---

## REST API (Backend)

### Infrastructure

- Hosted on a **GWDG cloud VM** (University of Göttingen / Max Planck Society)
- Ubuntu 22.04.5 LTS, x86_64, SSD-backed, `m1.medium` flavour
- IP: `141.5.108.226`, port `8080`

### Stack

- **FastAPI** (Python) — exposes the REST endpoints
- **PostgreSQL 15** — stores encrypted payloads only (no plaintext ever reaches the server)
- Both services run as **Docker containers** orchestrated with Docker Compose

### Endpoints

All endpoints require a bearer token in the `Authorization` header.

| Method | Path                 | Description                                |
| ------ | -------------------- | ------------------------------------------ |
| POST   | `/contacts`          | Upsert a contact (keyed by HMAC pseudonym) |
| POST   | `/messages`          | Upsert a message (keyed by fingerprint)    |
| POST   | `/contact-relations` | Upsert a contact relationship label        |

All endpoints use upsert semantics so the Android client can safely retry after a failed sync.

---

## Accessing the Cloud Server

### SSH Login

```bash
ssh -i privacy-instance1.pem -o IdentitiesOnly=yes cloud@141.5.108.226
```

### Navigating the Server

```bash
# Go to the API folder
cd /opt/privacyassistant
```

### Docker Commands

```bash
# Stop the running containers
docker-compose down

# Rebuild and start in the background (use this after any code change)
docker-compose up -d --build

# View API logs
docker logs privacyassistant_api_1

# Follow logs in real time
docker logs -f privacyassistant_api_1
```

### Database Access

```bash
# Open a psql session inside the PostgreSQL container
docker exec -it privacyassistant_postgres_1 psql -U appuser -d privacyassistant
```

Useful psql commands once inside:

```sql
-- List all tables
\dt

-- Check how many contacts are stored
SELECT COUNT(*) FROM contacts;

-- Check how many messages are stored
SELECT COUNT(*) FROM messages;

-- Exit psql
\q
```

---

## Planned Next Step: Story Viewer Capture

The next feature to implement is capturing Instagram Story viewer data. When a user swipes up on their own active story, Instagram shows a list of accounts that viewed it. The Accessibility Service should be extended to detect this viewer sheet, extract viewer names and elapsed-time timestamps, deduplicate with SHA-256 fingerprints, store in two new Room tables (`stories` and `story_viewers`), encrypt on-device using the existing pipeline, and sync via a new `/story-viewers` endpoint. See the paper's Future Work section for more detail.

---

## Contact

For questions about the original image annotation flow, contact Jakob Hampel and Mudit Tandon. For the DM capture extensions, contact Mohammad Hasnain Imtiyazali Sayyed and Denzel Wilson Thoppil at the Institute of Computer Science, University of Göttingen.
