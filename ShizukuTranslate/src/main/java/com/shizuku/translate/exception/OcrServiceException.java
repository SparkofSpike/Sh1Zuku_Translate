package com.shizuku.translate.exception;

public class OcrServiceException extends BusinessException {
    public OcrServiceException(String message) {
        super(message);
    }

    public OcrServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
