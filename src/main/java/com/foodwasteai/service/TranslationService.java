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
 * to natural, fluent Myanmar (Burmese) language with high-speed memory caching keyed by
 * sourceText + targetLanguage and robust offline rule-based fallback.
 */
public class TranslationService {
    private static final Logger logger = LoggerFactory.getLogger(TranslationService.class);

    private static final TranslationService INSTANCE = new TranslationService();

    // High-performance thread-safe translation cache keyed by sourceText + ":" + targetLanguage
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
     * Checks memory cache first, then direct glossary, then calls Gemini AI API if configured,
     * falling back to offline rule-based dictionary and template synthesis.
     *
     * @param englishText English text to translate
     * @return Natural Myanmar translation, or englishText on fatal fallback
     */
    public String translateToMyanmar(String englishText) {
        return translate(englishText, "mm");
    }

    /**
     * Core translation method keyed by sourceText + ":" + targetLanguage.
     */
    public String translate(String text, String targetLang) {
        if (text == null) {
            return "";
        }
        if (text.isEmpty()) {
            return "";
        }
        if (text.trim().isEmpty()) {
            return text;
        }

        String trimmed = text.trim();
        String target = (targetLang == null || targetLang.equalsIgnoreCase("my") || targetLang.equalsIgnoreCase("mm")) ? "mm" : targetLang.toLowerCase();

        if ("en".equals(target)) {
            return trimmed;
        }

        // Cache key format: sourceText + ":" + targetLanguage
        String cacheKey = trimmed + ":" + target;

        // 1. Check in-memory cache
        if (translationCache.containsKey(cacheKey)) {
            return translationCache.get(cacheKey);
        }

        // 2. Check direct offline glossary matches
        String glossaryMatch = matchOfflineGlossary(trimmed);
        if (glossaryMatch != null) {
            translationCache.put(cacheKey, glossaryMatch);
            return glossaryMatch;
        }

        // 3. Attempt Gemini Generative Language AI Translation if API key is available
        String apiKey = AppConfig.getGeminiApiKey();
        if (apiKey != null && !apiKey.isEmpty() && !"demo-key-placeholder".equalsIgnoreCase(apiKey)) {
            try {
                String aiTranslation = callGeminiTranslateApi(trimmed, apiKey);
                if (aiTranslation != null && !aiTranslation.trim().isEmpty()) {
                    String cleanResult = cleanAiOutput(aiTranslation);
                    translationCache.put(cacheKey, cleanResult);
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
            translationCache.put(cacheKey, synthesized);
            return synthesized;
        }

        // 5. Ultimate Fallback to English Original (never null)
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
        if (lower.contains("greater than zero") || lower.contains("greater than 0")) {
            return "ပမာဏသည် သုညထက် ကြီးရပါမည်";
        }
        if (lower.contains("invalid credentials")) {
            return "အသုံးပြုသူအမည် သို့မဟုတ် လျှို့ဝှက်နံပါတ် မှားယွင်းနေပါသည်";
        }
        if (lower.contains("unauthorized")) {
            return "ဝင်ရောက်ခွင့် မရှိပါ";
        }
        if (lower.contains("recipient id is required")) {
            return "ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်း ID လိုအပ်ပါသည်";
        }
        if (lower.contains("food item id is required")) {
            return "ကုန်ပစ္စည်း ID လိုအပ်ပါသည်";
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
        if (lower.contains("applied recommendation")) {
            return "အကြံပြုချက်ကို လက်ခံဆောင်ရွက်ပြီးပါပြီ";
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

        String systemPrompt = "You are a professional enterprise translator for the FoodWaste AI restaurant system.\n" +
                "Task: Translate the given English restaurant inventory, Prolog reasoning, or food waste intelligence text into fluent, natural, professional Myanmar (Burmese) Unicode script.\n\n" +
                "Strict Rules:\n" +
                "1. Translate English to natural professional Myanmar, not literal word-for-word translation.\n" +
                "2. Keep user-entered food item names (such as Fresh Chicken Breast, Organic Garden Salad Mix, Atlantic Salmon Fillet, Artisan Sliced Bread, etc.) in their original recognized English names exactly without alteration.\n" +
                "3. Preserve database identifiers, usernames, phone numbers, numeric values, and units (such as kg, liter, MMK, %, hours, days, units) exactly as provided.\n" +
                "4. Do NOT translate technical Prolog predicate names (such as assess_waste_risk/6, recommend_production/6, evaluate_redistribution/6, evaluate_priority_use/3) if present.\n" +
                "5. Return ONLY the translated Myanmar text. Do NOT add conversational filler, markdown fences, notes, summaries, or explanations.\n" +
                "6. Do NOT transliterate product names unless requested.";

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
        if (clean.startsWith("```") && clean.endsWith("```")) {
            clean = clean.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("\\n?```$", "").trim();
        }
        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        return clean;
    }

    /**
     * Initializes core glossary and rule patterns with keyed cache
     */
    private void initCoreGlossary() {
        // Domain Glossary (Requirement 4)
        putGlossary("Dashboard", "အနှစ်ချုပ်စာမျက်နှာ");
        putGlossary("Inventory", "ကုန်ပစ္စည်းလက်ကျန် စီမံခန့်ခွဲမှု");
        putGlossary("Sales Entry", "ရောင်းချမှုမှတ်တမ်း");
        putGlossary("Waste Records", "အလေအလွင့်မှတ်တမ်းများ");
        putGlossary("AI Prediction", "AI ခန့်မှန်းချက်");
        putGlossary("Recommendations", "AI အကြံပြုချက်များ");
        putGlossary("Redistribution", "ပိုလျှံအစားအစာ ပြန်လည်ဖြန့်ဝေမှု");
        putGlossary("High Risk", "အန္တရာယ်မြင့်");
        putGlossary("Medium Risk", "အလယ်အလတ်အန္တရာယ်");
        putGlossary("Low Risk", "အန္တရာယ်နည်း");
        putGlossary("Near Expiry", "သက်တမ်းကုန်ရန်နီး");
        putGlossary("Estimated Savings", "ခန့်မှန်းငွေကြေး သက်သာမှု");
        putGlossary("Immediate Use", "ချက်ချင်းအသုံးပြုရန်");
        putGlossary("High Priority", "ဦးစားပေးအဆင့်မြင့်");
        putGlossary("Standard", "ပုံမှန်အဆင့်");

        // Recommendations & Action Titles
        putGlossary("Reduce next production batch", "မနက်ဖြန် ထုတ်လုပ်မှု ပမာဏကို လျှော့ချပါ");
        putGlossary("Redistribute excess inventory", "ပိုလျှံနေသော ကုန်ပစ္စည်းကို ပရဟိတသို့ လှူဒါန်းပါ");
        putGlossary("Prioritize usage today", "ယနေ့အတွင်း ဦးစားပေး သုံးစွဲပါ");
        putGlossary("Monitor stock levels", "ကုန်ပစ္စည်းလက်ကျန် အခြေအနေကို စောင့်ကြည့်ပါ");
        putGlossary("Monitor stock", "ကုန်ပစ္စည်းလက်ကျန် အခြေအနေကို စောင့်ကြည့်ပါ");
        putGlossary("Adjust preparation quantity", "ပြင်ဆင်ချက်ပြုတ်မှု ပမာဏကို ညှိနှိုင်းပါ");
        putGlossary("Promote usage", "နေ့စဉ် အထူးဟင်းလျာများတွင် ထည့်သွင်း ရောင်းချပါ");
        putGlossary("Maintain normal operation", "ပုံမှန် ထုတ်လုပ်မှု အစီအစဉ်အတိုင်း ဆက်လက်ဆောင်ရွက်ပါ");
        putGlossary("Surplus inventory donation dispatch", "ပိုလျှံအစားအစာ လှူဒါန်းမှု ပို့ဆောင်ရေး");

        // Categories
        putGlossary("URGENT", "အရေးပေါ်");
        putGlossary("IMPORTANT", "အရေးကြီး");
        putGlossary("OPTIMIZATION", "စီမံညှိနှိုင်းမှု");
        putGlossary("REDISTRIBUTION", "ပြန်လည်လှူဒါန်းမှု");

        // Risk Levels
        putGlossary("HIGH", "အန္တရာယ်မြင့်");
        putGlossary("MEDIUM", "အလယ်အလတ်အန္တရာယ်");
        putGlossary("LOW", "အန္တရာယ်နည်း");

        // Statuses
        putGlossary("OK", "ပုံမှန်ကောင်းမွန်");
        putGlossary("NEAR_EXPIRY", "သက်တမ်းကုန်ရန်နီး");
        putGlossary("EXPIRED", "သက်တမ်းကုန်ပြီး");
        putGlossary("LOW_STOCK", "လက်ကျန်နည်း");
        putGlossary("PENDING", "စောင့်ဆိုင်းဆဲ");
        putGlossary("CONFIRMED", "အတည်ပြုပြီး");
        putGlossary("COLLECTED", "လက်ခံရယူပြီး");
        putGlossary("COMPLETED", "ပြီးစီး");
        putGlossary("ACCEPTED", "လက်ခံပြီး");
        putGlossary("DISMISSED", "ပယ်ဖျက်ပြီး");
        putGlossary("CANCELLED", "ပယ်ဖျက်ပြီး");
        putGlossary("ACTIVE", "အသုံးပြုဆဲ");
        putGlossary("INACTIVE", "ပိတ်ထားသည်");

        // Priority Usages
        putGlossary("IMMEDIATE_USE", "ချက်ချင်းအသုံးပြုရန်");
        putGlossary("HIGH_PRIORITY", "ဦးစားပေးအဆင့်မြင့်");
        putGlossary("MODERATE_PRIORITY", "အလယ်အလတ် ဦးစားပေး");
        putGlossary("STANDARD", "ပုံမှန်အဆင့်");
        putGlossary("DISPOSE_OR_COMPOST", "စွန့်ပစ် သို့မဟုတ် မြေဆွေးပြုလုပ်ရန်");

        // Food Categories
        putGlossary("Poultry", "ကြက်/ဘဲ/ငှက် အသား");
        putGlossary("Produce", "ဟင်းသီးဟင်းရွက်နှင့် သစ်သီးဝလံ");
        putGlossary("Seafood", "ပင်လယ်စာ");
        putGlossary("Grains", "ဂျုံနှင့် နှံစားသီးနှံ");
        putGlossary("Dairy", "နို့နှင့် နို့ထွက်ပစ္စည်း");
        putGlossary("Bakery", "မုန့်ဖုတ်ထုတ်ကုန်");

        // Waste Reasons
        putGlossary("OVERPRODUCTION", "ပိုလျှံထုတ်လုပ်မှု");
        putGlossary("UNSOLD", "ညနေခင်း ရောင်းမကုန်သော ပစ္စည်း");
        putGlossary("SPOILED", "သိုလှောင်မှု ချွတ်ယွင်းပျက်စီးခြင်း");
        putGlossary("DAMAGED", "ကိုင်တွယ်စဉ် ထိခိုက်ပျက်စီးခြင်း");
        putGlossary("PREPARATION_WASTE", "ချက်ပြုတ်ပြင်ဆင်မှု အလေအလွင့်");
        putGlossary("OTHER", "အခြားအကြောင်းရင်း");

        // Common Error Messages
        putGlossary("Recipient ID is required", "ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်း ID လိုအပ်ပါသည်");
        putGlossary("Food item ID is required", "ကုန်ပစ္စည်း ID လိုအပ်ပါသည်");
        putGlossary("Quantity must be greater than zero", "ပမာဏသည် သုညထက် ကြီးရပါမည်");
        putGlossary("Recipient not found or is inactive", "ပရဟိတ အဖွဲ့အစည်းကို ရှာမတွေ့ပါ သို့မဟုတ် ပိတ်ထားပါသည်");
        putGlossary("Insufficient stock for redistribution", "လှူဒါန်းရန် ကုန်ပစ္စည်း လက်ကျန် မလုံလောက်ပါ");
        putGlossary("Invalid credentials", "အသုံးပြုသူအမည် သို့မဟုတ် လျှို့ဝှက်နံပါတ် မှားယွင်းနေပါသည်");
        putGlossary("User account is inactive", "အသုံးပြုသူ အကောင့် ပိတ်ထားပါသည်");
        putGlossary("Unauthorized access", "ဝင်ရောက်ခွင့် မရှိပါ");
    }

    private void putGlossary(String en, String mm) {
        translationCache.put(en.trim() + ":mm", mm);
    }

    private String matchOfflineGlossary(String text) {
        return translationCache.get(text + ":mm");
    }

    /**
     * Pattern-based natural Myanmar translator for dynamic compound sentences
     */
    private String synthesizeMyanmarText(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        String trimmed = text.trim();
        String lower = trimmed.toLowerCase();

        // 1. Prolog Rule references - preserve all technical Prolog predicates verbatim
        if (trimmed.contains("assess_waste_risk") && trimmed.contains("Expired")) {
            return "Prolog စည်းမျဉ်း: assess_waste_risk/6 (အန္တရာယ်မြင့်: သက်တမ်းကုန်) -> evaluate_priority_use/3 (စွန့်ပစ် သို့မဟုတ် မြေဆွေးပြုလုပ်ရန်)";
        }
        if (trimmed.contains("assess_waste_risk") && trimmed.contains("High Risk") && trimmed.contains("recommend_production")) {
            return "Prolog စည်းမျဉ်း: assess_waste_risk/6 (အန္တရာယ်မြင့်) -> recommend_production/6 (ထုတ်လုပ်မှု ၁၅-၂၅% လျှော့ချပါ)";
        }
        if (trimmed.contains("assess_waste_risk") && (trimmed.contains("Medium Risk") || trimmed.contains("medium")) && trimmed.contains("evaluate_priority_use")) {
            return "Prolog စည်းမျဉ်း: assess_waste_risk/6 (အလယ်အလတ်အန္တရာယ်) -> evaluate_priority_use/3 (ဦးစားပေးအဆင့်မြင့်)";
        }
        if (trimmed.contains("assess_waste_risk") && (trimmed.contains("Low Risk") || trimmed.contains("low")) && trimmed.contains("recommend_production")) {
            return "Prolog စည်းမျဉ်း: assess_waste_risk/6 (အန္တရာယ်နည်း) -> recommend_production/6 (ပုံမှန် သတ်မှတ်ထားသော ထုတ်လုပ်မှုအတိုင်း ဆက်လက်ဆောင်ရွက်ပါ)";
        }
        if (trimmed.contains("evaluate_priority_use") && trimmed.contains("recommend_production")) {
            return "Prolog စည်းမျဉ်း: evaluate_priority_use/3 -> recommend_production/6 (ချက်ချင်း ဦးစားပေး သုံးစွဲပြီး ထုတ်လုပ်မှု ၂၀% လျှော့ချပါ)";
        }
        if (trimmed.contains("evaluate_redistribution") && trimmed.contains("assess_waste_risk")) {
            return "Prolog စည်းမျဉ်း: assess_waste_risk/6 -> evaluate_redistribution/6 (ပရဟိတ လှူဒါန်းရန် ပိုလျှံပစ္စည်းအဖြစ် အတည်ပြုသည်)";
        }
        if (trimmed.contains("evaluate_redistribution")) {
            return "Prolog စည်းမျဉ်း: evaluate_redistribution/6 -> ပရဟိတ လှူဒါန်းရန် သင့်တော်သော ပိုလျှံပစ္စည်းအဖြစ် အတည်ပြုသည်";
        }
        if (trimmed.contains("evaluate_priority_use") && (trimmed.contains("clear inventory within 3 days") || trimmed.contains("3 days"))) {
            return "Prolog စည်းမျဉ်း: evaluate_priority_use/3 -> ၃ ရက်အတွင်း ကုန်စင်စေရန် ဦးစားပေးအဆင့်မြင့် သုံးစွဲပါ";
        }
        if (trimmed.contains("evaluate_priority_use")) {
            return "Prolog စည်းမျဉ်း: evaluate_priority_use/3 -> သက်တမ်းကုန်ဆုံးရက် နီးကပ်နေသဖြင့် ချက်ချင်း ဦးစားပေး သုံးစွဲရန် လိုအပ်သည်";
        }
        if (trimmed.contains("recommend_production") && trimmed.contains("10-15%")) {
            return "Prolog စည်းမျဉ်း: recommend_production/6 (ထုတ်လုပ်မှု ၁၀-၁၅% အနည်းငယ် လျှော့ချပါ)";
        }
        if (lower.contains("reasons with swi-prolog") || lower.contains("reasoning with swi-prolog")) {
            return "SWI-Prolog နှင့် Gemini ဖြင့် စဉ်းစားတွက်ချက်နေပါသည်...";
        }

        // 2. Recommendation Title: Halt production and dispose of expired <Item>
        if (lower.contains("halt production and dispose of expired") || lower.contains("halt production and dispose")) {
            Matcher mItem = Pattern.compile("(?:dispose of expired|dispose of)\\s+([A-Za-z0-9\\s_-]+)$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                return String.format("%s သက်တမ်းကုန်ဆုံးသွားသဖြင့် ထုတ်လုပ်မှုရပ်ဆိုင်းပြီး ဘေးကင်းစွာ စွန့်ပစ်ပါ", itemName);
            }
            return "ထုတ်လုပ်မှု ရပ်ဆိုင်းပြီး သက်တမ်းကုန်ပစ္စည်းများကို ဘေးကင်းစွာ စွန့်ပစ်ပါ";
        }

        // 3. Recommendation Title: Reduce next production batch for <Item>
        if (lower.startsWith("reduce next production batch for") || (lower.contains("reduce") && lower.contains("production batch for"))) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s_-]+?)(?:\\s+by\\s+(\\d+%?)|$)", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                String pct = (mItem.groupCount() >= 2 && mItem.group(2) != null) ? mItem.group(2) : "";
                if (!pct.isEmpty()) {
                    return String.format("%s အတွက် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို %s လျှော့ချပါ", itemName, pct);
                }
                return String.format("%s အတွက် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို လျှော့ချပါ", itemName);
            }
            return "ခန့်မှန်း အလေအလွင့်များကို ကာကွယ်ရန် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို လျှော့ချပါ";
        }

        // 4. Recommendation: Surplus with specific food item and quantity
        // e.g. "Surplus stock (15.5 kg) for Organic Garden Salad Mix near expiry"
        if (lower.contains("surplus") && lower.contains("for ") && lower.contains("near expiry")) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s_-]+?)(?:\\s+near|\\s+with|\\s+to|$)", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            Matcher mQty = Pattern.compile("\\(([\\d.]+)\\s*([A-Za-z]+)?\\)").matcher(trimmed);
            if (mItem.find() && mQty.find()) {
                String itemName = mItem.group(1).trim();
                String qty = mQty.group(1);
                String unit = mQty.group(2) != null ? mQty.group(2) : "kg";
                return String.format("%s အတွက် သက်တမ်းကုန်ခါနီး ပိုလျှံလက်ကျန် (%s %s) တွေ့ရှိရသဖြင့် အလေအလွင့် ကာကွယ်ရန် လိုအပ်ပါသည်", itemName, qty, unit);
            }
        }

        // 5. Recommendation Title: Redistribute excess inventory for <Item>
        if (lower.contains("redistribute excess inventory for") || (lower.contains("redistribute") && lower.contains("for "))) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s_-]+)$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                return String.format("%s ၏ ပိုလျှံလက်ကျန်ကို ပရဟိတသို့ လှူဒါန်းပါ", itemName);
            }
            return "ပိုလျှံနေသော ကုန်ပစ္စည်းကို ပရဟိတသို့ လှူဒါန်းပါ";
        }

        // 6. Recommendation Title: Prioritize usage today for <Item>
        if (lower.contains("prioritize usage today for") || lower.contains("prioritize usage for")) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s_-]+)$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                return String.format("%s ကို ယနေ့ မီးဖိုချောင်တွင် ဦးစားပေး သုံးစွဲပါ", itemName);
            }
            return "ယနေ့အတွင်း ဦးစားပေး သုံးစွဲပါ";
        }

        // 6. Recommendation Title: Monitor stock for <Item>
        if (lower.startsWith("monitor stock for") || lower.startsWith("monitor stock levels for")) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s_-]+)$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                return String.format("%s ၏ ကုန်ပစ္စည်းလက်ကျန် အခြေအနေကို စောင့်ကြည့်ပါ", itemName);
            }
            return "ကုန်ပစ္စည်းလက်ကျန် အခြေအနေကို စောင့်ကြည့်ပါ";
        }

        // 7. Recommendation Title: Adjust preparation quantity for <Item>
        if (lower.startsWith("adjust preparation quantity for") || lower.startsWith("adjust kitchen batch for")) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s_-]+)$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                return String.format("%s အတွက် ပြင်ဆင်ချက်ပြုတ်မှု ပမာဏကို ညှိနှိုင်းပါ", itemName);
            }
            return "ပြင်ဆင်ချက်ပြုတ်မှု ပမာဏကို ညှိနှိုင်းပါ";
        }

        // 8. Recommendation Title: Promote usage for <Item>
        if (lower.startsWith("promote usage for")) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s_-]+)$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                return String.format("%s ကို နေ့စဉ် အထူးဟင်းလျာများတွင် ထည့်သွင်း ရောင်းချပါ", itemName);
            }
            return "နေ့စဉ် အထူးဟင်းလျာများတွင် ထည့်သွင်း ရောင်းချပါ";
        }

        // 9. Recommendation Title: Maintain normal operation for <Item>
        if (lower.startsWith("maintain normal operation for")) {
            Matcher mItem = Pattern.compile("for\\s+([A-Za-z0-9\\s_-]+)$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (mItem.find()) {
                String itemName = mItem.group(1).trim();
                return String.format("%s အတွက် ပုံမှန် ထုတ်လုပ်မှု အစီအစဉ်အတိုင်း ဆက်လက်ဆောင်ရွက်ပါ", itemName);
            }
            return "ပုံမှန် ထုတ်လုပ်မှု အစီအစဉ်အတိုင်း ဆက်လက်ဆောင်ရွက်ပါ";
        }

        // 10. Recommendation Description: Expired item notice
        // "Item has passed expiration date (1 day(s) ago). Do not serve to customers. Halt production and dispose of or compost safely."
        if (lower.contains("passed expiration date") || (lower.contains("item has expired") && lower.contains("do not serve"))) {
            Matcher mDays = Pattern.compile("(\\d+)\\s*day").matcher(trimmed);
            String days = mDays.find() ? mDays.group(1) : "၁";
            return String.format("ကုန်ပစ္စည်းသည် သက်တမ်းကုန်ဆုံးသွားပါပြီ (လွန်ခဲ့သော %s ရက်က)။ ဧည့်သည်များထံ မကျွေးမွေးပါနှင့်။ ထုတ်လုပ်မှု ရပ်ဆိုင်းပြီး ဘေးကင်းစွာ စွန့်ပစ်ပါ သို့မဟုတ် မြေဆွေးပြုလုပ်ပါ။", days);
        }

        // 11. Recommendation Description: Production reduction with stock vs demand & expiry
        // e.g. "Stock is 8.0 liter against 0.6 liter expected demand with 0-day expiry remaining. Reduce next scheduled production batch by 15-25% to prevent excess spoilage."
        if (lower.contains("stock is") && lower.contains("expected demand") && (lower.contains("reduce") || lower.contains("production"))) {
            Matcher m = Pattern.compile("stock\\s+is\\s+([\\d.]+)\\s*([a-zA-Z]+)?\\s+against\\s+([\\d.]+)\\s*([a-zA-Z]+)?\\s+expected\\s+demand\\s+with\\s+(\\d+)-day\\s+expiry", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (m.find()) {
                String stock = m.group(1);
                String unit1 = m.group(2) != null ? m.group(2) : "kg";
                String demand = m.group(3);
                String unit2 = m.group(4) != null ? m.group(4) : unit1;
                String days = m.group(5);
                return String.format("လက်ကျန် %s %s ရှိပြီး ခန့်မှန်းဝယ်လိုအား %s %s သာရှိကာ သက်တမ်းကုန်ဆုံးရန် %s ရက်သာ ကျန်ရှိပါသည်။ အလေအလွင့် ဆုံးရှုံးမှု ကာကွယ်ရန် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို ၁၅-၂၅%% လျှော့ချပါ။",
                        stock, unit1, demand, unit2, days);
            }
            return "လက်ကျန်ပမာဏသည် ခန့်မှန်းဝယ်လိုအားထက် ပိုလျှံနေပြီး သက်တမ်းကုန်ဆုံးရက် နီးကပ်နေသဖြင့် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို လျှော့ချပါ။";
        }

        // 12. Recommendation Description: Redistribute excess inventory
        // e.g. "Surplus stock (7.4 liter) detected near expiry. Dispatch to registered food bank or charity partner before expiry cutoff."
        if ((lower.contains("surplus stock") || lower.contains("surplus")) && (lower.contains("food bank") || lower.contains("charity partner") || lower.contains("dispatch"))) {
            Matcher m = Pattern.compile("surplus\\s+stock\\s*\\(?([\\d.]+)\\s*([a-zA-Z]+)?\\)?", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (m.find()) {
                String qty = m.group(1);
                String unit = m.group(2) != null ? m.group(2) : "kg";
                return String.format("သက်တမ်းကုန်ခါနီး ပိုလျှံလက်ကျန် (%s %s) တွေ့ရှိရပါသည်။ သက်တမ်းမကုန်မီ မှတ်ပုံတင်ထားသော အစားအစာဘဏ် သို့မဟုတ် ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်းသို့ ပို့ဆောင်လှူဒါန်းပါ။", qty, unit);
            }
            return "သက်တမ်းမကုန်မီ ပိုလျှံနေသော အစားအစာများကို မှတ်ပုံတင်ထားသော ပရဟိတ အဖွဲ့အစည်း သို့မဟုတ် အစားအစာဘဏ်သို့ ပို့ဆောင်လှူဒါန်းပါ။";
        }

        // 13. Recommendation Description: Prioritize usage today
        // e.g. "Item expires in 0 day(s). Prioritize in today's menu specials, meal prep, and kitchen consumption immediately."
        // e.g. "Item expires today (0-day expiry remaining). Prioritize in today's menu specials..."
        if (lower.contains("prioritize in today's menu specials") || (lower.contains("prioritize") && lower.contains("today") && lower.contains("consumption"))) {
            Matcher m = Pattern.compile("expires\\s+(?:in\\s+)?(\\d+)\\s*day", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            String days = m.find() ? m.group(1) : "၀";
            return String.format("ကုန်ပစ္စည်းသည် %s ရက်အတွင်း သက်တမ်းကုန်ဆုံးပါမည်။ ယနေ့ မီနူးအထူးဟင်းလျာများ၊ ချက်ပြုတ်ပြင်ဆင်မှုနှင့် မီးဖိုချောင်တွင် ချက်ချင်း ဦးစားပေး သုံးစွဲပါ။", days);
        }

        // 14. Recommendation Description: Monitor stock velocity
        // e.g. "Item expires in 2 days. Monitor stock velocity and turnover closely to avoid sudden overstock accumulation."
        if (lower.contains("monitor stock velocity and turnover") || (lower.contains("monitor stock") && lower.contains("accumulation"))) {
            Matcher m = Pattern.compile("expires\\s+in\\s+(\\d+)\\s*days?", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            String days = m.find() ? m.group(1) : "၂";
            return String.format("ကုန်ပစ္စည်းသည် %s ရက်အတွင်း သက်တမ်းကုန်ဆုံးပါမည်။ ပိုလျှံမှု မဖြစ်ပေါ်စေရန် ကုန်ပစ္စည်းလက်ကျန်နှင့် သုံးစွဲမှုနှုန်းကို အနီးကပ် စောင့်ကြည့်ပါ။", days);
        }

        // 15. Recommendation Description: Adjust kitchen batch preparation
        // e.g. "Moderate waste risk detected. Adjust kitchen batch preparation down by 10-15% according to expected demand (0.6 liter)."
        if (lower.contains("adjust kitchen batch preparation down") || (lower.contains("moderate waste risk detected") && lower.contains("adjust"))) {
            Matcher mDemand = Pattern.compile("expected\\s+demand\\s*\\(?([\\d.]+)\\s*([a-zA-Z]+)?\\)?", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (mDemand.find()) {
                String demand = mDemand.group(1);
                String unit = mDemand.group(2) != null ? mDemand.group(2) : "kg";
                return String.format("အလယ်အလတ် အလေအလွင့် ဖြစ်နိုင်ခြေ ရှိနေပါသည်။ ခန့်မှန်းဝယ်လိုအား (%s %s) အရ မီးဖိုချောင် ပြင်ဆင်ချက်ပြုတ်မှု ပမာဏကို ၁၀-၁၅%% လျှော့ချ ညှိနှိုင်းပါ။", demand, unit);
            }
            return "အလေအလွင့် ဖြစ်နိုင်ခြေ လျှော့ချရန် မီးဖိုချောင် ပြင်ဆင်ချက်ပြုတ်မှု ပမာဏကို ၁၀-၁၅% လျှော့ချ ညှိနှိုင်းပါ။";
        }

        // 16. Recommendation Description: Promote usage / feature in daily specials
        // e.g. "Feature in chef's daily side dish, combo promotions, or lunch specials to accelerate inventory drawdown within 2 days."
        if (lower.contains("feature in chef's daily side dish") || (lower.contains("lunch specials") && lower.contains("drawdown"))) {
            Matcher mDays = Pattern.compile("within\\s+(\\d+)\\s*days?", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            String days = mDays.find() ? mDays.group(1) : "၂";
            return String.format("ကုန်ပစ္စည်း %s ရက်အတွင်း လျင်မြန်စွာ ကုန်စင်စေရန် စားဖိုမှူး၏ နေ့စဉ် အထူးဟင်းလျာ၊ တွဲဖက်ရောင်းချမှု သို့မဟုတ် နေ့လယ်စာ ပရိုမိုးရှင်းများတွင် ထည့်သွင်း ရောင်းချပါ။", days);
        }

        // 17. Recommendation Description: Maintain normal operation
        // e.g. "Safe shelf-life remaining (5 days) and balanced stock levels. Maintain standard scheduled production batch and regular replenishment cycle."
        if (lower.contains("safe shelf-life remaining") || lower.contains("maintain standard scheduled production batch")) {
            Matcher mDays = Pattern.compile("remaining\\s*\\(?(\\d+)\\s*days?\\)?", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            String days = mDays.find() ? mDays.group(1) : "၅";
            return String.format("လုံလောက်သော သက်တမ်းကျန်ရှိပြီး (%s ရက်) လက်ကျန်ပမာဏ မျှတနေပါသဖြင့် ပုံမှန် သတ်မှတ်ထားသော ထုတ်လုပ်မှုနှင့် ပစ္စည်းဖြည့်တင်းမှု အစီအစဉ်အတိုင်း ဆက်လက် ဆောင်ရွက်ပါ။", days);
        }

        // 18. Prolog rule assessment reasons:
        if (lower.contains("reached or passed expiration date") || lower.contains("passed expiration date") || lower.contains("do not serve")) {
            return "ကုန်ပစ္စည်းသည် သက်တမ်းကုန်ဆုံးသွားပါပြီ။ ဧည့်သည်များထံ မကျွေးမွေးပါနှင့်။";
        }
        if (lower.contains("expires today") || lower.contains("product expires today")) {
            return "ကုန်ပစ္စည်းသည် ယနေ့ သက်တမ်းကုန်ဆုံးပါမည်။ ချက်ချင်း စားသုံးရန် သို့မဟုတ် အရေးယူဆောင်ရွက်ရန် လိုအပ်ပါသည်။";
        }
        if (lower.contains("expires within 24 hours") || lower.contains("within 24 hours")) {
            return "ကုန်ပစ္စည်းသည် ၂၄ နာရီအတွင်း သက်တမ်းကုန်ဆုံးပါမည်။ ချက်ချင်း အရေးယူဆောင်ရွက်ရန် လိုအပ်ပါသည်။";
        }
        if (lower.contains("expires within 2-3 days") || lower.contains("within 2-3 days")) {
            return "ကုန်ပစ္စည်းသည် ၂-၃ ရက်အတွင်း သက်တမ်းကုန်ဆုံးပါမည်။ သုံးစွဲမှုနှုန်းကို အနီးကပ် စောင့်ကြည့်ပါ။";
        }
        if (lower.contains("significantly exceeds expected demand") || lower.contains("significantly exceeds")) {
            return "ကုန်ပစ္စည်းလက်ကျန်သည် ခန့်မှန်းဝယ်လိုအားထက် သိသာစွာ ပိုလျှံနေပါသည်";
        }
        if (lower.contains("moderately exceeds")) {
            return "လက်ရှိကုန်ပစ္စည်းလက်ကျန်သည် ခန့်မှန်းဝယ်လိုအားထက် အသင့်အတင့် ပိုလျှံနေပါသည်";
        }
        if (lower.contains("short remaining shelf life")) {
            return "ကျန်ရှိသော သက်တမ်း နည်းပါးနေပါသည် (၃ ရက် သို့မဟုတ် ၃ ရက်အောက်)";
        }
        if (lower.contains("high historical waste rate")) {
            return "အတိတ်ကာလ အလေအလွင့်ဖြစ်ပွားမှုနှုန်း မြင့်မားခဲ့ပါသည်";
        }
        if (lower.contains("critical (>=") || lower.contains("historical waste rate is critical")) {
            return "အတိတ်ကာလ အလေအလွင့်ဖြစ်ပွားမှုနှုန်း အလွန်မြင့်မားပါသည် (၃၀% နှင့်အထက်)။ လက်ကျန်ပမာဏသည် ဝယ်လိုအားထက် ပိုလျှံနေပါသည်။";
        }
        if (lower.contains("safe shelf life remaining") || (lower.contains("safe shelf life") && lower.contains("balanced"))) {
            return "လုံလောက်သော သက်တမ်းကျန်ရှိပြီး (> ၃ ရက်) လက်ကျန်ပမာဏနှင့် ဝယ်လိုအား မျှတနေပါသည်";
        }
        if (lower.contains("halt production and dispose") || lower.contains("halt production")) {
            return "ထုတ်လုပ်မှု ရပ်ဆိုင်းပြီး သက်တမ်းကုန်ပစ္စည်းများကို ဘေးကင်းစွာ စွန့်ပစ်ပါ";
        }
        if (lower.contains("reduce production or redistribute immediately")) {
            return "ထုတ်လုပ်မှု လျှော့ချပါ သို့မဟုတ် ချက်ချင်း ပြန်လည်လှူဒါန်းပါ";
        }
        if (lower.contains("slightly reduce production by 10-15%")) {
            return "ထုတ်လုပ်မှုပမာဏကို ၁၀-၁၅% အနည်းငယ် လျှော့ချပြီး ကုန်ပစ္စည်း လည်ပတ်မှုကို စောင့်ကြည့်ပါ";
        }
        if (lower.contains("feature in daily specials to accelerate turnover") || lower.contains("feature in daily specials")) {
            return "ကုန်ပစ္စည်း လျင်မြန်စွာ ကုန်စင်စေရန် နေ့စဉ် အထူးဟင်းလျာများတွင် ထည့်သွင်းရောင်းချပါ";
        }
        if (lower.contains("maintain standard scheduled production batch") || lower.contains("maintain standard scheduled batch")) {
            return "ပုံမှန် သတ်မှတ်ထားသော ထုတ်လုပ်မှု အစီအစဉ်အတိုင်း ဆက်လက်ဆောင်ရွက်ပါ";
        }
        if (lower.contains("maintain optimal production aligned with customer demand")) {
            return "ဝယ်လိုအားနှင့်အညီ အကောင်းဆုံး ထုတ်လုပ်မှု ပမာဏကို ဆက်လက် ထိန်းသိမ်းပါ";
        }

        // 19. Redistribution Dispatch Notes
        if (lower.contains("surplus food donation of") || (lower.contains("donation of") && lower.contains("to"))) {
            Matcher mDon = Pattern.compile("([\\d.]+)\\s*([A-Za-z]+)?\\s+to\\s+(.+)$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (mDon.find()) {
                String qty = mDon.group(1);
                String unit = mDon.group(2) != null ? mDon.group(2) : "kg";
                String recipient = mDon.group(3).trim();
                return String.format("%s သို့ ပိုလျှံအစားအစာ %s %s လှူဒါန်းမှု အစီအစဉ်", recipient, qty, unit);
            }
        }
        if (lower.contains("dispatch") || lower.contains("surplus")) {
            Matcher mNote = Pattern.compile("([\\d.]+)\\s*([A-Za-z]+)?").matcher(trimmed);
            if (mNote.find()) {
                String qty = mNote.group(1);
                String unit = mNote.group(2) != null ? mNote.group(2) : "kg";
                return String.format("ပိုလျှံပမာဏ %s %s အတွက် အလိုအလျောက် လှူဒါန်းမှု အစီအစဉ်", qty, unit);
            }
            return "ပိုလျှံအစားအစာ လှူဒါန်းမှု ပို့ဆောင်ရေး အစီအစဉ်";
        }

        // If no pattern matched, return English original
        return trimmed;
    }
}
