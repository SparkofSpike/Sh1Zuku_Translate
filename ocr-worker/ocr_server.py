"""Flask entry point for the ShizukuTranslate OCR microservice.

This module is intentionally thin — all OCR logic lives in
:mod:`ocr_service` and configuration in :mod:`config`.
"""

from flask import Flask, request, jsonify

from config import Config
from ocr_service import OcrService

config: Config = Config()
app: Flask = Flask(__name__)
ocr_service: OcrService = OcrService(config)


@app.route("/ocr", methods=["POST"])
def ocr_image():
    """Handle image upload and return OCR-extracted Japanese text.

    Expects ``multipart/form-data`` with an ``image`` file field and
    an optional ``threshold`` form field.

    Returns:
        JSON ``{"text": …, "lines": N}`` on success (HTTP 200),
        JSON ``{"error": …}`` on failure (HTTP 400/500).
    """
    # Resolve threshold from request or config default
    try:
        threshold: float = float(request.form.get("threshold", config.threshold))
    except (ValueError, TypeError):
        threshold = 0.5  # conservative fallback on parse failure

    if "image" not in request.files:
        return jsonify({"error": "Image file required"}), 400

    file = request.files["image"]
    if file.filename == "":
        return jsonify({"error": "File name is empty"}), 400

    result = ocr_service.process_image(file, threshold)
    if "error" in result:
        return jsonify(result), 500
    return jsonify(result)


@app.route("/health", methods=["GET"])
def health():
    """Health-check endpoint.

    Returns:
        JSON ``{"status": "ok"}`` (HTTP 200).
    """
    return jsonify(ocr_service.is_healthy())


if __name__ == "__main__":
    port: int = config.port
    print(f"OCR service starting on port {port}")
    app.run(host="0.0.0.0", port=port, debug=False)
