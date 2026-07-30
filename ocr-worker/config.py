"""Configuration management for the OCR worker microservice.

Reads settings from environment variables with sensible defaults.
"""

import os
from dataclasses import dataclass, field


@dataclass
class Config:
    """Application configuration, populated from environment variables.

    Attributes:
        port: HTTP port for the Flask server (env: OCR_PORT, default: 5557).
        threshold: Confidence threshold for OCR results (env: OCR_THRESHOLD, default: 0.3).
    """

    port: int = field(
        default_factory=lambda: int(os.environ.get("OCR_PORT", "5557"))
    )
    threshold: float = field(
        default_factory=lambda: float(os.environ.get("OCR_THRESHOLD", "0.3"))
    )
