package net.kdt.pojavlaunch.gecko;

import java.io.IOException;
import java.io.InputStream;

/** Presents server-owned NBT through UIKit without rewriting the actual inventory. */
public final class IOSInventoryPresentation {
    private static InventoryLanguage language;
    private static String selected = "pt_br";
    private IOSInventoryPresentation() {}
    public static synchronized void language(String id) {
        if (!(id.equals("pt_br") || id.equals("en_us") || id.equals("es_es"))) return;
        if (language != null && selected.equals(id)) return;
        try (InputStream names = IOSInventoryPresentation.class.getResourceAsStream("/inventory_lang/" + id + ".tsv");
             InputStream keys = IOSInventoryPresentation.class.getResourceAsStream("/inventory_lang/items.tsv")) {
            if (names == null || keys == null) throw new IOException("Missing inventory language");
            language = InventoryLanguage.read(names, keys); selected = id;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    public static synchronized String project(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        if (language == null) language(selected);
        String[] lines = encoded.split("\n", -1);
        StringBuilder out = new StringBuilder(encoded.length());
        out.append(lines[0]).append('\n');
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] fields = lines[i].split("\t", -1);
            if (fields.length >= 6) {
                ThinContainerTag display = ThinContainerTag.read(fields[5]);
                ThinItemTag item = ThinItemTag.read(fields[5]);
                fields[3] = clean(display.name != null ? display.name : language.itemName(fields[2],fields[3],item.potionId));
                out.append(String.join("\t", fields)).append('\t');
                for (int line = 0; line < display.lore.size(); line++) {
                    if (line > 0) out.append('\u001f');
                    out.append(clean(display.lore.get(line)));
                }
            } else out.append(lines[i]);
            out.append('\n');
        }
        return out.toString();
    }
    private static String clean(String value) { return value.replace('\t',' ').replace('\r',' ').replace('\n',' '); }
    public static void selfTest() {
        String source = "0\t0\t\t\t0\n0\t2\tminecraft:stone\t\t1\t?\n";
        language("pt_br");
        if (!project(source).contains("Pedra")) throw new AssertionError("Portuguese item name");
        language("en_us");
        if (!project(source).contains("Stone")) throw new AssertionError("English item name");
        if (!project(source).contains("\t1\t?")) throw new AssertionError("Original stack data altered");
        if (ThinContainerTag.read("0a000008").name != null) throw new AssertionError("Truncated NBT accepted");
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream tag = new java.io.DataOutputStream(bytes);
            tag.writeByte(10); tag.writeUTF(""); tag.writeByte(10); tag.writeUTF("display");
            tag.writeByte(8); tag.writeUTF("Name"); tag.writeUTF("{\"text\":\"Play Skywars\"}");
            tag.writeByte(9); tag.writeUTF("Lore"); tag.writeByte(8); tag.writeInt(1);
            tag.writeUTF("{\"text\":\"Click to join\"}"); tag.writeByte(0); tag.writeByte(0);
            StringBuilder hex = new StringBuilder();
            for (byte value : bytes.toByteArray()) hex.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
            String projected = project(source.replace("\t?", "\t" + hex));
            if (!projected.contains("Play Skywars") || !projected.contains("Click to join") || !projected.contains(hex)) {
                throw new AssertionError("Server menu name, lore or original NBT lost");
            }
        } catch (IOException error) { throw new AssertionError(error); }
        language("pt_br");
        System.out.println("UIKit inventory projection tests passed: languages, original stack payload and malformed NBT");
    }
}
