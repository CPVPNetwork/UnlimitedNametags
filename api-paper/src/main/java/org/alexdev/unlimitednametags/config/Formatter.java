package org.alexdev.unlimitednametags.config;

import lombok.AccessLevel;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.alexdev.unlimitednametags.api.UnlimitedNameTagsInstancePaper;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

@SuppressWarnings("unused")
public enum Formatter {

    MINIMESSAGE(
            (plugin, player, text) -> plugin.formatTextForNametag(player, legacyToMiniMessage(text)),
            "MiniMessage"
    ),
    LEGACY(
            (plugin, player, text) -> getSTUPID().deserialize(replaceHexColorCodes(text)),
            "Legacy Text"
    ),
    UNIVERSAL(
            (plugin, player, text) -> {
                final String legacy = getHEX().serialize(getSTUPID().deserialize(replaceHexColorCodes(text)));
                final Component component = getHEX().deserialize(legacy);
                final String string = MiniMessage.miniMessage().serialize(component).replace("\\<", "<").replace("\\", "");
                return MINIMESSAGE.formatter.apply(plugin, player, string);
            },
            "Universal"
    );

    @NotNull
    private static String replaceHexColorCodes(@NotNull String text) {
        return text.replace('§', '&');
    }

    /**
     * Converts legacy colour codes ({@code &a}, {@code &l}, {@code &#RRGGBB}, {@code &x&R&R&G&G&B&B},
     * also with {@code §}) into MiniMessage tags, so legacy prefixes (e.g. from LuckPerms) render
     * correctly with the MiniMessage formatter. Text without legacy codes is returned unchanged.
     */
    @NotNull
    public static String legacyToMiniMessage(@NotNull String text) {
        if (text.indexOf('&') == -1 && text.indexOf('\u00A7') == -1) {
            return text;
        }
        final StringBuilder out = new StringBuilder(text.length() + 16);
        final int len = text.length();
        int i = 0;
        while (i < len) {
            final char c = text.charAt(i);
            if ((c == '&' || c == '\u00A7') && i + 1 < len) {
                final char next = text.charAt(i + 1);
                // &#RRGGBB
                if (next == '#' && i + 8 <= len && isHex(text, i + 2, 6)) {
                    out.append("<reset><#").append(text, i + 2, i + 8).append('>');
                    i += 8;
                    continue;
                }
                // &x&R&R&G&G&B&B
                if ((next == 'x' || next == 'X') && i + 14 <= len) {
                    final StringBuilder hex = new StringBuilder(6);
                    boolean valid = true;
                    for (int j = 0; j < 6; j++) {
                        final char marker = text.charAt(i + 2 + j * 2);
                        final char digit = text.charAt(i + 3 + j * 2);
                        if ((marker != '&' && marker != '\u00A7') || Character.digit(digit, 16) == -1) {
                            valid = false;
                            break;
                        }
                        hex.append(digit);
                    }
                    if (valid) {
                        out.append("<reset><#").append(hex).append('>');
                        i += 14;
                        continue;
                    }
                }
                final String tag = legacyTag(Character.toLowerCase(next));
                if (tag != null) {
                    out.append(tag);
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isHex(@NotNull String text, int start, int length) {
        for (int k = start; k < start + length; k++) {
            if (Character.digit(text.charAt(k), 16) == -1) {
                return false;
            }
        }
        return true;
    }

    private static String legacyTag(char code) {
        return switch (code) {
            case '0' -> "<reset><black>";
            case '1' -> "<reset><dark_blue>";
            case '2' -> "<reset><dark_green>";
            case '3' -> "<reset><dark_aqua>";
            case '4' -> "<reset><dark_red>";
            case '5' -> "<reset><dark_purple>";
            case '6' -> "<reset><gold>";
            case '7' -> "<reset><gray>";
            case '8' -> "<reset><dark_gray>";
            case '9' -> "<reset><blue>";
            case 'a' -> "<reset><green>";
            case 'b' -> "<reset><aqua>";
            case 'c' -> "<reset><red>";
            case 'd' -> "<reset><light_purple>";
            case 'e' -> "<reset><yellow>";
            case 'f' -> "<reset><white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
    }

    @Getter
    private final String name;

    private final TriFunction<UnlimitedNameTagsInstancePaper, CommandSender, String, Component> formatter;

    @Getter(value = AccessLevel.PRIVATE)
    private static final Pattern HEX_PATTERN = Pattern.compile("&#[a-fA-F0-9]{6}");
    @Getter(value = AccessLevel.PRIVATE)
    private static final String LEGACY_RESET = "&r";
    @Getter(value = AccessLevel.PRIVATE)
    private static final String REPLACE_RESET = "###RESET###";
    @Getter(value = AccessLevel.PRIVATE)
    private static final LegacyComponentSerializer STUPID = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .useUnusualXRepeatedCharacterHexFormat()
            .hexColors()
            .build();
    @Getter(value = AccessLevel.PRIVATE)
    private static final LegacyComponentSerializer HEX = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .build();

    Formatter(@NotNull TriFunction<UnlimitedNameTagsInstancePaper, CommandSender, String, Component> formatter, @NotNull String name) {
        this.formatter = formatter;
        this.name = name;
    }

    @NotNull
    public static Formatter from(@NotNull TextFormatter textFormatter) {
        return valueOf(textFormatter.name());
    }

    public Component format(@NotNull UnlimitedNameTagsInstancePaper plugin, @NotNull CommandSender audience, @NotNull String text) {
        return formatter.apply(plugin, audience, text);
    }
}
