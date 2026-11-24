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
        t("k_gate", "Gate", "بوابة");
        t("open_gate", "OPEN", "فتح");
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
        t("other", "General", "عام");
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

    // ------------------------------------------------------------------ music screen (keyed by its English text)
    private static final Map<String, String> TR = new HashMap<>();
    private static void r(String en, String arText) { TR.put(en, arText); }
    static {
        r("Add", "إضافة");
        r("Albums", "الألبومات");
        r("All ", "الكل ");
        r("All rooms", "كل الغرف");
        r("Anything you play here is heard there too", "كل ما تشغله هنا يُسمع هناك أيضاً");
        r("App update", "تحديث التطبيق");
        r("Artists", "الفنانون");
        r("Break", "استراحة");
        r("Browse", "تصفح");
        r("Cancel", "إلغاء");
        r("Change", "تغيير");
        r("Close", "إغلاق");
        r("Couldn't load the library (", "تعذر تحميل المكتبة (");
        r("Done", "تم");
        r("Downloading…", "جارٍ التنزيل…");
        r("Everywhere", "في كل مكان");
        r("Group rooms", "تجميع الغرف");
        r("Group rooms…", "تجميع الغرف…");
        r("Group this room", "ضم هذه الغرفة");
        r("Group", "تجميع");
        r("Groups", "المجموعات");
        r("Install?", "تثبيت؟");
        r("Join", "انضمام");
        r("Library", "المكتبة");
        r("Live", "مباشر");
        r("Loading rooms…", "جارٍ تحميل الغرف…");
        r("Loading…", "جارٍ التحميل…");
        r("Music", "الموسيقى");
        r("Mute this room", "كتم هذه الغرفة");
        r("Next", "التالي");
        r("Nothing found for “", "لا توجد نتائج لـ “");
        r("Nothing here yet – add music in Music Assistant", "لا يوجد شيء بعد – أضف الموسيقى في Music Assistant");
        r("Nothing played yet", "لم يتم تشغيل شيء بعد");
        r("Nothing playing", "لا يوجد تشغيل");
        r("Offline", "غير متصل");
        r("On a break from ", "في استراحة من ");
        r("Only ", "فقط ");
        r("Only this room", "هذه الغرفة فقط");
        r("Open Music", "فتح الموسيقى");
        r("Or rooms (", "أو الغرف (");
        r("Paused", "متوقف مؤقتاً");
        r("Pick a group to play in all its rooms. To make a new group, use Group rooms.", "اختر مجموعة للتشغيل في كل غرفها. لإنشاء مجموعة جديدة استخدم تجميع الغرف.");
        r("Pick rooms", "اختيار الغرف");
        r("Pick something in Music, or play from the AJ Audio app, AirPlay or Bluetooth.", "اختر شيئاً من المكتبة، أو شغّل من تطبيق AJ Audio أو AirPlay أو البلوتوث.");
        r("Play in ", "تشغيل في ");
        r("Play music in other rooms", "تشغيل الموسيقى في غرف أخرى");
        r("Play on ", "تشغيل على ");
        r("Play on", "تشغيل على");
        r("Play or pause", "تشغيل أو إيقاف مؤقت");
        r("Play this panel in…", "تشغيل صوت هذه الشاشة في…");
        r("Play", "تشغيل");
        r("Played by ", "شغّلها ");
        r("Playing ", "يعمل ");
        r("Playing in other rooms", "يعمل في غرف أخرى");
        r("Playing in: ", "يعمل في: ");
        r("Playing with this room", "يعمل مع هذه الغرفة");
        r("Playing “", "يتم تشغيل “");
        r("Playing", "يعمل الآن");
        r("Playlists", "قوائم التشغيل");
        r("Previous", "السابق");
        r("Radio", "الراديو");
        r("Recent", "الأخيرة");
        r("Rejoin", "العودة");
        r("Results for “", "نتائج “");
        r("Rooms", "الغرف");
        r("Scenes", "المشاهد");
        r("Search songs, artists, radio", "ابحث عن أغانٍ أو فنانين أو راديو");
        r("Searching for “", "جارٍ البحث عن “");
        r("Share", "مشاركة");
        r("Sharing stopped", "توقفت المشاركة");
        r("Songs", "الأغاني");
        r("Speakers", "السماعات");
        r("Starting: ", "جارٍ البدء: ");
        r("Starting…", "جارٍ البدء…");
        r("Stop sharing", "إيقاف المشاركة");
        r("Stop", "إيقاف");
        r("Take a break from ", "استراحة من ");
        r("Take a break", "خذ استراحة");
        r("Tap Install in the Android window", "اضغط تثبيت في نافذة أندرويد");
        r("Tap to play on ", "اضغط للتشغيل على ");
        r("Tap to play", "اضغط للتشغيل");
        r("This room is in it", "هذه الغرفة ضمنها");
        r("This room", "هذه الغرفة");
        r("Tick the rooms that should play together with ", "حدد الغرف التي تعمل مع ");
        r("Try again", "حاول مجدداً");
        r("Unmute this room", "إلغاء كتم هذه الغرفة");
        r("Until 07:00", "حتى 07:00");
        r("Until I stop my own music", "حتى أوقف موسيقاي");
        r("Until the group's music ends", "حتى تنتهي موسيقى المجموعة");
        r("Update", "تحديث");
        r("Volume · ", "الصوت · ");
        r("Whatever plays on ", "كل ما يعمل على ");
        r("Where this room plays now", "أين تعمل هذه الغرفة الآن");
        r("Where to play", "مكان التشغيل");
        r("You have ", "لديك ");
        r("AJ Panel vX is available", "يتوفر AJ Panel vX");
        r("This panel runs Android ", "هذه الشاشة تعمل بنظام أندرويد ");
        r("What works on this panel, in sync in every room:\n", "ما يعمل على هذه الشاشة بتزامن في كل غرفة:\n");
    }
    /** English text of the music screen → Arabic when Arabic is on (unknown text stays English) */
    public static String tr(String en) { if (!ar) return en; String a = TR.get(en); return a == null ? en : a; }

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
