package com.hivemem.ocr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
        try {
            // Drain stdout/stderr on separate threads so a verbose run can never fill a
            // 64KB pipe and block tesseract (which would make the timeout unreachable).
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outDrain = drain(p.getInputStream(), stdout);
            Thread errDrain = drain(p.getErrorStream(), stderr);

            try (var stdin = p.getOutputStream()) {
                stdin.write(pngBytes);
            }

            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                throw new IOException("tesseract timed out after " + timeoutSeconds + "s");
            }
            outDrain.join(TimeUnit.SECONDS.toMillis(5));
            errDrain.join(TimeUnit.SECONDS.toMillis(5));
            if (p.exitValue() != 0) {
                throw new IOException("tesseract failed (exit " + p.exitValue() + "): "
                        + stderr.toString(StandardCharsets.UTF_8));
            }
            return stdout.toString(StandardCharsets.UTF_8).trim();
        } finally {
            // Ensures the process dies on timeout AND when stdin.write throws (broken pipe).
            p.destroyForcibly();
        }
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            try {
                in.transferTo(sink);
            } catch (IOException ignored) {
                // Process was destroyed / stream closed — partial output is fine.
            }
        }, "tesseract-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
