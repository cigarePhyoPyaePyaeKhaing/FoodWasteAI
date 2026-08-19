package com.foodwasteai.service;

import com.foodwasteai.config.AppConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enterprise Translation Service for FoodWaste AI.
 * Handles automatic AI-driven translation of dynamic database content (Prolog reasoning,
 * AI recommendations, redistribution notes, error messages, notifications) from English
 * to natural, fluent Myanmar (Burmese) language with high-speed memory caching and
 * robust offline rule-based fallback.
 */
public class TranslationService {
    private static final Logger logger = LoggerFactory.getLogger(TranslationService.class);

    private static final TranslationService INSTANCE = new TranslationService();

    // High-performance thread-safe translation cache
    private final Map<String, String> translationCache = new ConcurrentHashMap<>();

    private final HttpClient httpClient;
    private final Gson gson;

    public static TranslationService getInstance() {
        return INSTANCE;
    }

    public TranslationService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.gson = new Gson();
        initCoreGlossary();
    }

    /**
     * Translates dynamic English text to natural Business Myanmar.
     * Checks memory cache first, then calls Gemini AI API if configured,
     * falling back to offline rule-based dictionary and template synthesis.
     *
     * @param englishText English text to translate
     * @return Natural Myanmar translation, or englishText on fatal fallback
     */
    public String translateToMyanmar(String englishText) {
        if (englishText == null) {
            return "";
        }
        if (englishText.isEmpty()) {
            return "";
        }
        if (englishText.trim().isEmpty()) {
            return englishText;
        }

        String trimmed = englishText.trim();

        // 1. Check in-memory cache
        if (translationCache.containsKey(trimmed)) {
            return translationCache.get(trimmed);
        }

        // 2. Check direct offline glossary matches
        String glossaryMatch = matchOfflineGlossary(trimmed);
        if (glossaryMatch != null) {
            translationCache.put(trimmed, glossaryMatch);
            return glossaryMatch;
        }

        // 3. Attempt Gemini Generative Language AI Translation if API key is available
        String apiKey = AppConfig.getGeminiApiKey();
        if (apiKey != null && !apiKey.isEmpty() && !"demo-key-placeholder".equalsIgnoreCase(apiKey)) {
            try {
                String aiTranslation = callGeminiTranslateApi(trimmed, apiKey);
                if (aiTranslation != null && !aiTranslation.trim().isEmpty()) {
                    String cleanResult = cleanAiOutput(aiTranslation);
                    translationCache.put(trimmed, cleanResult);
                    return cleanResult;
                }
            } catch (Exception e) {
                logger.warn("Gemini AI translation failed for text: '{}'. Falling back to offline rule engine. Reason: {}",
                        trimmed.length() > 50 ? trimmed.substring(0, 50) + "..." : trimmed, e.getMessage());
            }
        }

        // 4. Offline Rule-Based Synthesizer Fallback
        String synthesized = synthesizeMyanmarText(trimmed);
        if (synthesized != null && !synthesized.isEmpty()) {
            translationCache.put(trimmed, synthesized);
            return synthesized;
        }

        // 5. Ultimate Fallback to English Original
        return trimmed;
    }

    /**
     * Translates error message to Myanmar
     */
    public String translateErrorMessage(String errorEn) {
        if (errorEn == null || errorEn.trim().isEmpty()) return "အမှားအယွင်း ဖြစ်ပွားခဲ့ပါသည်";
        String lower = errorEn.toLowerCase();
        if (lower.contains("recipient not found") || lower.contains("inactive")) {
            return "ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်းကို ရှာမတွေ့ပါ သို့မဟုတ် ပိတ်ထားပါသည်";
        }
        if (lower.contains("food item not found")) {
            return "ကုန်ပစ္စည်းကို ရှာမတွေ့ပါ";
        }
        if (lower.contains("greater than zero")) {
            return "ပမာဏသည် သုညထက် ကြီးရပါမည်";
        }
        return translateToMyanmar(errorEn);
    }

    /**
     * Translates system notification to Myanmar
     */
    public String translateNotification(String notifEn) {
        if (notifEn == null || notifEn.trim().isEmpty()) return "";
        String lower = notifEn.toLowerCase();
        if (lower.contains("scheduled successfully") || lower.contains("surplus food dispatch")) {
            Matcher m = Pattern.compile("#(\\d+)").matcher(notifEn);
            String id = m.find() ? " #" + m.group(1) : "";
            return "ပိုလျှံအစားအစာ လှူဒါန်းမှု" + id + " အချိန်ဇယား အောင်မြင်စွာ သတ်မှတ်ပြီးပါပြီ";
        }
        return translateToMyanmar(notifEn);
    }

    /**
     * Clear memory cache (useful for tests or cache reload)
     */
    public void clearCache() {
        translationCache.clear();
        initCoreGlossary();
    }

    /**
     * Calls Gemini AI for translation with strict domain guidelines
     */
    private String callGeminiTranslateApi(String text, String apiKey) throws Exception {
        String model = AppConfig.getGeminiModel();
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

        String systemPrompt = "You are a professional translator for FoodWaste AI restaurant system.\n" +
                "Task: Translate the following English restaurant inventory / food waste intelligence text into fluent, natural, professional Myanmar (Burmese) Unicode script.\n\n" +
                "Strict Rules:\n" +
                "1. Keep food item names (such as Fresh Chicken Breast, Organic Garden Salad Mix, Atlantic Salmon Fillet, Artisan Sliced Bread, etc.) in their original recognized English names without alteration.\n" +
                "2. Do NOT translate database identifiers, numbers, metrics, currency units (kg, MMK, %, hours, days), or code enums.\n" +
                "3. Use natural Myanmar business phrasing suitable for kitchen managers and staff, not word-by-word literal translation.\n" +
                "4. Return ONLY the translated Myanmar text without markdown formatting or conversational filler.";

        JsonObject requestBody = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject contentObj = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject partObj = new JsonObject();
        partObj.addProperty("text", systemPrompt + "\n\nText to translate:\n" + text);
        parts.add(partObj);
        contentObj.add("parts", parts);
        contents.add(contentObj);
        requestBody.add("contents", contents);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .timeout(Duration.ofSeconds(4))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (httpResponse.statusCode() == 200) {
            JsonObject json = JsonParser.parseString(httpResponse.body()).getAsJsonObject();
            JsonArray candidates = json.getAsJsonArray("candidates");
            if (candidates != null && candidates.size() > 0) {
                JsonObject firstCandidate = candidates.get(0).getAsJsonObject();
                JsonObject content = firstCandidate.getAsJsonObject("content");
                JsonArray resParts = content.getAsJsonArray("parts");
                if (resParts != null && resParts.size() > 0) {
                    return resParts.get(0).getAsJsonObject().get("text").getAsString();
                }
            }
        }
        return null;
    }

    private String cleanAiOutput(String raw) {
        if (raw == null) return null;
        String clean = raw.trim();
        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        return clean;
    }

    /**
     * Initializes core glossary and rule patterns
     */
    private void initCoreGlossary() {
        // Recommendations & Action Titles
        translationCache.put("Reduce next production batch", "မနက်ဖြန် ထုတ်လုပ်မှု ပမာဏကို လျှော့ချပါ");
        translationCache.put("Redistribute excess inventory", "ပိုလျှံနေသော ကုန်ပစ္စည်းကို ပရဟိတသို့ လှူဒါန်းပါ");
        translationCache.put("Prioritize usage today", "ယနေ့အတွင်း ဦးစားပေး သုံးစွဲပါ");
        translationCache.put("Monitor stock levels", "ကုန်ပစ္စည်းလက်ကျန် အခြေအနေကို စောင့်ကြည့်ပါ");
        translationCache.put("Monitor stock", "ကုန်ပစ္စည်းလက်ကျန် အခြေအနေကို စောင့်ကြည့်ပါ");
        translationCache.put("Adjust preparation quantity", "ပြင်ဆင်ချက်ပြုတ်မှု ပမာဏကို ညှိနှိုင်းပါ");
        translationCache.put("Promote usage", "နေ့စဉ် အထူးဟင်းလျာများတွင် ထည့်သွင်း ရောင်းချပါ");
        translationCache.put("Maintain normal operation", "ပုံမှန် ထုတ်လုပ်မှု အစီအစဉ်အတိုင်း ဆက်လက်ဆောင်ရွက်ပါ");
        translationCache.put("Surplus inventory donation dispatch", "ပိုလျှံအစားအစာ လှူဒါန်းမှု ပို့ဆောင်ရေး");

        // Statuses and Categories
        translationCache.put("URGENT", "အရေးပေါ်");
        translationCache.put("IMPORTANT", "အရေးကြီး");
        translationCache.put("OPTIMIZATION", "စီမံညှိနှိုင်းမှု");
        translationCache.put("REDISTRIBUTION", "ပြန်လည်လှူဒါန်းမှု");
        translationCache.put("HIGH", "အန္တရာယ်မြင့်");
        translationCache.put("MEDIUM", "အလယ်အလတ်");
        translationCache.put("LOW", "အန္တရာယ်နည်း");
        translationCache.put("PENDING", "စောင့်ဆိုင်းဆဲ");
        translationCache.put("CONFIRMED", "အတည်ပြုပြီး");
        translationCache.put("COLLECTED", "လက်ခံရယူပြီး");
        translationCache.put("COMPLETED", "ပြီးစီး");
        translationCache.put("CANCELLED", "ပယ်ဖျက်ပြီး");

        // Standard Error Messages
        translationCache.put("Recipient ID is required", "ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်း ID လိုအပ်ပါသည်");
        translationCache.put("Food item ID is required", "ကုန်ပစ္စည်း ID လိုအပ်ပါသည်");
        translationCache.put("Quantity must be greater than zero", "ပမာဏသည် သုညထက် ကြီးရပါမည်");
        translationCache.put("Recipient not found or is inactive", "ပရဟိတ အဖွဲ့အစည်းကို ရှာမတွေ့ပါ သို့မဟုတ် ပိတ်ထားပါသည်");
        translationCache.put("Insufficient stock for redistribution", "လှူဒါန်းရန် ကုန်ပစ္စည်း လက်ကျန် မလုံလောက်ပါ");
        translationCache.put("Invalid credentials", "အသုံးပြုသူအမည် သို့မဟုတ် လျှို့ဝှက်နံပါတ် မှားယွင်းနေပါသည်");
        translationCache.put("User account is inactive", "အသုံးပြုသူ အကောင့် ပိတ်ထားပါသည်");
        translationCache.put("Unauthorized access", "ဝင်ရောက်ခွင့် မရှိပါ");
    }

    private String matchOfflineGlossary(String text) {
        return translationCache.get(text);
    }

    /**
     * Pattern-based natural Myanmar translator for dynamic compound sentences
     */
    private String synthesizeMyanmarText(String text) {
        String lower = text.toLowerCase();

        // 1. Prolog Rule references
        if (text.contains("assess_waste_risk") && text.contains("recommend_production")) {
            return "Prolog စည်းမျဉ်း: assess_waste_risk/6 (အန္တရာယ်မြင့်) -> recommend_production/6 (ထုတ်လုပ်မှု ၁၅-၂၅% လျှော့ချပါ)";
        }
        if (text.contains("evaluate_redistribution")) {
            return "Prolog စည်းမျဉ်း: evaluate_redistribution/6 -> ပရဟိတ လှူဒါန်းရန် သင့်တော်သော ပိုလျှံပစ္စည်းအဖြစ် အတည်ပြုသည်";
        }
        if (text.contains("evaluate_priority_use")) {
            return "Prolog စည်းမျဉ်း: evaluate_priority_use/3 -> သက်တမ်းကုန်ဆုံးရက် နီးကပ်နေသဖြင့် ချက်ချင်း ဦးစားပေး သုံးစွဲရန် လိုအပ်သည်";
        }

        // 2. Recommendation: Redistribute / Dispatch to food bank / charity partner
        // e.g. "Surplus stock (5.0 kg) detected near expiry. Dispatch to registered food bank or charity partner before expiry cutoff."
        if (lower.contains("food bank") || lower.contains("charity partner") || lower.contains("redistribut") || lower.contains("dispatch to")) {
            Matcher mQty = Pattern.compile("([\\d.]+)\\s*kg").matcher(text);
            String qty = mQty.find() ? mQty.group(1) : "";
            if (!qty.isEmpty()) {
                return String.format("သက်တမ်းကုန်ခါနီး ပိုလျှံလက်ကျန် (%s kg) တွေ့ရှိရပါသည်။ သက်တမ်းမကုန်မီ မှတ်ပုံတင်ထားသော အစားအစာဘဏ် သို့မဟုတ် ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်းသို့ ပို့ဆောင်လှူဒါန်းပါ", qty);
            }
            return "သက်တမ်းမကုန်မီ ပိုလျှံနေသော အစားအစာများကို မှတ်ပုံတင်ထားသော ပရဟိတ အဖွဲ့အစည်း သို့မဟုတ် အစားအစာဘဏ်သို့ ပို့ဆောင်လှူဒါန်းပါ";
        }

        // 3. Recommendation: Reduce next production batch
        // e.g. "Reduce next production batch for Fresh Chicken Breast by 20%"
        if (lower.contains("reduce next production batch") || lower.contains("reduce production batch")) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s]+?)(?:\\s+by\\s+(\\d+%?)|$)").matcher(text);
            Matcher mWaste = Pattern.compile("prevent\\s+([\\d.]+)\\s*kg").matcher(text);

            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                String pct = (mItem.groupCount() >= 2 && mItem.group(2) != null) ? mItem.group(2) : "";
                String waste = mWaste.find() ? mWaste.group(1) : "";
                if (!waste.isEmpty()) {
                    return String.format("%s အတွက် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို %s လျှော့ချ၍ ခန့်မှန်းအလေအလွင့် %s kg ကို ကာကွယ်ပါ", itemName, pct.isEmpty() ? "၁၅-၂၅%" : pct, waste);
                }
                if (!pct.isEmpty()) {
                    return String.format("%s အတွက် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို %s လျှော့ချပါ", itemName, pct);
                }
                return String.format("%s အတွက် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို လျှော့ချပါ", itemName);
            }
            return "ခန့်မှန်း အလေအလွင့်များကို ကာကွယ်ရန် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို လျှော့ချပါ";
        }

        // 4. Prioritize usage today
        if (lower.contains("prioritize usage today") || lower.contains("prioritize in today's menu") || (lower.contains("feature") && lower.contains("specials"))) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s]+?)(?:\\b|$)").matcher(text);
            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                return String.format("%s ကို ယနေ့ မီနူးနှင့် မီးဖိုချောင်တွင် ချက်ချင်း ဦးစားပေး အသုံးပြုပါ", itemName);
            }
            return "သက်တမ်းကုန်ဆုံးရက် နီးကပ်နေသဖြင့် ယနေ့ မီနူးအထူးဟင်းလျာများတွင် ချက်ချင်း ဦးစားပေး အသုံးပြုပါ";
        }

        // 5. High risk / surplus / expiry reasoning
        // e.g. "Stock exceeds expected demand (surplus: 8.0 kg) with only 1 day(s) until expiration"
        if (lower.contains("stock exceeds expected demand") || lower.contains("imminent expiration") || (lower.contains("expiry") && lower.contains("surplus")) || (lower.contains("surplus:") && lower.contains("expiration"))) {
            Matcher mDays = Pattern.compile("(\\d+)\\s*(?:day|days)").matcher(text);
            Matcher mSurplus = Pattern.compile("(?:surplus:?\\s*(?:inventory\\s*of)?|surplus\\s+stock\\s*\\(?)\\s*([\\d.]+)\\s*kg").matcher(text);

            String days = mDays.find() ? mDays.group(1) : "၁";
            String surplus = mSurplus.find() ? mSurplus.group(1) : "";

            if (!surplus.isEmpty()) {
                return String.format("သက်တမ်းကုန်ဆုံးရန် %s ရက်သာ ကျန်ရှိပြီး ပိုလျှံလက်ကျန် %s kg ရှိနေသဖြင့် အလေအလွင့် ဖြစ်နိုင်ခြေ မြင့်မားပါသည်", days, surplus);
            }
            return String.format("သက်တမ်းကုန်ဆုံးရန် %s ရက်သာ ကျန်ရှိသဖြင့် မီးဖိုချောင် အလေအလွင့် အန္တရာယ် မြင့်မားနေပါသည်။", days);
        }

        // 6. Monitor stock
        if (lower.contains("monitor stock") || lower.contains("moderate waste")) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s]+?)(?:\\b|$)").matcher(text);
            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                return String.format("%s ၏ ကုန်ပစ္စည်းလက်ကျန်နှင့် သုံးစွဲမှုနှုန်းကို အနီးကပ် စောင့်ကြည့်ပါ", itemName);
            }
            return "ပိုလျှံမှု မဖြစ်ပေါ်စေရန် ကုန်ပစ္စည်းလက်ကျန်နှင့် သုံးစွဲမှုနှုန်းကို အနီးကပ် စောင့်ကြည့်ပါ";
        }

        // 7. Adjust preparation quantity
        if (lower.contains("adjust preparation quantity") || lower.contains("adjust kitchen batch")) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s]+?)(?:\\b|$)").matcher(text);
            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                return String.format("%s အတွက် မီးဖိုချောင် ပြင်ဆင်ချက်ပြုတ်မှု ပမာဏကို ညှိနှိုင်းပါ", itemName);
            }
            return "အလေအလွင့် ဖြစ်နိုင်ခြေ လျှော့ချရန် မီးဖိုချောင် ပြင်ဆင်ချက်ပြုတ်မှု ပမာဏကို ညှိနှိုင်းပါ";
        }

        // 8. Promote usage
        if (lower.contains("promote usage")) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s]+?)(?:\\b|$)").matcher(text);
            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                return String.format("%s ကို စားဖိုမှူး၏ အထူးဟင်းလျာ သို့မဟုတ် တွဲဖက်ရောင်းချမှုများတွင် ထည့်သွင်း ရောင်းချပါ", itemName);
            }
            return "ကုန်ပစ္စည်း လျင်မြန်စွာ ကုန်စင်စေရန် စားဖိုမှူး၏ အထူးဟင်းလျာ သို့မဟုတ် တွဲဖက်ရောင်းချမှုများတွင် ထည့်သွင်း ရောင်းချပါ";
        }

        // 9. Maintain normal operation
        if (lower.contains("maintain normal operation") || lower.contains("well-balanced") || lower.contains("adequate shelf life")) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s]+?)(?:\\b|$)").matcher(text);
            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                return String.format("%s အတွက် ပုံမှန် ထုတ်လုပ်မှု အစီအစဉ်အတိုင်း ဆက်လက် ဆောင်ရွက်ပါ", itemName);
            }
            return "ကုန်ပစ္စည်းလက်ကျန်နှင့် ဝယ်လိုအား မျှတနေပြီး သက်တမ်းလုံလောက်စွာ ကျန်ရှိသဖြင့် ပုံမှန် အစီအစဉ်အတိုင်း ဆက်လက် ဆောင်ရွက်နိုင်ပါသည်";
        }

        // 10. Redistribution Dispatch Notes
        if (lower.contains("dispatch") || lower.contains("surplus")) {
            Matcher mNote = Pattern.compile("([\\d.]+)\\s*kg").matcher(text);
            if (mNote.find()) {
                return String.format("ပိုလျှံပမာဏ %s kg အတွက် အလိုအလျောက် လှူဒါန်းမှု အစီအစဉ်", mNote.group(1));
            }
            return "ပိုလျှံအစားအစာ လှူဒါန်းမှု ပို့ဆောင်ရေး အစီအစဉ်";
        }

        // If no pattern matched, return English original
        return text;
    }
}
