package org.jak_linux.dns66;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

/**
 * 拦截模式设置:
 *   0 = DNS 拦截(命中规则时直接返回空解析)
 *   1 = 规则拦截(命中规则时返回 0.0.0.0,连接立即失败)
 * 另外提供规则文件导入(Shadowrocket / Quantumult 格式)。
 */
public class RuleModeActivity extends Activity {

    private static final int REQ_RULES = 2001;

    private Configuration config;
    private RadioGroup group;
    private TextView info;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = FileHelper.loadCurrentSettings(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("拦截模式");
        title.setTextSize(22);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("两种模式使用不同的域名库：\n· DNS 拦截 → 内置精选原生列表（约 145 条）\n· 规则拦截 → 国内专项 + 开源全量 7 万条（更全，推荐）\n\n命中判断用我们自己的「三级算法」：\n① 核心保护：银行/支付/系统/主流APP主站 → 永不拦\n② 精确命中黑名单域名 → 拦\n③ 关键字命中广告词 → 拦\n→ 广告照拦，核心功能不会被误伤\n\n命中后：DNS 拦截返回空解析；规则拦截返回 0.0.0.0（更快更彻底）");
        sub.setPadding(0, pad / 2, 0, pad);
        root.addView(sub);

        group = new RadioGroup(this);
        RadioButton dns = new RadioButton(this);
        dns.setId(1);
        dns.setText("DNS 拦截 · 原生精选 145 条（轻快）");
        RadioButton rule = new RadioButton(this);
        rule.setId(2);
        rule.setText("规则拦截 · 国内专项+开源 7 万条（更全，推荐）");
        group.addView(dns);
        group.addView(rule);
        group.check(config.blockMode == 1 ? 2 : 1);
        root.addView(group);

        info = new TextView(this);
        info.setPadding(0, pad, 0, pad);
        root.addView(info);


        Button saveBtn = new Button(this);
        saveBtn.setText("保存");
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                config.blockMode = group.getCheckedRadioButtonId() == 2 ? 1 : 0;
                FileHelper.writeSettings(RuleModeActivity.this, config);
                Toast.makeText(RuleModeActivity.this, "已保存，重启拦截后生效", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        root.addView(saveBtn);

        TextView hint = new TextView(this);
        hint.setPadding(0, pad, 0, 0);
        hint.setText("只拦指定应用：\n设置 → 允许列表（Allowlist）→ 默认模式选“不上 VPN”，\n然后只勾选要拦的那个应用。这样其它应用的网络完全不受影响。");
        hint.setGravity(Gravity.START);
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(root);
        setContentView(scroll);
        refreshInfo();
    }

    private void refreshInfo() {
        int d = Math.max(RuleImporter.countDomains(this),
                RuleImporter.countFromAssets(this, RuleImporter.FILE_DOMAINS));
        int k = Math.max(RuleImporter.countKeywords(this),
                RuleImporter.countFromAssets(this, RuleImporter.FILE_KEYWORDS));
        int a = Math.max(RuleImporter.countAllow(this),
                RuleImporter.countFromAssets(this, RuleImporter.FILE_ALLOW));
        info.setText("内置规则：拦截 " + (d + k) + " 条（域名 " + d + " · 关键字 " + k + "）\n放行 " + a
                + " 条（优先于拦截）\n规则随应用内置，不联网、不上传任何数据。");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_RULES || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            int[] counts = RuleImporter.importStream(this, in);
            in.close();
            refreshInfo();
            Toast.makeText(this, "导入完成：域名 " + counts[0] + " 条，关键字 " + counts[1] + " 条",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导入失败: " + e, Toast.LENGTH_LONG).show();
        }
    }
}