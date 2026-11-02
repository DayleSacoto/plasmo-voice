package su.plo.voice.platform.forge.client.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import su.plo.voice.platform.forge.network.groups.GroupsState;

/**
 * Layout and data of the Group HUD without any Minecraft type: the in-game renderer and the position editor both
 * draw {@link Layout}, so the box they measure is identical. Coordinates are scaled GUI pixels relative to the box.
 *
 * <p>The box is one background panel holding a row per online member and a "+K" row when more than
 * {@link #MAX_VISIBLE} members are online. The style picks what a row shows: {@code [head] Name}, the head alone or
 * the name alone. There is no title and no icon: a member speaking through group voice gets a white outline around
 * the head, or a thin white underline under the name when no head is shown. Each head sits in a slot one
 * {@link #OUTLINE} wider on every side, so the outline never covers the skin or touches the next row. Bottom anchors
 * reverse the rows so the first member stays nearest the screen edge; right anchors put the head on the right with
 * the name next to it.
 */
public final class GroupHudModel {
    /** Panel padding around the rows. */
    public static final int PADDING = 2;
    public static final int HEAD_SIZE = 16;
    /** Space kept around a head for the speaking outline (matches PlayerHeads.OUTLINE). */
    public static final int OUTLINE = 1;
    public static final int HEAD_SLOT = HEAD_SIZE + 2 * OUTLINE;
    public static final int ROW_HEIGHT = HEAD_SLOT;
    public static final int ROW_GAP = 1;
    /** Gap between the head slot and the name. */
    public static final int TEXT_GAP = 3;
    public static final int FONT_HEIGHT = 8;
    /** Name style row: one pixel above the text, the text, a gap and the one pixel speaking underline. */
    public static final int NAME_ROW_HEIGHT = FONT_HEIGHT + 3;
    /** Speaking underline of the name style, below the text top. */
    public static final int UNDERLINE_OFFSET = FONT_HEIGHT + 1;
    public static final int MAX_VISIBLE = 10;
    public static final int MAX_TEXT_WIDTH = 120;
    /** Background opacity in percent; 25 is the 0x40 alpha the panel always had. */
    public static final int DEFAULT_OPACITY = 25;
    private static final String ELLIPSIS = "...";

    private GroupHudModel() {
    }

    public enum Kind {
        MEMBER, MORE
    }

    /** One online member as drawn. */
    public static final class Entry {
        public final UUID id;
        public final String name;
        /** Speaking through group voice right now: the head gets the white outline. */
        public final boolean speaking;

        public Entry(UUID id, String name, boolean speaking) {
            this.id = id;
            this.name = name;
            this.speaking = speaking;
        }
    }

    public static final class Row {
        public final Kind kind;
        public final String text;
        public final UUID memberId;
        public final boolean speaking;
        /** Row top, relative to the box. */
        public final int y;
        /** Head top left (inside its outline slot), relative to the box; -1 for the overflow row and the name style. */
        public final int headX;
        public final int headY;
        public final int textX;
        public final int textY;
        /** Top of the name underline while speaking in the name style; -1 otherwise. */
        public final int underlineY;

        Row(Kind kind, String text, UUID memberId, boolean speaking, int y, int headX, int headY, int textX,
            int textY, int underlineY) {
            this.kind = kind;
            this.text = text;
            this.memberId = memberId;
            this.speaking = speaking;
            this.y = y;
            this.headX = headX;
            this.headY = headY;
            this.textX = textX;
            this.textY = textY;
            this.underlineY = underlineY;
        }
    }

    public static final class Layout {
        public final List<Row> rows;
        public final int width;
        public final int height;
        public final HudOptions.OverlayStyle style;

        Layout(List<Row> rows, int width, int height, HudOptions.OverlayStyle style) {
            this.rows = Collections.unmodifiableList(rows);
            this.width = width;
            this.height = height;
            this.style = style;
        }
    }

    /** ARGB of the panel for an opacity in percent (clamped to 0..100); 0 is fully transparent. */
    public static int backgroundColor(int opacityPercent) {
        int percent = clampOpacity(opacityPercent);
        return Math.round(percent * 255F / 100F) << 24;
    }

    public static int clampOpacity(int opacityPercent) {
        return Math.max(0, Math.min(100, opacityPercent));
    }

