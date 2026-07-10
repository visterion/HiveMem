package com.hivemem.consumption;

import java.util.*;

/** Deterministic: turns accumulated DocGroups into ordered, status-tagged result docs.
 *  Any batch page not present in any group becomes its own 1-page pending document.
 *  Pages preserve each group's reading order (first occurrence of duplicates wins). */
public class PageReassembler {

    public record ResultDoc(List<Integer> pages, String status) {}

    private final ConsumptionProperties props;
    public PageReassembler(ConsumptionProperties props) { this.props = props; }

    public List<ResultDoc> toDocuments(List<DocGroup> groups, int totalPages) {
        double threshold = props.getReassemblyConfidenceThreshold();
        List<ResultDoc> out = new ArrayList<>();
        Set<Integer> assigned = new HashSet<>();
        for (DocGroup g : groups) {
            // Drop out-of-range pages (0 from Jackson defaults, >totalPages hallucinations) BEFORE
            // the dedupe: they must never enter `assigned`, so any real page they "stole" still
            // falls into the orphan net below instead of being silently lost.
            List<Integer> inRange = new ArrayList<>();
            for (Integer p : g.pages) if (p != null && p >= 1 && p <= totalPages) inRange.add(p);
            List<Integer> pages = new ArrayList<>(new LinkedHashSet<>(inRange)); // dedupe, KEEP reading order
            if (pages.isEmpty()) continue;
            assigned.addAll(pages);
            out.add(new ResultDoc(pages, g.minConfidence >= threshold ? "committed" : "pending"));
        }
        for (int p = 1; p <= totalPages; p++) {
            if (!assigned.contains(p)) out.add(new ResultDoc(List.of(p), "pending")); // orphan page
        }
        return out;
    }
}
