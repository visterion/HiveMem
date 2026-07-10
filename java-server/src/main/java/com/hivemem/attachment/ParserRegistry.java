package com.hivemem.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Component
public class ParserRegistry {

    private static final Logger log = LoggerFactory.getLogger(ParserRegistry.class);

    private final List<AttachmentParser> parsers;

    public ParserRegistry(List<AttachmentParser> parsers) {
        this.parsers = parsers;
    }

    public ParseResult parse(String mimeType, InputStream content) {
        for (AttachmentParser parser : parsers) {
            if (parser.supports(mimeType)) {
                try {
                    return parser.parse(content);
                } catch (Exception e) {
                    log.warn("Parser {} failed for mime type {}: {}",
                            parser.getClass().getSimpleName(), mimeType, e.getMessage());
                    return ParseResult.empty();
                }
            }
        }
        return ParseResult.empty();
    }
}
