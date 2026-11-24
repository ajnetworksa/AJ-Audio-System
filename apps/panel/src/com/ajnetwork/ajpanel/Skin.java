package com.ajnetwork.ajpanel;

import java.util.Calendar;

/**
 * The two panel looks (v4 mockups): GRAPHITE (default, evening/night) and PEARL (daytime).
 * Setting "look" per panel: auto (pearl while the sun is up, graphite after sunset) | graphite | pearl.
 * Flat colours and gradients only – no blur, no transparency layers – so even the DSPPA stays at 60 fps.
 */
public final class Skin {
    public final boolean dark;
    public final int bg0, bg1, bg2, sheen, card0, card1, text, sub, muted, line, chip, chipText, accent, accentBg0, accentBg1, logo0, logo1,
            danger, dangerBg, ok, okBg, sheetBg, scrim, track;
    private Skin(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg0 = 0xFF4B4F58; bg1 = 0xFF23252A; bg2 = 0xFF121316; sheen = 0x0DFFFFFF;
            card0 = 0xFF2F3238; card1 = 0xFF1B1D21; text = 0xFFF1F2F4; sub = 0xFF9CA0A8; muted = 0xFF6E727A; line = 0x17FFFFFF;
            chip = 0xFF23262C; chipText = 0xFFC9CDD4; accent = 0xFFFF9F43; accentBg0 = 0xFF3A2E22; accentBg1 = 0xFF2A221B;
            danger = 0xFFFF8A8A; dangerBg = 0xFF3A1F22; ok = 0xFF6FDB9A; okBg = 0xFF1C3326; sheetBg = 0xFF22252B; scrim = 0xB3000000; track = 0xFF3A3E46;
        } else {
            bg0 = 0xFFFBFCFD; bg1 = 0xFFEEF1F5; bg2 = 0xFFE3E8EE; sheen = 0x66FFFFFF;
            card0 = 0xFFFFFFFF; card1 = 0xFFF3F5F8; text = 0xFF2B2F36; sub = 0xFF707680; muted = 0xFF9AA0A8; line = 0x14000000;
            chip = 0xFFFFFFFF; chipText = 0xFF45505E; accent = 0xFFE07A22; accentBg0 = 0xFFFFF0E2; accentBg1 = 0xFFFDE3CB;
            danger = 0xFFC8423F; dangerBg = 0xFFFDE2E0; ok = 0xFF1FA971; okBg = 0xFFD9F4E7; sheetBg = 0xFFFFFFFF; scrim = 0x8C1B1F26; track = 0xFFDDE2E8;
        }
        logo0 = 0xFF7ED957; logo1 = 0xFF3E9A2C;
    }
    public static final Skin GRAPHITE = new Skin(true), PEARL = new Skin(false);

    /** colours of one tile kind: gradient top/bottom, text, sub text, value/accent colour, inner buttons, glow */
    public static final class Tone {
        public final int top, bottom, text, sub, value, btn, glow;
        Tone(int top, int bottom, int text, int sub, int value, int btn, int glow) { this.top = top; this.bottom = bottom; this.text = text; this.sub = sub; this.value = value; this.btn = btn; this.glow = glow; }
        /** readable text colour on top of {@link #value} (RUN chip) */
        public int dark() {
            int r = (value >> 16) & 255, g = (value >> 8) & 255, b = value & 255;
            return (0.299 * r + 0.587 * g + 0.114 * b) > 150 ? 0xFF3A2A1C : 0xFFFFFFFF;
        }
    }
    // graphite tones (v4)
    private static final Tone G_LIGHT = new Tone(0xFF4A3A22, 0xFF2A231A, 0xFFF1F2F4, 0xFFD9C29A, 0xFFFFD27A, 0xFF5A4A32, 0xFFFFD66B);
    private static final Tone G_CLIMATE = new Tone(0xFF2F6CF0, 0xFF1E3F9A, 0xFFFFFFFF, 0xFFC3D4FF, 0xFFFFFFFF, 0xFF4E86F5, 0xFF6FA0FF);
    private static final Tone G_HEAT = new Tone(0xFFE0662F, 0xFF97391A, 0xFFFFFFFF, 0xFFFFD2BE, 0xFFFFFFFF, 0xFFF08451, 0xFFFF9F6B);
    private static final Tone G_SCENE = new Tone(0xFF5C4636, 0xFF2E241D, 0xFFF1F2F4, 0xFFD8C3AF, 0xFFF3DDC3, 0xFF6E5646, 0xFFF3DDC3);
    private static final Tone G_COVER = new Tone(0xFF1F4D48, 0xFF15302D, 0xFFF1F2F4, 0xFFA9D6CE, 0xFF7FE6D2, 0xFF2E6B63, 0xFF5FD9C2);
    private static final Tone G_LOCK = new Tone(0xFF22402E, 0xFF16271D, 0xFFF1F2F4, 0xFFA7D3B6, 0xFF8FE8B4, 0xFF3BB273, 0xFF3BB273);
    private static final Tone G_UNLOCK = new Tone(0xFF4A2A26, 0xFF2A1917, 0xFFF1F2F4, 0xFFE0B3AC, 0xFFFF9A8A, 0xFFD9564A, 0xFFFF6B5B);
    private static final Tone G_SWITCH = new Tone(0xFF3B355E, 0xFF221F38, 0xFFF1F2F4, 0xFFC2BCE6, 0xFFB9A8FF, 0xFF524A80, 0xFF9C8CFF);
    private static final Tone G_MUSIC = new Tone(0xFF4A2F5E, 0xFF1D1A24, 0xFFF1F2F4, 0xFFBFB3D1, 0xFFD7B8F5, 0xFF463A57, 0xFFE0567F);
    private static final Tone G_OFF = new Tone(0xFF2F3238, 0xFF1E2025, 0xFFF1F2F4, 0xFF9CA0A8, 0xFFC9CDD4, 0xFF3A3E46, 0x00000000);
    // pearl tones (v3 pearl, brighter)
    private static final Tone P_LIGHT = new Tone(0xFFFFE7B8, 0xFFF9C978, 0xFF4A3105, 0xFF6E4E14, 0xFF8A5A00, 0xFFFFF4DD, 0xFFFFC53D);
    private static final Tone P_CLIMATE = new Tone(0xFFD6E4FF, 0xFFA9C4F5, 0xFF1F2A5C, 0xFF3B4785, 0xFF1F3FA0, 0xFFF0F5FF, 0xFF4F7BFF);
    private static final Tone P_HEAT = new Tone(0xFFFFE1D3, 0xFFFDBFA6, 0xFF4A2616, 0xFF7A4A35, 0xFFB8461A, 0xFFFFF1EA, 0xFFFF7A45);
    private static final Tone P_SCENE = new Tone(0xFFFFE9DA, 0xFFF6CDB0, 0xFF4A2E1A, 0xFF7A5238, 0xFF8A4A1A, 0xFFFFF5EC, 0xFFF0A060);
    private static final Tone P_COVER = new Tone(0xFFC9F0F2, 0xFF8FD8E0, 0xFF1D4650, 0xFF2F6370, 0xFF0E6E7A, 0xFFEFFBFC, 0xFF2CB8C8);
    private static final Tone P_LOCK = new Tone(0xFFD3F3E6, 0xFFA6E3CB, 0xFF13402F, 0xFF2E6B52, 0xFF13804F, 0xFFF0FBF6, 0xFF1FA971);
    private static final Tone P_UNLOCK = new Tone(0xFFFFE0DC, 0xFFF7B8B0, 0xFF4A1A15, 0xFF7A3A33, 0xFFB8302A, 0xFFFFF1EF, 0xFFE8584C);
    private static final Tone P_SWITCH = new Tone(0xFFE7E1FF, 0xFFC9BEFA, 0xFF2F2366, 0xFF4E4294, 0xFF4B3AC0, 0xFFF6F3FF, 0xFF6C5CE7);
    private static final Tone P_MUSIC = new Tone(0xFFEDE4FF, 0xFFCDBBF5, 0xFF2F2366, 0xFF4E4294, 0xFF6C3FD0, 0xFFF8F4FF, 0xFFB86BE0);
    private static final Tone P_OFF = new Tone(0xFFFFFFFF, 0xFFF1F3F6, 0xFF2B2F36, 0xFF707680, 0xFF45505E, 0xFFE9ECF0, 0x00000000);

    public Tone off() { return dark ? G_OFF : P_OFF; }
    public Tone music() { return dark ? G_MUSIC : P_MUSIC; }
    public Tone climate(boolean heat) { return heat ? (dark ? G_HEAT : P_HEAT) : (dark ? G_CLIMATE : P_CLIMATE); }

    /** tone of a tile: coloured when on (lights, switches) or always (scenes, covers, climate, locks) */
    public Tone tone(String kind, boolean on, boolean unavailable) {
        if (unavailable) return off();
        switch (kind) {
            case Dash.LIGHT: case Dash.DIMMER: return on ? (dark ? G_LIGHT : P_LIGHT) : off();
            case Dash.SWITCH: case Dash.FAN: case Dash.VALVE: case Dash.VACUUM: return on ? (dark ? G_SWITCH : P_SWITCH) : off();
            case Dash.CLIMATE: return on ? climate(false) : off();
            case Dash.HEATER: return on ? climate(true) : off();
            case Dash.COVER: return dark ? G_COVER : P_COVER;
            case Dash.SCENE: case Dash.SCRIPT: case Dash.BUTTON: return dark ? G_SCENE : P_SCENE;
            case Dash.LOCK: case Dash.ALARM: return on ? (dark ? G_LOCK : P_LOCK) : (dark ? G_UNLOCK : P_UNLOCK);
            default: return off();
        }
    }

    /**
     * auto: pearl while the sun is up (HA sun.sun when known, else 06:30–17:45), graphite otherwise.
     * @param sun "above_horizon" / "below_horizon" / null
     */
    public static Skin pick(String look, String sun) {
        if ("graphite".equals(look)) return GRAPHITE;
        if ("pearl".equals(look)) return PEARL;
        if ("above_horizon".equals(sun)) return PEARL;
        if ("below_horizon".equals(sun)) return GRAPHITE;
        Calendar c = Calendar.getInstance();
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return m >= 6 * 60 + 30 && m < 17 * 60 + 45 ? PEARL : GRAPHITE;
    }
}
