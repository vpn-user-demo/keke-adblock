package org.jak_linux.dns66;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 卡密验证管理类
 * 内置100张卡：50天卡 + 49月卡 + 1永久卡
 */
public class KeyManager {
    private static final String PREFS_NAME = "keke_activation";
    private static final String KEY_ACTIVATED = "activated";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_ACTIVATED_KEY = "activated_key";
    private static final String KEY_ACTIVATED_TIME = "activated_time";
    private static final String KEY_KEY_TYPE = "key_type"; // day, month, permanent

    // 卡密类型
    public static final int TYPE_DAY = 0;      // 天卡：1天
    public static final int TYPE_MONTH = 1;    // 月卡：30天
    public static final int TYPE_PERMANENT = 2;// 永久卡

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long MONTH_MS = 30L * DAY_MS;

    // 内置卡密列表
    private static final Map<String, Integer> keyMap = new HashMap<>();

    static {
        // 50张天卡: KEKE-D-0001 ~ KEKE-D-0050
        for (int i = 1; i <= 50; i++) {
            keyMap.put(String.format("KEKE-D-%04d", i), TYPE_DAY);
        }
        // 49张月卡: KEKE-M-0001 ~ KEKE-M-0049
        for (int i = 1; i <= 49; i++) {
            keyMap.put(String.format("KEKE-M-%04d", i), TYPE_MONTH);
        }
        // 1张永久卡
        keyMap.put("KEKE-P-0001", TYPE_PERMANENT);
    }

    private final Context context;
    private final SharedPreferences prefs;

    public KeyManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 获取设备唯一标识（Android ID）
     */
    private String getDeviceId() {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    /**
     * 检查是否已激活且未过期
     */
    public boolean isActivated() {
        if (!prefs.getBoolean(KEY_ACTIVATED, false)) {
            return false;
        }
        String savedDeviceId = prefs.getString(KEY_DEVICE_ID, "");
        String currentDeviceId = getDeviceId();
        if (!savedDeviceId.equals(currentDeviceId)) {
            return false; // 设备不匹配
        }

        int type = prefs.getInt(KEY_KEY_TYPE, -1);
        long activatedTime = prefs.getLong(KEY_ACTIVATED_TIME, 0);
        long now = System.currentTimeMillis();

        switch (type) {
            case TYPE_DAY:
                return (now - activatedTime) < DAY_MS;
            case TYPE_MONTH:
                return (now - activatedTime) < MONTH_MS;
            case TYPE_PERMANENT:
                return true;
            default:
                return false;
        }
    }

    /**
     * 验证并激活卡密
     * @return 错误信息，null表示成功
     */
    public String activate(String key) {
        if (TextUtils.isEmpty(key)) {
            return "请输入卡密";
        }
        key = key.trim().toUpperCase();

        Integer type = keyMap.get(key);
        if (type == null) {
            return "卡密无效，请检查后重试";
        }

        // 检查这张卡是否已经被其他设备用过
        // 简单硬编码版：不做服务端验证，直接激活
        // 注意：硬编码版无法防止同一卡密多设备使用

        String deviceId = getDeviceId();
        prefs.edit()
                .putBoolean(KEY_ACTIVATED, true)
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_ACTIVATED_KEY, key)
                .putLong(KEY_ACTIVATED_TIME, System.currentTimeMillis())
                .putInt(KEY_KEY_TYPE, type)
                .apply();

        return null;
    }

    /**
     * 获取剩余有效期提示
     */
    public String getExpiryInfo() {
        if (!isActivated()) {
            return "";
        }
        int type = prefs.getInt(KEY_KEY_TYPE, -1);
        long activatedTime = prefs.getLong(KEY_ACTIVATED_TIME, 0);
        long now = System.currentTimeMillis();

        switch (type) {
            case TYPE_DAY:
                long remainDay = (DAY_MS - (now - activatedTime)) / DAY_MS;
                return "天卡剩余 " + remainDay + " 天";
            case TYPE_MONTH:
                long remainMonth = (MONTH_MS - (now - activatedTime)) / DAY_MS;
                return "月卡剩余 " + remainMonth + " 天";
            case TYPE_PERMANENT:
                return "永久卡";
            default:
                return "";
        }
    }
}
