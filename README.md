# ShizukuTranslate

ShizukuTranslate is an AI translation tool for Japanese and Korean novels. It provides a Vue web application, a Spring Boot API, a local OCR worker for screenshots, and a Chrome/Edge extension for translating Pixiv novels in place.

## Features

### Web application

- **Novel translation** — DeepSeek-powered translation with `deepseek-v4-flash` as the default model; the web UI also offers `deepseek-v4-pro`.
- **Streaming output** — SSE streaming with typewriter-style rendering and cancellation support.
- **Translation cache** — Repeated streaming requests for the same user, model, prompt, and source text can be served from a 30-day cache.
- **Preset prompts** — Server-provided presets for series-specific terminology and character names, plus a custom prompt field.
- **OCR translation** — Upload, drag, or paste a screenshot; the PaddleOCR worker extracts the text and the web app sends it to the translation pipeline.
- **Accounts and access control** — JWT registration/login, admin-only statistics and survey management, and a registration agreement that must be read and accepted before registration.
- **Profile settings** — Users can configure a personal DeepSeek API key. When it is empty, translation falls back to the server key.
- **Translation history** — Completed translations are stored per user and can be browsed from the history pages.
- **Feedback survey** — Authenticated users can submit translation feedback.

### Browser extension

The `tranShilator-plugin/` directory contains the **Pixiv Novel Translator** Chrome/Edge Manifest V3 extension, currently version `1.2.0`. It fetches the novel through Pixiv's AJAX endpoint and streams the translation through the ShizukuTranslate backend using an `X-API-Key`.

The extension supports:

- Side panel display.
- Inline translation under the paragraphs of the current page.
- Full-novel inline translation with paragraph IDs mapped back across Pixiv page breaks.
- Paged translation that preserves Pixiv's `[newpage]` breaks.
- Chinese, English, and Korean output.
- Multiple server presets and a custom prompt.
- Optional DeepSeek thinking mode.
- Automatic translation, per-tab cancellation, and service-worker keep-alive for long novels.
- A history button that opens the web application's translation history.
- Background update checks at browser startup and every six hours, plus a manual **检查更新** action.

The extension does not silently replace its own files: browsers do not permit that for unpacked extensions. Updates are installed with the bundled Windows `CheckUpdate.exe` updater, then applied by refreshing the extension from the browser's extension management page.

## Architecture

```
┌─────────────────┐      ┌────────────────────┐      ┌───────────────┐
│  Vue 3 SPA      │────▶│  Java Spring Boot  │────▶│  DeepSeek API │
│  (TypeScript)   │◀────│  (port 5566)       │      └───────────────┘
│  + Pinia        │      │  + JWT Auth        │      ┌───────────────┐
│  + Vue Router   │      │  + H2 Database     │────▶│  Python OCR   │
└─────────────────┘      │  + SSE Streaming   │      │  Worker       │
                         └────────────────────┘      │  (port 5557)  │
                                                     │  PaddleOCR    │
                                                     └───────────────┘
```

### Tech stack

| Layer | Technology |
|-------|------------|
| **Frontend** | Vue 3, TypeScript, Vite, Pinia, Vue Router, Axios |
| **Backend** | Java 21, Spring Boot 3.2, Maven, Spring Data JPA, H2, JWT |
| **OCR** | Python 3.12, Flask, PaddleOCR with the Japanese model |
| **AI** | DeepSeek API (`deepseek-v4-flash` by default) |
| **Extension** | Chrome/Edge Manifest V3, vanilla JavaScript, SSE |
| **Updater** | .NET 8 Windows Forms, self-contained `win-x64` executable |

### Browser extension data flow

```
┌──────────────────────┐  ① extract novel_id  ┌──────────────────────────┐
│  Pixiv novel page    │───────────────────▶│  content.js (injected)   │
│  (user clicks go)    │◀───────────────────│  extracts paragraphs,    │
└──────────────────────┘  ⑤ render output    │  renders translated text │
         ▲                                    └───────────┬──────────────┘
         │                                  ② novel_id msg │
         │                                    (message)    ▼
┌────────┴───────────────┐         ┌─────────────────────────────────────┐
│   backend              │         │  background.js (MV3 service worker) │
│   /translate/stream    │◀────────│  + alarm keep-alive                 │
│   (X-API-Key auth)     │  ③ SSE  │  + per-tab abort control            │
│   ──▶ DeepSeek API     │  stream  │  + chrome.cookies PHPSESSID         │
└────────────────────────┘         └───────────┬─────────────────────────┘
                                               │ ② bare fetch source text
                                               ▼
                                     ┌──────────────────────┐
                                     │  pixiv.net AJAX API  │
                                     │  /ajax/novel/{id}    │
                                     └──────────────────────┘
```

