package com.shizuku.translate.dto;

/**
 * OCR 响应 DTO
 */
public class OcrResponse {

    private boolean success;
    private String text;
    private int lines;
    private String error;

    public static OcrResponse success(String text, int lines) {
        OcrResponse r = new OcrResponse();
        r.success = true;
        r.text = text;
        r.lines = lines;
        return r;
    }

    public static OcrResponse error(String error) {
        OcrResponse r = new OcrResponse();
        r.success = false;
        r.error = error;
        return r;
    }

    // Getters
    public boolean isSuccess() { return success; }
    public String getText() { return text; }
    public int getLines() { return lines; }
    public String getError() { return error; }
}
