package com.foodwasteai.service;

import com.foodwasteai.dao.UserDao;
import com.foodwasteai.model.User;
import com.foodwasteai.util.PasswordUtils;
import com.foodwasteai.util.ValidationUtils;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Account lifecycle backed exclusively by the persistent users table. */
public class UserService {
    private final UserDao userDao;
    private static final String DUMMY_HASH = PasswordUtils.hashPassword(UUID.randomUUID().toString());
    public UserService() { this(new UserDao()); }
    public UserService(UserDao userDao) { this.userDao = userDao; }

    public User register(String fullName, String email, String password, String confirmation) throws SQLException {
        if (fullName == null || fullName.trim().isEmpty() || fullName.trim().length() > 100) {
            throw new IllegalArgumentException("Full name is required and must be at most 100 characters.");
        }
        ValidationUtils.validateRegistration(email, password, confirmation);
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (userDao.findByEmail(normalized).isPresent()) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }
        User user = new User();
        user.setUsername(UUID.randomUUID().toString());
        user.setFullName(fullName.trim());
        user.setEmail(normalized);
        user.setPasswordHash(PasswordUtils.hashPassword(password));
        user.setRole(User.Role.STAFF);
        user.setActive(true);
        try {
            return userDao.save(user);
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 1062) {
                throw new IllegalArgumentException("An account with this email already exists.");
            }
            throw exception;
        }
    }

    public Optional<User> authenticate(String email, String password) throws SQLException {
        if (email == null || password == null || email.length() > 100 || password.length() > 1024) {
            return Optional.empty();
        }
        Optional<User> user = userDao.findByEmail(email.trim().toLowerCase(Locale.ROOT));
        boolean valid = PasswordUtils.verifyPassword(password, user.map(User::getPasswordHash).orElse(DUMMY_HASH));
        return valid && user.isPresent() && user.get().isActive() ? user : Optional.empty();
    }

    public Optional<User> findById(Long id) throws SQLException {
        return id == null ? Optional.empty() : userDao.findById(id);
    }
}
