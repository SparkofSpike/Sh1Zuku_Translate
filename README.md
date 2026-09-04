# ShizukuTranslate

ShizukuTranslate is an AI translation service for Japanese and Korean novels. The repository contains a Vue web application, a Spring Boot API, a Python OCR worker, and a Chrome/Edge Manifest V3 extension for translating Pixiv novels in place.

## Features

### Web application

- **Novel translation** through DeepSeek, OpenAI-compatible, or Anthropic-compatible model endpoints.
- **Personal model profiles** with reusable provider API keys: one personal API key can be shared by multiple model profiles. The profile page can proxy provider model-list detection and still permits manual model names when detection is unavailable.
- **Streaming translation** over Server-Sent Events (SSE), with cancellation support in the web UI.
- **Translation cache** for streaming requests. Cache entries are keyed by user, provider, endpoint, model, prompt, and source text, and are removed after 30 days.
- **Preset prompts** for series-specific terminology, plus an optional custom prompt.
- **Novel translation attachments**: upload TXT/MD files for automatic text parsing, or upload an image and choose **model processing** with `deepseek-v4-flash-vision-exp` or **OCR processing** through the PaddleOCR worker. Word/PDF files are not supported yet.
- **Accounts and access control** with JWT login, API keys for the browser extension, email verification codes, and administrator-only usage and announcement management.
- **Translation history** stored per user.
- **Token usage tracking** for live model calls, with personal totals and administrator charts, per-user summaries, and detailed logs.
- **Markdown announcements** rendered in the web application. Announcement content is stored as Markdown and raw HTML is not executed.
- **Feedback submission** through the authenticated survey endpoint.

### Browser extension

The `tranShilator-plugin/` directory contains the **Pixiv Novel Translator** Chrome/Edge Manifest V3 extension. The current manifest version is `1.4.0` (build metadata in `version.js` is generated from the release commit).

The extension supports:

- A floating side-panel translation view (used as a fallback when inline paragraphs cannot be located).
- Inline translation under paragraphs on the current Pixiv page (the default display mode).
- Full-novel inline translation with global paragraph IDs mapped across Pixiv page breaks.
- Paged translation that preserves Pixiv's `[newpage]` markers.
- Chinese, English, and Korean output.
- Site DeepSeek models and the user's saved model profiles.
- Multiple server URL presets, selectable translation presets, and a custom prompt.
- Optional DeepSeek thinking mode.
- Automatic translation, per-tab cancellation, and service-worker keep-alive alarms for long requests.
- One-click retranslation, targeted repair of missing numbered paragraphs, and local error-log submission from the popup.
- A history button that opens the web application's translation history.
- Release update checks at browser startup and every six hours, plus a manual update check in the popup.

The extension can translate public Pixiv novels without a Pixiv login; login-gated novels additionally require an authenticated Pixiv session. All translations require a reachable ShizukuTranslate backend URL and a user-generated extension API key. Because users may configure any HTTP(S) deployment and change it without rebuilding the extension, the Manifest V3 package requests HTTP/HTTPS host access. The extension uses that access for the configured backend and Pixiv requests; it does not inject content scripts into arbitrary sites. Unpacked browser extensions cannot silently replace their own files, so updates must be applied through the bundled Windows updater and then reloaded in the browser's extension management page.

## Architecture

```text
+----------------------+       +------------------------+       +----------------------+
| Vue 3 web application| ----> | Spring Boot API        | ----> | DeepSeek or          |
| TypeScript / Vite    | <---- | JWT and API-key auth   |       | compatible provider  |
+----------------------+       | SSE / JPA / H2         |       +----------------------+
                               +-----------+------------+
                                           |
                                           v
                               +------------------------+
                               | Python OCR worker      |
                               | Flask / PaddleOCR      |
                               +------------------------+

+----------------------+       +------------------------+
| Pixiv novel page     | ----> | MV3 extension          |
| content.js           | <---- | background.js          |
+----------------------+       +-----------+------------+
                                           |
                                           v
                               ShizukuTranslate SSE API
```

### Technology stack

| Layer | Technology |
|---|---|
| Web frontend | Vue 3, TypeScript, Vite, Pinia, Vue Router, Axios |
| Backend | Java 21 source/target, Spring Boot 3.2.0, Spring Data JPA, H2, Spring Security, JWT |
| OCR worker | Python, Flask, PaddleOCR with the Japanese model |
| AI integration | DeepSeek API, OpenAI-compatible chat completions, Anthropic Messages API |
| Browser extension | Chrome/Edge Manifest V3, vanilla JavaScript, SSE |
| Windows updater | .NET 8 Windows Forms, self-contained `win-x64` executable |

