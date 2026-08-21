package com.foodwasteai.service;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.UserDao;
import com.foodwasteai.model.User;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service managing user authentication, BCrypt password hashing, session tokens, and role validation.
 */
public class AuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private final UserDao userDao;
    private static final SecureRandom secureRandom = new SecureRandom();

    // Default session validity: 24 hours
    public static final long DEFAULT_SESSION_HOURS = 24;

    // In-memory token session registry: token -> UserSession
    private static final Map<String, UserSession> activeSessions = new ConcurrentHashMap<>();
    // In-memory users fallback when database is in offline mode
    private static final Map<String, User> memoryUsers = new ConcurrentHashMap<>();

    public static class UserSession {
        private final User user;
        private final String token;
        private final LocalDateTime expiresAt;

        public UserSession(User user, String token, LocalDateTime expiresAt) {
            this.user = user;
            this.token = token;
            this.expiresAt = expiresAt;
        }

        public User getUser() { return user; }
        public String getToken() { return token; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
        public boolean isExpired() { return LocalDateTime.now().isAfter(expiresAt); }
    }

    static {
        initFallbackUsers();
    }

    private static void initFallbackUsers() {
        // Hash 'admin123' with BCrypt
        String adminHash = BCrypt.hashpw("admin123", BCrypt.gensalt(12));
        User admin = new User(1L, "admin", "admin@foodwaste.ai", adminHash, "Restaurant Manager", User.Role.ADMIN, true);

        // Hash 'staff123' with BCrypt
        String staffHash = BCrypt.hashpw("staff123", BCrypt.gensalt(12));
        User staff = new User(2L, "staff", "staff@foodwaste.ai", staffHash, "Sarah Jenkins", User.Role.STAFF, true);

        memoryUsers.put("admin", admin);
        memoryUsers.put("staff", staff);
    }

    public AuthService() {
        this.userDao = new UserDao();
    }

    public AuthService(UserDao userDao) {
        this.userDao = userDao;
    }

    /**
     * Generates a cryptographically strong random token using SecureRandom (256-bit entropy).
     */
    public static String generateSecureToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        StringBuilder hexString = new StringBuilder("fwt_");
        for (byte b : randomBytes) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }

    /**
     * Authenticates user against BCrypt password hash and creates an active session.
     * Role is loaded strictly from the authenticated database user record (or backend memory registry).
     */
    public Optional<UserSession> authenticate(String username, String plainPassword) {
        if (username == null || plainPassword == null || username.trim().isEmpty() || plainPassword.isEmpty()) {
            return Optional.empty();
        }

        User user = null;
        if (DatabaseConfig.isAvailable()) {
            try {
                Optional<User> dbUser = userDao.findByUsername(username.trim());
                if (dbUser.isPresent()) {
                    user = dbUser.get();
                } else if (com.foodwasteai.config.AppConfig.isProduction()) {
                    logger.warn("Authentication failed: User '{}' not found in database", username);
                    return Optional.empty();
                }
            } catch (SQLException e) {
                logger.error("Database error querying user '{}': {}", username, e.getMessage());
                if (com.foodwasteai.config.AppConfig.isProduction()) {
                    return Optional.empty();
                }
            }
        }

        if (user == null && !com.foodwasteai.config.AppConfig.isProduction()) {
            user = memoryUsers.get(username.trim().toLowerCase());
        }

        if (user == null || !user.isActive()) {
            logger.warn("Authentication failed: User '{}' not found or inactive", username);
            return Optional.empty();
        }

        // Verify password using BCrypt
        boolean matches = false;
        try {
            matches = BCrypt.checkpw(plainPassword, user.getPasswordHash());
        } catch (Exception e) {
            logger.error("BCrypt hash verification error: {}", e.getMessage());
        }

        if (!matches) {
            logger.warn("Authentication failed: Invalid credentials for user '{}'", username);
            return Optional.empty();
        }

        UserSession session = createSession(user, DEFAULT_SESSION_HOURS * 60);
        logger.info("User '{}' (ID: {}, Role: {}) authenticated successfully", user.getUsername(), user.getId(), user.getRole());
        return Optional.of(session);
    }

    /**
     * Creates an active session for a verified User with specific duration.
     */
    public UserSession createSession(User user, long durationMinutes) {
        String token = generateSecureToken();
        UserSession session = new UserSession(user, token, LocalDateTime.now().plusMinutes(durationMinutes));
        activeSessions.put(token, session);
        return session;
    }

    /**
     * Validates session token and returns active User if authenticated.
     * Expired or non-existent tokens are rejected.
     */
    public Optional<User> validateToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return Optional.empty();
        }

        // Check if token starts with Bearer prefix
        String cleanToken = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();

        UserSession session = activeSessions.get(cleanToken);
        if (session != null) {
            if (session.isExpired()) {
                activeSessions.remove(cleanToken);
                logger.warn("Session token expired and evicted for user: {}", session.getUser().getUsername());
                return Optional.empty();
            }
            return Optional.of(session.getUser());
        }

        return Optional.empty();
    }

    /**
     * Terminates active session.
     */
    public void logout(String token) {
        if (token != null) {
            String cleanToken = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();
            activeSessions.remove(cleanToken);
            logger.info("Session terminated for token: {}...", cleanToken.substring(0, Math.min(cleanToken.length(), 10)));
        }
    }

    /**
     * Clears all sessions (useful for tests).
     */
    public static void clearAllSessions() {
        activeSessions.clear();
    }

    /**
     * Creates new user with secure BCrypt hash.
     */
    public User registerUser(String username, String email, String plainPassword, String fullName, User.Role role) throws SQLException {
        if (plainPassword == null || plainPassword.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }
        String passwordHash = BCrypt.hashpw(plainPassword, BCrypt.gensalt(12));
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setFullName(fullName);
        user.setRole(role != null ? role : User.Role.STAFF);
        user.setActive(true);

        if (DatabaseConfig.isAvailable()) {
            return userDao.save(user);
        } else {
            user.setId((long) (memoryUsers.size() + 1));
            user.setCreatedAt(LocalDateTime.now());
            memoryUsers.put(user.getUsername().toLowerCase(), user);
            return user;
        }
    }

    public List<User> getAllUsers() throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return userDao.findAll();
        }
        return new ArrayList<>(memoryUsers.values());
    }
}
