package com.foodwasteai.util;

import com.foodwasteai.model.FoodItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Authoritative, centralized Expiry Status Resolver for FoodWaste AI.
 * Enforces one single shared expiry policy across Inventory, SWI-Prolog reasoning,
 * AI predictions, Mitigation Recommendations, Dashboard alerts, and Waste logging.
 *
 * Policy:
 *  - expiry_date < today (daysRemaining < 0):
 *      State: EXPIRED
 *      Database status: EXPIRED
 *      Risk Level: HIGH (95%)
 *      Prolog Priority: DISPOSE_OR_COMPOST
 *      Action: Halt production and dispose of expired inventory safely
 *      Redistribution: false (unsafe for consumption/donation)
 *
 *  - expiry_date == today (daysRemaining == 0):
 *      State: SAME_DAY_EXPIRY
 *      Database status: NEAR_EXPIRY
 *      Risk Level: HIGH (85%)
 *      Prolog Priority: IMMEDIATE_USE
 *      Action: Reduce production or redistribute immediately
 *      Redistribution: true if surplus >= 5
 *
 *  - expiry_date > today && daysRemaining <= 3:
 *      State: NEAR_EXPIRY
 *      Database status: NEAR_EXPIRY
 *      Risk Level: 1 day -> HIGH (85%), 2-3 days -> MEDIUM (55%)
 *      Prolog Priority: 1-2 days -> IMMEDIATE_USE, 3 days -> HIGH_PRIORITY
 *      Action: 1-2 days -> Reduce production / redistribute, 3 days -> Slightly reduce by 10-15%
 *
 *  - expiry_date > today && daysRemaining > 3:
 *      State: SAFE
 *      Database status: LOW_STOCK (if qty <= threshold) or OK
 *      Risk Level: LOW (18%) (or MEDIUM if heavy overstock)
 *      Prolog Priority: STANDARD
 *      Action: Maintain standard scheduled production batch
 */
public class ExpiryStatusResolver {

    public enum ExpiryState {
        EXPIRED,
        SAME_DAY_EXPIRY,
        NEAR_EXPIRY,
        SAFE
    }

    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_NEAR_EXPIRY = "NEAR_EXPIRY";
    public static final String STATUS_LOW_STOCK = "LOW_STOCK";
    public static final String STATUS_OK = "OK";

    /**
     * Resolves the authoritative ExpiryState based on expiry date relative to today.
     */
    public static ExpiryState resolveState(LocalDate expiryDate, LocalDate today) {
        if (expiryDate == null) {
            return ExpiryState.SAFE;
        }
        LocalDate current = (today != null) ? today : LocalDate.now();
        long days = ChronoUnit.DAYS.between(current, expiryDate);

        if (days < 0) {
            return ExpiryState.EXPIRED;
        } else if (days == 0) {
            return ExpiryState.SAME_DAY_EXPIRY;
        } else if (days <= 3) {
            return ExpiryState.NEAR_EXPIRY;
        } else {
            return ExpiryState.SAFE;
        }
    }

    public static ExpiryState resolveState(LocalDate expiryDate) {
        return resolveState(expiryDate, LocalDate.now());
    }

    public static ExpiryState resolveState(FoodItem item) {
        if (item == null || item.getExpiryDate() == null) {
            return ExpiryState.SAFE;
        }
        return resolveState(item.getExpiryDate(), LocalDate.now());
    }

    /**
     * Resolves the database status column string ('EXPIRED', 'NEAR_EXPIRY', 'LOW_STOCK', 'OK').
     */
    public static String resolveStatus(LocalDate expiryDate, BigDecimal quantity, BigDecimal minStockThreshold, LocalDate today) {
        ExpiryState state = resolveState(expiryDate, today);
        switch (state) {
            case EXPIRED:
                return STATUS_EXPIRED;
            case SAME_DAY_EXPIRY:
            case NEAR_EXPIRY:
                return STATUS_NEAR_EXPIRY;
            case SAFE:
            default:
                if (quantity != null && minStockThreshold != null && quantity.compareTo(minStockThreshold) <= 0) {
                    return STATUS_LOW_STOCK;
                }
                return STATUS_OK;
        }
    }

    public static String resolveStatus(FoodItem item, LocalDate today) {
        if (item == null) return STATUS_OK;
        return resolveStatus(item.getExpiryDate(), item.getQuantity(), item.getMinStockThreshold(), today);
    }

    public static String resolveStatus(FoodItem item) {
        return resolveStatus(item, LocalDate.now());
    }

    /**
     * Calculates signed days remaining to expiry (negative if expired, 0 if today, positive if future).
     */
    public static int calculateDaysRemaining(LocalDate expiryDate, LocalDate today) {
        if (expiryDate == null) return 999;
        LocalDate current = (today != null) ? today : LocalDate.now();
        return (int) ChronoUnit.DAYS.between(current, expiryDate);
    }

