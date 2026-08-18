package com.foodwasteai.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central configuration manager reading environment variables
 * with support for local .env files and fallback defaults.
 */
public class AppConfig {
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);
    private static Dotenv dotenv;

    static {
        try {
            dotenv = Dotenv.configure()
                    .ignoreIfMissing()
                    .ignoreIfMalformed()
                    .load();
            logger.info("Configuration initialized (Dotenv loaded if present)");
        } catch (Exception e) {
            logger.warn("Dotenv could not be loaded, using System environment only: {}", e.getMessage());
            dotenv = null;
        }
    }

    public static String get(String key, String defaultValue) {
        // Priority 1: System Environment (Railway / Container env vars)
        String val = System.getenv(key);
        if (val != null && !val.trim().isEmpty()) {
            return val.trim();
        }

        // Priority 2: Java System property
        val = System.getProperty(key);
        if (val != null && !val.trim().isEmpty()) {
            return val.trim();
        }

        // Priority 3: Dotenv (.env file)
        if (dotenv != null) {
            val = dotenv.get(key);
            if (val != null && !val.trim().isEmpty()) {
                return val.trim();
            }
        }

        // Priority 4: Default fallback
        return defaultValue;
    }

    public static int getInt(String key, int defaultValue) {
        String val = get(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer for configuration key {}: '{}'. Using default: {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String val = get(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(val);
    }

    // Standardized Getters
    public static int getPort() {
        return getInt("PORT", getInt("APP_PORT", 8088));
    }

    public static String getDbHost() {
        return get("DB_HOST", "localhost");
    }

    public static int getDbPort() {
        return getInt("DB_PORT", 3306);
    }

    public static String getDbName() {
        return get("DB_NAME", "foodwaste_ai");
    }

    public static String getDbUser() {
        return get("DB_USER", "root");
    }

    public static String getDbPassword() {
        return get("DB_PASSWORD", "");
    }

    public static String getDbSslMode() {
        return get("DB_SSL_MODE", "PREFERRED");
    }

    public static String getSwiplPath() {
        return get("SWIPL_PATH", "swipl");
    }

    public static String getAppEnv() {
        return get("APP_ENV", "development");
    }

    public static String getAppSecret() {
        return get("APP_SECRET", "foodwaste_ai_default_secret_key_university_2026");
    }

    public static boolean isProduction() {
        return "production".equalsIgnoreCase(getAppEnv());
    }
}
