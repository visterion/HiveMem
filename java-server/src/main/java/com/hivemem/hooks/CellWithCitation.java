package com.hivemem.hooks;

import com.hivemem.search.CellSearchRepository.RankedRow;
import java.util.List;

public record CellWithCitation(RankedRow row, List<ReferenceInfo> refs) {}