## Extension data flow

```text
1. content.js extracts the Pixiv novel ID and sends it to background.js.
2. background.js fetches the novel from https://www.pixiv.net/ajax/novel/{id}.
3. background.js sends the source text to /api/v1/translate/stream with X-API-Key.
4. The backend calls the selected model provider and returns SSE events.
5. background.js forwards tokens to content.js, which renders the selected view.
```

The extension makes a best-effort `PHPSESSID` check before requesting a novel and includes browser-managed Pixiv credentials for login-gated content. It does not expose the user's configured model API keys to the extension: model profile responses requested with an extension API key omit API key previews.

## Installing the extension

### Install from a release

On Windows, download a release package and run the bundled `tranShilator-plugin/CheckUpdate.exe`. The updater downloads the latest release zip and installs the extension under:

```text
%LOCALAPPDATA%\PixivNovelTranslator\tranShilator-plugin
```

Then:

1. Open `edge://extensions` or `chrome://extensions`.
2. Enable Developer mode.
3. Choose **Load unpacked** and select the installed extension directory.
4. Log in to Pixiv, open the extension popup, and configure the backend URL and extension API key.

The standalone updater supports:

```text
CheckUpdate.exe --path <extension-folder>
CheckUpdate.exe --force
CheckUpdate.exe --no-pause
```

`--force` reinstalls the current version. `--no-pause` is retained for script compatibility. The updater checks the release version and validates the GitHub asset SHA-256 digest when one is available. It keeps a timestamped backup of the previous extension directory. After updating, click **Reload** on the extension card and refresh the Pixiv tab.

### Load the repository directory

For local development, enable Developer mode on the browser extension page, choose **Load unpacked**, and select the repository's `tranShilator-plugin/` directory. Configure the backend URL and extension API key in the popup.

### Build the Windows updater

The repository includes a self-contained .NET 8 Windows GUI updater. On Windows with the .NET 8 SDK installed:

```powershell
dotnet publish tranShilator-plugin/updatechecking/CheckUpdate.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o tranShilator-plugin
```

The resulting `tranShilator-plugin/CheckUpdate.exe` is placed beside the extension files and does not require .NET to be installed on the user's machine.

## Getting started

### Prerequisites

- **JDK 21** for the backend compiler configuration.
- **Node.js 20+** and npm for the frontend.
- **Python 3.12** for the OCR worker. The deployment script itself requires a modern Python version supporting the repository's type-hint syntax.
- **Maven** for the backend build.
- **OpenSSH** for the deployment workflow.
- A **DeepSeek API key** if the server should provide the default DeepSeek models or fallback service key.

### 1. Install and start the OCR worker

The OCR source code imports PaddlePaddle and PaddleOCR and initializes the Japanese model. Install those packages explicitly:

```powershell
cd ocr-worker
python -m pip install -r requirements.txt
python ocr_server.py
```

The worker listens on `http://localhost:5557` by default and provides:

- `GET /health`
- `POST /ocr` with a multipart file field named `image`

Use `OCR_PORT` and `OCR_THRESHOLD` to override the default port and confidence threshold. The first PaddleOCR startup may download model files. Install a PaddlePaddle build compatible with the installed Python version if the generic package is unavailable for the platform.

> `ocr-worker/requirements.txt` pins the PaddleOCR and PaddlePaddle versions used by this repository. If a platform does not provide these exact wheels, use a separately verified environment rather than silently upgrading production dependencies.

### 2. Start the backend

```powershell
cd ShizukuTranslate
$env:DEEPSEEK_API_KEY = "your-deepseek-api-key"
mvn spring-boot:run
```

The backend listens on `http://localhost:5566` and exposes the API under `http://localhost:5566/api/v1`. It serves the frontend from `src/main/resources/static` when a production frontend build has been copied there.

### 3. Start the frontend development server

```powershell
cd ShizukuTranslate-frontend
npm ci
npm run dev
```

Vite listens on `http://localhost:5173`. The frontend uses `http://localhost:5566/api/v1` by default. Set `VITE_API_BASE_URL` before starting Vite to use another backend:

```powershell
$env:VITE_API_BASE_URL = "http://localhost:5566/api/v1"
npm run dev
```

