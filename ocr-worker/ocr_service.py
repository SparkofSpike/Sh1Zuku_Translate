"""OCR service wrapping PaddleOCR for Japanese text extraction."""

import os
import tempfile
from typing import Any, Dict, List, Optional, Tuple, Union

from PIL import Image  # noqa: F401 — required by PaddleOCR internals
import paddle
from paddleocr import PaddleOCR

from config import Config

# PaddlePaddle environment workarounds (must be set before PaddleOCR import)
os.environ["FLAGS_use_mkldnn"] = "0"
os.environ["PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION"] = "python"

paddle.set_flags({"FLAGS_use_mkldnn": False})


class OcrService:
    """Service class for OCR operations using PaddleOCR (Japanese model).

    Handles image processing, text extraction, and line merging.
    """

    def __init__(self, config: Config) -> None:
        """Initialise the PaddleOCR engine with the Japanese language model.

        Args:
            config: Application configuration instance.
        """
        self.config: Config = config
        print("Loading PaddleOCR Japanese model...")
        self._ocr: PaddleOCR = PaddleOCR(
            lang="japan", use_textline_orientation=False
        )
        print("PaddleOCR model loaded!")

    def process_image(
        self,
        image_file: Any,
        threshold: Optional[float] = None,
    ) -> Dict[str, Any]:
        """Process an uploaded image and return OCR-extracted text.

        Args:
            image_file: A Werkzeug/Flask file-like upload object with
                a ``.save()`` method and ``.filename`` attribute.
            threshold: Confidence threshold override. Falls back to
                ``Config.threshold`` when *None*.

        Returns:
            A dict with keys ``text`` (str) and ``lines`` (int) on success,
            or ``error`` (str) on failure.
        """
        if threshold is None:
            threshold = self.config.threshold

        suffix: str = os.path.splitext(image_file.filename)[1] or ".jpg"
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            image_file.save(tmp)
            tmp_path: str = tmp.name

        try:
            result: Any = self._ocr.ocr(tmp_path)
            lines: List[Tuple[float, float, str]] = self._extract_lines(
                result, threshold
            )
            merged: List[Tuple[float, float, str]] = self._merge_lines(lines)
            full_text: str = "\n".join(t for _, _, t in merged)
            return {"text": full_text, "lines": len(merged)}
        except Exception as exc:
            return {"error": f"OCR failed: {exc}"}
        finally:
            try:
                os.unlink(tmp_path)
            except Exception:
                pass

    @staticmethod
    def _extract_lines(
        result: Any, threshold: float
    ) -> List[Tuple[float, float, str]]:
        """Extract (cx, cy, text) tuples from a raw PaddleOCR result.

        Handles both dict-style (PP-OCRv4+) and list-style (legacy) output
        formats.

        Args:
            result: Raw output from ``PaddleOCR.ocr()``.
            threshold: Confidence threshold for filtering.

        Returns:
            List of (center_x, center_y, text) tuples.
        """
        lines: List[Tuple[float, float, str]] = []
        if not result or len(result) == 0:
            return lines

        page: Any = result[0]
        if isinstance(page, dict):
            # Dict format (PP-OCRv4 / v6 structured output)
            texts: List[str] = page.get("rec_texts", [])
            scores: List[float] = page.get("rec_scores", [])
            boxes: Any = page.get("rec_polys", [])
            for i, text in enumerate(texts):
                conf: float = scores[i] if i < len(scores) else 0.0
                if conf > threshold and text and text.strip():
                    box: Any = boxes[i] if i < len(boxes) else None
                    if box is not None and len(box) >= 2:
                        cx: float = float(box[0][0] + box[1][0]) / 2.0
                        cy: float = float(box[0][1] + box[2][1]) / 2.0
                    else:
                        cx, cy = 0.0, float(i * 10)
                    lines.append((cx, cy, text.strip()))
        else:
            # Legacy list-of-tuples format
            for raw_line in page:
                box = raw_line[0]
                text: str = raw_line[1][0]
                conf: float = raw_line[1][1]
                if conf > threshold and text.strip():
                    cx = float(box[0][0] + box[1][0]) / 2.0
                    cy = float(box[0][1] + box[2][1]) / 2.0
                    lines.append((cx, cy, text))

        return lines

    @staticmethod
    def _merge_lines(
        lines: List[Tuple[float, float, str]],
    ) -> List[Tuple[float, float, str]]:
        """Merge vertically-nearby lines (within 20 px) in reading order.

        Args:
            lines: List of (cx, cy, text) tuples, expected in top-to-bottom
                reading order.

        Returns:
            Merged list where consecutive lines close in y-coordinate are
            concatenated.
        """
        merged: List[Tuple[float, float, str]] = []
        for cx, cy, text in lines:
            if merged and cy - merged[-1][1] < 20:
                prev_cx, prev_cy, prev_text = merged[-1]
                merged[-1] = (prev_cx, prev_cy, prev_text + text)
            else:
                merged.append((cx, cy, text))
        return merged

    @staticmethod
    def is_healthy() -> Dict[str, str]:
        """Return a health-check payload.

        Returns:
            ``{"status": "ok"}``.
        """
        return {"status": "ok"}
