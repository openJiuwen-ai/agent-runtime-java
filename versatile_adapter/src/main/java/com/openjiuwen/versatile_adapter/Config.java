package com.openjiuwen.versatile_adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * VersatileAdapter 配置。
 *
 * 对应 Python: config.py
 */
@Component
@ConfigurationProperties(prefix = "versatile-adapter")
public class Config {

    // ── App ─────────────────────────────────────────────────────────────────
    private String adapterAppName = "VersatileAdapter";

    // ── Versatile 低码平台 ───────────────────────────────────────────────────
    private String versatileUrlTemplate = "http://localhost:8080/api/v1/conversations/{conversation_id}";
    private int versatileTimeout = 600;

    // ── Server ─────────────────────────────────────────────────────────────
    private String adapterFastapiHost = "0.0.0.0";
    private int adapterFastapiPort = 8091;
    private boolean adapterFastapiDebug = false;
    private int adapterFastapiWorkers = 1;

    // ── Logging ────────────────────────────────────────────────────────────
    private String adapterLogLevel = "INFO";
    private String adapterLogFile;

    // Getters and Setters

    public String getAdapterAppName() { return adapterAppName; }
    public void setAdapterAppName(String adapterAppName) { this.adapterAppName = adapterAppName; }

    public String getVersatileUrlTemplate() { return versatileUrlTemplate; }
    public void setVersatileUrlTemplate(String versatileUrlTemplate) { this.versatileUrlTemplate = versatileUrlTemplate; }

    public int getVersatileTimeout() { return versatileTimeout; }
    public void setVersatileTimeout(int versatileTimeout) { this.versatileTimeout = versatileTimeout; }

    public String getAdapterFastapiHost() { return adapterFastapiHost; }
    public void setAdapterFastapiHost(String adapterFastapiHost) { this.adapterFastapiHost = adapterFastapiHost; }

    public int getAdapterFastapiPort() { return adapterFastapiPort; }
    public void setAdapterFastapiPort(int adapterFastapiPort) { this.adapterFastapiPort = adapterFastapiPort; }

    public boolean isAdapterFastapiDebug() { return adapterFastapiDebug; }
    public void setAdapterFastapiDebug(boolean adapterFastapiDebug) { this.adapterFastapiDebug = adapterFastapiDebug; }

    public int getAdapterFastapiWorkers() { return adapterFastapiWorkers; }
    public void setAdapterFastapiWorkers(int adapterFastapiWorkers) { this.adapterFastapiWorkers = adapterFastapiWorkers; }

    public String getAdapterLogLevel() { return adapterLogLevel; }
    public void setAdapterLogLevel(String adapterLogLevel) { this.adapterLogLevel = adapterLogLevel; }

    public String getAdapterLogFile() { return adapterLogFile; }
    public void setAdapterLogFile(String adapterLogFile) { this.adapterLogFile = adapterLogFile; }
}
