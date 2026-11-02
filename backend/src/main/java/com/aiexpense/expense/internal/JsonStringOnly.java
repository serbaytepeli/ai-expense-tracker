package com.aiexpense.expense.internal;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Accepts only a JSON string. Jackson would otherwise turn a JSON number such as {@code 149.90}
 * into a string, but money must arrive as a string (database-design.md section 2) so no client
 * ever sends it through binary floating point. Anything else is a 400 VALIDATION_ERROR.
 */
class JsonStringOnly extends StdDeserializer<String> {

    JsonStringOnly() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return (String) context.handleUnexpectedToken(String.class, parser);
        }
        return parser.getString();
    }
}
