package com.hivemem.ocr;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects the clockwise rotation (degrees) needed to make a scanned page upright, via Tesseract OSD
 * (`--psm 0`, orientation+script detection only). Best-effort: any failure returns 0 (do not rotate),
 * never throws into the pipeline. OSD traineddata ships in the runtime image (same as the --psm 1 path).
 */
public class PageOsd {

    private static final Logger log = LoggerFactory.getLogger(PageOsd.class);
    private static final Pattern ROTATE = Pattern.compile("(?m)^Rotate:\\s*(\\d+)");
    private static final Set<Integer> VALID = Set.of(0, 90, 180, 270);

    private final String tesseractPath;

    public PageOsd(String tesseractPath) {
        this.tesseractPath = tesseractPath;
    }

    /** @return 0/90/180/270 clockwise degrees to apply; 0 on any failure. */
    public int detectRotation(byte[] pngBytes, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(tesseractPath, "-", "-", "--psm", "0");
            // Merge stderr into stdout and drain the combined stream before waitFor: tesseract OSD can
            // emit per-page diagnostics on stderr; an unread stderr pipe could fill and deadlock against
            // our (large PNG) stdin write. parseRotate keys off the `Rotate:` line, so merged noise is harmless.
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var stdin = p.getOutputStream()) { stdin.write(pngBytes); }
            byte[] stdout = p.getInputStream().readAllBytes();
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return 0; }
            if (p.exitValue() != 0) return 0;
            return parseRotate(new String(stdout, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("OSD failed, leaving page unrotated: {}", e.toString());
            return 0;
        }
    }

    /** Parse the `Rotate: <deg>` line from OSD stdout. Returns 0 if absent/invalid/not a 90° multiple. */
    public static int parseRotate(String osdStdout) {
        if (osdStdout == null) return 0;
        Matcher m = ROTATE.matcher(osdStdout);
        if (!m.find()) return 0;
        try {
            int deg = Integer.parseInt(m.group(1)) % 360;
            return VALID.contains(deg) ? deg : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
