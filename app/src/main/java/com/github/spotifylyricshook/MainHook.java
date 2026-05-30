package com.github.spotifylyricshook;

import android.graphics.Color;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

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

        XposedBridge.log("[SLH] handleLoadPackage: com.spotify.music");

        sHttp = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11)")
                .header("Referer", "https://music.163.com")
                .build()))
            .build();

        // Hook 1: has_lyrics 检查 → 始终返回 true，强制 Spotify 走歌词 fetch 链
        try {
            Class<?> ctClass = lpparam.classLoader.loadClass("com.spotify.player.model.ContextTrack");
            XposedHelpers.findAndHookMethod("p.g831", lpparam.classLoader, "x", ctClass,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        boolean orig;
                        try {
                            orig = (boolean) XposedBridge.invokeOriginalMethod(
                                param.method, param.thisObject, param.args);
                        } catch (Throwable t) {
                            orig = false;
                        }
                        if (!orig) {
                            XposedBridge.log("[SLH] has_lyrics overridden → true");
                        }
                        return true;
                    }
                });
            XposedBridge.log("[SLH] g831.x hook OK");
        } catch (Throwable t) {
            XposedBridge.log("[SLH] g831.x hook FAILED: " + t);
        }

        // Hook 2: track 切换时缓存歌名/歌手（metadata() 返回 fxy 实现了 Map，直接 cast）
        try {
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

                            Map<?, ?> meta = (Map<?, ?>) XposedHelpers.callMethod(contextTrack, "metadata");
                            if (meta == null) {
                                XposedBridge.log("[SLH] metadata null, uri=" + uri);
                                return;
                            }

                            String title = (String) meta.get("title");
                            String artist = (String) meta.get("artist_name");
                            if (artist == null) artist = (String) meta.get("artist");

                            if (title != null && !title.isEmpty()) {
                                sTrackCache.put(uri, new String[]{title, artist != null ? artist : ""});
                                XposedBridge.log("[SLH] cached: " + title + " / " + artist);
                            } else {
                                XposedBridge.log("[SLH] title empty, keys=" + meta.keySet());
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[SLH] cw30 hook error: " + t);
                        }
                    }
                });
            XposedBridge.log("[SLH] cw30 hook OK");
        } catch (Throwable t) {
            XposedBridge.log("[SLH] cw30 hook FAILED: " + t);
        }

        // Hook 3: 歌词 entity 转 UI model，lines 为空时注入网易云
        try {
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
                            XposedBridge.log("[SLH] empty lyrics, uri=" + uri);

                            String[] info = sTrackCache.get(uri);
                            if (info == null) {
                                XposedBridge.log("[SLH] no cache for uri=" + uri
                                    + " (cache size=" + sTrackCache.size() + ")");
                                return;
                            }

                            XposedBridge.log("[SLH] fetching NetEase: " + info[0] + " / " + info[1]);
                            List<Object> om40Lines = fetchNetease(info[0], info[1], lpparam.classLoader);
                            if (om40Lines == null || om40Lines.isEmpty()) return;

                            param.setResult(buildAwc(om40Lines, lpparam.classLoader));
                            XposedBridge.log("[SLH] injected " + om40Lines.size() + " lines: " + info[0]);
                        } catch (Throwable t) {
                            XposedBridge.log("[SLH] oj40 hook error: " + t);
                        }
                    }
                });
            XposedBridge.log("[SLH] oj40 hook OK");
        } catch (Throwable t) {
            XposedBridge.log("[SLH] oj40 hook FAILED: " + t);
        }
    }

    private static List<Object> fetchNetease(String title, String artist, ClassLoader cl) {
        try {
            String q = URLEncoder.encode(title + " " + artist, "UTF-8");
            String searchUrl = "https://music.163.com/api/search/get?s=" + q + "&type=1&limit=5";
            XposedBridge.log("[SLH] NetEase search: " + searchUrl);

            Response searchResp = sHttp.newCall(new Request.Builder().url(searchUrl).build()).execute();
            if (!searchResp.isSuccessful()) {
                XposedBridge.log("[SLH] NetEase search HTTP " + searchResp.code());
                return null;
            }
            String searchBody = searchResp.body().string();

            JSONObject resultObj = new JSONObject(searchBody).optJSONObject("result");
            if (resultObj == null) {
                XposedBridge.log("[SLH] NetEase search: no 'result' field, body=" + searchBody.substring(0, Math.min(200, searchBody.length())));
                return null;
            }
            JSONArray songs = resultObj.optJSONArray("songs");
            if (songs == null || songs.length() == 0) {
                XposedBridge.log("[SLH] NetEase search: no songs for query=" + q);
                return null;
            }

            long songId = songs.getJSONObject(0).getLong("id");
            String songName = songs.getJSONObject(0).optString("name");
            XposedBridge.log("[SLH] NetEase matched: " + songName + " (id=" + songId + ")");

            String lrcUrl = "https://music.163.com/api/song/lyric?id=" + songId + "&lv=1";
            Response lrcResp = sHttp.newCall(new Request.Builder().url(lrcUrl).build()).execute();
            if (!lrcResp.isSuccessful()) {
                XposedBridge.log("[SLH] NetEase lyric HTTP " + lrcResp.code());
                return null;
            }
            String lrcBody = lrcResp.body().string();

            JSONObject lrcObj = new JSONObject(lrcBody).optJSONObject("lrc");
            if (lrcObj == null) {
                XposedBridge.log("[SLH] NetEase lyric: no 'lrc' field");
                return null;
            }
            String lrc = lrcObj.optString("lyric", "");
            if (lrc.isEmpty()) {
                XposedBridge.log("[SLH] NetEase lyric: empty lrc");
                return null;
            }

            List<Object> result = parseLrc(lrc, cl);
            XposedBridge.log("[SLH] parsed " + result.size() + " lines from LRC");
            return result;
        } catch (Throwable t) {
            XposedBridge.log("[SLH] fetchNetease error: " + t);
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