    public static int calculateDaysRemaining(LocalDate expiryDate) {
        return calculateDaysRemaining(expiryDate, LocalDate.now());
    }

    public static boolean isExpired(LocalDate expiryDate, LocalDate today) {
        return resolveState(expiryDate, today) == ExpiryState.EXPIRED;
    }

    public static boolean isExpired(LocalDate expiryDate) {
        return isExpired(expiryDate, LocalDate.now());
    }

    public static boolean isSameDayExpiry(LocalDate expiryDate, LocalDate today) {
        return resolveState(expiryDate, today) == ExpiryState.SAME_DAY_EXPIRY;
    }

    public static boolean isSameDayExpiry(LocalDate expiryDate) {
        return isSameDayExpiry(expiryDate, LocalDate.now());
    }

    public static boolean isNearExpiry(LocalDate expiryDate, LocalDate today) {
        ExpiryState s = resolveState(expiryDate, today);
        return s == ExpiryState.SAME_DAY_EXPIRY || s == ExpiryState.NEAR_EXPIRY;
    }

    public static boolean isNearExpiry(LocalDate expiryDate) {
        return isNearExpiry(expiryDate, LocalDate.now());
    }

    /**
     * Standardized English risk reasoning string for UI and Prolog consistency.
     */
    public static String getStandardRiskReasonEn(ExpiryState state, int daysRemaining) {
        switch (state) {
            case EXPIRED:
                return "Item has passed expiration date. Do not serve to customers.";
            case SAME_DAY_EXPIRY:
                return "Product expires today. Immediate consumption or action required.";
            case NEAR_EXPIRY:
                if (daysRemaining == 1) {
                    return "Product expires within 24 hours. Immediate action recommended.";
                }
                return "Product expires within 2-3 days. Monitor stock velocity closely.";
            case SAFE:
            default:
                return "Safe shelf life remaining (> 3 days) and stock is balanced with demand.";
        }
    }

    /**
     * Standardized Myanmar risk reasoning string for UI and Prolog consistency.
     */
    public static String getStandardRiskReasonMy(ExpiryState state, int daysRemaining) {
        switch (state) {
            case EXPIRED:
                return "ကုန်ပစ္စည်းသည် သက်တမ်းကုန်ဆုံးသွားပါပြီ။ ဧည့်သည်များထံ မကျွေးမွေးပါနှင့်။";
            case SAME_DAY_EXPIRY:
                return "ကုန်ပစ္စည်းသည် ယနေ့ သက်တမ်းကုန်ဆုံးပါမည်။ ချက်ချင်း စားသုံးရန် သို့မဟုတ် အရေးယူဆောင်ရွက်ရန် လိုအပ်ပါသည်။";
            case NEAR_EXPIRY:
                if (daysRemaining == 1) {
                    return "ကုန်ပစ္စည်းသည် ၂၄ နာရီအတွင်း သက်တမ်းကုန်ဆုံးပါမည်။ ချက်ချင်း အရေးယူဆောင်ရွက်ရန် လိုအပ်ပါသည်။";
                }
                return "ကုန်ပစ္စည်းသည် ၂-၃ ရက်အတွင်း သက်တမ်းကုန်ဆုံးပါမည်။ သုံးစွဲမှုနှုန်းကို အနီးကပ် စောင့်ကြည့်ပါ။";
            case SAFE:
            default:
                return "လုံလောက်သော သက်တမ်းကျန်ရှိပြီး (> ၃ ရက်) လက်ကျန်ပမာဏနှင့် ဝယ်လိုအား မျှတနေပါသည်။";
        }
    }

    /**
     * Standardized recommendation action string.
     */
    public static String getStandardAction(ExpiryState state) {
        switch (state) {
            case EXPIRED:
                return "Halt production and dispose of expired inventory safely";
            case SAME_DAY_EXPIRY:
            case NEAR_EXPIRY:
                return "Reduce production or redistribute immediately";
            case SAFE:
            default:
                return "Maintain standard scheduled production batch";
        }
    }

    /**
     * Standardized Prolog priority string.
     */
    public static String getStandardPriority(ExpiryState state, int daysRemaining) {
        switch (state) {
            case EXPIRED:
                return "DISPOSE_OR_COMPOST";
            case SAME_DAY_EXPIRY:
                return "IMMEDIATE_USE";
            case NEAR_EXPIRY:
                return (daysRemaining <= 2) ? "IMMEDIATE_USE" : "HIGH_PRIORITY";
            case SAFE:
            default:
                return "STANDARD";
        }
    }
}
