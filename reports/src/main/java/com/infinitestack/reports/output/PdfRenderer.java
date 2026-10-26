package com.infinitestack.reports.output;

/**
 * Rendered HTML in, PDF out (ARCHITECTURE section 18). Kept behind an interface so the engine can
 * move to another renderer (a headless browser, say) without touching anything else (section 41).
 */
public interface PdfRenderer {

    byte[] render(String html) throws PdfException;

    class PdfException extends Exception {
        public PdfException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
