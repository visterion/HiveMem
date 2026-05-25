package com.hivemem.hooks;

import com.hivemem.search.CellSearchRepository.RankedRow;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ContextFormatter {

    public String format(List<CellWithCitation> cells, int turn) {
        if (cells == null || cells.isEmpty()) return "";
        String body = cells.stream()
                .map(c -> "- " + safeSummary(c.row()) + "\n  " + sourceLine(c))
                .collect(Collectors.joining("\n"));
        return "<hivemem_context turn=\"" + turn + "\">\n"
                + "Relevant (summaries only - use hivemem_get_cell for details):\n"
                + body + "\n"
                + "</hivemem_context>";
    }

    private String sourceLine(CellWithCitation c) {
        RankedRow r = c.row();
        String base = r.realm() + "/" + r.topic();
        if (!c.refs().isEmpty()) {
            ReferenceInfo ref = c.refs().get(0);
            if (ref.url() != null && !ref.url().isBlank()) {
                return "[Quelle: " + base + " · " + ref.title() + " — " + ref.url() + "]";
            }
            return "[Quelle: " + base + " · " + ref.title() + "]";
        }
        String cellShort = r.id().toString().substring(0, 8);
        if (r.validFrom() != null) {
            return "[Quelle: " + base + " · Cell " + cellShort + " · " + r.validFrom().getYear() + "]";
        }
        return "[Quelle: " + base + " · Cell " + cellShort + "]";
    }

    private String safeSummary(RankedRow r) {
        if (r.summary() != null && !r.summary().isBlank()) return r.summary().strip();
        if (r.content() != null) {
            String collapsed = r.content().strip().replaceAll("\\s+", " ");
            return collapsed.length() > 120 ? collapsed.substring(0, 120) : collapsed;
        }
        return "(no summary)";
    }
}
