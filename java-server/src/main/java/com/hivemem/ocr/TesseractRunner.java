package com.hivemem.ocr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class TesseractRunner {

    private final String tesseractPath;

    public TesseractRunner(String tesseractPath) {
        this.tesseractPath = tesseractPath;
    }

    public String ocr(byte[] pngBytes, String languages, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                tesseractPath,
                "-",
                "-",
                "-l", languages,
                // psm 1 = automatic page segmentation WITH OSD, so rotated/upside-down scans are
                // auto-oriented before recognition (osd traineddata ships in the image). psm 3
                // skipped orientation detection and produced garbage on a 180°-fed page.
                "--psm", "1",
                "--oem", "1"
        );
        pb.redirectErrorStream(false);
        Process p = pb.start();
        try (var stdin = p.getOutputStream()) {
            stdin.write(pngBytes);
        }
        byte[] stdout = p.getInputStream().readAllBytes();
        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("tesseract timed out after " + timeoutSeconds + "s");
        }
        if (p.exitValue() != 0) {
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("tesseract failed (exit " + p.exitValue() + "): " + err);
        }
        return new String(stdout, StandardCharsets.UTF_8).trim();
    }
}