The extension requires an authenticated Pixiv session because it reads the `PHPSESSID` cookie to request the novel. It also requires a reachable backend URL and a user-generated plugin API key from the web app's **Profile** page.

## Installing the extension

### Option 1: install from a Release

On Windows, download the latest Release package and run `tranShilator-plugin/CheckUpdate.exe` (or `install.cmd`, which delegates to the bundled updater when it is present). By default, `CheckUpdate.exe` updates the extension directory containing the updater. Use `--path` when the browser already has an unpacked extension loaded from another directory.

For a fresh install under the user's local application data directory, run `install.ps1` directly. It downloads the latest `tranShilator-plugin-*.zip` asset and installs it under:

```text
%LOCALAPPDATA%\PixivNovelTranslator\tranShilator-plugin
```

Then:

1. Open `edge://extensions` or `chrome://extensions`.
2. Enable **Developer mode**.
3. Click **Load unpacked** and select the extension directory returned by the updater or installer.
4. Open Pixiv, log in, open the extension popup, and configure the backend URL and API key.

The updater supports these options:

```text
CheckUpdate.exe --path <folder>
CheckUpdate.exe --force
CheckUpdate.exe --no-pause
```

`--force` reinstalls the same version; `--no-pause` is retained for script compatibility. The updater validates the release version and, when GitHub provides one, the asset SHA-256 digest. It keeps a timestamped backup of the previous extension directory. After an update, click the extension card's **Reload** button in the browser extension page and refresh the Pixiv tab.

### Option 2: load the repository directory

For local development, open the browser extension page, enable **Developer mode**, choose **Load unpacked**, and select the repository's `tranShilator-plugin/` directory. Configure the backend URL and API key in the popup before translating.

### Building the Windows updater

The repository includes a self-contained .NET 8 Windows GUI updater. On Windows with the .NET 8 SDK installed:

```powershell
dotnet publish tranShilator-plugin/updatechecking/CheckUpdate.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o tranShilator-plugin
```

The resulting `tranShilator-plugin/CheckUpdate.exe` is placed beside the extension files and does not require .NET to be installed on the user's machine.

## Getting started

### Prerequisites

- **JDK 21** — required by the backend compiler configuration.
- **Node.js 20+** and npm — for the Vue frontend.
- **Python 3.12** — for the OCR worker.
- **Maven** — for the Java build.
- **A DeepSeek API key** — the backend requires `DEEPSEEK_API_KEY`; there is no fallback API key in the repository.

### 1. Install and start the OCR worker

The OCR implementation uses PaddleOCR with the Japanese model:

```powershell
cd ocr-worker
python -m pip install paddlepaddle paddleocr flask
python ocr_server.py       # http://localhost:5557
```

The worker exposes `GET /health` and `POST /ocr`. The image upload field is named `image`. `OCR_PORT` and `OCR_THRESHOLD` can be used to override the worker's port and default confidence threshold.

> The first PaddleOCR startup may download model files. On Windows, install a PaddlePaddle build compatible with the installed Python version if the generic command is not available.

### 2. Start the backend

```powershell
cd ShizukuTranslate
$env:DEEPSEEK_API_KEY = "your-deepseek-api-key"
mvn spring-boot:run       # http://localhost:5566
```

The backend serves the API under `http://localhost:5566/api/v1`. It also serves the frontend build from `src/main/resources/static` when production assets have been copied there.

### 3. Start the frontend development server

```powershell
cd ShizukuTranslate-frontend
npm install
npm run dev               # http://localhost:5173
```

The frontend calls `http://localhost:5566/api/v1` by default. Set `VITE_API_BASE_URL` before starting Vite to use another backend URL:

```powershell
$env:VITE_API_BASE_URL = "http://localhost:5566/api/v1"
npm run dev
```

The frontend About page receives the build date and short Git commit automatically from `vite.config.js`.

## Production build and deployment

To build a backend jar that contains the latest frontend assets:

```powershell
# 1. Build the frontend
cd ShizukuTranslate-frontend
npm ci
npm run build

# 2. Replace the backend's static resources
Remove-Item ..\ShizukuTranslate\src\main\resources\static -Recurse -Force -ErrorAction SilentlyContinue
New-Item ..\ShizukuTranslate\src\main\resources\static -ItemType Directory -Force
Copy-Item dist\* ..\ShizukuTranslate\src\main\resources\static\ -Recurse

# 3. Package the backend
cd ..\ShizukuTranslate
mvn clean package -DskipTests
```