The About page receives the build timestamp and short Git commit from `vite.config.js` during a frontend build.

## Model provider configuration

The web application's profile page supports multiple saved model profiles and reusable personal provider API keys. Each profile contains:

- A display name.
- A provider protocol: `deepseek`, `openai`, or `anthropic`.
- A model name.
- A reference to a personal API key (legacy inline keys are migrated lazily).
- A base URL for compatible providers.

One API key may be used by multiple profiles. The authenticated endpoint `POST /auth/model-profiles/detect` proxies the provider's `/models` endpoint; model detection is optional and manual model entry remains supported. Model selectors use slash-separated labels such as `站方/DeepSeek/deepseek-v4-flash`, `站方/DeepSeek/deepseek-v4-pro`, `站方/DeepSeek/deepseek-v4-flash-vision-exp（视觉）`, and `我的配置/openai/gpt-5.6-sol`.

The defaults are:

| Provider | Default base URL | API key behavior |
|---|---|---|
| DeepSeek | The server's `deepseek.api.base-url` setting | An empty personal key falls back to `DEEPSEEK_API_KEY` |
| OpenAI-compatible | `https://api.openai.com/v1` | A personal API key is required |
| Anthropic-compatible | `https://api.anthropic.com/v1` | A personal API key is required |

DeepSeek requests use `/chat/completions`. OpenAI-compatible requests use the same format. Anthropic-compatible requests use `/messages` and translate Anthropic usage fields into the application's token usage format. API keys are not returned in full; the authenticated web profile shows a masked preview, while extension model-profile responses omit the preview.

## Production build and deployment

To build a backend JAR containing the latest frontend assets:

```powershell
# Build the frontend
cd ShizukuTranslate-frontend
npm ci
npm run build

# Replace the backend static directory
Remove-Item ..\ShizukuTranslate\src\main\resources\static -Recurse -Force -ErrorAction SilentlyContinue
New-Item ..\ShizukuTranslate\src\main\resources\static -ItemType Directory -Force
Copy-Item dist\* ..\ShizukuTranslate\src\main\resources\static\ -Recurse

# Package the backend
cd ..\ShizukuTranslate
mvn clean package -DskipTests
```

The repository's local deployment workflow is:

```powershell
cd ..
python tools/ship.py
```

`tools/ship.py` uses the server, SSH, and Windows paths hard-coded at the top of the script. Unless `--skip-pull` is supplied, it pulls the latest Git revision, installs frontend dependencies with `npm ci`, builds the frontend, copies the generated assets into the backend static directory, packages the backend and OCR worker, uploads the deployment package over SSH, and restarts the remote backend task. The script also packages `.github/workflows/deploy.ps1` when present.

Useful options:

```text
python tools/ship.py --skip-pull
python tools/ship.py --upload-only
python tools/ship.py --help
```

`--upload-only` expects an existing `deploy_package/` and still performs remote upload/restart. The deployment script requires the configured local SSH key and access to the target Windows server; review its server settings before using it for another environment. The workflow has remote side effects and is not a portable local-only build command.

## Project structure

```text
Sh1Zuku_Translate/
├── ShizukuTranslate/           # Spring Boot backend and production static files
│   ├── src/main/java/com/shizuku/translate/
│   │   ├── config/             # Application, CORS, model, and security configuration
│   │   ├── controller/         # Auth, translation, OCR, history, survey, admin, and announcement APIs
│   │   ├── dto/                # Request and response DTOs
│   │   ├── entity/             # JPA entities, model profiles, cache, and usage logs
│   │   ├── exception/          # Global exception handler and custom exceptions
│   │   ├── integration/        # AI provider clients and protocol adapters
│   │   ├── repository/         # Spring Data repositories
│   │   ├── security/           # JWT and API-key authentication filters
│   │   └── service/            # Translation, OCR, user, survey, usage, and announcement services
│   └── src/main/resources/
│       ├── application.yml     # Runtime settings and prompt presets
│       └── static/              # Copied production frontend assets
├── ShizukuTranslate-frontend/  # Vue 3 and TypeScript frontend
│   └── src/
│       ├── api/                # Axios API client and SSE streaming
│       ├── components/         # OCR, preset, result, and announcement components
│       ├── router/              # Vue Router routes and auth guards
│       ├── stores/              # Pinia stores
│       ├── types/              # TypeScript interfaces
│       ├── utils/              # Shared utilities, including Markdown rendering
│       └── views/               # Translation, history, profile, survey, logs, admin, and auth pages
├── ocr-worker/                 # Python Flask and PaddleOCR microservice
│   ├── config.py               # Environment-based port and threshold settings
│   ├── ocr_server.py           # Flask entry point
│   ├── ocr_service.py          # PaddleOCR wrapper and line merging
│   ├── requirements.txt        # Currently stale Flask/EasyOCR dependency list
│   └── install_ocr.md           # OCR deployment notes
├── tranShilator-plugin/        # Chrome/Edge extension and CheckUpdate.exe
│   └── updatechecking/         # .NET updater source project
└── tools/                      # Local-only tooling (git-ignored)
    ├── build_extension.py      # Generates extension build metadata
    └── ship.py                 # Local build, packaging, upload, and restart workflow
```

