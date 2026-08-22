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

/**
 * Orchestrates the full Explainable AI reasoning pipeline:
 * User -> Gemini Chat -> Java Backend -> MySQL Data -> SWI-Prolog Reasoning -> Gemini Explanation -> Smart Recommendation
 * Supports English (EN) and Professional Myanmar (MM) localization.
 */
public class GeminiExplanationService {
    private static final Logger logger = LoggerFactory.getLogger(GeminiExplanationService.class);
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
        public void addSource(String source) { if (source != null && !this.sources.contains(source)) this.sources.add(source); }

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

    public GeminiExplanationService() {
        this.predictionService = new PredictionService();
        this.foodItemService = new FoodItemService();
        this.recommendationService = new RecommendationService();
        this.redistributionService = new RedistributionService();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public GeminiExplanationService(PredictionService predictionService, FoodItemService foodItemService,
                                  RecommendationService recommendationService, RedistributionService redistributionService) {
        this.predictionService = predictionService;
        this.foodItemService = foodItemService;
        this.recommendationService = recommendationService;
        this.redistributionService = redistributionService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Executes the complete pipeline:
     * User Query -> Intent Analysis -> Live MySQL Data -> SWI-Prolog Reasoning -> Gemini / Rule-Grounded Explanation -> Structured ChatResponse
     */
    public ChatResponse processUserQuery(String userQuery) {
        return processUserQuery(userQuery, "en");
    }

    public ChatResponse processUserQuery(String userQuery, String language) {
        if (userQuery == null || userQuery.trim().isEmpty()) {
            userQuery = (language != null && language.equalsIgnoreCase("mm")) ?
                    "ယနေ့ အလေအလွင့် အခြေအနေနှင့် အကြံပြုချက်များ ဘာတွေရှိပါသလဲ?" :
                    "What is the food waste status and recommendations today?";
        }

        boolean isMyanmar = (language != null && language.equalsIgnoreCase("mm")) || containsMyanmarScript(userQuery);
        String activeLang = isMyanmar ? "mm" : "en";

        ChatResponse response = new ChatResponse();
        response.setUserQuery(userQuery);

        try {
            // 1. Fetch live MySQL Inventory & Partners
            List<FoodItem> inventory = foodItemService.getAllFoodItems();
            List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();

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

            // 4. Identify Related Food Items mentioned in user query
            FoodItem matchedFoodItem = null;
            PrologAssessment matchedAssessment = null;
            String lowerQuery = userQuery.toLowerCase();

            for (FoodItem fi : inventory) {
                if (fi.getName() != null && !fi.getName().trim().isEmpty()) {
                    String fiNameLower = fi.getName().toLowerCase();
                    if (lowerQuery.contains(fiNameLower) || fiNameLower.contains(lowerQuery)) {
                        matchedFoodItem = fi;
                        break;
                    }
                    // Word boundary check (e.g. "milk" in "Fresh Milk")
                    String[] tokens = fiNameLower.split("\\s+");
                    for (String token : tokens) {
                        if (token.length() > 3 && lowerQuery.contains(token)) {
                            matchedFoodItem = fi;
                            break;
                        }
                    }
                    if (matchedFoodItem != null) break;
                }
            }

            if (matchedFoodItem != null) {
                final Long matchedId = matchedFoodItem.getId();
                matchedAssessment = items.stream()
                        .filter(a -> a.getFoodItemId() != null && a.getFoodItemId().equals(matchedId))
                        .findFirst().orElse(null);

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

                response.addSource("MySQL Inventory #" + matchedFoodItem.getId() + " (" + matchedFoodItem.getName() + ")");
                response.addSource("SWI-Prolog assess_waste_risk/6");
                if (matchedFoodItem.getExpiryStatus() != null) {
                    response.addSource("ExpiryStatusResolver (" + matchedFoodItem.getExpiryStatus() + ")");
                }
            } else {
                response.addSource("MySQL Live Kitchen Inventory (" + inventory.size() + " items)");
                response.addSource("SWI-Prolog Expert Reasoning Engine (foodwaste_rules.pl)");
            }

            // 5. Synthesize Explanation (Gemini API or Intelligent Grounded Fallback)
            String apiKey = AppConfig.getGeminiApiKey();
            String geminiExplanation = null;

            if (apiKey != null && !apiKey.trim().isEmpty()) {
                geminiExplanation = callGeminiApi(userQuery, inventory, items, recipients, apiKey, activeLang);
            }

            if (geminiExplanation == null || geminiExplanation.trim().isEmpty()) {
                geminiExplanation = generateRuleGroundedExplanation(userQuery, inventory, items, recipients, matchedFoodItem, matchedAssessment, activeLang);
                response.setSourceEngine(isMyanmar ? "SWI-Prolog Expert Reasoner (Myanmar XAI)" : "SWI-Prolog Expert Reasoner + Intelligent XAI Synthesizer");
            } else {
                response.setSourceEngine("Google Gemini (" + AppConfig.getGeminiModel() + ") + SWI-Prolog Ground Truth");
                response.addSource("Google Gemini Generative AI (" + AppConfig.getGeminiModel() + ")");
            }

            response.setAnswer(geminiExplanation);

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
            logger.error("Error in GeminiExplanationService: {}", e.getMessage(), e);
            if (isMyanmar) {
                response.setAnswer("ကျွန်ုပ်တို့၏ SWI-Prolog ယုတ္တိဗေဒစနစ်မှ မီးဖိုချောင် စာရင်းအင်းများကို ဆန်းစစ်ပေးပါသည်။ လက်ရှိတွင် စာရင်းသွင်းထားသော ကုန်ပစ္စည်း မရှိသေးပါက Inventory သို့ သွားရောက် ထည့်သွင်းပေးပါ။");
                response.setSourceEngine("FoodWaste AI Reasoner");
                response.addSmartAction(new SmartAction("ကုန်ပစ္စည်းလက်ကျန် ကြည့်ရှုမည်", "VIEW_INVENTORY", "အချက်အလက်", "/inventory.html"));
            } else {
                response.setAnswer("Our SWI-Prolog expert reasoning system evaluates live kitchen inventory. Please ensure food items are recorded in the Inventory section to generate waste predictions and mitigation directives.");
                response.setSourceEngine("FoodWaste AI Reasoner");
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

    /**
     * Calls Google Gemini Generative Language API
     */
    private String callGeminiApi(String userQuery, List<FoodItem> inventory, List<PrologAssessment> prologAssessments,
                                 List<RedistributionRecipient> recipients, String apiKey, String lang) {
        try {
            String model = AppConfig.getGeminiModel();
            String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

            StringBuilder contextBuilder = new StringBuilder();
            contextBuilder.append("You are the Explainable AI Assistant for FoodWaste AI, an intelligent conversational system for food waste prediction, prevention, and redistribution.\n\n");
            contextBuilder.append("CURRENT INVENTORY & DATABASE METRICS (MySQL):\n");
            for (FoodItem item : inventory) {
                contextBuilder.append(String.format("- %s (ID %d): Stock=%.1f %s, Price=%s MMK/unit, Expiry=%s, Status=%s, ExpiryStatus=%s\n",
                        item.getName(), item.getId(), item.getQuantity(), item.getUnit(), item.getPricePerUnit(), item.getExpiryDate(), item.getStatus(), item.getExpiryStatus()));
            }

            contextBuilder.append("\nSWI-PROLOG EXPERT REASONING RESULTS (Mathematical Ground Truth):\n");
            for (PrologAssessment a : prologAssessments) {
                contextBuilder.append(String.format("- %s: Risk=%s (%d%%), Priority=%s, Reasons=%s, RecAction=%s, Redistribute=%s\n",
                        a.getFoodName(), a.getRiskLevel(), Math.round(a.getRiskPercentage()), a.getPriorityUsage(),
                        String.join("; ", a.getReasons()), a.getRecommendation(), a.isRecommendRedistribution()));
            }

            contextBuilder.append("\nCHARITY REDISTRIBUTION PARTNERS:\n");
            for (RedistributionRecipient r : recipients) {
                contextBuilder.append(String.format("- %s (%s, Contact: %s, Phone: %s)\n", r.getName(), r.getOrganizationType(), r.getContactPerson(), r.getPhone()));
            }

            contextBuilder.append("\nINSTRUCTIONS FOR GEMINI:\n");
            if ("mm".equalsIgnoreCase(lang)) {
                contextBuilder.append("1. Answer the user's question in natural, highly articulate Professional Business Myanmar (Burmese) language using Myanmar Unicode script.\n");
            } else {
                contextBuilder.append("1. Answer the user's question concisely, clearly, and politely using an articulate, professional English tone.\n");
            }
            contextBuilder.append("2. Strictly adhere to the SWI-Prolog logical conclusions and MySQL metrics above. Do not invent contradictory numbers.\n");
            contextBuilder.append("3. Always preserve exact food names, numbers, units (liter, kg, MMK, pieces), and Prolog predicates (assess_waste_risk/6, evaluate_priority_use/3, recommend_production/6, evaluate_redistribution/6).\n");
            contextBuilder.append("4. Format with clean markdown: bold headings, bullet points, and clear actionable mitigation directives.\n");

            JsonObject requestBody = new JsonObject();
            JsonArray contents = new JsonArray();
            JsonObject contentObj = new JsonObject();
            JsonArray parts = new JsonArray();
            JsonObject partObj = new JsonObject();
            partObj.addProperty("text", contextBuilder.toString() + "\n\nUSER QUESTION: " + userQuery);
            parts.add(partObj);
            contentObj.add("parts", parts);
            contents.add(contentObj);
            requestBody.add("contents", contents);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                    .timeout(Duration.ofSeconds(6))
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
            } else {
                logger.warn("Gemini API call returned status {}: {}", httpResponse.statusCode(), httpResponse.body());
            }
        } catch (Exception e) {
            logger.warn("Gemini API call failed ({}). Using grounded XAI synthesizer fallback.", e.getMessage());
        }
        return null;
    }

    /**
     * Synthesizes a structured, dynamic Explainable AI response based on real Prolog & MySQL data
     * (Zero hardcoded canned responses, handles ANY food item or operational query).
     */
    private String generateRuleGroundedExplanation(String query, List<FoodItem> inventory,
                                                   List<PrologAssessment> items, List<RedistributionRecipient> recipients,
                                                   FoodItem matchedFoodItem, PrologAssessment matchedAssessment,
                                                   String lang) {
        String lowerQuery = query.toLowerCase();
        boolean isMm = "mm".equalsIgnoreCase(lang);

        // 1. Handle completely empty inventory
        if (inventory == null || inventory.isEmpty() || items == null || items.isEmpty()) {
            if (lowerQuery.contains("donat") || lowerQuery.contains("redistribut") || lowerQuery.contains("charit") || lowerQuery.contains("ngo") || lowerQuery.contains("လှူဒါန်း")) {
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
                       "၂။ ⚡ **SWI-Prolog Expert Engine** မှ အလေအလွင့် အန္တရာယ်နှင့် စီမံခန့်ခွဲမှု အကြံပြုချက်များကို အလိုအလျောက် တွက်ချက်ပေးမည် ဖြစ်ပါသည်။";
            } else {
                return "### 🍃 FoodWaste AI Daily Intelligence Summary\n\n" +
                       "No inventory items currently exist in your kitchen inventory.\n\n" +
                       "**Next Steps:**\n" +
                       "1. 📦 Navigate to **Inventory** and add your restaurant food items.\n" +
                       "2. ⚡ The **SWI-Prolog Expert Engine** will automatically evaluate waste risk probabilities and generate intelligent mitigation directives.";
            }
        }

        // 2. Specific Food Item Analysis (e.g., "Why is Fresh Milk risky?", "Tell me about chicken", etc.)
        if (matchedFoodItem != null && matchedAssessment != null) {
            String foodName = matchedFoodItem.getName();
            double stock = matchedFoodItem.getQuantity() != null ? matchedFoodItem.getQuantity().doubleValue() : 0.0;
            String unit = matchedFoodItem.getUnit() != null ? matchedFoodItem.getUnit() : "liter";
            double demand = matchedAssessment.getExpectedDemand();
            double surplus = Math.max(0.0, stock - demand);
            int expiryDays = matchedAssessment.getExpiryDays();
            String expiryStatus = matchedFoodItem.getExpiryStatus() != null ? matchedFoodItem.getExpiryStatus() : "SAFE";
            String riskLevel = matchedAssessment.getRiskLevel();
            double riskPct = matchedAssessment.getRiskPercentage();
            double pricePerUnit = matchedFoodItem.getPricePerUnit() != null ? matchedFoodItem.getPricePerUnit().doubleValue() : 0.0;
            double potentialLoss = stock * pricePerUnit;

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

            String expiryStatusDisplay = expiryStatus;
            if (isMm) {
                if ("EXPIRED".equalsIgnoreCase(expiryStatus)) expiryStatusDisplay = "သက်တမ်းကုန်ပြီး";
                else if ("SAME_DAY_EXPIRY".equalsIgnoreCase(expiryStatus)) expiryStatusDisplay = "ယနေ့သက်တမ်းကုန်";
                else if ("NEAR_EXPIRY".equalsIgnoreCase(expiryStatus)) expiryStatusDisplay = "သက်တမ်းကုန်ရန်နီး";
                else expiryStatusDisplay = "ပုံမှန်ကောင်းမွန်";
            }

            if (isMm) {
                return String.format(
                        "### 🍲 %s အလေအလွင့် အန္တရာယ်နှင့် အခြေအနေ ဆန်းစစ်ချက်\n\n" +
                        "**အန္တရာယ် အဆင့်အတန်း:** **%s (%d%% ဖြစ်နိုင်ခြေ)**\n" +
                        "**သက်တမ်း အခြေအနေ:** `%s` (သက်တမ်းကုန်ရန် %d ရက်ကျန်ရှိ)\n\n" +
                        "**SWI-Prolog ယုတ္တိဗေဒ အကြောင်းရင်းများ (`assess_waste_risk/6`):**\n" +
                        "%s\n\n" +
                        "**လက်ရှိ မီးဖိုချောင် စာရင်းအင်း အချက်အလက်များ:**\n" +
                        "- **လက်ကျန်ပမာဏ:** %.1f %s\n" +
                        "- **ခန့်မှန်းဝယ်လိုအား:** %.1f %s\n" +
                        "- **ပိုလျှံနေသော ပမာဏ:** %.1f %s\n" +
                        "- **ဆုံးရှုံးနိုင်ခြေ တန်ဖိုး:** %,.0f MMK\n\n" +
                        "**AI လုပ်ဆောင်ချက် လမ်းညွှန်ချက်:**\n" +
                        "💡 **%s**",
                        foodName,
                        riskLevelDisplay,
                        Math.round(riskPct),
                        expiryStatusDisplay,
                        expiryDays,
                        reasonsBullet,
                        stock, unit,
                        demand, unit,
                        surplus, unit,
                        potentialLoss,
                        recommendation
                );
            } else {
                return String.format(
                        "### 🍲 %s Waste Risk & Expiry Assessment\n\n" +
                        "**Risk Level:** **%s (%d%% Probability)**\n" +
                        "**Expiry Status:** `%s` (%d day(s) remaining)\n\n" +
                        "**SWI-Prolog Logical Reasons (`assess_waste_risk/6`):**\n" +
                        "%s\n\n" +
                        "**Operational Inventory Metrics:**\n" +
                        "- **Current Stock:** %.1f %s\n" +
                        "- **Expected Demand:** %.1f %s\n" +
                        "- **Surplus Quantity:** %.1f %s\n" +
                        "- **Financial Spoilage at Risk:** %,.0f MMK\n\n" +
                        "**Smart AI Action Directive:**\n" +
                        "💡 **%s**",
                        foodName,
                        riskLevelDisplay,
                        Math.round(riskPct),
                        expiryStatusDisplay,
                        expiryDays,
                        reasonsBullet,
                        stock, unit,
                        demand, unit,
                        surplus, unit,
                        potentialLoss,
                        recommendation
                );
            }
        }

        // 3. High Risk Query ("Which items are risky?", "high risk items", etc.)
        if (lowerQuery.contains("risk") || lowerQuery.contains("danger") || lowerQuery.contains("အန္တရာယ်") || lowerQuery.contains("စွန့်ပစ်")) {
            List<PrologAssessment> highRiskItems = items.stream().filter(i -> "HIGH".equalsIgnoreCase(i.getRiskLevel())).toList();
            if (!highRiskItems.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (isMm) {
                    sb.append(String.format("### ⚠️ အလေအလွင့် အန္တရာယ်မြင့် မီးဖိုချောင်သုံး ပစ္စည်းများ (%d မျိုး)\n\n", highRiskItems.size()));
                    sb.append("SWI-Prolog ယုတ္တိဗေဒစနစ်မှ ချက်ချင်း အရေးယူဆောင်ရွက်ရန် လိုအပ်သော ပစ္စည်းများကို တွေ့ရှိထားပါသည်:\n\n");
                    for (PrologAssessment a : highRiskItems) {
                        sb.append(String.format("- **%s**: အန္တရာယ် **%d%%** (လက်ကျန်: %.1f %s, သက်တမ်းကုန်ရန်: %d ရက်) → *%s*\n",
                                a.getFoodName(), Math.round(a.getRiskPercentage()), a.getStock(), a.getUnit(), a.getExpiryDays(),
                                a.getRecommendationMy() != null ? a.getRecommendationMy() : a.getRecommendation()));
                    }
                    sb.append("\n💡 *Recommendations စာမျက်နှာတွင် အဆိုပြုချက်များကို အတည်ပြုနိုင်ပါသည်။*");
                } else {
                    sb.append(String.format("### ⚠️ Priority High-Risk Kitchen Items (%d Items)\n\n", highRiskItems.size()));
                    sb.append("Our SWI-Prolog expert reasoning system flagged these items requiring immediate operational action:\n\n");
                    for (PrologAssessment a : highRiskItems) {
                        sb.append(String.format("- **%s**: Risk **%d%%** (Stock: %.1f %s, Expiry: %d day(s)) → *%s*\n",
                                a.getFoodName(), Math.round(a.getRiskPercentage()), a.getStock(), a.getUnit(), a.getExpiryDays(), a.getRecommendation()));
                    }
                    sb.append("\n💡 *Navigate to the Recommendations tab to execute automated mitigation directives.*");
                }
                return sb.toString();
            }
        }

        // 4. Expired / Near Expiry Query
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

        // 5. Menu / Cooking / Priority Use Query ("What should we cook today?", etc.)
        if (lowerQuery.contains("cook") || lowerQuery.contains("menu") || lowerQuery.contains("priorit") || lowerQuery.contains("ချက်") || lowerQuery.contains("သုံးစွဲ")) {
            List<PrologAssessment> priorityList = items.stream()
                    .filter(i -> "IMMEDIATE_USE".equalsIgnoreCase(i.getPriorityUsage()) || "HIGH_PRIORITY".equalsIgnoreCase(i.getPriorityUsage()))
                    .toList();

            if (!priorityList.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (isMm) {
                    sb.append("### 👨‍🍳 ယနေ့ မီးဖိုချောင် ဦးစားပေး ချက်ပြုတ်သုံးစွဲရမည့် အစားအစာများ\n\n");
                    sb.append("SWI-Prolog `evaluate_priority_use/3` စည်းမျဉ်းအရ သက်တမ်းမကုန်မီ ဦးစားပေးသုံးစွဲသင့်သော ကုန်ကြမ်းများ:\n\n");
                    for (PrologAssessment a : priorityList) {
                        sb.append(String.format("- **%s** (လက်ကျန်: %.1f %s, သက်တမ်း: %d ရက်) → နေ့စဉ် အထူးဟင်းလျာတွင် ထည့်သွင်းချက်ပြုတ်ပါ\n",
                                a.getFoodName(), a.getStock(), a.getUnit(), a.getExpiryDays()));
                    }
                    sb.append("\n💡 *ဤပစ္စည်းများကို ယနေ့ မီနူးတွင် ဦးစားပေး သုံးစွဲခြင်းဖြင့် အလေအလွင့်ကို အထိရောက်ဆုံး ကာကွယ်နိုင်ပါသည်။*");
                } else {
                    sb.append("### 👨‍🍳 Chef's Priority Kitchen Usage Plan for Today\n\n");
                    sb.append("Grounded in SWI-Prolog `evaluate_priority_use/3`, prioritize these ingredients for today's lunch/dinner service:\n\n");
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
                       String.format("SWI-Prolog စည်းမျဉ်း `evaluate_redistribution/6` အရ လှူဒါန်းရန် သင့်တော်သော ပိုလျှံပစ္စည်း **%d မျိုး** ရှိပါသည်:\n\n", eligibleCount) +
                       "**မှတ်ပုံတင်ထားသော ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်းများ:**\n" +
                       recipientBlock.toString() + "\n" +
                       "💡 *Redistribution စာမျက်နှာသို့ သွားရောက်၍ ကယ်ဆယ်ရေး လှူဒါန်းမှု အချိန်ဇယားဆွဲနိုင်ပါသည်။*";
            } else {
                return "### 🤝 Surplus Food Redistribution & Charity Plan\n\n" +
                       String.format("Based on SWI-Prolog rule `evaluate_redistribution/6`, **%d food item(s)** are eligible for surplus donation:\n\n", eligibleCount) +
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
                    "ကျွန်ုပ်တို့၏ **SWI-Prolog Expert Engine** မှ မီးဖိုချောင်ရှိ ကုန်ပစ္စည်း %d မျိုးကို ဆန်းစစ်တွက်ချက်ပြီး ဖြစ်ပါသည်:\n\n" +
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
                    "Our **SWI-Prolog Expert Engine** analyzed %d food items across your live MySQL inventory.\n\n" +
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
