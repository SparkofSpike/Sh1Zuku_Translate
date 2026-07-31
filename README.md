# ShizukuTranslate

> AI-powered novel translation tool with OCR image recognition and CI/CD auto-deploy.

Translate Japanese/Korean light novels and web novels into Chinese using DeepSeek AI, with PaddleOCR for image text extraction.

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
| **OCR** | Python, Flask, PaddleOCR (Japanese model) |
| **AI** | DeepSeek API (v4-flash / v4-pro) |
| **CI/CD** | GitHub Actions — auto-build + SCP deploy to Windows server |

## Features

- **Text Translation** — Translate novels via DeepSeek API with customizable system prompts
- **Preset System** — Pre-configured prompts for specific series/characters (e.g., character name mappings)
- **SSE Streaming** — Real-time typewriter-style translation output
- **OCR Recognition** — Upload screenshots of Japanese text, auto-extract with PaddleOCR
- **User System** — JWT-based auth with admin/user roles
- **Translation History** — Browse and review past translations
- **Feedback Survey** — Rate translation quality and suggest improvements
- **Auto Deploy** — Push to `main` → GitHub Actions builds and deploys to your server

## Development

### Prerequisites

- **JDK 21** — for Spring Boot backend
- **Node.js 20+** — for Vue frontend
- **Python 3.12** — for OCR worker (PaddleOCR requires 3.12)
- **Maven** — for Java build

### Quick Start (Local Dev)

#### 1. Start OCR Service

```powershell
# Install dependencies
pip install paddlepaddle==3.3.1 paddleocr==3.7.0 flask

# Start OCR (port 5557)
cd ocr-worker
python ocr_server.py
```

#### 2. Start Backend

```powershell
cd ShizukuTranslate
mvn spring-boot:run
```

#### 3. Start Frontend Dev Server

```powershell
cd ShizukuTranslate-frontend
npm install
npm run dev
```

Open `http://localhost:5173` in your browser.

### Building for Production

```powershell
# Build frontend
cd ShizukuTranslate-frontend
npm run build

# Copy to backend static
xcopy dist ..\ShizukuTranslate\src\main\resources\static /E /I

# Build backend jar
cd ..\ShizukuTranslate
mvn clean package -DskipTests
```

## Deployment

### CI/CD Pipeline (GitHub Actions)

Pushing to `main` triggers automatic deployment:

```
push → Checkout → Build Vue → Maven package → SCP to server → Restart services
```

### GitHub Secrets Required

| Secret | Description |
|--------|-------------|
| `SERVER_HOST` | Server IP/domain |
| `SERVER_PORT` | SSH port |
| `SERVER_USER` | SSH username |
| `SERVER_SSH_KEY` | SSH private key |
| `SERVER_PATH` | Deployment directory (e.g., `D:\Sh1ZukuTranslate`) |

### Manual Deploy

Run `deploy.bat` or `start-dev.bat` (local Windows) or `start.sh` (Linux).

## Project Structure

```
Sh1Zuku_Translate/
├── ShizukuTranslate/           # Java Spring Boot backend
│   ├── src/main/java/com/shizuku/translate/
│   │   ├── config/             # App, CORS, DeepSeek, Security configs
│   │   ├── controller/         # REST controllers (Translate, OCR, Auth, etc.)
│   │   ├── dto/                # Request/response DTOs
│   │   ├── entity/             # JPA entities (User, TranslationRecord, Survey)
│   │   ├── exception/          # Global exception handler + custom exceptions
│   │   ├── integration/        # DeepSeek API client
│   │   ├── repository/         # JPA repositories
│   │   ├── security/           # JWT token provider + auth filter
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
│       └── views/              # Page views (Translate, History, Survey, Admin)
├── ocr-worker/                 # Python OCR microservice
│   ├── config.py               # Environment-based configuration
│   ├── ocr_server.py           # Flask entry point
│   └── ocr_service.py          # PaddleOCR wrapper
└── .github/workflows/
    └── deploy.yml              # CI/CD auto-deploy pipeline
```

## Configuration

Key environment variables for production:

| Variable | Default | Description |
|----------|---------|-------------|
| `DEEPSEEK_API_KEY` | (hardcoded fallback) | DeepSeek API key |
| `JWT_SECRET` | `this-is-a-default-secret-key` | JWT signing key |
| `OCR_PORT` | `5557` | OCR service port |
| `OCR_THRESHOLD` | `0.3` | OCR confidence threshold |

## License

MIT
