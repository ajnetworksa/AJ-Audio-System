package com.ajnetwork.ajpanel;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Panel text in English and Arabic (full right-to-left UI when Arabic is on). Setting "lang" = en | ar per panel.
 * Times and numbers keep Latin digits in both languages (easier to read across the room).
 */
public final class Lang {
    private Lang() { }
    public static volatile boolean ar;
    private static final Map<String, String[]> T = new HashMap<>();
    private static void t(String k, String en, String arText) { T.put(k, new String[]{en, arText}); }

    static {
        // navigation
        t("home", "Home", "الرئيسية");
        t("rooms", "Rooms", "الغرف");
        t("scenes", "Scenes", "المشاهد");
        t("climate", "Climate", "التكييف");
        t("music", "Music", "الموسيقى");
        t("security", "Security", "الأمان");
        t("apps", "Apps", "التطبيقات");
        t("settings", "Settings", "الإعدادات");
        t("back", "Back", "رجوع");
        // states
        t("on", "On", "تشغيل");
        t("off", "Off", "إيقاف");
        t("open", "Open", "مفتوح");
        t("closed", "Closed", "مغلق");
        t("opening", "Opening", "يفتح");
        t("closing", "Closing", "يغلق");
        t("stop", "Stop", "إيقاف");
        t("open_cmd", "Open", "فتح");
        t("close_cmd", "Close", "إغلاق");
        t("locked", "Locked", "مقفل");
        t("unlocked", "Unlocked", "غير مقفل");
        t("jammed", "Jammed", "عالق");
        t("lock_cmd", "Lock", "قفل");
        t("unlock_cmd", "Unlock", "فتح القفل");
        t("unavailable", "Offline", "غير متصل");
        t("run", "RUN", "تشغيل");
        t("done", "Done", "تم");
        t("cooling", "Cooling", "تبريد");
        t("heating", "Heating", "تدفئة");
        t("drying", "Drying", "تجفيف");
        t("fan_only", "Fan", "مروحة");
        t("idle", "Idle", "في وضع الانتظار");
        t("auto", "Auto", "تلقائي");
        t("cool", "Cool", "تبريد");
        t("heat", "Heat", "تدفئة");
        t("dry", "Dry", "تجفيف");
        t("fan", "Fan", "مروحة");
        t("now", "now", "الآن");
        t("armed_away", "Armed away", "مفعّل (خارج المنزل)");
        t("armed_home", "Armed home", "مفعّل (في المنزل)");
        t("armed_night", "Armed night", "مفعّل (ليلي)");
        t("disarmed", "Disarmed", "غير مفعّل");
        t("triggered", "ALARM", "إنذار");
        t("cleaning", "Cleaning", "ينظف");
        t("docked", "Docked", "في القاعدة");
        // kinds (tile sub line)
        t("k_dimmer", "Dimmer", "إضاءة متدرجة");
        t("k_light", "Light", "إضاءة");
        t("k_switch", "Switch", "مفتاح");
        t("k_fan", "Fan", "مروحة");
        t("k_cover", "Curtain", "ستارة");
        t("k_climate", "AC", "مكيف");
        t("k_heater", "Water heater", "سخان");
        t("k_lock", "Smart lock", "قفل ذكي");
        t("k_scene", "Scene", "مشهد");
        t("k_script", "Routine", "روتين");
        t("k_alarm", "Alarm", "الإنذار");
        t("k_vacuum", "Vacuum", "مكنسة");
        t("k_valve", "Valve", "صمام");
        t("k_button", "Button", "زر");
        // home
        t("now_playing", "NOW PLAYING", "يعمل الآن");
        t("nothing_playing", "Nothing playing", "لا يوجد تشغيل");
        t("tap_music", "Tap to choose music", "اضغط لاختيار الموسيقى");
        t("energy_today", "ENERGY TODAY", "استهلاك اليوم");
        t("vs_yesterday", "vs yesterday", "مقارنة بالأمس");
        t("lights_on", "%d lights on", "%d أضواء مضاءة");
        t("light_on", "1 light on", "ضوء واحد مضاء");
        t("all_off", "All lights off", "كل الأضواء مطفأة");
        t("music_playing", "music playing", "الموسيقى تعمل");
        t("open_n", "%d open", "%d مفتوح");
        t("alarm_chip", "Alarm", "إنذار");
        t("whole_home", "Whole home", "كل المنزل");
        t("this_room", "This room", "هذه الغرفة");
        t("other", "Other", "أخرى");
        t("no_devices", "No devices in this room yet", "لا توجد أجهزة في هذه الغرفة");
        t("no_scenes", "No scenes yet – create them in Home Assistant", "لا توجد مشاهد بعد – أنشئها في Home Assistant");
        t("connect_ha", "Connect Home Assistant", "اربط Home Assistant");
        t("connect_ha_sub", "Open Settings → Home Assistant and paste a token, or set it from the AJ Installer.", "افتح الإعدادات ← Home Assistant والصق الرمز، أو اضبطه من تطبيق AJ Installer.");
        t("ha_offline", "Home Assistant not reachable", "تعذر الوصول إلى Home Assistant");
        t("ha_token_bad", "Home Assistant token rejected", "رمز Home Assistant مرفوض");
        t("connecting", "Connecting…", "جارٍ الاتصال…");
        t("devices_n", "%d devices", "%d أجهزة");
        t("brightness", "Brightness", "السطوع");
        t("position", "Position", "الموضع");
        t("target", "Target", "الهدف");
        t("mode", "Mode", "الوضع");
        t("all_rooms", "ALL ROOMS", "كل الغرف");
        t("vol", "Vol", "الصوت");
        t("enter_pin", "Enter PIN", "أدخل الرمز");
        t("wrong_pin", "Wrong PIN", "رمز خاطئ");
        t("cancel", "Cancel", "إلغاء");
        t("no_apps", "No apps", "لا توجد تطبيقات");
        t("welcome", "Welcome", "أهلاً وسهلاً");
        t("humidity", "Humidity", "الرطوبة");
        t("temperature", "Temperature", "الحرارة");
        t("openings", "Doors & windows", "الأبواب والنوافذ");
        t("motion", "Motion", "الحركة");
        t("detected", "Detected", "تم الرصد");
        t("clear", "Clear", "لا يوجد");
        t("smoke", "Smoke / leak", "دخان / تسرب");
        t("ok", "OK", "سليم");
        t("all_closed", "All closed", "الكل مغلق");
    }

    public static String s(String key) {
        String[] v = T.get(key);
        if (v == null) return key;
        return ar ? v[1] : v[0];
    }
    public static String f(String key, int n) { return String.format(Locale.US, s(key), n); }

    /** locale for day and month names; Latin digits */
    public static Locale locale() { return ar ? Locale.forLanguageTag("ar-SA-u-nu-latn") : Locale.UK; }

    /** HA state → short label */
    public static String state(String st) {
        if (st == null) return "";
        String k = st.toLowerCase(Locale.ROOT);
        if (T.containsKey(k)) return s(k);
        if (k.equals("unknown")) return s("unavailable");
        return st;
    }
}
