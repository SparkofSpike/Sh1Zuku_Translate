"""ocr_server.py - Japanese vertical/horizontal OCR microservice"""

import os
import tempfile

os.environ["FLAGS_use_mkldnn"] = "0"
os.environ["PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION"] = "python"

from flask import Flask, request, jsonify
from PIL import Image
import paddle
paddle.set_flags({'FLAGS_use_mkldnn': False})
from paddleocr import PaddleOCR

app = Flask(__name__)

print("Loading PaddleOCR Japanese model...")
ocr = PaddleOCR(lang='japan', use_textline_orientation=False)
print("PaddleOCR model loaded!")


@app.route('/ocr', methods=['POST'])
def ocr_image():
    try:
        threshold = float(request.form.get('threshold', 0.3))
    except:
        threshold = 0.5

    if 'image' not in request.files:
        return jsonify({'error': 'Image file required'}), 400

    file = request.files['image']
    if file.filename == '':
        return jsonify({'error': 'File name is empty'}), 400

    suffix = os.path.splitext(file.filename)[1] or '.jpg'
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        file.save(tmp)
        tmp_path = tmp.name

    try:
        result = ocr.ocr(tmp_path)

        lines = []
        if result and len(result) > 0:
            page = result[0]
            if isinstance(page, dict):
                texts = page.get('rec_texts', [])
                scores = page.get('rec_scores', [])
                boxes = page.get('rec_polys', [])
                for i, text in enumerate(texts):
                    conf = scores[i] if i < len(scores) else 0
                    if conf > threshold and text and text.strip():
                        box = boxes[i] if i < len(boxes) else None
                        if box is not None and len(box) >= 2:
                            cx = float(box[0][0] + box[1][0]) / 2
                            cy = float(box[0][1] + box[2][1]) / 2
                        else:
                            cx, cy = 0, i * 10
                        lines.append((cx, cy, text.strip()))
            else:
                for line in page:
                    box = line[0]
                    text = line[1][0]
                    conf = line[1][1]
                    if conf > threshold and text.strip():
                        cx = (box[0][0] + box[1][0]) / 2
                        cy = (box[0][1] + box[2][1]) / 2
                        lines.append((cx, cy, text))

        merged = []
        for cx, cy, text in lines:
            if merged and cy - merged[-1][1] < 20:
                merged[-1] = (merged[-1][0], merged[-1][1], merged[-1][2] + text)
            else:
                merged.append((cx, cy, text))

        full_text = '\n'.join([t for _, _, t in merged])
        return jsonify({'text': full_text, 'lines': len(merged)})

    except Exception as e:
        return jsonify({'error': f'OCR failed: {str(e)}'}), 500
    finally:
        try:
            os.unlink(tmp_path)
        except:
            pass


@app.route('/health', methods=['GET'])
def health():
    return jsonify({'status': 'ok'})


if __name__ == '__main__':
    port = int(os.environ.get('OCR_PORT', 5557))
    print(f'OCR service starting on port {port}')
    app.run(host='0.0.0.0', port=port, debug=False)