## Configuration

### Backend environment variables

| Variable | Description |
|---|---|
| `DEEPSEEK_API_KEY` | Server-wide DeepSeek key. Required when using the site-provided DeepSeek models or when a DeepSeek profile has no personal key. |
| `JWT_SECRET` | **Required in every deployment.** Use a strong random value; blank and known weak defaults are rejected, so the backend will not start without it. |
| `JWT_ISSUER` | JWT issuer claim. Default: `shizuku-translate`. |
| `MAIL_HOST` | SMTP host for verification-code emails. Leave blank to disable sending (registration and email verification then fail with a clear error). |
| `MAIL_PORT` | SMTP port. Default: `465` (SSL). |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | SMTP account credentials. |
| `MAIL_FROM` | From address for verification emails. Defaults to `MAIL_USERNAME`. |
| `MAIL_SSL` | Use implicit SSL/TLS (true) or STARTTLS (false). Default: `true`. |
| `CORS_ALLOWED_ORIGIN_PATTERNS` | Comma-separated allowed browser origins. Default: localhost frontend/backend origins. |
| `STREAM_CORE_POOL_SIZE` | Core threads for streaming translations. Default: `4`. |
| `STREAM_MAX_POOL_SIZE` | Maximum streaming translation threads. Default: `16`. |
| `STREAM_QUEUE_CAPACITY` | Queued streaming requests before rejection. Default: `64`; saturation returns a clear request-rejection error. |
| `OCR_PORT` | OCR worker port. Default: `5557`. |
| `OCR_THRESHOLD` | OCR confidence threshold. Default: `0.3`. |
| `VITE_API_BASE_URL` | Frontend build/development API base URL override. Default: `http://localhost:5566/api/v1`. |

Other runtime defaults in `application.yml`:

- Backend HTTP port: `5566`.
- DeepSeek base URL: `https://api.deepseek.com/v1`.
- Default DeepSeek model: `deepseek-v4-flash`.
- Vision model: `deepseek-v4-flash-vision-exp`; only this visual-capable model can use image model processing. Image OCR processing remains available through the OCR worker.
- DeepSeek thinking mode: disabled by default; requests may override it.
- OCR worker URL: `http://localhost:5557`.
- H2 file database: `./data/translatordb`.
- Multipart limits: 20 MB per file and 25 MB per request.
- Translation cache cleanup: entries older than 30 days are removed daily at 03:00.
- Administrator usernames: configured by `app.admin-usernames` in `application.yml`.

### Email verification

Registration requires a six-digit code delivered to the address by email, which prevents throwaway (fake-mailbox) accounts from registering and draining the server's token budget. Accounts created before this feature was enabled are unverified (`email_verified` is NULL for existing rows); they keep their history and settings but receive HTTP 403 from the translation endpoints and from extension API-key creation until they verify the address on the profile page. The profile page can also change the account email, which sends a code to the new address and only then switches and verifies it. Configured administrator usernames are exempt from the verification gate.

Codes are stored hashed in memory, expire after 10 minutes, are single-use, and are invalidated after five failed attempts. Sending is rate limited with a 60-second resend cooldown, a daily cap per address, a per-IP window cap, and a global hourly cap.

### Persistence and token usage

The backend uses an H2 file database with Hibernate schema updates enabled. The database contains users, translation history, model profiles, API keys, announcements, translation cache entries, survey records, and token usage logs.

Live model responses that report token usage are logged with the provider, model, input tokens, output tokens, total tokens, source type, estimate flag, and timestamp. Cache hits return cached translations without creating a new live provider usage event. On startup, the historical migration service can backfill cache usage and estimate older translation records that do not contain provider usage data; estimated entries are marked separately in the administrator log view.

### Main API routes