For the repository's configured Windows server, `python ship.py` is the local deployment workflow. It pulls the repository unless `--skip-pull` is supplied, builds the frontend and backend, packages the OCR worker, uploads the package over SSH, and restarts the services. It depends on the local SSH key and the server settings defined in that script; adapt those settings before using it for another environment.

Useful deployment options:

```text
python ship.py --skip-pull
python ship.py --upload-only
python ship.py --help
```

The legacy `deploy.bat`, `debug.bat`, and `start-dev.bat` files contain machine-specific Windows paths and are not the portable deployment interface.

## Project structure

```
Sh1Zuku_Translate/
├── ShizukuTranslate/           # Java Spring Boot backend and production static files
│   ├── src/main/java/com/shizuku/translate/
│   │   ├── config/             # Application, CORS, DeepSeek, and security config
│   │   ├── controller/         # Auth, translation, OCR, history, survey, and admin APIs
│   │   ├── dto/                # Request/response DTOs
│   │   ├── entity/             # JPA entities and translation cache
│   │   ├── exception/          # Global handler and custom exceptions
│   │   ├── integration/        # DeepSeek API client
│   │   ├── repository/         # JPA repositories
│   │   ├── security/           # JWT and API-key authentication filters
│   │   └── service/            # Translation, OCR, user, survey, and key services
│   └── src/main/resources/
│       └── application.yml     # Runtime configuration and translation presets
├── ShizukuTranslate-frontend/  # Vue 3 + TypeScript frontend
│   └── src/
│       ├── api/                # Axios API client and SSE streaming
│       ├── components/         # Image, OCR, preset, and result components
│       ├── router/              # Vue Router routes and auth guards
│       ├── stores/              # Pinia stores
│       ├── types/              # TypeScript interfaces
│       └── views/              # Translate, history, profile, survey, and admin pages
├── ocr-worker/                 # Python PaddleOCR microservice
│   ├── config.py               # Environment-based port and threshold config
│   ├── ocr_server.py           # Flask entry point
│   ├── ocr_service.py          # PaddleOCR wrapper and line merging
│   └── install_ocr.md          # OCR deployment notes
├── tranShilator-plugin/        # Chrome/Edge extension and CheckUpdate.exe
│   └── updatechecking/         # .NET updater source
├── build_extension.py          # Generates the extension build version metadata
├── install.cmd                 # Windows extension install/update entry point
└── ship.py                     # Local build/package/deployment workflow
```

## Configuration

### Backend environment variables

| Variable | Description |
|----------|-------------|
| `DEEPSEEK_API_KEY` | **Required.** Server-wide DeepSeek API key used when a user has not configured a personal key. |
| `JWT_SECRET` | JWT signing secret. The application has a development default; set a strong value in production. |
| `OCR_PORT` | OCR worker HTTP port; default `5557`. |
| `OCR_THRESHOLD` | OCR confidence threshold; default `0.3`. |

Other runtime defaults in `application.yml`:

- Backend HTTP port: `5566`.
- OCR worker URL: `http://localhost:5557`.
- H2 file database: `./data/translatordb`.
- Multipart upload limit: 20 MB per file and 25 MB per request.
- DeepSeek thinking mode: disabled by default; the extension can override it per request.
- Translation cache cleanup: entries older than 30 days are removed daily at 03:00.

Users can set a personal DeepSeek API key on the **Profile** page. The key is used for that user's translation requests and is never returned by the profile endpoint. The same page can generate, list, and revoke API keys for the browser extension.

### Main API routes

All routes are prefixed with `/api/v1`:

| Route | Purpose |
|-------|---------|
| `POST /auth/register` and `POST /auth/login` | Account registration and JWT login |
| `GET /auth/profile` and `PUT /auth/profile/ai-key` | Profile and personal DeepSeek key settings |
| `POST /auth/api-key`, `GET /auth/api-keys`, `DELETE /auth/api-key/{id}` | Browser-extension API key management |
| `POST /translate` | Non-streaming translation |
| `POST /translate/stream` | SSE streaming translation |
| `POST /ocr` and `GET /ocr/health` | OCR proxy and health check |
| `GET /translations` and `GET /translations/{id}` | Translation history |
| `GET /presets` | Public translation presets |

Authenticated API requests use the JWT `Authorization: Bearer <token>` header. The extension uses `X-API-Key: <key>`.

## License

[MIT](LICENSE)
