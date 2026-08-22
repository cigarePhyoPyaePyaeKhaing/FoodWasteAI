package com.foodwasteai.service;

import com.foodwasteai.config.AppConfig;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.RedistributionRecipient;
import com.foodwasteai.prolog.PrologAssessment;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
 * FoodWaste AI Assistant - Core Conversational & Reasoning Pipeline.
 *
 * Architecture:
 *   User Message
 *     ↓
 *   Language Detection
 *     ↓
 *   Fast Intent Classification (GREETING, CASUAL_CHAT, IDENTITY, CAPABILITIES)
 *     ↓
 *   Food Entity Extraction (Current Message Food Entity > Previous Context Food > Global Query)
 *     ↓
 *   Unknown Food Boundary Handling (if food not present in live DB inventory)
 *     ↓
 *   Live MySQL Inventory & Recipients Lookup
 *     ↓
 *   SWI-Prolog Expert Reasoning & ExpiryStatusResolver
 *     ↓
 *   Groq AI Natural Explanation (with High-Precision Rule-Grounded Fallback)
 *     ↓
 *   Standardized Response Model (type, answer, relatedFoodItems, smartDirectives, sources)
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

    /**
     * Standardized JSON Response Model for Frontend & API consumers
     */
    public static class ChatResponse implements Serializable {
        private static final long serialVersionUID = 1L;
        private String userQuery;
        private String answer;
        private String explanation; // Synchronized alias for answer
        private List<String> sources = new ArrayList<>();
        private List<Map<String, Object>> relatedFoodItems = new ArrayList<>();
        private Map<String, Object> riskInfo = new LinkedHashMap<>();
        private String sourceEngine;
        private Map<String, Object> prologSummary = new LinkedHashMap<>();
        private List<SmartAction> smartRecommendations = new ArrayList<>();
        private List<SmartAction> smartDirectives = new ArrayList<>(); // Alias for frontend
        private String responseType; // GREETING | CASUAL_CHAT | IDENTITY | CAPABILITIES | SPECIFIC_FOOD | HIGH_RISK_LIST | COOK_PRIORITY | REDISTRIBUTION | DAILY_SUMMARY | UNKNOWN_FOOD | OUT_OF_DOMAIN
        private String type; // Alias for responseType

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

        public Map<String, Object> getPrologSummary() { return prologSummary != null ? prologSummary : new LinkedHashMap<>(); }
        public void setPrologSummary(Map<String, Object> prologSummary) { this.prologSummary = prologSummary != null ? prologSummary : new LinkedHashMap<>(); }

        public List<SmartAction> getSmartRecommendations() { return smartRecommendations; }
        public void setSmartRecommendations(List<SmartAction> smartRecommendations) {
            this.smartRecommendations = smartRecommendations;
            this.smartDirectives = smartRecommendations;
        }
        public void addSmartAction(SmartAction action) {
            this.smartRecommendations.add(action);
            if (!this.smartDirectives.contains(action)) {
                this.smartDirectives.add(action);
            }
        }

        public List<SmartAction> getSmartDirectives() { return smartDirectives; }
        public void setSmartDirectives(List<SmartAction> smartDirectives) {
            this.smartDirectives = smartDirectives;
            this.smartRecommendations = smartDirectives;
        }

        public String getResponseType() { return responseType != null ? responseType : type; }
        public void setResponseType(String responseType) {
            this.responseType = responseType;
            this.type = responseType;
        }

        public String getType() { return type != null ? type : responseType; }
        public void setType(String type) {
            this.type = type;
            this.responseType = type;
        }
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

    public static class FoodExtractionResult implements Serializable {
        private static final long serialVersionUID = 1L;
        private final FoodItem matchedFoodItem;
        private final String candidateFoodName;
        private final boolean found;

        public FoodExtractionResult(FoodItem matchedFoodItem, String candidateFoodName, boolean found) {
            this.matchedFoodItem = matchedFoodItem;
            this.candidateFoodName = candidateFoodName;
            this.found = found;
        }

        public FoodItem getMatchedFoodItem() { return matchedFoodItem; }
        public String getCandidateFoodName() { return candidateFoodName; }
        public boolean isFound() { return found; }
    }

    // Common food lexicon to detect food queries even if not currently in this kitchen's DB
    private static final Set<String> KNOWN_FOOD_LEXICON = new HashSet<>(Arrays.asList(
            // Seafood
            "salmon", "tuna", "trout", "cod", "tilapia", "mackerel", "sardine", "fish", "seafood",
            "shrimp", "prawn", "crab", "lobster", "squid", "octopus", "clam", "mussel", "oyster", "unicornfish",
            // Meat & Poultry
            "chicken", "poultry", "breast", "drumstick", "wing", "beef", "steak", "veal", "pork",
            "bacon", "ham", "sausage", "lamb", "mutton", "duck", "turkey", "meat",
            // Dairy & Egg
            "milk", "cheese", "butter", "cream", "yogurt", "dairy", "egg", "eggs",
            // Grains & Bakery
            "rice", "bread", "noodle", "noodles", "pasta", "spaghetti", "flour", "toast", "bagel",
            "croissant", "sandwich", "burger", "pizza",
            // Produce & Veg & Fruit
            "potato", "potatoes", "tomato", "tomatoes", "onion", "onions", "garlic", "ginger",
            "carrot", "carrots", "cabbage", "lettuce", "spinach", "cucumber", "mushroom", "mushrooms",
            "broccoli", "cauliflower", "celery", "avocado", "bean", "beans", "pea", "peas", "corn",
            "tofu", "salad", "apple", "apples", "banana", "bananas", "orange", "oranges", "lemon",
            "lemons", "lime", "strawberry", "strawberries", "blueberry", "grape", "grapes", "mango",
            "mangoes", "pineapple", "watermelon", "fruit", "dragonfruit",
            // Myanmar Food Terms
            "ဆယ်လ်မွန်", "ဆော်လမွန်", "ငါး", "ငါးသေတ္တာ", "ပုစွန်", "ဂဏန်း", "ပြည်ကြီးငါး",
            "ကြက်သား", "ကြက်ရင်အုံ", "ကြက်တောင်ပံ", "ကြက်ပေါင်", "အမဲသား", "ဝက်သား", "ဆိတ်သား", "ဘဲသား",
            "နို့", "နို့စိမ်း", "ဒိန်ချဉ်", "ထောပတ်", "ဒိန်ခဲ", "ကြက်ဥ", "ဘဲဥ", "ဆန်", "ထမင်း",
            "ခေါက်ဆွဲ", "ကြာဇံ", "ပေါင်မုန့်", "ခရမ်းချဉ်သီး", "အာလူး", "ကြက်သွန်ဖြူ", "ကြက်သွန်နီ",
            "ဂေါ်ဖီ", "မုန်လာဥ", "မှို", "တို့ဟူး", "ပန်းသီး", "ငှက်ပျောသီး", "လိမ္မော်သီး", "သရက်သီး",
            "ဖရဲသီး", "သခွားသီး"
    ));

    private static final Set<String> CANDIDATE_STOPWORDS = new HashSet<>(Arrays.asList(
            "food", "foods", "food item", "food items", "item", "items", "inventory", "kitchen",
            "today", "waste", "status", "summary", "report", "action", "recommendation", "recommendations",
            "donation", "donations", "which", "what", "how", "who", "all", "any", "everything",
            "it", "this", "that", "them", "the item", "our", "your", "my", "me", "us", "we", "you",
            "high risk", "risk", "risky", "safe", "expired", "expiry", "demand", "stock", "shelf",
            "general", "our general", "overall", "total", "average", "current", "system", "daily",
            "general waste", "overall waste", "total waste", "waste risk",
            "food waste", "kitchen inventory", "kitchen items", "food products", "products", "product",
            // Myanmar stopwords
            "အစားအစာ", "ပစ္စည်း", "ကုန်ပစ္စည်း", "မီးဖိုချောင်", "ယနေ့", "အနှစ်ချုပ်", "အစီရင်ခံစာ",
            "အကြံပြုချက်", "လှူဒါန်းမှု", "ဘယ်ဟာ", "ဘာ", "ဒါ", "၎င်း", "အန္တရာယ်", "အန္တရာယ်ရှိ", "အန္တရာယ်မြင့်", "အထွေထွေ", "အားလုံး"
    ));

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
        FOOD_SYNONYMS.put("ဆယ်လ်မွန်", "salmon");
        FOOD_SYNONYMS.put("ဆော်လမွန်", "salmon");
        FOOD_SYNONYMS.put("ပုစွန်", "shrimp");
        FOOD_SYNONYMS.put("အသီးအရွက်", "vegetable");
        FOOD_SYNONYMS.put("ဟင်းသီးဟင်းရွက်", "vegetable");
        FOOD_SYNONYMS.put("သီးနှံ", "vegetable");
        FOOD_SYNONYMS.put("ပေါင်မုန့်", "bread");
        FOOD_SYNONYMS.put("ဒိန်ချဉ်", "yogurt");
        FOOD_SYNONYMS.put("ထောပတ်", "butter");
        FOOD_SYNONYMS.put("ဒိန်ခဲ", "cheese");

        // English synonyms
        FOOD_SYNONYMS.put("salmon", "salmon");
        FOOD_SYNONYMS.put("tuna", "tuna");
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

    public ChatResponse processUserQuery(String userQuery) {
        return processUserQuery(userQuery, "en", "default_session");
    }

    public ChatResponse processUserQuery(String userQuery, String language) {
        return processUserQuery(userQuery, language, "default_session");
    }

    /**
     * Executes the complete FoodWaste AI conversation pipeline according to specification.
     */
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
        response.setPrologSummary(new LinkedHashMap<>());

        String cleanQuery = userQuery.trim().toLowerCase();

        // =========================================================================
        // 1. FAST CASUAL / GREETING / IDENTITY ROUTING (Priority 1)
        // No MySQL, Prolog, Groq, cards, or smart directives needed for casual chat.
        // =========================================================================

        // A. Greetings (hi, hello, hey, မင်္ဂလာပါ)
        if (isGreeting(cleanQuery)) {
            ctx.setLastIntent("GREETING");
            String greeting = isMyanmar ?
                    "မင်္ဂလာပါ 👋 ကျွန်ုပ်က FoodWaste AI Assistant ပါ။ ဒီနေ့ ဘာကူညီပေးရမလဲ?" :
                    "Hello 👋 I'm FoodWaste AI Assistant. What would you like help with today?";
            response.setAnswer(greeting);
            response.setResponseType("GREETING");
            response.setSourceEngine(null);
            response.getSources().clear();
            response.getSmartRecommendations().clear();
            response.getRelatedFoodItems().clear();
            return response;
        }

        // B. Casual Chat (hehe, haha, bored, what are you doing?, how are you?)
        if (isCasualChat(cleanQuery)) {
            ctx.setLastIntent("CASUAL_CHAT");
            String casual = getCasualResponse(cleanQuery, isMyanmar);
            response.setAnswer(casual);
            response.setResponseType("CASUAL_CHAT");
            response.setSourceEngine(null);
            response.getSources().clear();
            response.getSmartRecommendations().clear();
            response.getRelatedFoodItems().clear();
            return response;
        }

        // C. Thanks
        if (isThanks(cleanQuery)) {
            ctx.setLastIntent("CASUAL_CHAT");
            String thanks = isMyanmar ?
                    "ရပါတယ်! အစားအစာ စီမံခန့်ခွဲမှု ဆန်းစစ်ရန် လိုအပ်ပါက မည်သည့်အချိန်မဆို မေးမြန်းနိုင်ပါတယ်။" :
                    "You're welcome! Let me know if you need help analyzing food waste.";
            response.setAnswer(thanks);
            response.setResponseType("CASUAL_CHAT");
            response.setSourceEngine(null);
            response.getSources().clear();
            response.getSmartRecommendations().clear();
            response.getRelatedFoodItems().clear();
            return response;
        }

        // D. Identity (who are you?)
        if (isIdentity(cleanQuery)) {
            ctx.setLastIntent("IDENTITY");
            String idText = isMyanmar ?
                    "ကျွန်ုပ်က FoodWaste AI Assistant ပါ။ စားသောက်ဆိုင်တွေမှာ အစားအစာ အလေအလွင့် လျှော့ချဖို့ အသိဉာဏ်သုံးပြီး ကူညီပေးပါတယ်။" :
                    "I am FoodWaste AI Assistant. I help restaurants minimize food waste and optimize kitchen operations.";
            response.setAnswer(idText);
            response.setResponseType("IDENTITY");
            response.setSourceEngine(null);
            response.getSources().clear();
            response.getSmartRecommendations().clear();
            response.getRelatedFoodItems().clear();
            return response;
        }

        // E. Capabilities (what can you do?)
        if (isCapabilities(cleanQuery)) {
            ctx.setLastIntent("CAPABILITIES");
            String capText = isMyanmar ?
                    "ကျွန်ုပ်က ကုန်ပစ္စည်းလက်ကျန် စစ်ဆေးခြင်း၊ သက်တမ်းကုန်ရက် စောင့်ကြည့်ခြင်း၊ အလေအလွင့် အန္တရာယ် တွက်ချက်ခြင်း၊ မီးဖိုချောင် ချက်ပြုတ်မှု ဦးစားပေး သတ်မှတ်ခြင်းနှင့် ပိုလျှံပစ္စည်းများ လှူဒါန်းခြင်းတို့ကို ကူညီပေးနိုင်ပါတယ်။" :
                    "I can help you track inventory levels, monitor food expiry dates, analyze waste risks, optimize prep batches, and schedule surplus food donations.";
            response.setAnswer(capText);
            response.setResponseType("CAPABILITIES");
            response.setSourceEngine(null);
            response.getSources().clear();
            response.getSmartRecommendations().clear();
            response.getRelatedFoodItems().clear();
            return response;
        }

        try {
            // Fetch live inventory and recipients
            List<FoodItem> inventory = foodItemService.getAllFoodItems();
            List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();

            // =========================================================================
            // 2. FAST OPERATIONAL INTENT ROUTING (Priority 2: Before Food Extraction)
            // =========================================================================
            boolean isDailySummaryIntent = isDailySummary(cleanQuery);
            boolean isHighRiskListIntent = !isDailySummaryIntent && isHighRiskListQuery(cleanQuery);
            boolean isCookPriorityIntent = !isDailySummaryIntent && !isHighRiskListIntent && isCookPriorityQuery(cleanQuery);
            boolean isRedistributionIntent = !isDailySummaryIntent && !isHighRiskListIntent && !isCookPriorityIntent && isRedistributionQuery(cleanQuery);

            FoodExtractionResult foodResult = null;
            FoodItem matchedFoodItem = null;
            PrologAssessment matchedAssessment = null;

            // Only perform food candidate extraction if query is NOT a general operational intent
            if (!isDailySummaryIntent && !isHighRiskListIntent && !isCookPriorityIntent && !isRedistributionIntent) {
                foodResult = extractFoodCandidate(userQuery, inventory);
                if (foodResult != null) {
                    if (foodResult.isFound()) {
                        // Item detected and found in live inventory
                        matchedFoodItem = foodResult.getMatchedFoodItem();
                        ctx.setLastFoodItemName(matchedFoodItem.getName());
                        ctx.setLastFoodItemId(matchedFoodItem.getId());
                        ctx.setLastIntent("SPECIFIC_FOOD");
                    } else {
                        // UNKNOWN FOOD: Food entity mentioned in user query but NOT in inventory
                        String unknownName = foodResult.getCandidateFoodName();
                        ctx.setLastFoodItemName(unknownName);
                        ctx.setLastFoodItemId(null);
                        ctx.setLastIntent("UNKNOWN_FOOD");

                        String capitalized = capitalize(unknownName);
                        String unknownAnswer = isMyanmar ?
                                capitalized + " ကို လက်ရှိ ကုန်ပစ္စည်းစာရင်းထဲမှာ မတွေ့ပါဘူး။ Stock၊ သက်တမ်းနဲ့ အလေအလွင့်အန္တရာယ်ကို ဆန်းစစ်လိုပါက Inventory ထဲကို အရင်ထည့်ပေးပါ။" :
                                "I can't find **" + capitalized + "** in the current inventory. Add it to Inventory first if you want me to analyze its stock, expiry, and waste risk.";

                        response.setAnswer(unknownAnswer);
                        response.setResponseType("UNKNOWN_FOOD");
                        response.setSourceEngine(null);
                        response.getSources().clear();
                        response.getSmartRecommendations().clear();
                        response.getRelatedFoodItems().clear();
                        return response;
                    }
                } else if (ctx.getLastFoodItemName() != null && isFollowUpQuery(cleanQuery)) {
                    // Multi-Turn Context Follow-Up: "it", "this", "what should I do with it"
                    final String lastFoodName = ctx.getLastFoodItemName();
                    matchedFoodItem = inventory.stream()
                            .filter(fi -> fi.getName() != null && fi.getName().equalsIgnoreCase(lastFoodName))
                            .findFirst().orElse(null);
                    if (matchedFoodItem != null) {
                        ctx.setLastIntent("SPECIFIC_FOOD");
                    } else {
                        // Previous context item was unknown / not in inventory
                        String capitalized = capitalize(lastFoodName);
                        String unknownAnswer = isMyanmar ?
                                capitalized + " ကို လက်ရှိ ကုန်ပစ္စည်းစာရင်းထဲမှာ မတွေ့ပါဘူး။ Stock၊ သက်တမ်းနဲ့ အလေအလွင့်အန္တရာယ်ကို ဆန်းစစ်လိုပါက Inventory ထဲကို အရင်ထည့်ပေးပါ။" :
                                "I can't find **" + capitalized + "** in the current inventory. Add it to Inventory first if you want me to analyze its stock, expiry, and waste risk.";
                        response.setAnswer(unknownAnswer);
                        response.setResponseType("UNKNOWN_FOOD");
                        response.setSourceEngine(null);
                        response.getSources().clear();
                        response.getSmartRecommendations().clear();
                        response.getRelatedFoodItems().clear();
                        return response;
                    }
                }
            }

            // =========================================================================
            // 3. OUT-OF-DOMAIN CHECK
            // =========================================================================
            if (matchedFoodItem == null && isUnknownTopic(cleanQuery, inventory)) {
                ctx.setLastIntent("OUT_OF_DOMAIN");
                String unknownText = isMyanmar ?
                        "ကျွန်ုပ်က အစားအစာ အလေအလွင့်နဲ့ မီးဖိုချောင်စီမံခန့်ခွဲမှုကို အဓိကကူညီပေးတာပါ။ အခြားအကြောင်းအရာတွေကိုလည်း စကားပြောနိုင်ပေမယ့် live data မရှိရင် အတည်ပြုထားတဲ့အချက်အလက် မဖြစ်နိုင်ပါဘူး။" :
                        "I mainly specialize in food waste and kitchen operations, but I can still chat with you. For live information outside this system, I may not have verified data.";
                response.setAnswer(unknownText);
                response.setResponseType("OUT_OF_DOMAIN");
                response.setSourceEngine(null);
                response.getSources().clear();
                response.getSmartRecommendations().clear();
                response.getRelatedFoodItems().clear();
                return response;
            }

            // =========================================================================
            // 4. SWI-PROLOG EXPERT REASONING & DATA GROUNDING
            // =========================================================================
            Map<String, Object> prologReport = predictionService.assessInventory(inventory);
            response.setPrologSummary(prologReport);

            @SuppressWarnings("unchecked")
            List<PrologAssessment> items = (List<PrologAssessment>) prologReport.get("items");
            if (items == null) items = Collections.emptyList();

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

                if (matchedAssessment == null) {
                    int days = matchedFoodItem.getExpiryDaysRemaining() != null ?
                            matchedFoodItem.getExpiryDaysRemaining() :
                            (matchedFoodItem.getExpiryDate() != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), matchedFoodItem.getExpiryDate()) : 5);
                    String status = matchedFoodItem.getExpiryStatus() != null ? matchedFoodItem.getExpiryStatus() : "SAFE";
                    String risk = "LOW";
                    double riskPct = 10.0;
                    if ("EXPIRED".equalsIgnoreCase(status) || days < 0) {
                        risk = "HIGH";
                        riskPct = 95.0;
                    } else if ("SAME_DAY_EXPIRY".equalsIgnoreCase(status) || days == 0) {
                        risk = "HIGH";
                        riskPct = 85.0;
                    } else if ("NEAR_EXPIRY".equalsIgnoreCase(status) || days <= 2) {
                        risk = "MEDIUM";
                        riskPct = 50.0;
                    }
                    List<String> reasons = new ArrayList<>();
                    if (days < 0) reasons.add("Food item has expired (" + Math.abs(days) + " days ago)");
                    else if (days == 0) reasons.add("Food item expires today");
                    else reasons.add("Shelf life remaining: " + days + " day(s)");

                    matchedAssessment = new PrologAssessment();
                    matchedAssessment.setFoodItemId(matchedFoodItem.getId());
                    matchedAssessment.setFoodName(matchedFoodItem.getName());
                    matchedAssessment.setRiskLevel(risk);
                    matchedAssessment.setRiskPercentage(riskPct);
                    matchedAssessment.setRiskScore(riskPct);
                    matchedAssessment.setExpiryDays(days);
                    matchedAssessment.setStock(matchedFoodItem.getQuantity() != null ? matchedFoodItem.getQuantity().doubleValue() : 0.0);
                    matchedAssessment.setUnit(matchedFoodItem.getUnit() != null ? matchedFoodItem.getUnit() : "kg");
                    matchedAssessment.setExpectedDemand(matchedFoodItem.getQuantity() != null ? matchedFoodItem.getQuantity().doubleValue() * 0.8 : 0.0);
                    matchedAssessment.setPriorityUsage(days <= 0 ? "EXPIRED_ACTION" : "NORMAL_USE");
                    matchedAssessment.setRecommendRedistribution(false);
                    matchedAssessment.setRecommendation(days <= 0 ? "Do not serve. Stop production and dispose or compost safely." : "Monitor stock levels and usage.");
                    matchedAssessment.setReasons(reasons);
                    matchedAssessment.setReasonsMy(reasons);
                    matchedAssessment.setRecommendationMy(days <= 0 ? "ဧည့်သည်များကို မကျွေးမွေးပါနှင့်။ ထုတ်လုပ်မှုကို ရပ်ဆိုင်း၍ စွန့်ပစ် သို့မဟုတ် မြေဆွေးပြုလုပ်ပါ" : "ပုံမှန် သတ်မှတ်ထားသော ထုတ်လုပ်မှုအတိုင်း ဆက်လက်ဆောင်ရွက်ပါ");
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

            // =========================================================================
            // 5. SYNTHESIZE EXPLANATION (Groq Cloud API or Rule-Grounded Explanation)
            // =========================================================================
            String apiKey = AppConfig.getGroqApiKey();
            String groqExplanation = null;

            if (apiKey != null && !apiKey.trim().isEmpty() && !"demo-key-placeholder".equalsIgnoreCase(apiKey)) {
                groqExplanation = callGroqApi(userQuery, inventory, items, recipients, matchedFoodItem, apiKey, activeLang);
            }

            // Strict Grounding & Anti-Hallucination Validation
            if (matchedFoodItem != null) {
                String matchedName = matchedFoodItem.getName();
                if (groqExplanation != null) {
                    String groqLower = groqExplanation.toLowerCase();
                    boolean containsMatched = groqLower.contains(matchedName.toLowerCase());

                    boolean hasUnwantedFood = false;
                    for (FoodItem otherItem : inventory) {
                        if (otherItem.getName() != null && !otherItem.getName().equalsIgnoreCase(matchedName)) {
                            String otherNameLower = otherItem.getName().toLowerCase();
                            if (groqLower.contains(otherNameLower) && !userQuery.toLowerCase().contains(otherNameLower)) {
                                hasUnwantedFood = true;
                                break;
                            }
                        }
                    }

                    if (!containsMatched || hasUnwantedFood) {
                        logger.warn("Response validation failed for food query on {}. Falling back to rule-grounded explanation.", matchedName);
                        groqExplanation = null;
                    }
                }
            }

            if (groqExplanation == null || groqExplanation.trim().isEmpty()) {
                groqExplanation = generateRuleGroundedExplanation(userQuery, inventory, items, recipients, matchedFoodItem, matchedAssessment, activeLang);
            }

            response.setAnswer(groqExplanation);
            response.setSourceEngine(isMyanmar ? "FoodWaste AI Assistant\nသင့်အစားအစာ စီမံခန့်ခွဲမှု အကူ" : "FoodWaste AI Assistant\nYour food waste helper");

            // Set authoritative response type
            if (matchedFoodItem != null) {
                response.setResponseType("SPECIFIC_FOOD");
            } else if (isActionRequiredQuery(cleanQuery)) {
                response.setResponseType("ACTION_REQUIRED");
            } else if (isDailySummaryIntent) {
                response.setResponseType("DAILY_SUMMARY");
            } else if (isHighRiskListIntent) {
                response.setResponseType("HIGH_RISK_LIST");
            } else if (isCookPriorityIntent) {
                response.setResponseType("COOK_PRIORITY");
            } else if (isRedistributionIntent) {
                response.setResponseType("REDISTRIBUTION");
            } else if (cleanQuery.contains("cook") || cleanQuery.contains("menu") || cleanQuery.contains("priorit") || cleanQuery.contains("chef")
                    || cleanQuery.contains("ချက်") || cleanQuery.contains("မီနူး") || cleanQuery.contains("ဦးစားပေး")) {
                response.setResponseType("COOK_PRIORITY");
            } else if (cleanQuery.contains("donat") || cleanQuery.contains("redistribut") || cleanQuery.contains("charit") || cleanQuery.contains("ngo")
                    || cleanQuery.contains("လှူဒါန်း") || cleanQuery.contains("ပရဟိတ")) {
                response.setResponseType("REDISTRIBUTION");
            } else if (cleanQuery.contains("risk") || cleanQuery.contains("high risk") || cleanQuery.contains("danger") || cleanQuery.contains("action")
                    || cleanQuery.contains("အန္တရာယ်") || cleanQuery.contains("အန္တရာယ်မြင့်") || cleanQuery.contains("စွန့်ပစ်")) {
                response.setResponseType("HIGH_RISK_LIST");
            } else {
                response.setResponseType("DAILY_SUMMARY");
            }

            // =========================================================================
            // 6. GENERATE ACTIONABLE SMART DIRECTIVES (Only for Allowed Actionable Types)
            // Rules: Show Smart Directives ONLY for: SPECIFIC_FOOD, COOK_PRIORITY, ACTION_REQUIRED
            // Never show for: GREETING, CASUAL_CHAT, IDENTITY, DAILY_SUMMARY, REDISTRIBUTION_EMPTY, HIGH_RISK_LIST
            // =========================================================================
            String finalType = response.getResponseType();
            if ("SPECIFIC_FOOD".equalsIgnoreCase(finalType) && matchedFoodItem != null && matchedAssessment != null) {
                boolean isExpired = "EXPIRED".equalsIgnoreCase(matchedFoodItem.getExpiryStatus()) || matchedAssessment.getExpiryDays() < 0;
                if (isExpired) {
                    String title = isMyanmar ?
                            "🛑 " + matchedFoodItem.getName() + " ထုတ်လုပ်မှု ချက်ချင်းရပ်ဆိုင်းမည်" :
                            "🛑 Stop Production & Dispose " + matchedFoodItem.getName();
                    response.addSmartAction(new SmartAction(title, "REDUCE_PRODUCTION", isMyanmar ? "အရေးပေါ်" : "URGENT", "foodItemId=" + matchedFoodItem.getId()));
                } else {
                    if ("HIGH".equalsIgnoreCase(matchedAssessment.getRiskLevel())) {
                        String title = isMyanmar ?
                                "⚡ " + matchedFoodItem.getName() + " ထုတ်လုပ်မှုပမာဏ လျှော့ချမည်" :
                                "⚡ Reduce Next Prep Batch for " + matchedFoodItem.getName();
                        response.addSmartAction(new SmartAction(title, "REDUCE_PRODUCTION", isMyanmar ? "အရေးပေါ်" : "URGENT", "foodItemId=" + matchedFoodItem.getId()));
                    }
                    if (matchedAssessment.isRecommendRedistribution() && !isExpired && matchedAssessment.getExpiryDays() > 0) {
                        String title = isMyanmar ?
                                "🤝 " + matchedFoodItem.getName() + " ပိုလျှံမှု ပရဟိတသို့ လှူဒါန်းမည်" :
                                "🤝 Dispatch Surplus " + matchedFoodItem.getName() + " to Charity";
                        response.addSmartAction(new SmartAction(title, "SCHEDULE_DONATION", isMyanmar ? "ပြန်လည်လှူဒါန်းမှု" : "REDISTRIBUTION", "foodItemId=" + matchedFoodItem.getId() + "&foodName=" + matchedFoodItem.getName()));
                    }
                }
            } else if ("COOK_PRIORITY".equalsIgnoreCase(finalType)) {
                for (PrologAssessment a : items) {
                    if (a.getExpiryDays() >= 0 && ("IMMEDIATE_USE".equalsIgnoreCase(a.getPriorityUsage()) || "HIGH_PRIORITY".equalsIgnoreCase(a.getPriorityUsage())) && response.getSmartRecommendations().size() < 2) {
                        String title = isMyanmar ?
                                "👨‍🍳 " + a.getFoodName() + " ယနေ့ ဦးစားပေး ချက်ပြုတ်မည်" :
                                "👨‍🍳 Prioritize " + a.getFoodName() + " in Today's Prep";
                        response.addSmartAction(new SmartAction(title, "COOK_PRIORITY", isMyanmar ? "ဦးစားပေး" : "HIGH", "foodItemId=" + a.getFoodItemId()));
                    }
                }
            } else if ("ACTION_REQUIRED".equalsIgnoreCase(finalType)) {
                for (PrologAssessment a : items) {
                    if ("HIGH".equalsIgnoreCase(a.getRiskLevel()) && response.getSmartRecommendations().size() < 2) {
                        String title = isMyanmar ?
                                "⚡ " + a.getFoodName() + " ထုတ်လုပ်မှုပမာဏ လျှော့ချမည်" :
                                "⚡ " + a.getRecommendation();
                        response.addSmartAction(new SmartAction(title, "REDUCE_PRODUCTION", isMyanmar ? "အရေးပေါ်" : "URGENT", "foodItemId=" + a.getFoodItemId()));
                    }
                    if (a.isRecommendRedistribution() && a.getExpiryDays() > 0 && response.getSmartRecommendations().size() < 3) {
                        String title = isMyanmar ?
                                "🤝 " + a.getFoodName() + " ပိုလျှံမှု ပရဟိတသို့ လှူဒါန်းမည်" :
                                "🤝 Dispatch Surplus " + a.getFoodName() + " to Charity";
                        response.addSmartAction(new SmartAction(title, "SCHEDULE_DONATION", isMyanmar ? "ပြန်လည်လှူဒါန်းမှု" : "REDISTRIBUTION", "foodItemId=" + a.getFoodItemId()));
                    }
                }
                if (response.getSmartRecommendations().isEmpty()) {
                    String title = isMyanmar ? "📦 ကုန်ပစ္စည်းလက်ကျန် ကြည့်ရှုမည်" : "📦 View Kitchen Inventory";
                    response.addSmartAction(new SmartAction(title, "VIEW_INVENTORY", "INFO", "/inventory.html"));
                }
            }

            // Strictly clear directives and related food items for all non-allowed types
            if (!"SPECIFIC_FOOD".equalsIgnoreCase(finalType) && !"COOK_PRIORITY".equalsIgnoreCase(finalType) && !"ACTION_REQUIRED".equalsIgnoreCase(finalType)) {
                response.getSmartDirectives().clear();
                response.getSmartRecommendations().clear();
            }

            if (!"SPECIFIC_FOOD".equalsIgnoreCase(finalType)) {
                response.getRelatedFoodItems().clear();
            }

        } catch (Exception e) {
            logger.error("Error in GroqAIService processing query: {}", e.getMessage(), e);
            if (response.getSourceEngine() == null) {
                response.setSourceEngine(isMyanmar ? "FoodWaste AI Assistant\nသင့်အစားအစာ စီမံခန့်ခွဲမှု အကူ" : "FoodWaste AI Assistant\nYour food waste helper");
            }
            if (response.getSmartRecommendations().isEmpty()) {
                response.addSmartAction(new SmartAction(isMyanmar ? "📦 ကုန်ပစ္စည်းလက်ကျန် ကြည့်ရှုမည်" : "📦 View Kitchen Inventory", "VIEW_INVENTORY", "INFO", "/inventory.html"));
            }
            if (isMyanmar) {
                response.setAnswer("အခုချိန်မှာ AI အဖြေပြည့်စုံစွာ မထုတ်နိုင်သေးပေမယ့် လက်ရှိ အစားအစာအချက်အလက်တွေကို အခြေခံပြီး ကူညီပေးနိုင်ပါတယ်။");
                response.setResponseType("DAILY_SUMMARY");
            } else {
                response.setAnswer("I'm having trouble generating a full response right now, but I can still help with your current food data.");
                response.setResponseType("DAILY_SUMMARY");
            }
        }

        return response;
    }

    private String getCasualResponse(String cleanQuery, boolean isMyanmar) {
        String clean = cleanQuery.replaceAll("[^a-zA-Z0-9\u1000-\u109F\\s]", "").toLowerCase().trim();

        // 1. Laughter / Humor (hehe, haha)
        if (clean.matches(".*(hehe|haha|lol|lmao|rofl|funny|joke|ဟီးဟီး|ဟားဟား).*")) {
            return isMyanmar ?
                    "ဟားဟား 😄 ဒီနေ့ ဘာများ စစ်ဆေးချင်ပါသလဲ?" :
                    "Haha 😄 What would you like to check today?";
        }

        // 2. Boredom
        if (clean.matches(".*(boring|bored|borin|ပျင်း).*")) {
            return isMyanmar ?
                    "😄 ခဏတာ စကားပြောပေးနိုင်ပါတယ်။ လိုအပ်ရင် ဒီနေ့ အစားအစာ အလေအလွင့် အခြေအနေကိုလည်း စစ်ဆေးနိုင်ပါတယ်။" :
                    "😄 I can keep you company for a moment. If you want, we can also check today's food waste situation.";
        }

        // 3. "what are you doing" / "what you doing"
        if (clean.matches(".*(what.*you.*doing|what.*are.*you.*doing|what.*r.*u.*doing|whatcha.*doing|what.*doing|ဘာလုပ်နေလဲ|ဘာတွေလုပ်နေလဲ).*")) {
            return isMyanmar ?
                    "မီးဖိုချောင် ကုန်ပစ္စည်းစာရင်းကို စောင့်ကြည့်ပြီး အလေအလွင့် လျှော့ချရေးကို ကူညီပေးနေပါတယ်။ ဘာကူညီပေးရမလဲ?" :
                    "I am monitoring kitchen inventory and helping minimize food waste. How can I help you today?";
        }

        // 4. Affirmations (ok, cool, sure, alright, great, nice)
        if (clean.matches("^(ok|okay|sure|alright|cool|great|awesome|nice|good|fine|perfect|ဟုတ်|ဟုတ်ကဲ့|ကောင်းပါပြီ|အိုကေ)$")) {
            return isMyanmar ?
                    "ကောင်းပါပြီ! အစားအစာ အန္တရာယ် သို့မဟုတ် ကုန်ပစ္စည်းလက်ကျန် စစ်ဆေးလိုပါက အချိန်မရွေး မေးမြန်းနိုင်ပါတယ်။" :
                    "Sounds good! Let me know whenever you'd like to check food risk or inventory status.";
        }

        // 5. Goodbye (bye, see you, goodnight)
        if (clean.matches(".*(bye|goodbye|see you|cya|take care|good night|တာ့တာ).*")) {
            return isMyanmar ?
                    "တာ့တာ! သာယာသောနေ့လေး ဖြစ်ပါစေ။" :
                    "Goodbye! Have a great day and happy waste-free cooking!";
        }

        // Default Casual response (how are you)
        return isMyanmar ?
                "ကျွန်ုပ် နေကောင်းပါတယ်၊ မေးမြန်းပေးလို့ ကျေးဇူးတင်ပါတယ်။ အစားအစာ အလေအလွင့် စီမံခန့်ခွဲမှုနဲ့ ပတ်သက်ပြီး ဘာကူညီပေးရမလဲ?" :
                "I am doing well, thank you for asking! How can I help you manage food inventory and minimize waste today?";
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

        if (clean.matches("^(hehe|hehehe|haha|hahaha|hekoo|heyy|lol|lmao|rofl|ဟီးဟီး|ဟားဟား)$") ||
            clean.startsWith("hehe") || clean.startsWith("haha") || clean.equals("hekoo")) {
            return true;
        }

        if (clean.contains("bored") || clean.contains("boring") || clean.contains("ပျင်း")) {
            return true;
        }

        if (clean.matches(".*(what.*you.*doing|what.*are.*you.*doing|what.*r.*u.*doing|whatcha.*doing|what.*doing|ဘာလုပ်နေလဲ|ဘာတွေလုပ်နေလဲ).*")) {
            return true;
        }

        if (clean.equals("how do you do") || clean.equals("how are you") || clean.equals("how are u") ||
            clean.equals("how are you doing") || clean.equals("how is it going") || clean.equals("hows it going") ||
            clean.equals("whats up") || clean.equals("what is up") || clean.equals("sup") ||
            clean.equals("how are things") || clean.equals("nice to meet you") || clean.equals("good to see you") ||
            clean.equals("how is your day") || clean.equals("hows your day") || clean.equals("are you there") ||
            clean.equals("ok") || clean.equals("okay") || clean.equals("sure") || clean.equals("alright") ||
            clean.equals("cool") || clean.equals("fine") || clean.equals("great") || clean.equals("awesome") ||
            clean.equals("nice") || clean.equals("good") || clean.equals("perfect") ||
            clean.equals("bye") || clean.equals("goodbye") || clean.equals("see you") || clean.equals("cya") ||
            clean.equals("take care") || clean.equals("good night") || clean.equals("yes") || clean.equals("no") ||
            clean.equals("yeah") || clean.equals("yep") || clean.equals("nope")) {
            return true;
        }

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
            clean.equals("hekoo") || clean.equals("heyy") || clean.equals("hallo") ||
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

    private boolean isActionRequiredQuery(String q) {
        if (q == null) return false;
        String clean = q.toLowerCase().trim();
        return clean.contains("what action") || clean.contains("what actions") ||
               clean.contains("actions should we take") || clean.contains("action should we take") ||
               clean.contains("action required") || clean.contains("what should we do") ||
               clean.contains("how to prevent waste") || clean.contains("general waste risk") ||
               clean.contains("ဘာလုပ်ဆောင်ရမလဲ") || clean.contains("ဆောင်ရွက်ရန်");
    }

    private boolean isDailySummary(String q) {
        if (q == null) return false;
        String clean = q.toLowerCase().trim();
        if (isActionRequiredQuery(clean)) return false;

        // English keywords
        if (clean.equals("today") || clean.equals("daily") || clean.equals("summary") || clean.equals("report") ||
            clean.contains("daily summary") || clean.contains("daily report") || clean.contains("today summary") ||
            clean.contains("waste report") || clean.contains("today's food waste summary") ||
            clean.contains("give me today's food waste summary") || clean.contains("give me today's summary") ||
            clean.contains("give me today summary") || clean.contains("today's food waste") ||
            clean.contains("today waste summary") || clean.contains("summary of today") || clean.contains("today overview")) {
            return true;
        }
        // Myanmar keywords: ယနေ့, ဒီနေ့, အနှစ်ချုပ်, အခြေအနေ, အစီရင်ခံစာ, အလေအလွင့်, အစားအသောက် အလေအလွင့်
        if (clean.equals("ယနေ့") || clean.equals("ဒီနေ့") || clean.equals("အနှစ်ချုပ်") ||
            clean.contains("ယနေ့ အစားအသောက် အလေအလွင့်") || clean.contains("အစားအသောက် အလေအလွင့် အခြေအနေ") ||
            clean.contains("အလေအလွင့် အခြေအနေ") || clean.contains("ယနေ့ အခြေအနေ") || clean.contains("ဒီနေ့ အခြေအနေ") ||
            clean.contains("မီးဖိုချောင် အနှစ်ချုပ်") || clean.contains("အနှစ်ချုပ် ရှင်းပြပါ") ||
            clean.contains("အခြေအနေကို ရှင်းပြပါ")) {
            return true;
        }
        return false;
    }

    private boolean isHighRiskListQuery(String q) {
        if (q == null) return false;
        String clean = q.toLowerCase().trim();
        if (clean.contains("which food items are high risk") || clean.contains("which food are high risk") ||
            clean.contains("which items are high risk") || clean.contains("which food is high risk") ||
            clean.contains("show high risk") || clean.contains("high risk items") || clean.contains("high risk food") ||
            clean.contains("high risk foods") || clean.contains("list high risk") || clean.contains("high risk list") ||
            clean.contains("အန္တရာယ်မြင့် ပစ္စည်း") || clean.contains("အန္တရာယ်မြင့် အစားအသောက်") ||
            clean.contains("ဘယ်အစားအစာတွေက အန္တရာယ်မြင့်") || clean.contains("အန္တရာယ်အမြင့်ဆုံး") ||
            clean.contains("အန္တရာယ်အရှိဆုံး")) {
            return true;
        }
        return false;
    }

    private boolean isCookPriorityQuery(String q) {
        if (q == null) return false;
        String clean = q.toLowerCase().trim();
        if (clean.contains("what should i cook") || clean.contains("what should our chef cook") ||
            clean.contains("cook or prioritize today") || clean.contains("what to cook today") ||
            clean.contains("what should we cook") || clean.contains("cook priority") || clean.contains("prep priority") ||
            clean.contains("ဘာအရင်ချက်ရမလဲ") || clean.contains("ဦးစားပေး ချက်ပြုတ်") || clean.contains("ယနေ့ ဘယ်ကုန်ကြမ်းတွေကို ဦးစားပေး")) {
            return true;
        }
        return false;
    }

    private boolean isRedistributionQuery(String q) {
        if (q == null) return false;
        String clean = q.toLowerCase().trim();
        if (clean.contains("which surplus items should be redistributed") || clean.contains("what can i donate") ||
            clean.contains("what should we donate") || clean.contains("surplus donation") ||
            clean.contains("donation list") || clean.contains("redistribution list") ||
            clean.contains("ဘာလှူလို့ရမလဲ") || clean.contains("ပြန်လည်လှူဒါန်းသင့်") || clean.contains("လှူဒါန်းရန် ပိုလျှံ")) {
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
                        if (tok.length() >= 3 && !CANDIDATE_STOPWORDS.contains(tok.toLowerCase()) && !tok.equalsIgnoreCase("fresh") &&
                                !tok.equalsIgnoreCase("organic") && !tok.equalsIgnoreCase("test") && q.contains(tok.toLowerCase())) {
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

    private FoodExtractionResult extractFoodCandidate(String query, List<FoodItem> inventory) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }

        String lowerQuery = query.toLowerCase().trim();

        // 1. Match with live inventory items first (exact name or token match)
        FoodItem matched = extractFoodEntity(query, inventory);
        if (matched != null) {
            return new FoodExtractionResult(matched, matched.getName(), true);
        }

        // 2. Check known food lexicon terms (items not present in this kitchen's DB)
        for (String lexiconTerm : KNOWN_FOOD_LEXICON) {
            boolean termPresent = false;
            if (containsMyanmarScript(lexiconTerm)) {
                termPresent = lowerQuery.contains(lexiconTerm);
            } else {
                termPresent = lowerQuery.matches(".*\\b" + java.util.regex.Pattern.quote(lexiconTerm) + "\\b.*");
            }

            if (termPresent) {
                return new FoodExtractionResult(null, lexiconTerm, false);
            }
        }

        // 3. Structural regex pattern matching to extract candidate food names
        String[] patterns = {
                "(?:what is the (?:waste )?risk (?:for|of)|risk (?:for|of)|why is|how risky is|is|tell me about|what about|how about|check)\\s+(?:the\\s+)?([a-zA-Z0-9\u1000-\u109F\\s]+?)(?:\\s+risky|\\s+safe|\\s+expired|\\s+status|\\s+good|\\s+bad|\\s+risk|\\?|$|\\.)",
                "([a-zA-Z0-9\u1000-\u109F\\s]+?)\\s+(?:waste risk|risk status|expiry date|shelf life)",
                "([a-zA-Z0-9\u1000-\u109F\\s]+?)(?:\\s+အန္တရာယ်ရှိလား|\\s+အကြောင်း|\\s+ဘာဖြစ်|\\s+အန္တရာယ်|\\s+အလေအလွင့်|\\s+သက်တမ်း|\\s+ရော|\\s+ရော\\?)"
        };

        for (String pat : patterns) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pat, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(lowerQuery);
            if (m.find()) {
                String candidate = m.group(1).trim();
                candidate = candidate.replaceAll("^(the|our|my|a|an|all|any)\\s+", "").replaceAll("\\s+(the|our|my|a|an|all|any)$", "").trim();
                boolean isStopWord = CANDIDATE_STOPWORDS.contains(candidate) ||
                        candidate.contains("food") || candidate.contains("waste") || candidate.contains("item") ||
                        candidate.contains("status") || candidate.contains("inventory") || candidate.contains("kitchen") ||
                        candidate.contains("summary") || candidate.contains("today") || candidate.contains("action") ||
                        candidate.contains("recommend") || candidate.contains("report") || candidate.contains("chef") ||
                        candidate.contains("menu") || candidate.contains("donat") || candidate.contains("charit") ||
                        candidate.contains("general") || candidate.contains("overall") || candidate.contains("total") ||
                        candidate.contains("အစားအစာ") || candidate.contains("အနှစ်ချုပ်") || candidate.contains("မီးဖိုချောင်");
                if (candidate.length() >= 2 && !isStopWord) {
                    return new FoodExtractionResult(null, candidate, false);
                }
            }
        }

        return null;
    }

    private String capitalize(String str) {
        if (str == null || str.trim().isEmpty()) return "";
        String[] words = str.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase()).append(" ");
            }
        }
        return sb.toString().trim();
    }

    private FoodItem extractFoodEntity(String query, List<FoodItem> inventory) {
        if (query == null || query.trim().isEmpty() || inventory == null || inventory.isEmpty()) {
            return null;
        }

        String lowerQuery = query.toLowerCase().trim();
        FoodItem bestMatch = null;
        int bestScore = 0;

        for (FoodItem fi : inventory) {
            if (fi.getName() == null || fi.getName().trim().isEmpty()) continue;
            String fiNameLower = fi.getName().toLowerCase().trim();
            String fiCatLower = fi.getCategory() != null ? fi.getCategory().toLowerCase().trim() : "";
            int score = 0;

            // 1. Exact phrase match
            if (lowerQuery.contains(fiNameLower)) {
                score = 300 + fiNameLower.length();
            } else if (fiNameLower.contains(lowerQuery) && lowerQuery.length() >= 3) {
                score = 250 + lowerQuery.length();
            } else {
                // 2. Multilingual & Synonym match
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
                        if (fiNameLower.contains(synVal) || fiNameLower.contains(synKey)) {
                            int synScore = 200 + synVal.length();
                            if (synScore > score) {
                                score = synScore;
                            }
                        }
                    }
                }

                // 3. Significant word token match
                String[] tokens = fiNameLower.split("\\s+");
                for (String token : tokens) {
                    String t = token.replaceAll("[^a-zA-Z0-9\u1000-\u109F]", "").toLowerCase();
                    if (t.length() >= 3 && !t.matches(".*\\d+.*") && !CANDIDATE_STOPWORDS.contains(t) && !t.equals("fresh") && !t.equals("organic") &&
                            !t.equals("item") && !t.equals("cooked") && !t.equals("food") && !t.equals("test") && !t.equals("waste")) {
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
                            }
                        }
                    }
                }
            }

            if (score > bestScore || (score == bestScore && fi.getId() != null && bestMatch != null && bestMatch.getId() != null && fi.getId() > bestMatch.getId())) {
                bestScore = score;
                bestMatch = fi;
            }
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
                               List<RedistributionRecipient> recipients, FoodItem matchedFoodItem, String apiKey, String lang) {
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

            StringBuilder systemPrompt = new StringBuilder();
            systemPrompt.append("You are FoodWaste AI Assistant.\n\n");
            systemPrompt.append("Act like a professional, friendly food waste consultant.\n\n");
            systemPrompt.append("Answer naturally and directly.\n\n");
            systemPrompt.append("For FoodWaste questions, only use the verified facts provided in the context.\n\n");
            systemPrompt.append("Never invent:\n");
            systemPrompt.append("- food items\n");
            systemPrompt.append("- stock quantities\n");
            systemPrompt.append("- prices\n");
            systemPrompt.append("- expiry dates\n");
            systemPrompt.append("- risk scores\n");
            systemPrompt.append("- predictions\n");
            systemPrompt.append("- donation eligibility\n\n");
            systemPrompt.append("Never contradict the provided rule-engine decision.\n\n");
            systemPrompt.append("Do not expose internal architecture unless the user explicitly asks.\n\n");
            systemPrompt.append("Do not mention:\n");
            systemPrompt.append("- Groq\n");
            systemPrompt.append("- MySQL\n");
            systemPrompt.append("- API\n");
            systemPrompt.append("- backend\n");
            systemPrompt.append("- model names\n");
            systemPrompt.append("- SWI-Prolog\n");
            systemPrompt.append("- internal pipeline\n\n");
            systemPrompt.append("SAFETY RULE: Never recommend redistribution or cooking of expired food for human consumption.\n\n");

            if (matchedFoodItem != null) {
                systemPrompt.append("TARGET FOOD FOCUS:\n");
                systemPrompt.append("The user is specifically inquiring about: ").append(matchedFoodItem.getName()).append(".\n");
                systemPrompt.append("Your response MUST focus exclusively on ").append(matchedFoodItem.getName()).append(".\n");
                systemPrompt.append("Do NOT analyze or discuss other food items in this answer.\n\n");
            }

            systemPrompt.append("OPERATIONAL KITCHEN FACTS:\n");
            systemPrompt.append(contextBuilder.toString());
            systemPrompt.append("\nLANGUAGE RULES:\n");
            if ("mm".equalsIgnoreCase(lang)) {
                systemPrompt.append("1. Answer in natural, fluent Myanmar language without robotic repetitions. Do not add 'ခင်ဗျာ' to every single sentence.\n");
            } else {
                systemPrompt.append("1. Answer in clear, natural English without any Myanmar script.\n");
            }
            systemPrompt.append("2. Strictly preserve exact food names, numbers, units (liter, kg, MMK, pieces), percentages, and status terms.\n");

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
                logger.warn("Groq API returned status: {} | body: {}", httpResponse.statusCode(), httpResponse.body());
            }
        } catch (Exception e) {
            logger.warn("Groq AI API call failed: {}. Falling back to rule-grounded explanation.", e.getMessage());
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

        // 1. Empty Inventory Handling
        if (inventory == null || inventory.isEmpty()) {
            if (lowerQuery.contains("donat") || lowerQuery.contains("redistribut") || lowerQuery.contains("charit") || lowerQuery.contains("လှူဒါန်း")) {
                if (isMm) {
                    return "### 🤝 ပိုလျှံအစားအစာ ပြန်လည်လှူဒါန်းရေး အစီအစဉ်\n\n" +
                           "လက်ရှိတွင် ပြန်လည်လှူဒါန်းရန် သင့်တော်သော ပိုလျှံအစားအစာ မရှိသေးပါ။";
                } else {
                    return "### 🤝 Surplus Food Redistribution Plan\n\n" +
                           "No current inventory items are eligible for redistribution.";
                }
            }

            if (isMm) {
                return "### 🍃 FoodWaste AI နေ့စဉ် မီးဖိုချောင် အနှစ်ချုပ်\n\n" +
                       "လက်ရှိ မီးဖိုချောင် စာရင်းအတွင်း ကုန်ပစ္စည်း မရှိသေးပါ။\n\n" +
                       "**ဆောင်ရွက်ရန်:**\n" +
                       "၁။ 📦 **Inventory** သို့သွား၍ အစားအစာများကို ထည့်သွင်းပါ။\n" +
                       "၂။ ⚡ စနစ်မှ အလေအလွင့် အန္တရာယ်နှင့် စီမံခန့်ခွဲမှု အကြံပြုချက်များကို အလိုအလျောက် တွက်ချက်ပေးမည် ဖြစ်ပါသည်။";
            } else {
                return "### 🍃 FoodWaste AI Daily Intelligence Summary\n\n" +
                       "No inventory items currently exist in your kitchen inventory.\n\n" +
                       "**Next Steps:**\n" +
                       "1. 📦 Navigate to **Inventory** and add food items.\n" +
                       "2. ⚡ The system will automatically evaluate waste risks and generate actionable mitigation directives.";
            }
        }

        // 2. Specific Food Analysis Format
        if (matchedFoodItem != null) {
            String foodName = matchedFoodItem.getName();
            double stock = matchedFoodItem.getQuantity() != null ? matchedFoodItem.getQuantity().doubleValue() : 0.0;
            String unit = matchedFoodItem.getUnit() != null ? matchedFoodItem.getUnit() : "kg";
            int expiryDays = matchedAssessment != null ? matchedAssessment.getExpiryDays() :
                    (matchedFoodItem.getExpiryDaysRemaining() != null ? matchedFoodItem.getExpiryDaysRemaining() :
                    (matchedFoodItem.getExpiryDate() != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), matchedFoodItem.getExpiryDate()) : 5));
            String expiryStatus = matchedFoodItem.getExpiryStatus() != null ? matchedFoodItem.getExpiryStatus() : "SAFE";
            String riskLevel = matchedAssessment != null ? matchedAssessment.getRiskLevel() : "LOW";
            double riskPct = matchedAssessment != null ? matchedAssessment.getRiskPercentage() : 10.0;

            boolean isExpired = "EXPIRED".equalsIgnoreCase(expiryStatus) || expiryDays < 0;

            List<String> reasons = (matchedAssessment != null && isMm && matchedAssessment.getReasonsMy() != null && !matchedAssessment.getReasonsMy().isEmpty()) ?
                    matchedAssessment.getReasonsMy() : (matchedAssessment != null && matchedAssessment.getReasons() != null && !matchedAssessment.getReasons().isEmpty() ? matchedAssessment.getReasons() : List.of("Shelf life remaining: " + expiryDays + " day(s)"));
            String reasonsBullet = reasons.stream().map(r -> "- " + r).reduce((a, b) -> a + "\n" + b).orElse(isMm ? "- သက်တမ်းကုန်ဆုံးရက် နီးကပ်နေပါသည်" : "- Expiry date approaching");

            String recommendation;
            if (isExpired) {
                recommendation = isMm ?
                        "ဧည့်သည်များကို မကျွေးမွေးပါနှင့်။ ထုတ်လုပ်မှုကို ရပ်ဆိုင်း၍ စွန့်ပစ် သို့မဟုတ် မြေဆွေးပြုလုပ်ရန် မှတ်တမ်းတင်ပါ။" :
                        "Do not serve or cook. Stop further production and dispose or compost safely.";
            } else {
                recommendation = (matchedAssessment != null && isMm && matchedAssessment.getRecommendationMy() != null) ?
                        matchedAssessment.getRecommendationMy() : (matchedAssessment != null && matchedAssessment.getRecommendation() != null ? matchedAssessment.getRecommendation() : "Monitor stock levels and usage.");
            }

            String riskLevelDisplay = riskLevel;
            if (isMm) {
                if ("HIGH".equalsIgnoreCase(riskLevel)) riskLevelDisplay = "အန္တရာယ်မြင့်";
                else if ("MEDIUM".equalsIgnoreCase(riskLevel)) riskLevelDisplay = "အလယ်အလတ်အန္တရာယ်";
                else riskLevelDisplay = "အန္တရာယ်နည်း";
            }

            if (isMm) {
                return String.format(
                        "### 🍲 Food Item:\n%s\n\n" +
                        "**Status:**\n`%s` (%s)\n\n" +
                        "**Risk:**\n**%d%% (%s)**\n\n" +
                        "**Stock:**\n%.1f %s\n\n" +
                        "**Reason:**\n%s\n\n" +
                        "**Recommendation:**\n%s",
                        foodName,
                        expiryStatus,
                        expiryDays < 0 ? "သက်တမ်းကုန်ပြီး " + Math.abs(expiryDays) + " ရက်လွန်" : (expiryDays == 0 ? "ယနေ့သက်တမ်းကုန်မည်" : "သက်တမ်းကုန်ရန် " + expiryDays + " ရက်ကျန်"),
                        Math.round(riskPct),
                        riskLevelDisplay,
                        stock, unit,
                        reasonsBullet,
                        recommendation
                );
            } else {
                return String.format(
                        "### 🍲 Food Item:\n%s\n\n" +
                        "**Status:**\n`%s` (%s)\n\n" +
                        "**Risk:**\n**%d%% (%s)**\n\n" +
                        "**Stock:**\n%.1f %s\n\n" +
                        "**Reason:**\n%s\n\n" +
                        "**Recommendation:**\n%s",
                        foodName,
                        expiryStatus,
                        expiryDays < 0 ? "expired " + Math.abs(expiryDays) + " day(s) ago" : (expiryDays == 0 ? "expires today" : expiryDays + " day(s) remaining"),
                        Math.round(riskPct),
                        riskLevelDisplay,
                        stock, unit,
                        reasonsBullet,
                        recommendation
                );
            }
        }

        // 3. Global High Risk Query
        if (lowerQuery.contains("risk") || lowerQuery.contains("danger") || lowerQuery.contains("အန္တရာယ်") || lowerQuery.contains("စွန့်ပစ်")) {
            List<PrologAssessment> highRiskItems = items.stream().filter(i -> "HIGH".equalsIgnoreCase(i.getRiskLevel())).toList();
            if (!highRiskItems.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (isMm) {
                    sb.append(String.format("### ⚠️ အလေအလွင့် အန္တရာယ်မြင့် မီးဖိုချောင်သုံး ပစ္စည်းများ (%d မျိုး)\n\n", highRiskItems.size()));
                    sb.append("ချက်ချင်း အရေးယူဆောင်ရွက်ရန် လိုအပ်သော အန္တရာယ်မြင့် ပစ္စည်းများ:\n\n");
                    for (PrologAssessment a : highRiskItems) {
                        sb.append(String.format("- **%s**: အန္တရာယ် **%d%%** (လက်ကျန်: %.1f %s) → *%s*\n",
                                a.getFoodName(), Math.round(a.getRiskPercentage()), a.getStock(), a.getUnit(),
                                a.getRecommendationMy() != null ? a.getRecommendationMy() : a.getRecommendation()));
                    }
                } else {
                    sb.append(String.format("### ⚠️ Priority High-Risk Kitchen Items (%d Items)\n\n", highRiskItems.size()));
                    sb.append("The following items require operational attention:\n\n");
                    for (PrologAssessment a : highRiskItems) {
                        sb.append(String.format("- **%s**: Risk **%d%%** (Stock: %.1f %s) → *%s*\n",
                                a.getFoodName(), Math.round(a.getRiskPercentage()), a.getStock(), a.getUnit(), a.getRecommendation()));
                    }
                }
                return sb.toString();
            } else {
                return isMm ?
                        "### ⚠️ အလေအလွင့် အန္တရာယ် စစ်ဆေးချက်\n\nလက်ရှိတွင် အန္တရာယ်မြင့် ကုန်ပစ္စည်း မရှိပါ။" :
                        "### ⚠️ Risk Assessment\n\nNo high-risk food items are currently present in inventory.";
            }
        }

        // 4. Cook Priority Query (Ranked by expiry urgency and risk; never recommend cooking expired items)
        if (lowerQuery.contains("cook") || lowerQuery.contains("menu") || lowerQuery.contains("priorit") || lowerQuery.contains("chef") || lowerQuery.contains("ချက်") || lowerQuery.contains("သုံးစွဲ")) {
            List<PrologAssessment> cookEligible = items.stream()
                    .filter(i -> i.getExpiryDays() >= 0 && ("IMMEDIATE_USE".equalsIgnoreCase(i.getPriorityUsage()) || "HIGH_PRIORITY".equalsIgnoreCase(i.getPriorityUsage()) || "NEAR_EXPIRY".equalsIgnoreCase(i.getRiskLevel()) || "HIGH".equalsIgnoreCase(i.getRiskLevel())))
                    .sorted((a, b) -> Integer.compare(a.getExpiryDays(), b.getExpiryDays()))
                    .toList();

            if (!cookEligible.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (isMm) {
                    sb.append("### 👨‍🍳 ယနေ့ မီးဖိုချောင် ဦးစားပေး ချက်ပြုတ်သုံးစွဲရမည့် အစားအစာများ\n\n");
                    for (PrologAssessment a : cookEligible) {
                        sb.append(String.format("- **%s** (လက်ကျန်: %.1f %s, သက်တမ်း: %d ရက်) → နေ့စဉ် အထူးဟင်းလျာတွင် ထည့်သွင်းချက်ပြုတ်ပါ\n",
                                a.getFoodName(), a.getStock(), a.getUnit(), a.getExpiryDays()));
                    }
                    sb.append("\n💡 *ဤပစ္စည်းများကို ဦးစားပေး သုံးစွဲခြင်းဖြင့် အလေအလွင့်ကို အထိရောက်ဆုံး ကာကွယ်နိုင်ပါသည်။*");
                } else {
                    sb.append("### 👨‍🍳 Chef's Priority Kitchen Usage Plan for Today\n\n");
                    for (PrologAssessment a : cookEligible) {
                        sb.append(String.format("- **%s** (Stock: %.1f %s, Shelf-life: %d day(s)) → Feature in daily specials\n",
                                a.getFoodName(), a.getStock(), a.getUnit(), a.getExpiryDays()));
                    }
                    sb.append("\n💡 *Drawing down these near-expiry ingredients today prevents future spoilage.*");
                }
                return sb.toString();
            } else {
                return isMm ?
                        "### 👨‍🍳 မီးဖိုချောင် ချက်ပြုတ်မှု ဦးစားပေး\n\nယနေ့အတွက် အရေးပေါ် ချက်ပြုတ်သုံးစွဲရန် လိုအပ်သော ကုန်ပစ္စည်း မရှိသေးပါ။" :
                        "### 👨‍🍳 Chef's Priority Usage\n\nNo urgent ingredients currently require immediate priority cooking.";
            }
        }

        // 5. Redistribution / Donation Query (Only items eligible by rules; no expired items)
        if (lowerQuery.contains("donat") || lowerQuery.contains("redistribut") || lowerQuery.contains("charit") || lowerQuery.contains("ngo") || lowerQuery.contains("bank") || lowerQuery.contains("လှူဒါန်း") || lowerQuery.contains("ပရဟိတ")) {
            List<PrologAssessment> eligibleItems = items.stream()
                    .filter(PrologAssessment::isRecommendRedistribution)
                    .filter(i -> i.getExpiryDays() >= 0)
                    .toList();

            StringBuilder sb = new StringBuilder();
            if (isMm) {
                sb.append("### 🤝 ပိုလျှံအစားအစာ ပြန်လည်လှူဒါန်းရေး အစီအစဉ်\n\n");
                if (!eligibleItems.isEmpty()) {
                    sb.append(String.format("လှူဒါန်းရန် သင့်တော်သော ပိုလျှံပစ္စည်း **%d မျိုး** ရှိပါသည်:\n\n", eligibleItems.size()));
                    for (PrologAssessment a : eligibleItems) {
                        double surplus = Math.max(0, a.getStock() - a.getExpectedDemand());
                        sb.append(String.format("- **%s** (ခန့်မှန်း ပိုလျှံ: %.1f %s, သက်တမ်းကျန်: %d ရက်)\n",
                                a.getFoodName(), surplus, a.getUnit(), a.getExpiryDays()));
                    }
                    sb.append("\n💡 *အသေးစိတ် အချိန်ဇယားဆွဲရန် Redistribution စာမျက်နှာသို့ သွားရောက်ပါ။*");
                } else {
                    sb.append("လက်ရှိတွင် ပြန်လည်လှူဒါန်းရန် သင့်တော်သော ပိုလျှံအစားအစာ မရှိသေးပါ။\n");
                }
                if (recipients != null && !recipients.isEmpty()) {
                    sb.append("\n**ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်းများ:**\n");
                    for (RedistributionRecipient r : recipients) {
                        String type = r.getOrganizationType() != null ? r.getOrganizationType() : "ပရဟိတ";
                        sb.append(String.format("- **%s** (%s)\n", r.getName(), type));
                    }
                }
            } else {
                sb.append("### 🤝 Surplus Food Redistribution Plan\n\n");
                if (!eligibleItems.isEmpty()) {
                    sb.append(String.format("**%d food item(s)** are eligible for surplus donation:\n\n", eligibleItems.size()));
                    for (PrologAssessment a : eligibleItems) {
                        double surplus = Math.max(0, a.getStock() - a.getExpectedDemand());
                        sb.append(String.format("- **%s** (Projected Surplus: %.1f %s, Shelf-life: %d day(s))\n",
                                a.getFoodName(), surplus, a.getUnit(), a.getExpiryDays()));
                    }
                    sb.append("\n💡 *Navigate to the Redistribution tab to schedule dispatches.*");
                } else {
                    sb.append("No current inventory items are eligible for redistribution.\n");
                }
                if (recipients != null && !recipients.isEmpty()) {
                    sb.append("\n**Registered Redistribution Partners & Food Banks:**\n");
                    for (RedistributionRecipient r : recipients) {
                        String type = r.getOrganizationType() != null ? r.getOrganizationType() : "Charity";
                        sb.append(String.format("- **%s** (%s)\n", r.getName(), type));
                    }
                }
            }
            return sb.toString();
        }

        // 6. General Kitchen Daily Summary
        long highCount = items.stream().filter(i -> "HIGH".equalsIgnoreCase(i.getRiskLevel())).count();
        Map<String, Double> surplusByUnit = new LinkedHashMap<>();
        double potentialSavings = 0.0;
        for (PrologAssessment a : items) {
            double surplus = Math.max(0, a.getStock() - a.getExpectedDemand());
            if (surplus > 0 && a.getUnit() != null) {
                surplusByUnit.merge(a.getUnit(), surplus, Double::sum);
            }
        }
        for (FoodItem item : inventory) {
            if (item.getQuantity() != null && item.getPricePerUnit() != null) {
                potentialSavings += item.getQuantity().doubleValue() * item.getPricePerUnit().doubleValue() * 0.3;
            }
        }

        String surplusDisplay;
        if (surplusByUnit.isEmpty()) {
            surplusDisplay = isMm ? "0 (ပိုလျှံမှု မရှိ)" : "0 (no surplus)";
        } else {
            StringBuilder sb2 = new StringBuilder();
            surplusByUnit.forEach((unit2, amt) -> {
                if (sb2.length() > 0) sb2.append(" • ");
                sb2.append(String.format("%.1f %s", amt, unit2));
            });
            surplusDisplay = sb2.toString();
        }

        if (isMm) {
            return String.format(
                    "### 🍃 FoodWaste AI နေ့စဉ် မီးဖိုချောင် အနှစ်ချုပ်\n\n" +
                    "မီးဖိုချောင်ရှိ ကုန်ပစ္စည်း %d မျိုးကို ဆန်းစစ်ထားပါသည်:\n\n" +
                    "**အဓိက အချက်အလက်များ:**\n" +
                    "- **အန္တရာယ်မြင့် ကုန်ပစ္စည်းများ:** %d မျိုး\n" +
                    "- **ခန့်မှန်း ပိုလျှံအလေအလွင့်:** %s\n" +
                    "- **ခန့်မှန်း ချွေတာနိုင်မှု:** %,.0f MMK\n\n" +
                    "💡 *တိကျသော ကုန်ပစ္စည်း (ဥပမာ- Fresh Milk) သို့မဟုတ် 'အန္တရာယ်ရှိသော ပစ္စည်းများ' ဟု မေးမြန်းနိုင်ပါသည်။*",
                    inventory.size(), highCount, surplusDisplay, potentialSavings
            );
        } else {
            return String.format(
                    "### 🍃 FoodWaste AI Daily Intelligence Summary\n\n" +
                    "I have analyzed %d food item(s) across your kitchen inventory:\n\n" +
                    "**Key Metrics:**\n" +
                    "- **High Waste Risk Items:** %d item(s)\n" +
                    "- **Total Projected Surplus:** %s\n" +
                    "- **Potential Savings:** %,.0f MMK\n\n" +
                    "💡 *Try asking about a specific ingredient (e.g. 'Why is Fresh Milk risky?') or 'What should we cook today?'*",
                    inventory.size(), highCount, surplusDisplay, potentialSavings
            );
        }
    }
}
