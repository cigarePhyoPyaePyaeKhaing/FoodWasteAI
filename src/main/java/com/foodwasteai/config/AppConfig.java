package com.foodwasteai.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Central configuration manager reading environment variables
 * with support for Railway production environment, local .env files, and fallback defaults.
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
        if (isValidValue(val)) {
            return val.trim();
        }

        // Priority 2: Java System property
        val = System.getProperty(key);
        if (isValidValue(val)) {
            return val.trim();
        }

        // Priority 3: Dotenv (.env file)
        if (dotenv != null) {
            val = dotenv.get(key);
            if (isValidValue(val)) {
                return val.trim();
            }
        }

        // Priority 4: Default fallback
        return defaultValue;
    }

    private static boolean isValidValue(String val) {
        if (val == null) return false;
        String trimmed = val.trim();
        if (trimmed.isEmpty()) return false;
        // Exclude unexpanded literal shell variable placeholders like "$PORT" or "${PORT}"
        if (trimmed.startsWith("$")) return false;
        return true;
    }

    public static int getInt(String key, int defaultValue) {
        String val = get(key, null);
        if (val == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer for configuration key {}: '{}'. Using default: {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String val = get(key, null);
        if (val == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(val);
    }

    // Standardized Getters
    public static int getPort() {
        // Direct environment check without triggering warnings for literal '$PORT'
        String envPort = System.getenv("PORT");
        if (isValidValue(envPort)) {
            try {
                return Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ignored) {}
        }

        String appPort = System.getenv("APP_PORT");
        if (isValidValue(appPort)) {
            try {
                return Integer.parseInt(appPort.trim());
            } catch (NumberFormatException ignored) {}
        }

        String sysPort = System.getProperty("server.port");
        if (isValidValue(sysPort)) {
            try {
                return Integer.parseInt(sysPort.trim());
            } catch (NumberFormatException ignored) {}
        }

        return 8080;
    }

    public static String getDbHost() {
        String host = get("DB_HOST", null);
        if (host != null) return host;

        host = get("MYSQLHOST", null);
        if (host != null) return host;

        host = get("MYSQL_HOST", null);
        if (host != null) return host;

        host = get("DATABASE_HOST", null);
        if (host != null) return host;

        // Try parsing from DATABASE_URL / MYSQL_URL
        ParsedDbUrl parsed = parseDatabaseUrl();
        if (parsed != null && parsed.host != null) {
            return parsed.host;
        }

        return "localhost";
    }

    public static int getDbPort() {
        String portStr = get("DB_PORT", null);
        if (portStr != null) {
            try { return Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}
        }

        portStr = get("MYSQLPORT", null);
        if (portStr != null) {
            try { return Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}
        }

        portStr = get("MYSQL_PORT", null);
        if (portStr != null) {
            try { return Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}
        }

        portStr = get("DATABASE_PORT", null);
        if (portStr != null) {
            try { return Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}
        }

        ParsedDbUrl parsed = parseDatabaseUrl();
        if (parsed != null && parsed.port > 0) {
            return parsed.port;
        }

        return 3306;
    }

    public static String getDbName() {
        String name = get("DB_NAME", null);
        if (name != null) return name;

        name = get("MYSQLDATABASE", null);
        if (name != null) return name;

        name = get("MYSQL_DATABASE", null);
        if (name != null) return name;

        name = get("DATABASE_NAME", null);
        if (name != null) return name;

        ParsedDbUrl parsed = parseDatabaseUrl();
        if (parsed != null && parsed.dbName != null) {
            return parsed.dbName;
        }

        return "foodwaste_ai";
    }

    public static String getDbUser() {
        String user = get("DB_USER", null);
        if (user != null) return user;

        user = get("MYSQLUSER", null);
        if (user != null) return user;

        user = get("MYSQL_USER", null);
        if (user != null) return user;

        user = get("DATABASE_USER", null);
        if (user != null) return user;

        ParsedDbUrl parsed = parseDatabaseUrl();
        if (parsed != null && parsed.user != null) {
            return parsed.user;
        }

        return "root";
    }

    public static String getDbPassword() {
        String pass = get("DB_PASSWORD", null);
        if (pass != null) return pass;

        pass = get("MYSQLPASSWORD", null);
        if (pass != null) return pass;

        pass = get("MYSQL_PASSWORD", null);
        if (pass != null) return pass;

        pass = get("DATABASE_PASSWORD", null);
        if (pass != null) return pass;

        ParsedDbUrl parsed = parseDatabaseUrl();
        if (parsed != null && parsed.password != null) {
            return parsed.password;
        }

        return "";
    }

    public static String getDbSslMode() {
        String ssl = get("DB_SSL_MODE", null);
        if (ssl != null) return ssl;

        ssl = get("MYSQL_SSL_MODE", null);
        if (ssl != null) return ssl;

        return isProduction() ? "REQUIRED" : "PREFERRED";
    }

    public static String getSwiplPath() {
        return get("SWIPL_PATH", "swipl");
    }

    public static String getGroqApiKey() {
        return get("GROQ_API_KEY", "");
    }

    public static String getGroqModel() {
        return get("GROQ_MODEL", "llama-3.3-70b-versatile");
    }

    public static String getOllamaHost() {
        return get("OLLAMA_HOST", "http://localhost:11434");
    }

    public static String getOllamaModel() {
        return get("OLLAMA_MODEL", "qwen2.5:3b");
    }

    public static String getGeminiApiKey() {
        return get("GEMINI_API_KEY", get("GOOGLE_API_KEY", ""));
    }

    public static String getGeminiModel() {
        return get("GEMINI_MODEL", "gemini-1.5-flash");
    }

    public static String getAppEnv() {
        String env = get("APP_ENV", null);
        if (env != null) return env;

        env = get("ENVIRONMENT", null);
        if (env != null) return env;

        if (get("RAILWAY_ENVIRONMENT", null) != null || get("RAILWAY_SERVICE_ID", null) != null) {
            return "production";
        }

        return "development";
    }

    public static String getAppSecret() {
        return get("APP_SECRET", "foodwaste_ai_default_secret_key_university_2026");
    }

    public static boolean isProduction() {
        return "production".equalsIgnoreCase(getAppEnv());
    }

    private static class ParsedDbUrl {
        String host;
        int port = 3306;
        String dbName;
        String user;
        String password;
    }

    private static ParsedDbUrl parseDatabaseUrl() {
        String raw = get("DATABASE_URL", get("MYSQL_URL", get("AIVEN_URL", null)));
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            String clean = raw.trim();
            if (clean.startsWith("jdbc:mysql://")) {
                clean = clean.substring(5); // convert to uri format mysql://
            }
            URI uri = new URI(clean);
            ParsedDbUrl parsed = new ParsedDbUrl();
            parsed.host = uri.getHost();
            if (uri.getPort() > 0) {
                parsed.port = uri.getPort();
            }
            String path = uri.getPath();
            if (path != null && path.length() > 1) {
                parsed.dbName = path.substring(1);
            }
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                parsed.user = parts[0];
                if (parts.length > 1) {
                    parsed.password = parts[1];
                }
            }
            return parsed;
        } catch (Exception e) {
            logger.warn("Could not parse database URL: {}", e.getMessage());
            return null;
        }
    }
}
