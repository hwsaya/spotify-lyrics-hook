package com.github.spotifylyricshook;

import android.graphics.Color;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import okhttp3.OkHttpClient;
import okhttp3.Request;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainHook implements IXposedHookLoadPackage {

    private static final Map<String, String[]> sTrackCache = new ConcurrentHashMap<>();
    private static OkHttpClient sHttp;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.spotify.music".equals(lpparam.packageName)) return;

        sHttp = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11)")
                .header("Referer", "https://music.163.com")
                .build()))
            .build();

        XposedHelpers.findAndHookMethod("p.cw30", lpparam.classLoader, "apply", Object.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (XposedHelpers.getIntField(param.thisObject, "a") != 14) return;
                    try {
                        Object playerState = param.args[0];
                        Object trackWrapper = XposedHelpers.callMethod(playerState, "track");
                        Object contextTrack = XposedHelpers.callMethod(trackWrapper, "h");
                        if (contextTrack == null) return;

                        String uri = (String) XposedHelpers.callMethod(contextTrack, "uri");
                        if (uri == null || uri.isEmpty() || sTrackCache.containsKey(uri)) return;

                        Map<String, String> meta = getMetadata(contextTrack);
                        if (meta == null) return;

                        String title = meta.get("title");
                        String artist = meta.get("artist_name");
                        if (artist == null) artist = meta.get("artist");
                        if (title != null && !title.isEmpty()) {
                            sTrackCache.put(uri, new String[]{title, artist != null ? artist : ""});
                        }
                    } catch (Throwable ignored) {}
                }
            });

        XposedHelpers.findAndHookMethod("p.oj40", lpparam.classLoader, "apply", Object.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (XposedHelpers.getIntField(param.thisObject, "a") != 15) return;
                    try {
                        Object tn40 = param.args[0];
                        List<?> lines = (List<?>) XposedHelpers.getObjectField(tn40, "b");
                        if (lines != null && !lines.isEmpty()) return;

                        String uri = (String) XposedHelpers.getObjectField(tn40, "a");
                        String[] info = sTrackCache.get(uri);
                        if (info == null) return;

                        List<Object> om40Lines = fetchNetease(info[0], info[1], lpparam.classLoader);
                        if (om40Lines == null || om40Lines.isEmpty()) return;

                        param.setResult(buildAwc(om40Lines, lpparam.classLoader));
                    } catch (Throwable ignored) {}
                }
            });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> getMetadata(Object contextTrack) {
        String[] candidates = {"metadata", "getMetadataMap"};
        for (String m : candidates) {
            try {
                return (Map<String, String>) XposedHelpers.callMethod(contextTrack, m);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static List<Object> fetchNetease(String title, String artist, ClassLoader cl) {
        try {
            String q = URLEncoder.encode(title + " " + artist, "UTF-8");
            String searchJson = sHttp.newCall(new Request.Builder()
                .url("https://music.163.com/api/search/get?s=" + q + "&type=1&limit=5")
                .build()).execute().body().string();

            JSONArray songs = new JSONObject(searchJson).getJSONObject("result").getJSONArray("songs");
            if (songs.length() == 0) return null;
            long songId = songs.getJSONObject(0).getLong("id");

            String lrcJson = sHttp.newCall(new Request.Builder()
                .url("https://music.163.com/api/song/lyric?id=" + songId + "&lv=1")
                .build()).execute().body().string();

            String lrc = new JSONObject(lrcJson).getJSONObject("lrc").getString("lyric");
            return parseLrc(lrc, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<Object> parseLrc(String lrc, ClassLoader cl) throws Exception {
        Constructor<?> ctor = cl.loadClass("p.om40")
            .getConstructor(long.class, String.class, ArrayList.class);
        Pattern p = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})](.*)");
        List<Object> result = new ArrayList<>();
        for (String line : lrc.split("\n")) {
            Matcher m = p.matcher(line.trim());
            if (!m.matches()) continue;
            String ms3 = m.group(3);
            long ms = Long.parseLong(m.group(1)) * 60_000
                    + Long.parseLong(m.group(2)) * 1_000
                    + Long.parseLong(ms3.length() == 2 ? ms3 + "0" : ms3);
            String words = m.group(4).trim();
            if (!words.isEmpty())
                result.add(ctor.newInstance(ms, words, new ArrayList<>()));
        }
        return result;
    }

    private static Object buildAwc(List<Object> lines, ClassLoader cl) throws Exception {
        Class<?> pm40Cls = cl.loadClass("p.pm40");
        Class<?> sm40Cls = cl.loadClass("p.sm40");
        Class<?> rn40Cls = cl.loadClass("p.rn40");
        Class<?> awcCls  = cl.loadClass("p.awc");

        Object pm40 = pm40Cls
            .getConstructor(String.class, String.class, String.class)
            .newInstance("netease", "NetEase", "网易云音乐");
        Object sm40 = sm40Cls
            .getConstructor(boolean.class)
            .newInstance(false);
        Object rn40 = rn40Cls
            .getConstructor(int.class, int.class, int.class, int.class, int.class, boolean.class)
            .newInstance(
                Color.parseColor("#FF353535"),
                Color.parseColor("#FF565656"),
                Color.parseColor("#FFFFFFFF"),
                Color.parseColor("#FFCDCDCD"),
                Color.parseColor("#FF1ED760"),
                true
            );

        return awcCls
            .getConstructor(List.class, int.class, List.class, String.class,
                boolean.class, pm40Cls, sm40Cls, int.class, rn40Cls)
            .newInstance(lines, 2, Collections.emptyList(), "zh", false, pm40, sm40, 1, rn40);
    }
}
