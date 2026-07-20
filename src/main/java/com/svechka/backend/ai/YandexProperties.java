package com.svechka.backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared config for the Yandex Cloud AI Studio clients (SpeechKit STT + YandexGPT) — both
 * services are billed against the same folder and authenticate with the same static API key.
 */
@ConfigurationProperties(prefix = "yandex")
public class YandexProperties {

    private String folderId;
    private String apiKey;
    private String sttModel = "general";
    private String gptModel = "yandexgpt";
    private int timeoutSeconds = 30;

    public String getFolderId() {
        return folderId;
    }

    public void setFolderId(String folderId) {
        this.folderId = folderId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getSttModel() {
        return sttModel;
    }

    public void setSttModel(String sttModel) {
        this.sttModel = sttModel;
    }

    public String getGptModel() {
        return gptModel;
    }

    public void setGptModel(String gptModel) {
        this.gptModel = gptModel;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
