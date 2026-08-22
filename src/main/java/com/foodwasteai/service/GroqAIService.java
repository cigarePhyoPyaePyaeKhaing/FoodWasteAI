package com.foodwasteai.service;

import com.foodwasteai.config.AppConfig;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.RedistributionRecipient;
import com.foodwasteai.prolog.PrologAssessment;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Free Hosted Cloud AI Conversational Assistant powered by Groq (api.groq.com):
 * Architecture:
 * User -> ChatServlet -> Java Backend -> Live MySQL Data -> SWI-Prolog Reasoning -> Groq AI (Llama-3.3-70b-versatile) / Grounded XAI -> Smart Directives
 * Supports English (EN) and Professional Myanmar (MM) localization.
 */
public class GroqAIService {
    private static final Logger logger = LoggerFactory.getLogger(GroqAIService.class);
    private static final String GROQ_API_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";

    private final PredictionService predictionService;
    private final FoodItemService foodItemService;
    private final RecommendationService recommendationService;
    private final RedistributionService redistributionService;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public static class ChatResponse implements Serializable {
        private static final long serialVersionUID = 1L;
        private String userQuery;
        private String answer;
        private String explanation; // Synchronized alias for answer
        private List<String> sources = new ArrayList<>();
        private List<Map<String, Object>> relatedFoodItems = new ArrayList<>();
        private Map<String, Object> riskInfo = new LinkedHashMap<>();
        private String sourceEngine;
        private Map<String, Object> prologSummary;
        private List<SmartAction> smartRecommendations = new ArrayList<>();

        public ChatResponse() {}

        public String getUserQuery() { return userQuery; }
        public void setUserQuery(String userQuery) { this.userQuery = userQuery; }

        public String getAnswer() { return answer != null ? answer : explanation; }
        public void setAnswer(String answer) {
            this.answer = answer;
            this.explanation = answer;
        }

        public String getExplanation() { return explanation != null ? explanation : answer; }
        public void setExplanation(String explanation) {
            this.explanation = explanation;
            this.answer = explanation;
        }

        public List<String> getSources() { return sources; }
        public void setSources(List<String> sources) { this.sources = sources; }
        public void addSource(String source) {
            if (source != null && !this.sources.contains(source)) {
                this.sources.add(source);
            }
        }

        public List<Map<String, Object>> getRelatedFoodItems() { return relatedFoodItems; }
        public void setRelatedFoodItems(List<Map<String, Object>> relatedFoodItems) { this.relatedFoodItems = relatedFoodItems; }
        public void addRelatedFoodItem(Map<String, Object> item) { this.relatedFoodItems.add(item); }

        public Map<String, Object> getRiskInfo() { return riskInfo; }
        public void setRiskInfo(Map<String, Object> riskInfo) { this.riskInfo = riskInfo; }

        public String getSourceEngine() { return sourceEngine; }
        public void setSourceEngine(String sourceEngine) { this.sourceEngine = sourceEngine; }

        public Map<String, Object> getPrologSummary() { return prologSummary; }
        public void setPrologSummary(Map<String, Object> prologSummary) { this.prologSummary = prologSummary; }

        public List<SmartAction> getSmartRecommendations() { return smartRecommendations; }
        public void setSmartRecommendations(List<SmartAction> smartRecommendations) { this.smartRecommendations = smartRecommendations; }
        public void addSmartAction(SmartAction action) { this.smartRecommendations.add(action); }
    }

    public static class SmartAction implements Serializable {
        private static final long serialVersionUID = 1L;
        private String title;
        private String actionType; // REDUCE_PRODUCTION, PRIORITIZE_MENU, SCHEDULE_DONATION, VIEW_INVENTORY
        private String badge;
        private String payload;

        public SmartAction() {}

        public SmartAction(String title, String actionType, String badge, String payload) {
            this.title = title;
            this.actionType = actionType;
            this.badge = badge;
            this.payload = payload;
        }

        public String getTitle() { return title; }
        public String getActionType() { return actionType; }
        public String getBadge() { return badge; }
        public String getPayload() { return payload; }
    }

    public static class ConversationContext implements Serializable {
        private static final long serialVersionUID = 1L;
        private String lastFoodItemName;
        private Long lastFoodItemId;
        private String lastIntent;
        private String lastLanguage;
        private long lastInteractionTime;

        public ConversationContext() {
            this.lastInteractionTime = System.currentTimeMillis();
        }

        public String getLastFoodItemName() { return lastFoodItemName; }
        public void setLastFoodItemName(String lastFoodItemName) { this.lastFoodItemName = lastFoodItemName; }

        public Long getLastFoodItemId() { return lastFoodItemId; }
        public void setLastFoodItemId(Long lastFoodItemId) { this.lastFoodItemId = lastFoodItemId; }

        public String getLastIntent() { return lastIntent; }
        public void setLastIntent(String lastIntent) { this.lastIntent = lastIntent; }

        public String getLastLanguage() { return lastLanguage; }
        public void setLastLanguage(String lastLanguage) { this.lastLanguage = lastLanguage; }

        public long getLastInteractionTime() { return lastInteractionTime; }
        public void updateTime() { this.lastInteractionTime = System.currentTimeMillis(); }
    }

    private static final Map<String, ConversationContext> SESSION_CONTEXT_MAP = new ConcurrentHashMap<>();

    private static final Map<String, String> FOOD_SYNONYMS = new LinkedHashMap<>();
    static {
        // Myanmar food synonyms to English keywords
        FOOD_SYNONYMS.put("ကြက်သား", "chicken");
        FOOD_SYNONYMS.put("ကြက်", "chicken");
        FOOD_SYNONYMS.put("နို့", "milk");
        FOOD_SYNONYMS.put("နို့စိမ်း", "milk");
        FOOD_SYNONYMS.put("အမဲသား", "beef");
        FOOD_SYNONYMS.put("အမဲ", "beef");
        FOOD_SYNONYMS.put("ဆန်", "rice");
        FOOD_SYNONYMS.put("ထမင်း", "rice");
        FOOD_SYNONYMS.put("ကြက်ဥ", "egg");
        FOOD_SYNONYMS.put("ဘဲဥ", "egg");
        FOOD_SYNONYMS.put("ဥ", "egg");
        FOOD_SYNONYMS.put("ဝက်သား", "pork");
        FOOD_SYNONYMS.put("ဝက်", "pork");
        FOOD_SYNONYMS.put("ငါး", "fish");
        FOOD_SYNONYMS.put("ပုစွန်", "shrimp");
        FOOD_SYNONYMS.put("အသီးအရွက်", "vegetable");
        FOOD_SYNONYMS.put("ဟင်းသီးဟင်းရွက်", "vegetable");
        FOOD_SYNONYMS.put("သီးနှံ", "vegetable");
        FOOD_SYNONYMS.put("ပေါင်မုန့်", "bread");
        FOOD_SYNONYMS.put("ဒိန်ချဉ်", "yogurt");
        FOOD_SYNONYMS.put("ထောပတ်", "butter");
        FOOD_SYNONYMS.put("ဒိန်ခဲ", "cheese");

        // English synonyms
        FOOD_SYNONYMS.put("chicken", "chicken");
        FOOD_SYNONYMS.put("poultry", "chicken");
        FOOD_SYNONYMS.put("breast", "chicken");
        FOOD_SYNONYMS.put("milk", "milk");
        FOOD_SYNONYMS.put("dairy", "milk");
        FOOD_SYNONYMS.put("beef", "beef");
        FOOD_SYNONYMS.put("meat", "beef");
        FOOD_SYNONYMS.put("egg", "egg");
        FOOD_SYNONYMS.put("eggs", "egg");
        FOOD_SYNONYMS.put("rice", "rice");
        FOOD_SYNONYMS.put("pork", "pork");
        FOOD_SYNONYMS.put("fish", "fish");
        FOOD_SYNONYMS.put("seafood", "fish");
        FOOD_SYNONYMS.put("veggie", "vegetable");
        FOOD_SYNONYMS.put("vegetable", "vegetable");
        FOOD_SYNONYMS.put("bread", "bread");
        FOOD_SYNONYMS.put("yogurt", "yogurt");
        FOOD_SYNONYMS.put("butter", "butter");
        FOOD_SYNONYMS.put("cheese", "cheese");
    }

