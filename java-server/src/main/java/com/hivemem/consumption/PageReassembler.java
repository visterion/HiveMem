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
            List<Integer> pages = new ArrayList<>(new LinkedHashSet<>(g.pages)); // dedupe, KEEP reading order
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