    /**
     * The online members with their group speaking state: {@code groupSpeaking} are the players heard on the group
     * line (never proximity), and the local player counts as speaking while {@code selfGroupTransmitting}. Offline
     * members are dropped; server order is kept so rows do not jump while somebody talks.
     */
    public static List<Entry> entries(List<GroupsState.Member> members, Set<UUID> groupSpeaking, UUID selfId,
                                      boolean selfGroupTransmitting) {
        List<Entry> entries = new ArrayList<>();
        for (GroupsState.Member member : members) {
            if (!member.online) continue;
            boolean speaking = member.id.equals(selfId) ? selfGroupTransmitting : groupSpeaking.contains(member.id);
            entries.add(new Entry(member.id, member.name, speaking));
        }
        return entries;
    }

    /** The {@code [head] Name} style, see {@link #layout(List, boolean, boolean, HudOptions.OverlayStyle, ToIntFunction, IntFunction)}. */
    public static Layout layout(List<Entry> members, boolean right, boolean bottom,
                                ToIntFunction<String> textWidth, IntFunction<String> moreText) {
        return layout(members, right, bottom, HudOptions.OverlayStyle.NAME_SKIN, textWidth, moreText);
    }

    /**
     * @param style     what a member row shows; head rows keep the head slot height, name-only rows are
     *                  {@link #NAME_ROW_HEIGHT} high with room for the speaking underline
     * @param textWidth pixel width of a string in the HUD font
     * @param moreText  text of the overflow row for K hidden members, e.g. "+3 more"
     * @return an empty 0x0 layout when nobody is online
     */
    public static Layout layout(List<Entry> members, boolean right, boolean bottom, HudOptions.OverlayStyle style,
                                ToIntFunction<String> textWidth, IntFunction<String> moreText) {
        if (style == null) style = HudOptions.OverlayStyle.NAME_SKIN;
        if (members.isEmpty()) return new Layout(new ArrayList<Row>(), 0, 0, style);
        boolean head = style.hasSkin();
        boolean named = style.hasName();
        int rowHeight = head ? ROW_HEIGHT : NAME_ROW_HEIGHT;
        int visible = Math.min(members.size(), MAX_VISIBLE);
        List<Entry> shown = members.subList(0, visible);
        String more = members.size() > visible ? moreText.apply(members.size() - visible) : null;

        int contentWidth = 0;
        List<String> names = new ArrayList<>();
        for (Entry entry : shown) {
            String name = fit(entry.name, textWidth);
            names.add(name);
            int rowWidth = (head ? HEAD_SLOT : 0) + (head && named ? TEXT_GAP : 0)
                    + (named ? textWidth.applyAsInt(name) : 0);
            contentWidth = Math.max(contentWidth, rowWidth);
        }
        if (more != null) contentWidth = Math.max(contentWidth, textWidth.applyAsInt(more));
        int width = 2 * PADDING + contentWidth;

        List<Row> logical = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            Entry entry = shown.get(i);
            String name = names.get(i);
            int nameWidth = textWidth.applyAsInt(name);
            int slotX = right ? width - PADDING - HEAD_SLOT : PADDING;
            int textX;
            if (!head) textX = right ? width - PADDING - nameWidth : PADDING;
            else textX = right ? slotX - TEXT_GAP - nameWidth : slotX + HEAD_SLOT + TEXT_GAP;
            logical.add(new Row(Kind.MEMBER, name, entry.id, entry.speaking, 0, head ? slotX + OUTLINE : -1, 0,
                    textX, 0, -1));
        }
        if (more != null) {
            int textX = right ? width - PADDING - textWidth.applyAsInt(more) : PADDING;
            logical.add(new Row(Kind.MORE, more, null, false, 0, -1, -1, textX, 0, -1));
        }
        if (bottom) Collections.reverse(logical);

        List<Row> rows = new ArrayList<>();
        int y = PADDING;
        for (Row row : logical) {
            boolean member = row.kind == Kind.MEMBER;
            int headY = member && head ? y + OUTLINE : -1;
            int textY = y + (rowHeight - FONT_HEIGHT) / 2;
            int underlineY = member && !head && row.speaking ? textY + UNDERLINE_OFFSET : -1;
            rows.add(new Row(row.kind, row.text, row.memberId, row.speaking, y, row.headX, headY, row.textX, textY,
                    underlineY));
            y += rowHeight + ROW_GAP;
        }
        return new Layout(rows, width, y - ROW_GAP + PADDING, style);
    }

    /** The text cut with "..." so it is at most {@link #MAX_TEXT_WIDTH} pixels wide. */
    static String fit(String text, ToIntFunction<String> textWidth) {
        if (textWidth.applyAsInt(text) <= MAX_TEXT_WIDTH) return text;
        String cut = text;
        while (!cut.isEmpty() && textWidth.applyAsInt(cut + ELLIPSIS) > MAX_TEXT_WIDTH) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + ELLIPSIS;
    }
}
