package com.openjiuwen.service.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Query endpoint configuration.
 */
@ConfigurationProperties(prefix = "openjiuwen.service.query")
public class QueryProperties {

    private Endpoint webflux = new Endpoint();
    private boolean legacyPathEnabled = true;

    public Endpoint getWebflux() {
        return webflux;
    }

    public void setWebflux(Endpoint webflux) {
        this.webflux = webflux != null ? webflux : new Endpoint();
    }

    public boolean isLegacyPathEnabled() {
        return legacyPathEnabled;
    }

    public void setLegacyPathEnabled(boolean legacyPathEnabled) {
        this.legacyPathEnabled = legacyPathEnabled;
    }

    public static class Endpoint {

        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
