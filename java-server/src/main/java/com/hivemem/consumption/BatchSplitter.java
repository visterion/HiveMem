package com.hivemem.consumption;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

public class BatchSplitter {

    /** @param cutAfterPages 1-based page numbers to cut AFTER. Out-of-range/duplicate ignored.
     *  @return one PDF per resulting document, in order. */
    public List<byte[]> split(byte[] pdfBytes, List<Integer> cutAfterPages) throws IOException {
        try (PDDocument src = Loader.loadPDF(pdfBytes)) {
            int total = src.getNumberOfPages();
            TreeSet<Integer> cuts = new TreeSet<>();
            for (Integer c : cutAfterPages) {
                if (c != null && c >= 1 && c < total) cuts.add(c); // c==total is a no-op cut
            }
            List<int[]> ranges = new ArrayList<>(); // [startIdx, endIdxExclusive] 0-based
            int start = 0;
            for (int cut : cuts) { ranges.add(new int[] {start, cut}); start = cut; }
            ranges.add(new int[] {start, total});

            List<byte[]> out = new ArrayList<>();
            for (int[] range : ranges) {
                try (PDDocument part = new PDDocument()) {
                    for (int i = range[0]; i < range[1]; i++) {
                        part.importPage(src.getPage(i));
                    }
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    part.save(baos);
                    out.add(baos.toByteArray());
                }
            }
            return out;
        }
    }
}
