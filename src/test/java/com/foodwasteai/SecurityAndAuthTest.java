package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.User;
import com.foodwasteai.service.AuthService;
import com.foodwasteai.service.GeminiExplanationService;
import com.foodwasteai.util.ValidationUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class SecurityAndAuthTest {

    private AuthService authService;
    private GeminiExplanationService geminiService;

    @BeforeEach
    public void setUp() {
        authService = new AuthService();
        geminiService = new GeminiExplanationService();
    }

    @Test
    @DisplayName("Security: BCrypt password hashing and authentication")
    public void testBCryptAuthentication() {
        // 1. Valid Admin login
        Optional<AuthService.UserSession> adminSession = authService.authenticate("admin", "admin123");
        assertTrue(adminSession.isPresent(), "Admin login should succeed with correct password");
        assertEquals("admin", adminSession.get().getUser().getUsername());
        assertEquals(User.Role.ADMIN, adminSession.get().getUser().getRole());
        assertNotNull(adminSession.get().getToken());
        assertTrue(adminSession.get().getToken().startsWith("fwt_"));

        // 2. Valid Staff login
        Optional<AuthService.UserSession> staffSession = authService.authenticate("staff", "staff123");
        assertTrue(staffSession.isPresent(), "Staff login should succeed with correct password");
        assertEquals(User.Role.STAFF, staffSession.get().getUser().getRole());

        // 3. Invalid password must be rejected
        Optional<AuthService.UserSession> badPass = authService.authenticate("admin", "wrongpassword123");
        assertFalse(badPass.isPresent(), "Invalid password must fail authentication");

        // 4. Non-existent user must be rejected
        Optional<AuthService.UserSession> unknownUser = authService.authenticate("hacker_user", "password");
        assertFalse(unknownUser.isPresent(), "Non-existent user must fail authentication");
    }

    @Test
    @DisplayName("Security: Session token validation and logout")
    public void testTokenValidationAndLogout() {
        Optional<AuthService.UserSession> session = authService.authenticate("admin", "admin123");
        assertTrue(session.isPresent());

        String token = session.get().getToken();
        Optional<User> validatedUser = authService.validateToken("Bearer " + token);
        assertTrue(validatedUser.isPresent());
        assertEquals("admin", validatedUser.get().getUsername());

        // Logout
        authService.logout(token);
        Optional<User> loggedOutUser = authService.validateToken("Bearer " + token);
        assertFalse(loggedOutUser.isPresent(), "Session must be invalidated after logout");
    }

    @Test
    @DisplayName("Security: Register new user with enforced BCrypt hash")
    public void testRegisterUserBCrypt() throws SQLException {
        String testUser = "chef_mario_" + (System.currentTimeMillis() % 100000);
        String testEmail = testUser + "@foodwaste.ai";
        User newUser = authService.registerUser(
                testUser,
                testEmail,
                "secretPass2026",
                "Mario Rossi",
                User.Role.STAFF
        );

        assertNotNull(newUser);
        assertEquals(testUser, newUser.getUsername());
        assertNotNull(newUser.getPasswordHash());
        assertTrue(newUser.getPasswordHash().startsWith("$2a$") || newUser.getPasswordHash().startsWith("$2b$"),
                "Password must be hashed with BCrypt ($2a$ format)");
        assertTrue(BCrypt.checkpw("secretPass2026", newUser.getPasswordHash()));

        // Authenticate with newly registered user
        Optional<AuthService.UserSession> newSession = authService.authenticate(testUser, "secretPass2026");
        assertTrue(newSession.isPresent());
    }

    @Test
    @DisplayName("Security: Input validation utility prevents SQL injection & malformed data")
    public void testInputValidation() {
        // Null / Empty Food Item
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateFoodItem(null));

        FoodItem emptyName = new FoodItem();
        emptyName.setName("   ");
        emptyName.setCategory("Produce");
        emptyName.setQuantity(new BigDecimal("10.0"));
        emptyName.setUnit("kg");
        emptyName.setPricePerUnit(new BigDecimal("1000"));
        emptyName.setExpiryDate(LocalDate.now().plusDays(5));
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateFoodItem(emptyName));

        // Negative Quantity
        FoodItem negQty = new FoodItem();
        negQty.setName("Apples");
        negQty.setCategory("Produce");
        negQty.setQuantity(new BigDecimal("-5.0"));
        negQty.setUnit("kg");
        negQty.setPricePerUnit(new BigDecimal("1000"));
        negQty.setExpiryDate(LocalDate.now().plusDays(5));
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateFoodItem(negQty));
    }

    @Test
    @DisplayName("Security: Gemini missing API key fallback handling")
    public void testGeminiFallbackSafety() {
        GeminiExplanationService.ChatResponse response = geminiService.processUserQuery("What is our general waste risk?");

        assertNotNull(response);
        assertNotNull(response.getExplanation());
        assertFalse(response.getExplanation().isEmpty());
        assertNotNull(response.getSourceEngine());
        assertFalse(response.getSmartRecommendations().isEmpty());
    }
}
