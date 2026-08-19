# ShizukuTranslate

An AI-powered novel translation tool that translates Japanese / Korean light novels into Chinese, with OCR support for extracting text straight from screenshots.

## Features

- **Novel translation** — Powered by the DeepSeek API, with a default model of `deepseek-v4-flash` and optional AI-reasoning mode per request
- **Preset system** — Pre-configured prompts for specific series and character-name mappings (e.g. 超时空辉夜姬！), fully customizable per request
- **SSE streaming output** — Real-time typewriter-style translation, with caching so repeated translations return instantly
- **OCR image recognition** — Upload or paste a screenshot of Japanese text; PaddleOCR extracts it and the translation pipeline takes over
- **Account system** — JWT-based registration/login with an admin role
- **User profile** — Configure your own DeepSeek API key (falls back to the site key when empty) and generate API keys for the browser extension
- **Registration agreement** — First-time users must scroll through and accept the terms before registering
- **Translation history** — Full-text history of past translations, browsable on the web
- **Feedback survey** — Rate translation quality and suggest improvements
- **Browser extension** — A Chrome/Edge MV3 extension (`pixiv-novel-translator/`) that translates Pixiv novels in place, in four display modes (side panel, inline, inline-full, paged)

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

### Tech Stack

| Layer | Technology |
|-------|-----------|
| **Frontend** | Vue 3, TypeScript, Vite, Pinia, Vue Router, Axios |
| **Backend** | Java 21, Spring Boot 3.2, Maven, JPA/H2, JWT |
| **OCR** | Python 3.12, Flask, PaddleOCR (Japanese model) |
| **AI** | DeepSeek API (`deepseek-v4-flash`) |

### Browser Extension

The `pixiv-novel-translator/` Chrome/Edge MV3 extension translates Pixiv novels without leaving the page:

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
│   (X-API-Key auth)     │  ③ SSE  │  + per-tab abort control           │
│   ──▶ DeepSeek API     │  stream  │  + chrome.cookies PHPSESSID        │
└────────────────────────┘         └───────────┬─────────────────────────┘
                                               │ ② bare fetch source text
                                               ▼
                                     ┌──────────────────────┐
                                     │  pixiv.net AJAX API  │
                                     │  /ajax/novel/{id}    │
                                     └──────────────────────┘
```

Translation runs as an SSE stream through the site backend, authenticated with a user-generated API key (see the **Profile** page). Results can be displayed as a side panel, inline under each original paragraph, or with Pixiv's `[newpage]` breaks preserved.

### Installing the extension

The extension is distributed from this repository (not browser stores). Browsers forbid non-store extensions from silently replacing their own code, so the extension uses background update detection plus a one-click Windows updater:

- **Automatic update detection:** the background service worker checks the latest GitHub release when the browser starts and every 6 hours. A new-version badge and one OS notification are shown when an update is found.
- **One-click updater (`CheckUpdate.exe`):** download `CheckUpdate.exe` from the latest Release and double-click it. It automatically checks the version, downloads the matching extension zip, verifies the package when GitHub provides a SHA-256 digest, installs it to `%LOCALAPPDATA%\PixivNovelTranslator\pixiv-novel-translator\`, keeps a backup of the previous copy, and opens the extension management page.
- **Command-line options:** `CheckUpdate.exe --chrome` opens Chrome's extension page; `CheckUpdate.exe --path <folder>` updates a custom unpacked-extension directory; `--force` reinstalls the same version; `--no-pause` is useful in scripts. `install.cmd` automatically uses `CheckUpdate.exe` when it is beside the script, and otherwise falls back to PowerShell.
- **Manual install:** open `edge://extensions` (or `chrome://extensions`), enable **Developer mode**, click **Load unpacked**, and select the `pixiv-novel-translator/` folder.
- **Applying an update:** click the notification or the popup's **检查更新** result to open the release page, run `CheckUpdate.exe`, then click the reload icon on the extension card in `edge://extensions` and refresh the Pixiv page.

## Building the Windows updater

The repository includes a self-contained .NET 8 updater. On Windows with the .NET 8 SDK installed:

```powershell
dotnet publish CheckUpdate.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o CheckUpdate-publish
```