All backend API routes use the `/api/v1` prefix. JWT-authenticated requests use `Authorization: Bearer <token>`. The browser extension uses `X-API-Key`.

| Route | Access | Purpose |
|---|---|---|
| `POST /auth/register` | Public | Register an account. Requires a verification code sent to the email address, so the address must be reachable; new accounts are created email-verified. |
| `POST /auth/email/send-code` | Public | Send a six-digit verification code to an email address (rate limited per address and IP). |
| `POST /auth/email/verify` | Authenticated | Verify the code for the account address; also updates the account when the address changed. |
| `POST /auth/login` | Public | Return a JWT. |
| `GET /auth/me` | Authenticated | Return the current username, administrator status, and email-verification status. |
| `GET /auth/profile` | Authenticated | Return profile (including `emailVerified`) and masked model configuration information. |
| `GET /auth/model-profiles` | Authenticated or extension API key | List the current user's model profiles. Extension requests omit key previews. |
| `POST /auth/model-profiles/detect` | Authenticated | Proxy a provider `/models` request for optional model-name detection. |
| `POST /auth/model-profiles` | Authenticated | Create a model profile. |
| `PUT /auth/model-profiles/{id}` | Authenticated | Update a model profile. |
| `DELETE /auth/model-profiles/{id}` | Authenticated | Delete a model profile. |
| `PUT /auth/profile/model` | Authenticated | Legacy single-profile configuration endpoint. |
| `PUT /auth/profile/ai-key` | Authenticated | Legacy personal API key endpoint. |
| `GET /auth/usage` | Authenticated | Return the current user's token usage summary and charts. |
| `POST /auth/api-key` | Authenticated + email verified | Generate a browser-extension API key. |
| `GET /auth/api-keys` | Authenticated | List the user's extension API keys. |
| `DELETE /auth/api-key/{id}` | Authenticated | Revoke an extension API key. |
| `POST /translate` | Authenticated or extension API key + email verified | Perform a non-streaming translation. Unverified accounts receive HTTP 403. |
| `POST /translate/stream` | Authenticated or extension API key + email verified | Stream a translation over SSE. Unverified accounts receive HTTP 403. |
| `GET /translations` | Authenticated | List the current user's translation history. |
| `GET /translations/{id}` | Authenticated | Read one history record owned by the current user. |
| `POST /ocr` | Authenticated | Proxy an image to the OCR worker. |
| `POST /translate/image` | Authenticated + email verified | Translate an uploaded image with the visual model-processing mode. |
| `GET /ocr/health` | Authenticated | Check the OCR worker through the backend. |
| `GET /presets` | Public | Return configured preset names. |
| `GET /announcements` | Public | Return announcements in reverse chronological order. |
| `POST /survey` | Authenticated | Submit translation and experience feedback. |
| `GET /stats/users` | Authenticated | Return the registered-user count. |
| `GET /admin/usage` | Administrator | Return global token totals, charts, and per-user summaries. |
| `GET /admin/usage/users/{userId}` | Administrator | Return one user's token log. |
| `POST /admin/announcements` | Administrator | Publish an announcement as raw Markdown text. |
| `POST /plugin/logs` | Authenticated or extension API key | Submit browser-extension error reports. |
| `GET /plugin/logs` | Authenticated | List the current user's reports; administrators see all reports. |
| `DELETE /admin/announcements/{id}` | Administrator | Delete an announcement. |

## Known limitations

- The announcement renderer supports a safe Markdown subset implemented in the frontend; raw HTML is escaped rather than rendered.
- The frontend package defines `dev`, `build`, and `preview` scripts but no test or typecheck script; `npm run build` is the available frontend verification command.
- The backend exposes the H2 console at `/h2-console` in the checked-in configuration; protect or disable it before exposing the service outside a trusted environment.
- H2 file storage is convenient for this deployment but is not a replacement for a production database with stronger operational tooling.
- The browser extension is distributed as an unpacked extension rather than through a browser store, so users must reload it after updates.
- The extension requests broad HTTP/HTTPS host access because the backend URL is user-configurable and may change independently of the extension release; requests are limited in code to the configured backend and Pixiv flows.
- Upstream cancellation is cooperative: disconnecting or cancelling an SSE request interrupts the backend worker and stops reading the model response; providers that do not react immediately to a closed HTTP connection may continue processing briefly.
- Model providers may impose their own rate limits, context limits, outages, or content policies.

## License

[MIT](LICENSE)
