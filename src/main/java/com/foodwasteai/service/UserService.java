package com.foodwasteai.service;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.UserDao;
import com.foodwasteai.model.User;
import com.foodwasteai.util.PasswordUtils;
import com.foodwasteai.util.ValidationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service handling user account lifecycle, Gmail registration,
 * secure password verification, and authentication sessions.
 */
public class UserService {
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    private final UserDao userDao;

    // Thread-safe in-memory store for development/test fallback when database is offline
    private static final Map<Long, User> memoryUsers = new ConcurrentHashMap<>();
    private static final Map<String, Long> emailIndex = new ConcurrentHashMap<>();
    private static final AtomicLong idGenerator = new AtomicLong(10);

    static {
        // Seed standard accounts in memory fallback
        initMemoryUsers();
    }

    private static void initMemoryUsers() {
        User admin = new User(1L, "admin", "admin@gmail.com", PasswordUtils.hashPassword("Admin123!"),
                "Restaurant Manager", User.Role.ADMIN, true);
        admin.setCreatedAt(LocalDateTime.now());
        admin.setUpdatedAt(LocalDateTime.now());
        memoryUsers.put(admin.getId(), admin);
        emailIndex.put(admin.getEmail().toLowerCase(), admin.getId());

        User staff = new User(2L, "staff", "staff@gmail.com", PasswordUtils.hashPassword("Staff123!"),
                "Sarah Jenkins", User.Role.STAFF, true);
        staff.setCreatedAt(LocalDateTime.now());
        staff.setUpdatedAt(LocalDateTime.now());
        memoryUsers.put(staff.getId(), staff);
        emailIndex.put(staff.getEmail().toLowerCase(), staff.getId());
    }

    public UserService() {
        this.userDao = new UserDao();
    }

    public UserService(UserDao userDao) {
        this.userDao = userDao;
    }

    /**
     * Registers a new user account with strict @gmail.com domain validation,
     * duplicate prevention, and strong password hashing.
     */
    public User register(String email, String password, String confirmPassword) throws SQLException {
        ValidationUtils.validateRegistration(email, password, confirmPassword);

        String normEmail = email.trim().toLowerCase();

        // Check for duplicate email
        Optional<User> existing = findByEmail(normEmail);
        if (existing.isPresent()) {
            throw new IllegalArgumentException("Email '" + normEmail + "' is already registered");
        }

        String username = normEmail;
        String localPart = normEmail.substring(0, normEmail.indexOf('@'));
        String fullName = formatFullName(localPart);

        String hashedPassword = PasswordUtils.hashPassword(password);

        User newUser = new User();
        newUser.setUsername(username);
        newUser.setEmail(normEmail);
        newUser.setPasswordHash(hashedPassword);
        newUser.setFullName(fullName);
        newUser.setRole(User.Role.STAFF);
        newUser.setActive(true);
        newUser.setCreatedAt(LocalDateTime.now());
        newUser.setUpdatedAt(LocalDateTime.now());

        if (DatabaseConfig.isAvailable()) {
            User saved = userDao.save(newUser);
            // Also mirror in memory for consistent fallback
            memoryUsers.put(saved.getId(), saved);
            emailIndex.put(saved.getEmail().toLowerCase(), saved.getId());
            logger.info("Successfully registered new user in database: {} (ID: {})", saved.getEmail(), saved.getId());
            return saved;
        }

        long newId = idGenerator.incrementAndGet();
        newUser.setId(newId);
        memoryUsers.put(newId, newUser);
        emailIndex.put(normEmail, newId);
        logger.info("Successfully registered new user in memory fallback: {} (ID: {})", normEmail, newId);
        return newUser;
    }

    /**
     * Authenticates user with email and password.
     * Returns User if credentials are valid and account is active.
     */
    public Optional<User> authenticate(String email, String password) throws SQLException {
        if (email == null || password == null || email.trim().isEmpty() || password.isEmpty()) {
            return Optional.empty();
        }

        String normEmail = email.trim().toLowerCase();
        Optional<User> userOpt = findByEmail(normEmail);
        if (userOpt.isEmpty()) {
            // Also allow username login if matches
            userOpt = findByUsername(email.trim());
        }

        if (userOpt.isEmpty()) {
            logger.warn("Authentication failed: user '{}' not found", normEmail);
            return Optional.empty();
        }

        User user = userOpt.get();
        if (!user.isActive()) {
            logger.warn("Authentication failed: user '{}' is inactive", normEmail);
            return Optional.empty();
        }

        boolean passwordValid = PasswordUtils.verifyPassword(password, user.getPasswordHash());
        if (!passwordValid) {
            logger.warn("Authentication failed: invalid password for user '{}'", normEmail);
            return Optional.empty();
        }

        logger.info("Authentication successful for user: {} (ID: {})", user.getEmail(), user.getId());
        return Optional.of(user);
    }

    public Optional<User> findById(Long id) throws SQLException {
        if (id == null) return Optional.empty();
        if (DatabaseConfig.isAvailable()) {
            Optional<User> dbUser = userDao.findById(id);
            if (dbUser.isPresent()) return dbUser;
        }
        return Optional.ofNullable(memoryUsers.get(id));
    }

    public Optional<User> findByEmail(String email) throws SQLException {
        if (email == null || email.trim().isEmpty()) return Optional.empty();
        String norm = email.trim().toLowerCase();
        if (DatabaseConfig.isAvailable()) {
            Optional<User> dbUser = userDao.findByEmail(norm);
            if (dbUser.isPresent()) return dbUser;
        }
        Long id = emailIndex.get(norm);
        if (id != null) {
            return Optional.ofNullable(memoryUsers.get(id));
        }
        // Direct scan memory users
        for (User u : memoryUsers.values()) {
            if (u.getEmail() != null && u.getEmail().equalsIgnoreCase(norm)) {
                return Optional.of(u);
            }
        }
        return Optional.empty();
    }

    public Optional<User> findByUsername(String username) throws SQLException {
        if (username == null || username.trim().isEmpty()) return Optional.empty();
        String norm = username.trim().toLowerCase();
        if (DatabaseConfig.isAvailable()) {
            Optional<User> dbUser = userDao.findByUsername(norm);
            if (dbUser.isPresent()) return dbUser;
        }
        for (User u : memoryUsers.values()) {
            if (u.getUsername() != null && u.getUsername().equalsIgnoreCase(norm)) {
                return Optional.of(u);
            }
        }
        return Optional.empty();
    }

    private String formatFullName(String localPart) {
        if (localPart == null || localPart.isEmpty()) return "User";
        String clean = localPart.replaceAll("[._\\-+]+", " ").trim();
        String[] words = clean.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) {
                    sb.append(w.substring(1).toLowerCase());
                }
                sb.append(" ");
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? "User" : result;
    }
}
