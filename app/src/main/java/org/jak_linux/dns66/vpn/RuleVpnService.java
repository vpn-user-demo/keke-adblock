package org.jak_linux.dns66.vpn;

import org.jak_linux.dns66.db.RuleDatabase;

/**
 * 规则拦截模式专用的 VPN 服务。
 * 与 DnsVpnService 是两套独立的通道：系统里会看到不同的 VPN 会话，
 * 同一时间只有当前模式对应的那一个在运行。
 */
public class RuleVpnService extends AdVpnService {

    @Override
    protected String sessionName() {
        return "可可 · 规则拦截";
    }

    @Override
    public void onCreate() {
        super.onCreate();
        RuleDatabase.modeOverride = 1;
    }
}