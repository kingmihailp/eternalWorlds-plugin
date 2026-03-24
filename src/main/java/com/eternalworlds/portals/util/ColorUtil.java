package com.eternalworlds.portals.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses color codes in strings for sending to players.
 *
 * Supported formats:
 *   &a, &b, &l, &r  — standard Bukkit legacy codes
 *   &#RRGGBB        — hex with ampersand-hash prefix
 *   #RRGGBB         — bare hex (only if NOT preceded by §x sequence already)
 */
public final class ColorUtil {

    /** Matches &#RRGGBB or standalone #RRGGBB */
    private static final Pattern HEX_PATTERN =
            Pattern.compile("&#([A-Fa-f0-9]{6})|(?<!§x§[0-9A-Fa-f]§[0-9A-Fa-f]§[0-9A-Fa-f]§[0-9A-Fa-f]§[0-9A-Fa-f])#([A-Fa-f0-9]{6})");

    private ColorUtil() {}

    /**
     * Converts color codes (including hex) in the given string to Bukkit §-codes.
     * Returns null if input is null.
     */
    public static String parse(String message) {
        if (message == null) return null;

        // 1. Convert hex colors (&#RRGGBB and #RRGGBB) → §x§R§R§G§G§B§B
        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append('§').append(c);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(sb);

        // 2. Convert standard &-codes → §-codes
        return sb.toString().replace("&", "§");
    }
}
