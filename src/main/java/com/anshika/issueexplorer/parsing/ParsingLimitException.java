package com.anshika.issueexplorer.parsing;

import java.io.IOException;

public final class ParsingLimitException extends IOException {
    public ParsingLimitException(String message) {
        super(message);
    }
}
