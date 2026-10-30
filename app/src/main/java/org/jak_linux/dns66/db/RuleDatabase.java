/* Copyright (C) 2016 - 2017 Software Freedom Conservancy (author: Julian Andres Klode) <jak@jak-linux.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.jak_linux.dns66.db;

import android.content.Context;
import androidx.annotation.Nullable;
import android.util.Log;

import org.jak_linux.dns66.Configuration;
import org.jak_linux.dns66.FileHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents hosts that are blocked.
 * <p>
 * This is a very basic set of hosts. But it supports lock-free
 * readers with writers active at the same time, only the writers
 * having to take a lock.
 */
public class RuleDatabase {

    private static final String TAG = "RuleDatabase";
    private static final RuleDatabase instance = new RuleDatabase();
    final AtomicReference<HashSet<String>> blockedHosts = new AtomicReference<>(new HashSet<String>());
    HashSet<String> nextBlockedHosts = null;
    private final AtomicLong blockedRequestCount = new AtomicLong(0);
    private final AtomicReference<Set<String>> keywordRules = new AtomicReference<>(new HashSet<String>());
    private final AtomicReference<Set<String>> allowRules = new AtomicReference<>(new HashSet<String>());
    private final AtomicReference<Set<String>> allowKeywordRules = new AtomicReference<>(new HashSet<String>());

    /** 当前拦截模式, 0 = DNS, 1 = 规则(黑洞 IP) */
    public static volatile int blockMode = 0;

    /** 由启动的服务决定: -1 = 跟随配置, 0 = DNS 通道, 1 = 规则通道 */
    public static volatile int modeOverride = -1;

    /**
     * 核心保护域名清单（我们自己的算法第 1 级）:
     * 银行、支付、系统更新、主流 APP 主站等「绝不能误拦」的域名。
     * host 或其父域命中这里 → 一律放行，优先级最高，任何规则都拦不到它。
     */
    private static final Set<String> CORE_PROTECT = new HashSet<>(Arrays.asList(
            // 支付
            "alipay.com", "weixin.qq.com", "weixin.com", "wechat.com", "tenpay.com",
            "unionpay.com", "95599.cn", "95588.cn", "95533.com",
            // 银行
            "icbc.com.cn", "ccb.com", "abchina.com", "boc.cn", "cmbchina.com", "cmb.com",
            "ecitic.com", "cgbchina.com.cn", "spdb.com.cn", "pab.com.cn", "cib.com.cn",
            "hxb.com.cn", "bankcomm.com", "cgbchina.com", "cebbank.com", "bankofbeijing.com.cn",
            // 主流 APP 主站
            "taobao.com", "tmall.com", "jd.com", "qq.com", "baidu.com", "douyin.com",
            "kuaishou.com", "gifshow.com", "bilibili.com", "weibo.com", "meituan.com",
            "dianping.com", "pinduoduo.com", "yangkeduo.com", "xiaohongshu.com", "zhihu.com",
            "163.com", "126.com", "douban.com", "zhuanzhuan.com", "xianyuapp.com",
            // 系统 / 应用商店 / 通用服务
            "google.com", "googleapis.com", "googleusercontent.com", "gstatic.com",
            "android.com", "microsoft.com", "apple.com", "icloud.com", "update.microsoft.com",
            "oppomobile.com", "vivoglobal.com", "mi.com", "xiaomi.com", "huawei.com",
            "vmall.com", "samsung.com", "onesignal.com", "firebaseio.com", "play.google.com"
    ));

