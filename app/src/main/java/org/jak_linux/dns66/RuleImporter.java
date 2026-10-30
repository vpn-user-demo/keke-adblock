package org.jak_linux.dns66;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 导入 Shadowrocket / Quantumult 风格的域名规则:
 *   DOMAIN,example.com,REJECT
 *   DOMAIN-KEYWORD,cdn,REJECT
 * 解析后拆成两个文件,供 RuleDatabase 加载。
 */
public final class RuleImporter {

    public static final String FILE_DOMAINS = "dns66_rules_domains.txt";
    public static final String FILE_KEYWORDS = "dns66_rules_keywords.txt";
    public static final String FILE_ALLOW = "dns66_rules_allow.txt";

    private RuleImporter() {
    }

    /** @return {域名条数, 关键字条数} */
    public static int[] importStream(Context context, InputStream in) throws IOException {
        Set<String> domains = new LinkedHashSet<>();
        Set<String> keywords = new LinkedHashSet<>();
        Set<String> allow = new LinkedHashSet<>();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            String[] parts = line.split(",");
            String head = parts[0].trim().toUpperCase(Locale.ENGLISH);
            String action = parts.length >= 3 ? parts[2].trim().toUpperCase(Locale.ENGLISH) : "";
            boolean isAllow = action.equals("DIRECT") || action.equals("ALLOW")
                    || action.equals("WHITELIST") || action.equals("BYPASS");
            if (parts.length >= 2 && head.equals("DOMAIN")) {
                String host = parts[1].trim().toLowerCase(Locale.ENGLISH);
                if (isAllow) {
                    allow.add(host);
                } else {
                    domains.add(host);
                }
            } else if (parts.length >= 2 && head.equals("DOMAIN-KEYWORD")) {
                String kw = parts[1].trim().toLowerCase(Locale.ENGLISH);
                if (isAllow) {
                    allow.add("K:" + kw);
                } else {
                    keywords.add(kw);
                }
            } else if (line.startsWith("||")) {
                String d = line.replace("||", "").replace("^", "").trim();
                if (!d.isEmpty()) {
                    domains.add(d.toLowerCase(Locale.ENGLISH));
                }
            } else if (!line.contains(" ")) {
                domains.add(line.toLowerCase(Locale.ENGLISH));
            }
        }
        reader.close();

        write(context, FILE_DOMAINS, domains);
        write(context, FILE_KEYWORDS, keywords);
        write(context, FILE_ALLOW, allow);
        return new int[]{domains.size(), keywords.size()};
    }

    private static void write(Context context, String name, Set<String> lines) throws IOException {
        File out = new File(context.getFilesDir(), name);
        FileOutputStream fos = new FileOutputStream(out);
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            sb.append(s).append('\n');
        }
        fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        fos.close();
    }

    public static int countFromAssets(Context context, String name) {
        try {
            InputStream in = context.getAssets().open(name);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            int n = 0;
            while (r.readLine() != null) {
                n++;
            }
            r.close();
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    public static int countDomains(Context context) {
        return countLines(new File(context.getFilesDir(), FILE_DOMAINS));
    }

    public static int countKeywords(Context context) {
        return countLines(new File(context.getFilesDir(), FILE_KEYWORDS));
    }
    public static int countAllow(Context context) {
        return countLines(new File(context.getFilesDir(), FILE_ALLOW));
    }

    private static int countLines(File f) {
        if (!f.exists()) {
            return 0;
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream(f), StandardCharsets.UTF_8));
            int n = 0;
            while (r.readLine() != null) {
                n++;
            }
            r.close();
            return n;
        } catch (IOException e) {
            return 0;
        }
    }
}