    public GroqAIService() {
        this.predictionService = new PredictionService();
        this.foodItemService = new FoodItemService();
        this.recommendationService = new RecommendationService();
        this.redistributionService = new RedistributionService();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    public GroqAIService(PredictionService predictionService, FoodItemService foodItemService,
                          RecommendationService recommendationService, RedistributionService redistributionService) {
        this.predictionService = predictionService;
        this.foodItemService = foodItemService;
        this.recommendationService = recommendationService;
        this.redistributionService = redistributionService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    /**
     * Executes the complete Groq Cloud AI pipeline:
     * User Query -> Intent Analysis -> Live MySQL Data -> SWI-Prolog Reasoning -> Groq AI / Rule-Grounded Explanation
     */
    public ChatResponse processUserQuery(String userQuery) {
        return processUserQuery(userQuery, "en", "default_session");
    }

    public ChatResponse processUserQuery(String userQuery, String language) {
        return processUserQuery(userQuery, language, "default_session");
    }

    public ChatResponse processUserQuery(String userQuery, String language, String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = "default_session";
        }
        ConversationContext ctx = SESSION_CONTEXT_MAP.computeIfAbsent(sessionId, k -> new ConversationContext());
        ctx.updateTime();

        if (userQuery == null || userQuery.trim().isEmpty()) {
            userQuery = (language != null && language.equalsIgnoreCase("mm")) ?
                    "မင်္ဂလာပါ" : "hello";
        }

        boolean isMyanmar = (language != null && language.equalsIgnoreCase("mm")) || containsMyanmarScript(userQuery);
        String activeLang = isMyanmar ? "mm" : "en";
        ctx.setLastLanguage(activeLang);

        ChatResponse response = new ChatResponse();
        response.setUserQuery(userQuery);

        // --- 0. FAST INTENT DETECTION (CASUAL_CHAT > GREETING > THANKS > IDENTITY > CAPABILITIES) ---
        // Optimization: Greetings and casual chat execute immediately without MySQL, Prolog, or Groq API calls.
        String cleanQuery = userQuery.trim().toLowerCase();

        if (isCasualChat(cleanQuery)) {
            ctx.setLastIntent("CASUAL_CHAT");
            String casual = isMyanmar ?
                    "ကျွန်ုပ် နေကောင်းပါတယ်ခင်ဗျာ၊ မေးမြန်းပေးလို့ ကျေးဇူးတင်ပါတယ်။ စားသောက်ဆိုင် အစားအစာ အလေအလွင့် စီမံခန့်ခွဲမှုနဲ့ ပတ်သက်ပြီး ဘာများ ကူညီပေးရမလဲခင်ဗျာ။" :
                    "I am doing well, thank you for asking! I'm here to help you manage food inventory and minimize kitchen waste. How can I help you today?";
            response.setAnswer(casual);
            response.setSourceEngine("FoodWaste AI Assistant");
            return response;
        }

        if (isGreeting(cleanQuery)) {
            ctx.setLastIntent("GREETING");
            String greeting = isMyanmar ?
                    "မင်္ဂလာပါ။ ကျွန်ုပ်သည် FoodWaste AI Assistant ဖြစ်ပါသည်။\n\nအစားအစာ အန္တရာယ်၊ သက်တမ်း၊ အလေအလွင့် လျှော့ချမှုနှင့် ပြန်လည်လှူဒါန်းမှုများကို မေးမြန်းနိုင်ပါသည်။" :
                    "Hello! I am FoodWaste AI Assistant.\n\nAsk me about food risk, expiry, waste reduction, or redistribution.";
            response.setAnswer(greeting);
            response.setSourceEngine("FoodWaste AI Assistant");
            return response;
        }

        if (isThanks(cleanQuery)) {
            ctx.setLastIntent("THANKS");
            String thanks = isMyanmar ?
                    "ရပါတယ်ခင်ဗျာ! အစားအသောက် အလေအလွင့် ဆန်းစစ်ရန် လိုအပ်ပါက မည်သည့်အချိန်မဆို မေးမြန်းနိုင်ပါသည်။" :
                    "You're welcome! Let me know if you need help analyzing food waste.";
            response.setAnswer(thanks);
            response.setSourceEngine("FoodWaste AI Assistant");
            return response;
        }

        if (isIdentity(cleanQuery)) {
            ctx.setLastIntent("IDENTITY");
            String idText = isMyanmar ?
                    "ကျွန်ုပ်သည် FoodWaste AI Assistant ဖြစ်ပါသည်။ စားသောက်ဆိုင်များတွင် အစားအစာ အလေအလွင့် လျှော့ချရေးကို ကူညီပေးပါသည်။" :
                    "I am FoodWaste AI Assistant. I help restaurants reduce food waste using intelligent analysis.";
            response.setAnswer(idText);
            response.setSourceEngine("FoodWaste AI Assistant");
            return response;
        }

        if (isCapabilities(cleanQuery)) {
            ctx.setLastIntent("CAPABILITIES");
            String capText = isMyanmar ?
                    "ကျွန်ုပ်သည် ကုန်ပစ္စည်းလက်ကျန် စာရင်းစစ်ဆေးခြင်း၊ သက်တမ်းကုန်ရက် စောင့်ကြည့်ခြင်း၊ အလေအလွင့် အန္တရာယ် တွက်ချက်ခြင်း၊ မီးဖိုချောင် ချက်ပြုတ်မှု ဦးစားပေး သတ်မှတ်ခြင်းနှင့် ပိုလျှံပစ္စည်းများ လှူဒါန်းခြင်းတို့ကို ကူညီပေးနိုင်ပါသည်။" :
                    "I can help you track inventory levels, monitor food expiry dates, analyze waste risks, optimize prep batches, and schedule surplus food donations.";
            response.setAnswer(capText);
            response.setSourceEngine("FoodWaste AI Assistant");
            return response;
        }

        try {
            // 1. Fetch live MySQL Inventory & Partners for Food & Operational queries
            List<FoodItem> inventory = foodItemService.getAllFoodItems();
            List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();

            // 1.1 Check for unrelated / unknown topic
            if (isUnknownTopic(cleanQuery, inventory)) {
                ctx.setLastIntent("UNKNOWN_TOPIC");
                String unknownText = isMyanmar ?
                        "ကျွန်ုပ်သည် စားသောက်ဆိုင် အစားအသောက် အလေအလွင့် စီမံခန့်ခွဲရေးကို အဓိက ကူညီပေးပါသည်။\n" +
                        "ကုန်ပစ္စည်းလက်ကျန်၊ သက်တမ်းကုန်ရက်၊ အလေအလွင့် လျှော့ချရေးနှင့် လှူဒါန်းမှုဆိုင်ရာများကို မေးမြန်းနိုင်ပါသည်။" :
                        "I specialize in FoodWaste management.\n" +
                        "I can help with inventory, expiry, waste reduction, and redistribution.";
                response.setAnswer(unknownText);
                response.setSourceEngine(isMyanmar ? "FoodWaste AI Assistant\nသင့်အစားအစာ စီမံခန့်ခွဲမှု အကူ" : "FoodWaste AI Assistant\nYour food waste helper");
                response.addSmartAction(new SmartAction(isMyanmar ? "📦 ကုန်ပစ္စည်းလက်ကျန် ကြည့်ရှုမည်" : "📦 View Kitchen Inventory", "VIEW_INVENTORY", "INFO", "/inventory.html"));
                return response;
            }

            // 2. Execute SWI-Prolog Expert Reasoning
            Map<String, Object> prologReport = predictionService.assessAllInventory();
            response.setPrologSummary(prologReport);

            @SuppressWarnings("unchecked")
            List<PrologAssessment> items = (List<PrologAssessment>) prologReport.get("items");
            if (items == null) items = Collections.emptyList();

            // 3. Compute Risk Summary Info
            long highRiskCount = items.stream().filter(i -> "HIGH".equalsIgnoreCase(i.getRiskLevel())).count();
            long medRiskCount = items.stream().filter(i -> "MEDIUM".equalsIgnoreCase(i.getRiskLevel())).count();
            long lowRiskCount = items.stream().filter(i -> "LOW".equalsIgnoreCase(i.getRiskLevel())).count();
            double overallRisk = prologReport.get("overallRiskScore") instanceof Number ?
                    ((Number) prologReport.get("overallRiskScore")).doubleValue() : 0.0;
            double potentialSavings = prologReport.get("potentialSavings") instanceof Number ?
                    ((Number) prologReport.get("potentialSavings")).doubleValue() : 0.0;

            Map<String, Object> riskInfoMap = new LinkedHashMap<>();
            riskInfoMap.put("totalItemsEvaluated", inventory.size());
            riskInfoMap.put("highRiskCount", highRiskCount);
            riskInfoMap.put("mediumRiskCount", medRiskCount);
            riskInfoMap.put("lowRiskCount", lowRiskCount);
            riskInfoMap.put("overallRiskScore", Math.round(overallRisk));
            riskInfoMap.put("potentialSavingsMMK", potentialSavings);
            response.setRiskInfo(riskInfoMap);

            // 4. Identify Related Food Items (Priority: NEW FOOD ENTITY > FOLLOW-UP CONTEXT > GENERAL CONTEXT)
            FoodItem matchedFoodItem = extractFoodEntity(userQuery, inventory);
            PrologAssessment matchedAssessment = null;

            if (matchedFoodItem != null) {
                // Priority 1: New food entity detected in user message -> overrides previous context immediately
                ctx.setLastFoodItemName(matchedFoodItem.getName());
                ctx.setLastFoodItemId(matchedFoodItem.getId());
                ctx.setLastIntent("FOOD_QUERY");
            } else if (ctx.getLastFoodItemName() != null && isFollowUpQuery(cleanQuery)) {
                // Priority 2: Follow-up pronoun/context reference ("it", "this", "that", "what should I do with it")
                final String lastFoodName = ctx.getLastFoodItemName();
                matchedFoodItem = inventory.stream()
                        .filter(fi -> fi.getName() != null && fi.getName().equalsIgnoreCase(lastFoodName))
                        .findFirst().orElse(null);
                if (matchedFoodItem != null) {
                    System.out.println("Follow-up context reference resolved to:\n" + matchedFoodItem.getName());
                    logger.info("Follow-up context reference resolved to: {}", matchedFoodItem.getName());
                }
            }

            if (matchedFoodItem != null) {
                final Long matchedId = matchedFoodItem.getId();
                final String matchedName = matchedFoodItem.getName();
                matchedAssessment = items.stream()
                        .filter(a -> (a.getFoodItemId() != null && a.getFoodItemId().equals(matchedId)) ||
                                     (a.getFoodName() != null && a.getFoodName().equalsIgnoreCase(matchedName)))
                        .findFirst().orElse(null);

                if (matchedAssessment == null && matchedId != null) {
                    try {
                        matchedAssessment = predictionService.assessFoodItemById(matchedId).orElse(null);
                    } catch (Exception ignored) {}
                }

                Map<String, Object> itemMap = new LinkedHashMap<>();
                itemMap.put("id", matchedFoodItem.getId());
                itemMap.put("name", matchedFoodItem.getName());
                itemMap.put("stock", matchedFoodItem.getQuantity());
                itemMap.put("unit", matchedFoodItem.getUnit());
                itemMap.put("pricePerUnit", matchedFoodItem.getPricePerUnit());
                itemMap.put("expiryDate", matchedFoodItem.getExpiryDate() != null ? matchedFoodItem.getExpiryDate().toString() : "");
                itemMap.put("expiryStatus", matchedFoodItem.getExpiryStatus());
                itemMap.put("riskLevel", matchedAssessment != null ? matchedAssessment.getRiskLevel() : "LOW");
                itemMap.put("riskScore", matchedAssessment != null ? Math.round(matchedAssessment.getRiskPercentage()) : 0);
                response.addRelatedFoodItem(itemMap);

                if (isMyanmar) {
                    response.addSource("ကုန်ပစ္စည်းစာရင်း (" + matchedFoodItem.getName() + ")");
                    response.addSource("သက်တမ်းဆန်းစစ်ချက်");
                    response.addSource("အလေအလွင့်အကဲဖြတ်ချက်");
                } else {
                    response.addSource("Inventory records (" + matchedFoodItem.getName() + ")");
                    response.addSource("Expiry analysis");
                    response.addSource("Waste evaluation");
                }
            } else {
                if (isMyanmar) {
                    response.addSource("ကုန်ပစ္စည်းစာရင်း");
                    response.addSource("သက်တမ်းဆန်းစစ်ချက်");
                    response.addSource("အလေအလွင့်အကဲဖြတ်ချက်");
                } else {
                    response.addSource("Inventory records");
                    response.addSource("Expiry analysis");
                    response.addSource("Waste evaluation");
                }
            }

            // 5. Synthesize Explanation (Groq Cloud API or Intelligent Grounded Fallback)
            String apiKey = AppConfig.getGroqApiKey();
            String groqExplanation = null;

            if (apiKey != null && !apiKey.trim().isEmpty() && !"demo-key-placeholder".equalsIgnoreCase(apiKey)) {
                groqExplanation = callGroqApi(userQuery, inventory, items, recipients, apiKey, activeLang);
            }

            // 5.1 Response Validation: If a specific food item was detected, ensure response focuses on it
            if (matchedFoodItem != null && groqExplanation != null) {
                String matchedName = matchedFoodItem.getName();
                boolean containsMatched = groqExplanation.toLowerCase().contains(matchedName.toLowerCase());
                if (!containsMatched) {
                    logger.warn("Response validation mismatch: Expected {} in answer. Falling back to grounded XAI.", matchedName);
                    groqExplanation = null;
                }
            }

            if (groqExplanation == null || groqExplanation.trim().isEmpty()) {
                groqExplanation = generateRuleGroundedExplanation(userQuery, inventory, items, recipients, matchedFoodItem, matchedAssessment, activeLang);
                response.setSourceEngine(isMyanmar ? "FoodWaste AI Assistant\nသင့်အစားအစာ စီမံခန့်ခွဲမှု အကူ" : "FoodWaste AI Assistant\nYour food waste helper");
            } else {
                response.setSourceEngine(isMyanmar ? "FoodWaste AI Assistant\nသင့်အစားအစာ စီမံခန့်ခွဲမှု အကူ" : "FoodWaste AI Assistant\nYour food waste helper");
            }

            response.setAnswer(groqExplanation);

            // 6. Generate Context-Aware Smart Action Buttons
            if (matchedFoodItem != null && matchedAssessment != null) {
                if ("HIGH".equalsIgnoreCase(matchedAssessment.getRiskLevel())) {
                    String title = isMyanmar ?
                            "⚡ " + matchedFoodItem.getName() + " ထုတ်လုပ်မှုပမာဏ လျှော့ချမည်" :
                            "⚡ Reduce Next Prep Batch for " + matchedFoodItem.getName();
                    response.addSmartAction(new SmartAction(title, "REDUCE_PRODUCTION", isMyanmar ? "အရေးပေါ်" : "URGENT", "foodItemId=" + matchedFoodItem.getId()));
                }
                if (matchedAssessment.isRecommendRedistribution() || (matchedFoodItem.getQuantity() != null && matchedFoodItem.getQuantity().doubleValue() > matchedAssessment.getExpectedDemand())) {
                    String title = isMyanmar ?
                            "🤝 " + matchedFoodItem.getName() + " ပိုလျှံမှု ပရဟိတသို့ လှူဒါန်းမည်" :
                            "🤝 Dispatch Surplus " + matchedFoodItem.getName() + " to Charity";
                    response.addSmartAction(new SmartAction(title, "SCHEDULE_DONATION", isMyanmar ? "ပြန်လည်လှူဒါန်းမှု" : "REDISTRIBUTION", "foodItemId=" + matchedFoodItem.getId() + "&foodName=" + matchedFoodItem.getName()));
                }
            } else if (!items.isEmpty()) {
                for (PrologAssessment a : items) {
                    if ("HIGH".equalsIgnoreCase(a.getRiskLevel()) && response.getSmartRecommendations().size() < 2) {
                        String title = isMyanmar ?
                                "⚡ " + a.getFoodName() + " ထုတ်လုပ်မှုပမာဏ လျှော့ချမည်" :
                                "⚡ " + a.getRecommendation();
                        response.addSmartAction(new SmartAction(title, "REDUCE_PRODUCTION", isMyanmar ? "အရေးပေါ်" : "URGENT", "foodItemId=" + a.getFoodItemId()));
                    }
                    if (a.isRecommendRedistribution() && response.getSmartRecommendations().size() < 3) {
                        String title = isMyanmar ?
                                "🤝 " + a.getFoodName() + " ပိုလျှံမှု ပရဟိတသို့ လှူဒါန်းမည်" :
                                "🤝 Dispatch Surplus " + a.getFoodName() + " to Charity";
                        response.addSmartAction(new SmartAction(title, "SCHEDULE_DONATION", isMyanmar ? "ပြန်လည်လှူဒါန်းမှု" : "REDISTRIBUTION", "foodItemId=" + a.getFoodItemId()));
                    }
                }
            }

            if (response.getSmartRecommendations().isEmpty()) {
                String title = isMyanmar ? "📦 ကုန်ပစ္စည်းလက်ကျန်နှင့် ဝယ်လိုအား ကြည့်ရှုမည်" : "📦 View Kitchen Inventory & Demand";
                response.addSmartAction(new SmartAction(title, "VIEW_INVENTORY", "INFO", "/inventory.html"));
            }

        } catch (Exception e) {
            logger.error("Error in GroqAIService: {}", e.getMessage(), e);
            if (isMyanmar) {
                response.setAnswer("ကျွန်ုပ်သည် စားသောက်ဆိုင် စာရင်းအင်းများနှင့် အလေအလွင့် လျှော့ချရေးကို ကူညီပေးပါသည်။ လက်ရှိတွင် စာရင်းသွင်းထားသော ကုန်ပစ္စည်း မရှိသေးပါက Inventory သို့ သွားရောက် ထည့်သွင်းပေးပါ။");
                response.setSourceEngine("FoodWaste AI Assistant\nသင့်အစားအစာ စီမံခန့်ခွဲမှု အကူ");
                response.addSmartAction(new SmartAction("ကုန်ပစ္စည်းလက်ကျန် ကြည့်ရှုမည်", "VIEW_INVENTORY", "အချက်အလက်", "/inventory.html"));
            } else {
                response.setAnswer("I help evaluate kitchen inventory and identify waste risks. Please ensure food items are recorded in the Inventory section to generate waste predictions and mitigation directives.");
                response.setSourceEngine("FoodWaste AI Assistant\nYour food waste helper");
                response.addSmartAction(new SmartAction("View Kitchen Inventory", "VIEW_INVENTORY", "INFO", "/inventory.html"));
            }
        }

        return response;
    }

    private boolean containsMyanmarScript(String text) {
        if (text == null) return false;
        for (char c : text.toCharArray()) {
            if (c >= '\u1000' && c <= '\u109F') {
                return true;
            }
        }
        return false;
    }

    private boolean isCasualChat(String q) {
        if (q == null) return false;
        String clean = q.replaceAll("[^a-zA-Z0-9\u1000-\u109F\\s]", "").toLowerCase().trim();

        // English casual chat
        if (clean.equals("how do you do") || clean.equals("how are you") || clean.equals("how are u") ||
            clean.equals("how are you doing") || clean.equals("how is it going") || clean.equals("hows it going") ||
            clean.equals("whats up") || clean.equals("what is up") || clean.equals("sup") ||
            clean.equals("how are things") || clean.equals("nice to meet you") || clean.equals("good to see you") ||
            clean.equals("how is your day") || clean.equals("hows your day") || clean.equals("are you there") ||
            clean.equals("ok") || clean.equals("okay") || clean.equals("sure") || clean.equals("alright") ||
            clean.equals("cool") || clean.equals("bye") || clean.equals("goodbye") || clean.equals("see you") ||
            clean.equals("take care")) {
            return true;
        }

        // Myanmar casual chat
        if (clean.contains("နေကောင်းလား") || clean.contains("နေကောင်းပါသလား") || clean.contains("ဘယ်လိုလဲ") ||
            clean.contains("အဆင်ပြေလား") || clean.contains("ဘာထူးလဲ") || clean.equals("ဟုတ်ကဲ့") ||
            clean.equals("ကောင်းပါပြီ") || clean.equals("တာ့တာ") || clean.equals("ဟုတ်") ||
            clean.equals("အိုကေ")) {
            return true;
        }

        return false;
    }

    private boolean isGreeting(String q) {
        if (q == null) return false;
        String clean = q.replaceAll("[^a-zA-Z0-9\u1000-\u109F\\s]", "").toLowerCase().trim();
        if (clean.equals("hi") || clean.equals("hello") || clean.equals("hey") || clean.equals("hiya") ||
            clean.equals("good morning") || clean.equals("good afternoon") || clean.equals("good evening") ||
            clean.equals("greetings") || clean.equals("howdy") || clean.equals("yo") ||
            clean.equals("hi there") || clean.equals("hello there")) {
            return true;
        }
        if (clean.equals("မင်္ဂလာပါ") || clean.equals("ဟယ်လို") ||
            clean.equals("မင်္ဂလာနံနက်ခင်းပါ") || clean.equals("မင်္ဂလာညနေခင်းပါ") || clean.equals("မင်္ဂလာပါရှင်") ||
            clean.equals("မင်္ဂလာပါခင်ဗျာ")) {
            return true;
        }
        return false;
    }

    private boolean isThanks(String q) {
        if (q == null) return false;
        String clean = q.replaceAll("[^a-zA-Z0-9\u1000-\u109F\\s]", "").toLowerCase().trim();
        if (clean.equals("thank you") || clean.equals("thanks") || clean.equals("thx") ||
            clean.equals("thank you very much") || clean.equals("many thanks") || clean.equals("thanks a lot")) {
            return true;
        }
        if (clean.equals("ကျေးဇူးတင်ပါတယ်") || clean.equals("ကျေးဇူးပါ") || clean.equals("ကျေးဇူးတင်ပါတယ်ရှင်") ||
            clean.equals("ကျေးဇူးတင်ပါတယ်ခင်ဗျာ")) {
            return true;
        }
        return false;
    }

    private boolean isIdentity(String q) {
        if (q == null) return false;
        String clean = q.replaceAll("[^a-zA-Z0-9\u1000-\u109F\\s]", "").toLowerCase().trim();
        if (clean.equals("who are you") || clean.equals("who are u") || clean.equals("what is your name") ||
            clean.equals("whats your name") || clean.equals("tell me about yourself")) {
            return true;
        }
        if (clean.contains("မင်းဘယ်သူလဲ") || clean.contains("သင်ဘယ်သူလဲ") || clean.contains("နာမည်ဘယ်သူလဲ") ||
            clean.equals("ဘယ်သူလဲ")) {
            return true;
        }
        return false;
    }

    private boolean isCapabilities(String q) {
        if (q == null) return false;
        String clean = q.replaceAll("[^a-zA-Z0-9\u1000-\u109F\\s]", "").toLowerCase().trim();
        if (clean.equals("what can you do") || clean.equals("what do you do") || clean.equals("help") ||
            clean.equals("how can you help") || clean.equals("what are your features")) {
            return true;
        }
        if (clean.contains("ဘာတွေလုပ်ပေးနိုင်လဲ") || clean.contains("ဘယ်လိုကူညီပေးနိုင်လဲ") || clean.equals("အကူအညီ")) {
            return true;
        }
        return false;
    }

    private boolean isFoodWasteQuery(String q, List<FoodItem> inventory) {
        if (q == null) return false;
        String[] keywords = {
            "waste", "food", "risk", "expiry", "expire", "expired", "shelf", "life", "inventory",
            "stock", "item", "cook", "menu", "chef", "donat", "redistribut", "charit", "surplus",
            "loss", "price", "metric", "predict", "recommend", "summary", "report", "kitchen",
            "demand", "prep", "batch", "prevent", "spoil",
            // Myanmar keywords
            "အလေအလွင့်", "အန္တရာယ်", "သက်တမ်း", "ကုန်", "ပစ္စည်း", "လက်ကျန်", "မီးဖိုချောင်", "ချက်",
            "မီနူး", "လှူဒါန်း", "ပရဟိတ", "ပိုလျှံ", "ဆုံးရှုံး", "ခန့်မှန်း", "အကြံပြု", "အနှစ်ချုပ်",
            "ဝယ်လိုအား", "စွန့်ပစ်", "ဟင်း"
        };
        for (String kw : keywords) {
            if (q.contains(kw)) return true;
        }

        if (inventory != null) {
            for (FoodItem item : inventory) {
                if (item.getName() != null && !item.getName().trim().isEmpty()) {
                    String name = item.getName().toLowerCase().trim();
                    if (q.contains(name)) return true;
                    for (String tok : name.split("\\s+")) {
                        if (tok.length() >= 3 && !tok.equals("fresh") && !tok.equals("organic") && q.contains(tok)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isUnknownTopic(String q, List<FoodItem> inventory) {
        if (isFoodWasteQuery(q, inventory)) {
            return false;
        }
        if (q.contains("weather") || q.contains("temperature") || q.contains("forecast") ||
            q.contains("football") || q.contains("world cup") || q.contains("president") ||
            q.contains("movie") || q.contains("song") || q.contains("sing") ||
            q.contains("game") || q.contains("joke") || q.contains("story") ||
            q.contains("ရာသီဥတု") || q.contains("သီချင်း") || q.contains("ရုပ်ရှင်") ||
            q.contains("ဘောလုံး") || q.contains("ဟာသ") || q.contains("ကဗျာ")) {
            return true;
        }
        return false;
    }

    /**
     * Extracts and matches any food entity mentioned in user query against live inventory.
     * Highest priority: Exact Phrase > Multilingual Synonyms > Meaningful Tokens > Category.
     */
    private FoodItem extractFoodEntity(String query, List<FoodItem> inventory) {
        if (query == null || query.trim().isEmpty() || inventory == null || inventory.isEmpty()) {
            return null;
        }

        String lowerQuery = query.toLowerCase().trim();
        FoodItem bestMatch = null;
        int bestScore = 0;
        String detectedWord = null;

        for (FoodItem fi : inventory) {
            if (fi.getName() == null || fi.getName().trim().isEmpty()) continue;
            String fiNameLower = fi.getName().toLowerCase().trim();
            String fiCatLower = fi.getCategory() != null ? fi.getCategory().toLowerCase().trim() : "";
            int score = 0;
            String matchedTerm = null;

            // 1. Exact food name match in query
            if (lowerQuery.contains(fiNameLower)) {
                score = 300 + fiNameLower.length();
                matchedTerm = fiNameLower;
            } else if (fiNameLower.contains(lowerQuery) && lowerQuery.length() >= 3) {
                score = 250 + lowerQuery.length();
                matchedTerm = lowerQuery;
            } else {
                // 2. Multilingual & Synonym match (Highest priority for user shorthand)
                for (Map.Entry<String, String> entry : FOOD_SYNONYMS.entrySet()) {
                    String synKey = entry.getKey().toLowerCase().trim();
                    String synVal = entry.getValue().toLowerCase().trim();
                    boolean containsSyn = false;
                    if (containsMyanmarScript(synKey)) {
                        containsSyn = lowerQuery.contains(synKey);
                    } else {
                        containsSyn = lowerQuery.matches(".*\\b" + java.util.regex.Pattern.quote(synKey) + "\\b.*");
                    }

                    if (containsSyn) {
                        if (fiNameLower.contains(synVal) || fiCatLower.contains(synVal) || fiNameLower.contains(synKey)) {
                            int synScore = 200 + synVal.length();
                            if (synScore > score) {
                                score = synScore;
                                matchedTerm = synKey;
                            }
                        }
                    }
                }

                // 3. Significant word token match (e.g. "chicken", "milk", "beef", "rice", "salmon")
                String[] tokens = fiNameLower.split("\\s+");
                for (String token : tokens) {
                    String t = token.replaceAll("[^a-zA-Z0-9\u1000-\u109F]", "").toLowerCase();
                    if (t.length() >= 3 && !t.equals("fresh") && !t.equals("organic") && !t.equals("item") && !t.equals("cooked") && !t.equals("food")) {
                        boolean tokenMatch = false;
                        if (containsMyanmarScript(t)) {
                            tokenMatch = lowerQuery.contains(t);
                        } else {
                            tokenMatch = lowerQuery.matches(".*\\b" + java.util.regex.Pattern.quote(t) + "\\b.*");
                        }

                        if (tokenMatch) {
                            int tokScore = 150 + t.length();
                            if (tokScore > score) {
                                score = tokScore;
                                matchedTerm = t;
                            }
                        }
                    }
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestMatch = fi;
                detectedWord = matchedTerm;
            }
        }

        if (bestMatch != null && bestScore > 0) {
            logger.info("Detected food keyword: {} | Matched inventory item: {} | Context updated: {}",
                    detectedWord != null ? detectedWord : bestMatch.getName(), bestMatch.getName(), bestMatch.getName());
        }

        return bestMatch;
    }

    private boolean isFollowUpQuery(String q) {
        if (q == null) return false;
        String clean = q.toLowerCase().trim();

        if (clean.contains("food bank") || clean.contains("charit") || clean.contains("surplus item") ||
            clean.contains("high risk") || clean.contains("cook today") || clean.contains("menu today") ||
            clean.contains("priorit") || clean.contains("summary") || clean.contains("overview") ||
            clean.contains("today's") || clean.contains("ပရဟိတ") || clean.contains("အန္တရာယ်မြင့်") ||
            clean.contains("အနှစ်ချုပ်") || clean.contains("ဦးစားပေး")) {
            return false;
        }

        if (clean.startsWith("what about") || clean.startsWith("how about") ||
            clean.matches(".*\\b(it|that|this|them|the item)\\b.*") ||
            clean.contains("ဘာလုပ်") || clean.contains("ဘယ်လို") || clean.contains("ဒါ") ||
            clean.contains("၎င်း") || clean.endsWith("ရော") || clean.endsWith("ရော?")) {
            return true;
        }

        return false;
    }

    private String callGroqApi(String userQuery, List<FoodItem> inventory, List<PrologAssessment> prologAssessments,
                               List<RedistributionRecipient> recipients, String apiKey, String lang) {
        try {
            String model = AppConfig.getGroqModel();

            StringBuilder contextBuilder = new StringBuilder();
            contextBuilder.append("CURRENT INVENTORY & DATABASE METRICS:\n");
            for (FoodItem item : inventory) {
                contextBuilder.append(String.format("- %s (ID %d): Stock=%.1f %s, Price=%s MMK/unit, Expiry=%s, Status=%s, ExpiryStatus=%s\n",
                        item.getName(), item.getId(), item.getQuantity(), item.getUnit(), item.getPricePerUnit(), item.getExpiryDate(), item.getStatus(), item.getExpiryStatus()));
            }

            contextBuilder.append("\nEXPERT REASONING RESULTS:\n");
            for (PrologAssessment a : prologAssessments) {
                contextBuilder.append(String.format("- %s: Risk=%s (%d%%), Priority=%s, Reasons=%s, RecAction=%s, Redistribute=%s\n",
                        a.getFoodName(), a.getRiskLevel(), Math.round(a.getRiskPercentage()), a.getPriorityUsage(),
                        String.join("; ", a.getReasons()), a.getRecommendation(), a.isRecommendRedistribution()));
            }

            contextBuilder.append("\nCHARITY REDISTRIBUTION PARTNERS:\n");
            for (RedistributionRecipient r : recipients) {
                contextBuilder.append(String.format("- %s (%s, Contact: %s, Phone: %s)\n", r.getName(), r.getOrganizationType(), r.getContactPerson(), r.getPhone()));
            }

            StringBuilder systemPrompt = new StringBuilder();
            systemPrompt.append("You are FoodWaste AI Assistant.\n\n");
            systemPrompt.append("Act like a professional food waste consultant.\n\n");
            systemPrompt.append("Answer naturally like a human assistant.\n\n");
            systemPrompt.append("Use verified food inventory information.\n\n");
            systemPrompt.append("Never invent:\n");
            systemPrompt.append("- food items\n");
            systemPrompt.append("- stock quantity\n");
            systemPrompt.append("- expiry date\n");
            systemPrompt.append("- risk percentage\n\n");
            systemPrompt.append("Do not mention:\n");
            systemPrompt.append("- Groq\n");
            systemPrompt.append("- Gemini\n");
            systemPrompt.append("- MySQL\n");
            systemPrompt.append("- SWI-Prolog Expert Reasoning Engine\n");
            systemPrompt.append("- backend\n");
            systemPrompt.append("- API\n");
            systemPrompt.append("- internal architecture\n\n");
            systemPrompt.append("unless the user explicitly asks.\n\n");
            systemPrompt.append("OPERATIONAL KITCHEN FACTS & DEDUCTIONS:\n");
            systemPrompt.append(contextBuilder.toString());
            systemPrompt.append("\nRULES:\n");
            if ("mm".equalsIgnoreCase(lang)) {
                systemPrompt.append("1. Answer in natural, fluent Myanmar (Burmese) language using Myanmar Unicode script.\n");
            } else {
                systemPrompt.append("1. Answer in clear, natural English.\n");
            }
            systemPrompt.append("2. Strictly preserve exact food names, numbers, units (liter, kg, MMK, pieces), percentages, and status terms.\n");
            systemPrompt.append("3. Format with clean markdown headings and bullet points.\n");

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", model);
            requestBody.addProperty("temperature", 0.2);
            requestBody.addProperty("max_tokens", 1024);

            JsonArray messages = new JsonArray();

            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt.toString());
            messages.add(sysMsg);

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", userQuery);
            messages.add(userMsg);

            requestBody.add("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_API_ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                    .timeout(Duration.ofSeconds(6))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() == 200) {
                JsonObject jsonResponse = gson.fromJson(httpResponse.body(), JsonObject.class);
                JsonArray choices = jsonResponse.getAsJsonArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JsonObject firstChoice = choices.get(0).getAsJsonObject();
                    JsonObject message = firstChoice.getAsJsonObject("message");
                    if (message != null && message.has("content")) {
                        return message.get("content").getAsString().trim();
                    }
                }
            } else {
                logger.warn("Groq API responded with status code: {} | body: {}", httpResponse.statusCode(), httpResponse.body());
            }
        } catch (Exception e) {
            logger.warn("Groq AI API call failed or timed out: {}. Using high-precision grounded XAI.", e.getMessage());
        }
        return null;
    }

    private String generateRuleGroundedExplanation(String userQuery, List<FoodItem> inventory,
                                                  List<PrologAssessment> items,
                                                  List<RedistributionRecipient> recipients,
                                                  FoodItem matchedFoodItem,
                                                  PrologAssessment matchedAssessment,
                                                  String lang) {
        boolean isMm = "mm".equalsIgnoreCase(lang) || containsMyanmarScript(userQuery);
        String lowerQuery = userQuery.toLowerCase().trim();

        if (inventory == null || inventory.isEmpty()) {
            if (lowerQuery.contains("donat") || lowerQuery.contains("redistribut") || lowerQuery.contains("charit") || lowerQuery.contains("လှူဒါန်း")) {
                StringBuilder recipientBlock = new StringBuilder();
                if (recipients != null && !recipients.isEmpty()) {
                    int idx = 1;
                    for (RedistributionRecipient r : recipients) {
                        recipientBlock.append(String.format("%d. 🏢 **%s** (%s, %s: %s)\n",
                                idx, r.getName(), r.getOrganizationType(), isMm ? "ဖုန်း" : "Phone", r.getPhone()));
                        idx++;
                    }
                }

                if (isMm) {
                    return "### 🤝 ပိုလျှံအစားအစာ ပြန်လည်လှူဒါန်းရေး အစီအစဉ်\n\n" +
                           "လက်ရှိတွင် လှူဒါန်းရန် ပိုလျှံအစားအစာ စာရင်း မရှိသေးပါ။\n\n" +
                           "**မှတ်ပုံတင်ထားသော ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်းများ:**\n" +
                           recipientBlock.toString() + "\n" +
                           "💡 *ပိုလျှံအစားအစာများ ရှိလာပါက Redistribution ကဏ္ဍတွင် အချိန်ဇယားဆွဲနိုင်ပါသည်။*";
                } else {
                    return "### 🤝 Surplus Food Redistribution Directory\n\n" +
                           "No surplus food items are currently flagged for donation in your inventory.\n\n" +
                           "**Verified Charity Partners Available for Pickup:**\n" +
                           recipientBlock.toString() + "\n" +
                           "💡 *When surplus food is identified, you can schedule dispatches in the Redistribution tab.*";
                }
            }

            if (isMm) {
                return "### 🍃 FoodWaste AI နေ့စဉ် မီးဖိုချောင် အနှစ်ချုပ် အစီရင်ခံစာ\n\n" +
                       "လက်ရှိ မီးဖိုချောင် စာရင်းအတွင်း ကုန်ပစ္စည်း မရှိသေးပါ။\n\n" +
                       "**ဆောင်ရွက်ရန်:**\n" +
                       "၁။ 📦 **Inventory (ကုန်ပစ္စည်းလက်ကျန်)** သို့သွား၍ အစားအစာများကို ထည့်သွင်းပါ။\n" +
                       "၂။ ⚡ စနစ်မှ အလေအလွင့် အန္တရာယ်နှင့် စီမံခန့်ခွဲမှု အကြံပြုချက်များကို အလိုအလျောက် တွက်ချက်ပေးမည် ဖြစ်ပါသည်။";
            } else {
                return "### 🍃 FoodWaste AI Daily Intelligence Summary\n\n" +
                       "No inventory items currently exist in your kitchen inventory.\n\n" +
                       "**Next Steps:**\n" +
                       "1. 📦 Navigate to **Inventory** and add your restaurant food items.\n" +
                       "2. ⚡ The system will automatically evaluate waste risk probabilities and generate intelligent mitigation directives.";
            }
        }

        if (matchedFoodItem != null && matchedAssessment != null) {
            String foodName = matchedFoodItem.getName();
            double stock = matchedFoodItem.getQuantity() != null ? matchedFoodItem.getQuantity().doubleValue() : 0.0;
            String unit = matchedFoodItem.getUnit() != null ? matchedFoodItem.getUnit() : "liter";
            double surplus = Math.max(0.0, stock - matchedAssessment.getExpectedDemand());
            int expiryDays = matchedAssessment.getExpiryDays();
            String expiryStatus = matchedFoodItem.getExpiryStatus() != null ? matchedFoodItem.getExpiryStatus() : "SAFE";
            String riskLevel = matchedAssessment.getRiskLevel();
            double riskPct = matchedAssessment.getRiskPercentage();

            List<String> reasons = (isMm && matchedAssessment.getReasonsMy() != null && !matchedAssessment.getReasonsMy().isEmpty()) ?
                    matchedAssessment.getReasonsMy() : matchedAssessment.getReasons();
            String reasonsBullet = reasons.stream().map(r -> "- " + r).reduce((a, b) -> a + "\n" + b).orElse(isMm ? "- သက်တမ်းကုန်ဆုံးရက် နီးကပ်နေပါသည်" : "- Expiry date approaching");

            String recommendation = (isMm && matchedAssessment.getRecommendationMy() != null) ?
                    matchedAssessment.getRecommendationMy() : matchedAssessment.getRecommendation();

            String riskLevelDisplay = riskLevel;
            if (isMm) {
                if ("HIGH".equalsIgnoreCase(riskLevel)) riskLevelDisplay = "အန္တရာယ်မြင့်";
                else if ("MEDIUM".equalsIgnoreCase(riskLevel)) riskLevelDisplay = "အလယ်အလတ်အန္တရာယ်";
                else riskLevelDisplay = "အန္တရာယ်နည်း";
            }

            if (isMm) {
                return String.format(
                        "### 🍲 Food Item:\n%s\n\n" +
                        "**Status:**\n`%s` (သက်တမ်းကုန်ရန် %d ရက်ကျန်ရှိ)\n\n" +
                        "**Risk:**\n**%d%% (%s)**\n\n" +
                        "**Stock:**\n%.1f %s\n\n" +
                        "**Reason:**\n%s\n\n" +
                        "**Recommendation:**\n%s\n\n" +
                        "**Data Sources:**\n" +
                        "- ကုန်ပစ္စည်းစာရင်း\n" +
                        "- သက်တမ်းဆန်းစစ်ချက်\n" +
                        "- အလေအလွင့်အကဲဖြတ်ချက်",
                        foodName,
                        expiryStatus,
                        expiryDays,
                        Math.round(riskPct),
                        riskLevelDisplay,
                        stock, unit,
                        reasonsBullet,
                        recommendation
                );
            } else {
                return String.format(
                        "### 🍲 Food Item:\n%s\n\n" +
                        "**Status:**\n`%s` (%d day(s) remaining)\n\n" +
                        "**Risk:**\n**%d%% (%s)**\n\n" +
                        "**Stock:**\n%.1f %s\n\n" +
                        "**Reason:**\n%s\n\n" +
                        "**Recommendation:**\n%s\n\n" +
                        "**Data Sources:**\n" +
                        "- Inventory records\n" +
                        "- Expiry analysis\n" +
                        "- Waste evaluation",
                        foodName,
                        expiryStatus,
                        expiryDays,
                        Math.round(riskPct),
                        riskLevelDisplay,
                        stock, unit,
                        reasonsBullet,
                        recommendation
                );
            }
        }

        if (lowerQuery.contains("risk") || lowerQuery.contains("danger") || lowerQuery.contains("အန္တရာယ်") || lowerQuery.contains("စွန့်ပစ်")) {
            List<PrologAssessment> highRiskItems = items.stream().filter(i -> "HIGH".equalsIgnoreCase(i.getRiskLevel())).toList();
            if (!highRiskItems.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (isMm) {
                    sb.append(String.format("### ⚠️ အလေအလွင့် အန္တရာယ်မြင့် မီးဖိုချောင်သုံး ပစ္စည်းများ (%d မျိုး)\n\n", highRiskItems.size()));
                    sb.append("ချက်ချင်း အရေးယူဆောင်ရွက်ရန် လိုအပ်သော အန္တရာယ်မြင့် ပစ္စည်းများကို အောက်တွင် ဖော်ပြထားပါသည်:\n\n");
                    for (PrologAssessment a : highRiskItems) {
                        sb.append(String.format("- **%s**: အန္တရာယ် **%d%%** (လက်ကျန်: %.1f %s, သက်တမ်းကုန်ရန်: %d ရက်) → *%s*\n",
                                a.getFoodName(), Math.round(a.getRiskPercentage()), a.getStock(), a.getUnit(), a.getExpiryDays(),
                                a.getRecommendationMy() != null ? a.getRecommendationMy() : a.getRecommendation()));
                    }
                    sb.append("\n💡 *Recommendations စာမျက်နှာတွင် အဆိုပြုချက်များကို အတည်ပြုနိုင်ပါသည်။*");
                } else {
                    sb.append(String.format("### ⚠️ Priority High-Risk Kitchen Items (%d Items)\n\n", highRiskItems.size()));
                    sb.append("The following items require immediate operational action to prevent spoilage:\n\n");
                    for (PrologAssessment a : highRiskItems) {
                        sb.append(String.format("- **%s**: Risk **%d%%** (Stock: %.1f %s, Expiry: %d day(s)) → *%s*\n",
                                a.getFoodName(), Math.round(a.getRiskPercentage()), a.getStock(), a.getUnit(), a.getExpiryDays(), a.getRecommendation()));
                    }
                    sb.append("\n💡 *Navigate to the Recommendations tab to execute automated mitigation directives.*");
                }
                return sb.toString();
            }
        }

        if (lowerQuery.contains("expir") || lowerQuery.contains("shelf") || lowerQuery.contains("သက်တမ်း")) {
            List<FoodItem> expiredList = inventory.stream()
                    .filter(i -> "EXPIRED".equalsIgnoreCase(i.getExpiryStatus()) || "SAME_DAY_EXPIRY".equalsIgnoreCase(i.getExpiryStatus()) || "NEAR_EXPIRY".equalsIgnoreCase(i.getExpiryStatus()))
                    .toList();

            if (!expiredList.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (isMm) {
                    sb.append(String.format("### 📅 သက်တမ်းကုန်ဆုံးခြင်းနှင့် သက်တမ်းကုန်ခါနီး စာရင်း (%d မျိုး)\n\n", expiredList.size()));
                    for (FoodItem fi : expiredList) {
                        String statusStr = "EXPIRED".equalsIgnoreCase(fi.getExpiryStatus()) ? "❌ သက်တမ်းကုန်ပြီး" :
                                ("SAME_DAY_EXPIRY".equalsIgnoreCase(fi.getExpiryStatus()) ? "⚠️ ယနေ့သက်တမ်းကုန်" : "⚡ သက်တမ်းကုန်ရန်နီး");
                        sb.append(String.format("- **%s** (%s): လက်ကျန် %.1f %s (သက်တမ်းကုန်ရက်: %s)\n",
                                fi.getName(), statusStr, fi.getQuantity(), fi.getUnit(), fi.getExpiryDate()));
                    }
                    sb.append("\n💡 *သက်တမ်းကုန်ဆုံးသွားသော အစားအစာများကို ဧည့်သည်များထံ မကျွေးမွေးပါနှင့်။ စွန့်ပစ် သို့မဟုတ် မြေဆွေးပြုလုပ်ရန် မှတ်တမ်းတင်ပါ။*");
                } else {
                    sb.append(String.format("### 📅 Expiry & Shelf-Life Tracker (%d Items)\n\n", expiredList.size()));
                    for (FoodItem fi : expiredList) {
                        String statusStr = "EXPIRED".equalsIgnoreCase(fi.getExpiryStatus()) ? "❌ EXPIRED" :
                                ("SAME_DAY_EXPIRY".equalsIgnoreCase(fi.getExpiryStatus()) ? "⚠️ SAME DAY EXPIRY" : "⚡ NEAR EXPIRY");
                        sb.append(String.format("- **%s** (%s): Stock %.1f %s (Expiry Date: %s)\n",
                                fi.getName(), statusStr, fi.getQuantity(), fi.getUnit(), fi.getExpiryDate()));
                    }
                    sb.append("\n💡 *Do not serve expired items. Dispose safely and log under Waste Records.*");
                }
                return sb.toString();
            }
        }

        if (lowerQuery.contains("cook") || lowerQuery.contains("menu") || lowerQuery.contains("priorit") || lowerQuery.contains("ချက်") || lowerQuery.contains("သုံးစွဲ")) {
            List<PrologAssessment> priorityList = items.stream()
                    .filter(i -> "IMMEDIATE_USE".equalsIgnoreCase(i.getPriorityUsage()) || "HIGH_PRIORITY".equalsIgnoreCase(i.getPriorityUsage()))
                    .toList();

            if (!priorityList.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (isMm) {
                    sb.append("### 👨‍🍳 ယနေ့ မီးဖိုချောင် ဦးစားပေး ချက်ပြုတ်သုံးစွဲရမည့် အစားအစာများ\n\n");
                    sb.append("ဦးစားပေးသုံးစွဲသင့်သော ကုန်ကြမ်းများ:\n\n");
                    for (PrologAssessment a : priorityList) {
                        sb.append(String.format("- **%s** (လက်ကျန်: %.1f %s, သက်တမ်း: %d ရက်) → နေ့စဉ် အထူးဟင်းလျာတွင် ထည့်သွင်းချက်ပြုတ်ပါ\n",
                                a.getFoodName(), a.getStock(), a.getUnit(), a.getExpiryDays()));
                    }
                    sb.append("\n💡 *ဤပစ္စည်းများကို ယနေ့ မီနူးတွင် ဦးစားပေး သုံးစွဲခြင်းဖြင့် အလေအလွင့်ကို အထိရောက်ဆုံး ကာကွယ်နိုင်ပါသည်။*");
                } else {
                    sb.append("### 👨‍🍳 Chef's Priority Kitchen Usage Plan for Today\n\n");
                    sb.append("Based on priority usage rules, prioritize these ingredients for today's lunch/dinner service:\n\n");
                    for (PrologAssessment a : priorityList) {
                        sb.append(String.format("- **%s** (Stock: %.1f %s, Shelf-life: %d day(s)) → Feature in daily specials\n",
                                a.getFoodName(), a.getStock(), a.getUnit(), a.getExpiryDays()));
                    }
                    sb.append("\n💡 *Drawing down these near-expiry ingredients today prevents future financial spoilage.*");
                }
                return sb.toString();
            }
        }

        // 6. Redistribution / Donation Query
        if (lowerQuery.contains("donat") || lowerQuery.contains("redistribut") || lowerQuery.contains("charit") || lowerQuery.contains("ngo") || lowerQuery.contains("လှူဒါန်း") || lowerQuery.contains("ပရဟိတ")) {
            StringBuilder recipientBlock = new StringBuilder();
            if (recipients != null && !recipients.isEmpty()) {
                int idx = 1;
                for (RedistributionRecipient r : recipients) {
                    recipientBlock.append(String.format("%d. 🏢 **%s** (%s, %s: %s)\n",
                            idx, r.getName(), r.getOrganizationType(), isMm ? "ဖုန်း" : "Phone", r.getPhone()));
                    idx++;
                }
            }

            long eligibleCount = items.stream().filter(PrologAssessment::isRecommendRedistribution).count();

            if (isMm) {
                return "### 🤝 ပိုလျှံအစားအစာ ပြန်လည်လှူဒါန်းရေး အစီအစဉ်\n\n" +
                       String.format("လှူဒါန်းရန် သင့်တော်သော ပိုလျှံပစ္စည်း **%d မျိုး** ရှိပါသည်:\n\n", eligibleCount) +
                       "**မှတ်ပုံတင်ထားသော ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်းများ:**\n" +
                       recipientBlock.toString() + "\n" +
                       "💡 *Redistribution စာမျက်နှာသို့ သွားရောက်၍ ကယ်ဆယ်ရေး လှူဒါန်းမှု အချိန်ဇယားဆွဲနိုင်ပါသည်။*";
            } else {
                return "### 🤝 Surplus Food Redistribution & Charity Plan\n\n" +
                       String.format("Based on redistribution rules, **%d food item(s)** are eligible for surplus donation:\n\n", eligibleCount) +
                       "**Verified Charity Partners Available for Dispatch:**\n" +
                       recipientBlock.toString() + "\n" +
                       "💡 *Navigate to the Redistribution tab to schedule automated courier dispatches.*";
            }
        }

        // 7. General Kitchen Summary
        long highCount = items.stream().filter(i -> "HIGH".equalsIgnoreCase(i.getRiskLevel())).count();
        double totalSurplus = items.stream().mapToDouble(i -> Math.max(0, i.getStock() - i.getExpectedDemand())).sum();
        String partnerName = (recipients != null && !recipients.isEmpty()) ? recipients.get(0).getName() : (isMm ? "ပရဟိတ အဖွဲ့အစည်း" : "a verified food bank");

        if (isMm) {
            return String.format(
                    "### 🍃 FoodWaste AI နေ့စဉ် မီးဖိုချောင် အနှစ်ချုပ် အစီရင်ခံစာ\n\n" +
                    "မီးဖိုချောင်ရှိ ကုန်ပစ္စည်း %d မျိုးကို ဆန်းစစ်တွက်ချက်ထားပါသည်:\n\n" +
                    "**အဓိက တွေ့ရှိချက်များ:**\n" +
                    "- **အန္တရာယ်မြင့် ကုန်ပစ္စည်းများ:** %d မျိုး\n" +
                    "- **ခန့်မှန်း ပိုလျှံအလေအလွင့်:** %.1f liter/kg\n" +
                    "- **ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်း:** %s\n\n" +
                    "💡 *တိကျသော ကုန်ပစ္စည်းအမည် (ဥပမာ- Fresh Milk, Chicken) သို့မဟုတ် 'အန္တရာယ်ရှိသော ပစ္စည်းများ' ဟု မေးမြန်းနိုင်ပါသည်။*",
                    inventory.size(), highCount, totalSurplus, partnerName
            );
        } else {
            return String.format(
                    "### 🍃 FoodWaste AI Daily Intelligence Summary\n\n" +
                    "I have analyzed %d food items across your kitchen inventory:\n\n" +
                    "**Key Metrics:**\n" +
                    "- **High Waste Risk Items:** %d items\n" +
                    "- **Total Projected Surplus:** %.1f liter/kg\n" +
                    "- **Primary Charity Partner:** %s\n\n" +
                    "💡 *Try asking about a specific ingredient (e.g. 'Why is Fresh Milk risky?') or 'What should we cook today?'*",
                    inventory.size(), highCount, totalSurplus, partnerName
            );
        }
    }
}
