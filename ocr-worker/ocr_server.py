"""Flask entry point for the ShizukuTranslate OCR microservice.

This module is intentionally thin — all OCR logic lives in
:mod:`ocr_service` and configuration in :mod:`config`.
"""

from flask import Flask, request, jsonify
from werkzeug.exceptions import BadRequest, RequestEntityTooLarge

from config import Config
from ocr_service import OcrService

config: Config = Config()
app: Flask = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = config.max_content_length
ocr_service: OcrService = OcrService(config)


@app.errorhandler(BadRequest)
def bad_request(error):
    return jsonify({"error": "BAD_REQUEST", "message": str(error.description)}), 400


@app.errorhandler(RequestEntityTooLarge)
def request_too_large(_error):
    return jsonify({"error": "PAYLOAD_TOO_LARGE", "message": "Image upload is too large"}), 413


@app.errorhandler(Exception)
def unhandled_error(error):
    app.logger.exception("Unhandled OCR worker error", exc_info=error)
    return jsonify({"error": "OCR_INTERNAL_ERROR", "message": "OCR worker failed"}), 500


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
        return jsonify({"error": "BAD_REQUEST", "message": "threshold must be a number"}), 400

    if threshold < 0 or threshold > 1:
        return jsonify({"error": "BAD_REQUEST", "message": "threshold must be between 0 and 1"}), 400

    if "image" not in request.files:
        return jsonify({"error": "BAD_REQUEST", "message": "Image file required"}), 400

    file = request.files["image"]
    if file.filename == "":
        return jsonify({"error": "BAD_REQUEST", "message": "File name is empty"}), 400

    result = ocr_service.process_image(file, threshold)
    if "error" in result:
        return jsonify({"error": "OCR_FAILED", "message": result["error"]}), 500
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