    /**
     * 我们自己的算法：判断 host 或其父域是否命中核心保护清单。
     */
    private static boolean isCoreProtected(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ENGLISH);
        if (CORE_PROTECT.contains(h)) return true;
        int idx = h.indexOf('.');
        while (idx > 0) {
            h = h.substring(idx + 1);
            if (CORE_PROTECT.contains(h)) return true;
            idx = h.indexOf('.');
        }
        return false;
    }

    /**
     * 我们自己的算法：判定一个关键字是不是「强广告关键字」。
     * 强关键字证据力足，单条命中即可拦截；弱关键字（如 ad/track 这类可能误伤的短词）
     * 单条不拦，要凑够多条证据才拦，从而减少误拦核心。
     */
    private static boolean isStrongKeyword(String kw) {
        String k = kw.toLowerCase(Locale.ENGLISH);
        if (k.length() < 6) return false;
        if (k.contains("doubleclick") || k.contains("adsrv") || k.contains("adnxs")
                || k.contains("googlesyndication") || k.contains("mmstat") || k.contains("umeng")
                || k.contains("cnzz") || k.contains("51.la") || k.contains("adserver")
                || k.contains("adsense") || k.contains("advertising") || k.contains("adservice")
                || k.contains("metrics") || k.contains("analytics") || k.contains("telemetry")
                || k.contains("tracking") || k.contains("collection") || k.contains("pixel")
                || k.contains("adclick") || k.contains("adtech") || k.contains("adsrvr")) {
            return true;
        }
        return false;
    }

    /**
     * Package-private constructor for instance and unit tests.
     */
    RuleDatabase() {

    }


    /**
     * Returns the instance of the rule database.
     */
    public static RuleDatabase getInstance() {
        return instance;
    }

    /**
     * Parse a single line in a hosts file
     *
     * @param line A line to parse
     * @return A host
     */
    @Nullable
    static String parseLine(String line) {
        int endOfLine = line.indexOf('#');

        if (endOfLine == -1)
            endOfLine = line.length();

        // Trim spaces
        while (endOfLine > 0 && Character.isWhitespace(line.charAt(endOfLine - 1)))
            endOfLine--;

        // The line is empty.
        if (endOfLine <= 0)
            return null;

        // Find beginning of host field
        int startOfHost = 0;

        if (line.regionMatches(0, "127.0.0.1", 0, 9) && (endOfLine <= 9 || Character.isWhitespace(line.charAt(9))))
            startOfHost += 10;
        else if (line.regionMatches(0, "::1", 0, 3) && (endOfLine <= 3 || Character.isWhitespace(line.charAt(3))))
            startOfHost += 4;
        else if (line.regionMatches(0, "0.0.0.0", 0, 7) && (endOfLine <= 7 || Character.isWhitespace(line.charAt(7))))
            startOfHost += 8;

        // Trim of space at the beginning of the host.
        while (startOfHost < endOfLine && Character.isWhitespace(line.charAt(startOfHost)))
            startOfHost++;

        // Reject lines containing a space
        for (int i = startOfHost; i < endOfLine; i++) {
            if (Character.isWhitespace(line.charAt(i)))
                return null;
        }

        if (startOfHost >= endOfLine)
            return null;

        return line.substring(startOfHost, endOfLine).toLowerCase(Locale.ENGLISH);
    }

    /**
     * Checks if a host is blocked.
     *
     * @param host A hostname
     * @return true if the host is blocked, false otherwise.
     */
    public boolean isBlocked(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }

        // 第 1 级 · 核心保护（最高优先，永不误拦）
        // 银行 / 支付 / 系统 / 主流 APP 主站：一律放行
        if (isCoreProtected(host)) {
            return false;
        }

        // 放行规则优先: 命中也放行
        if (allowRules.get().contains(host)) {
            return false;
        }
        for (String allowed : allowKeywordRules.get()) {
            if (!allowed.isEmpty() && host.contains(allowed)) {
                return false;
            }
        }

        // 第 2 级 · 精确黑名单: 命中内置域名库 → 拦
        if (blockedHosts.get().contains(host)) {
            return true;
        }

        // 第 3 级 · 关键字匹配: 命中即拦
        // 核心域名已经在第 1 级被保护放行，所以这里不用再计分收窄，
        // 命中广告关键字就直接拦，广告照拦、核心照样安全。
        for (String keyword : keywordRules.get()) {
            if (!keyword.isEmpty() && host.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 规则文件里的关键字条数 */
    public int getKeywordCount() {
        return keywordRules.get().size();
    }

    /**
     * Check if any hosts are blocked
     *
     * @return true if any hosts are blocked, false otherwise.
     */
    boolean isEmpty() {
        return blockedHosts.get().isEmpty();
    }

    /**
     * Get the number of currently blocked hosts.
     *
     * @return The number of blocked hosts.
     */
    public int getBlockedHostCount() {
        return blockedHosts.get().size();
    }

    /**
     * Get a copy of the currently blocked hosts.
     *
     * @return A set copy of blocked host names.
     */
    public Set<String> getBlockedHosts() {
        return new HashSet<>(blockedHosts.get());
    }

    /**
     * Count one blocked DNS request.
     */
    public void incrementBlockedCount() {
        blockedRequestCount.incrementAndGet();
    }

    /**
     * Get the total number of blocked DNS requests since service start.
     *
     * @return The number of blocked requests.
     */
    public long getBlockedRequestCount() {
        return blockedRequestCount.get();
    }

    /**
     * Load the hosts according to the configuration
     *
     * @param context A context used for opening files.
     * @throws InterruptedException Thrown if the thread was interrupted, so we don't waste time
     *                              reading more host files than needed.
     */
    public synchronized void initialize(Context context) throws InterruptedException {
        Configuration config = FileHelper.loadCurrentSettings(context);

        nextBlockedHosts = new HashSet<>(blockedHosts.get().size());

        Log.i(TAG, "Loading block list");
        int mode = modeOverride >= 0 ? modeOverride : config.blockMode;
        if (mode == 1) {
            // 规则拦截模式：只加载自定义规则（国内专项 + 开源域名 + 关键字）
            Log.i(TAG, "Rule mode: loading custom domains + keywords");
            loadExternalRules(context);
        } else {
            // DNS 拦截模式：只加载内置 blocklist（DNS66 原生列表）
            Log.i(TAG, "DNS mode: loading builtin blocklist");
            if (!config.hosts.enabled) {
                Log.d(TAG, "loadBlockedHosts: Not loading, disabled.");
            } else {
                for (Configuration.Item item : config.hosts.items) {
                    if (Thread.interrupted())
                        throw new InterruptedException("Interrupted");
                    loadItem(context, item);
                }
            }
        }
        blockMode = mode;
        blockedHosts.set(nextBlockedHosts);
        Runtime.getRuntime().gc();
    }

    /**
     * Loads an item. An item can be backed by a file or contain a value in the location field.
     *
     * @param context Context to open files
     * @param item    The item to load.
     * @throws InterruptedException If the thread was interrupted.
     */
    private void loadItem(Context context, Configuration.Item item) throws InterruptedException {
        if (item.state == Configuration.Item.STATE_IGNORE)
            return;

        InputStreamReader reader;
        try {
            reader = FileHelper.openItemFile(context, item);
        } catch (FileNotFoundException e) {
            Log.d(TAG, "loadItem: File not found: " + item.location);
            return;
        }

        if (reader == null) {
            addHost(item, item.location);
            return;
        } else {
            loadReader(item, reader);
        }
    }

    /**
     * Add a single host for an item.
     *
     * @param item The item the host belongs to
     * @param host The host
     */
    private void addHost(Configuration.Item item, String host) {
        // Single address to block
        if (item.state == Configuration.Item.STATE_ALLOW) {
            nextBlockedHosts.remove(host);
        } else if (item.state == Configuration.Item.STATE_DENY) {
            nextBlockedHosts.add(host);
        }
    }

    /**
     * Load a single file
     *
     * @param item   The configuration item referencing the file
     * @param reader A reader to read lines from
     * @throws InterruptedException If thread was interrupted
     */
    boolean loadReader(Configuration.Item item, Reader reader) throws InterruptedException {
        int count = 0;
        try {
            Log.d(TAG, "loadBlockedHosts: Reading: " + item.location);
            try (BufferedReader br = new BufferedReader(reader)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (Thread.interrupted())
                        throw new InterruptedException("Interrupted");
                    String host = parseLine(line);
                    if (host != null) {
                        count += 1;
                        addHost(item, host);
                    }
                }
            }
            Log.d(TAG, "loadBlockedHosts: Loaded " + count + " hosts from " + item.location);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "loadBlockedHosts: Error while reading " + item.location + " after " + count + " items", e);
            return false;
        } finally {
            FileHelper.closeOrWarn(reader, TAG, "loadBlockedHosts: Error closing " + item.location);
        }
    }
    /**
     * 加载通过“拦截模式”页面导入的规则文件。
     * 域名规则按 hosts 语义并入精确匹配集合，关键字规则单独匹配。
     */
    private void loadExternalRules(Context context) {
        Set<String> keywords = new HashSet<>();
        BufferedReader reader = openRules(context, org.jak_linux.dns66.RuleImporter.FILE_KEYWORDS);
        if (reader != null) {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim().toLowerCase(Locale.ENGLISH);
                    if (!line.isEmpty()) {
                        keywords.add(line);
                    }
                }
                reader.close();
            } catch (IOException e) {
                Log.w(TAG, "Cannot read keyword rules", e);
            }
        }
        BufferedReader domainReader = openRules(context, org.jak_linux.dns66.RuleImporter.FILE_DOMAINS);
        if (domainReader != null) {
            try {
                String line;
                while ((line = domainReader.readLine()) != null) {
                    line = line.trim().toLowerCase(Locale.ENGLISH);
                    if (!line.isEmpty()) {
                        nextBlockedHosts.add(line);
                    }
                }
                domainReader.close();
            } catch (IOException e) {
                Log.w(TAG, "Cannot read domain rules", e);
            }
        }
        keywordRules.set(keywords);

        Set<String> allow = new HashSet<>();
        Set<String> allowKw = new HashSet<>();
        BufferedReader allowReader = openRules(context, org.jak_linux.dns66.RuleImporter.FILE_ALLOW);
        if (allowReader != null) {
            try {
                String line;
                while ((line = allowReader.readLine()) != null) {
                    line = line.trim().toLowerCase(Locale.ENGLISH);
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.startsWith("k:")) {
                        allowKw.add(line.substring(2));
                    } else {
                        allow.add(line);
                    }
                }
                allowReader.close();
            } catch (IOException e) {
                Log.w(TAG, "Cannot read allow rules", e);
            }
        }
        allowRules.set(allow);
        allowKeywordRules.set(allowKw);

        Log.i(TAG, "Loaded rules: " + nextBlockedHosts.size() + " hosts, "
                + keywords.size() + " keywords, " + allow.size() + " allowed");
    }

    /** 优先读应用私有目录里的规则(导入/下发)，没有就回退到 APK 内置的规则 */
    private static BufferedReader openRules(Context context, String name) {
        File f = new File(context.getFilesDir(), name);
        try {
            if (f.exists()) {
                return new BufferedReader(new java.io.InputStreamReader(
                        new FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8));
            }
            return new BufferedReader(new java.io.InputStreamReader(
                    context.getAssets().open(name), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Cannot open rules " + name, e);
            return null;
        }
    }
}