The resulting `CheckUpdate-publish/CheckUpdate.exe` can be distributed beside `install.cmd` or uploaded as a Release asset. It does not require .NET to be installed on the user's machine.

## Getting Started

### Prerequisites

- **JDK 21** — for the Spring Boot backend
- **Node.js 20+** — for the Vue frontend
- **Python 3.12** — for the OCR worker
- **Maven** — for the Java build
- **A DeepSeek API key** — the backend reads it from the `DEEPSEEK_API_KEY` environment variable (there is no fallback value)

### 1. Start the OCR service

```powershell
cd ocr-worker
pip install paddlepaddle paddleocr flask
python ocr_server.py      # listens on port 5557
```

### 2. Start the backend

```powershell
cd ShizukuTranslate
$env:DEEPSEEK_API_KEY = "your-deepseek-api-key"
mvn spring-boot:run       # listens on port 5566
```

### 3. Start the frontend dev server

```powershell
cd ShizukuTranslate-frontend
npm install
npm run dev               # http://localhost:5173
```

The dev server proxies API calls to `http://localhost:5566/api/v1` by default (override with `VITE_API_BASE_URL`).

## Production Build & Deploy

```powershell
# 1. Build the frontend
cd ShizukuTranslate-frontend
npm run build

# 2. Copy the build output into the backend's static resources
Remove-Item ..\ShizukuTranslate\src\main\resources\static -Recurse -Force
Copy-Item dist\* ..\ShizukuTranslate\src\main\resources\static\ -Recurse

# 3. Package the backend
cd ..\ShizukuTranslate
mvn clean package -DskipTests

# 4. Deploy with the one-click script (build → upload → restart services on the server)
cd ..
python ship.py
```

> `ship.py` handles the full deploy pipeline: it rebuilds everything, stops the old backend/OCR services, uploads the new jar, and restarts them. The deployment target is a Windows server; services are managed by Windows scheduled tasks with a watchdog that auto-recovers them if they crash.

## Project Structure

```
Sh1Zuku_Translate/
├── ShizukuTranslate/           # Java Spring Boot backend
│   ├── src/main/java/com/shizuku/translate/
│   │   ├── config/             # App, CORS, DeepSeek, Security configs
│   │   ├── controller/         # REST controllers (Translate, OCR, Auth, ...)
│   │   ├── dto/                # Request/response DTOs
│   │   ├── entity/             # JPA entities (User, TranslationRecord, ...)
│   │   ├── exception/          # Global exception handler + custom exceptions
│   │   ├── integration/        # DeepSeek API client
│   │   ├── repository/         # JPA repositories
│   │   ├── security/           # JWT + API-key auth filters
│   │   └── service/            # Translation, OCR, User, Survey services
│   └── src/main/resources/
│       └── application.yml     # App config (DB, API keys, presets)
├── ShizukuTranslate-frontend/  # Vue 3 TypeScript frontend
│   └── src/
│       ├── api/                # Axios API client with SSE streaming
│       ├── components/         # Reusable UI components
│       ├── router/             # Vue Router config
│       ├── stores/             # Pinia stores (auth)
│       ├── types/              # TypeScript interfaces
│       └── views/              # Page views (Translate, History, Profile, ...)
├── ocr-worker/                 # Python OCR microservice
│   ├── config.py               # Environment-based configuration
│   ├── ocr_server.py           # Flask entry point (port 5557)
│   └── ocr_service.py          # PaddleOCR wrapper
├── pixiv-novel-translator/     # Chrome/Edge browser extension
├── CheckUpdate.cs              # Standalone Windows updater source
└── CheckUpdate.csproj          # Self-contained CheckUpdate.exe build
```

## Configuration

Environment variables used in production:

| Variable | Description |
|----------|-------------|
| `DEEPSEEK_API_KEY` | DeepSeek API key (**required**, no default) |
| `JWT_SECRET` | JWT signing secret for the backend |
| `OCR_PORT` | OCR service port (default `5557`) |
| `OCR_THRESHOLD` | OCR confidence threshold (default `0.3`) |

Users can also set their own DeepSeek API key on the **Profile** page; per-user keys take priority over the global one, which is used as a fallback.

## License

[MIT](LICENSE)
