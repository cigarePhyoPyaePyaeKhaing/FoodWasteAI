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

    public static class ChatResponse {
        private String userQuery;
        private String explanation;
        private String sourceEngine;
        private Map<String, Object> prologSummary;
        private List<SmartAction> smartRecommendations = new ArrayList<>();

        public ChatResponse() {}

        public String getUserQuery() { return userQuery; }
        public void setUserQuery(String userQuery) { this.userQuery = userQuery; }

        public String getExplanation() { return explanation; }
        public void setExplanation(String explanation) { this.explanation = explanation; }

        public String getSourceEngine() { return sourceEngine; }
        public void setSourceEngine(String sourceEngine) { this.sourceEngine = sourceEngine; }

        public Map<String, Object> getPrologSummary() { return prologSummary; }
        public void setPrologSummary(Map<String, Object> prologSummary) { this.prologSummary = prologSummary; }

        public List<SmartAction> getSmartRecommendations() { return smartRecommendations; }
        public void setSmartRecommendations(List<SmartAction> smartRecommendations) { this.smartRecommendations = smartRecommendations; }
        public void addSmartAction(SmartAction action) { this.smartRecommendations.add(action); }
    }

    public static class SmartAction {
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
     * Executes the complete pipeline: User Query -> MySQL Data -> SWI-Prolog Reasoning -> Gemini Explanation -> Smart Recommendations
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

            // 3. Synthesize Gemini Explanation
            String apiKey = AppConfig.getGeminiApiKey();
            String geminiExplanation = null;

            if (apiKey != null && !apiKey.trim().isEmpty()) {
                geminiExplanation = callGeminiApi(userQuery, inventory, items, recipients, apiKey, activeLang);
            }

            if (geminiExplanation == null || geminiExplanation.trim().isEmpty()) {
                geminiExplanation = generateRuleGroundedExplanation(userQuery, inventory, items, recipients, activeLang);
                response.setSourceEngine(isMyanmar ? "SWI-Prolog Expert Reasoner (Myanmar XAI)" : "SWI-Prolog Expert Reasoner + Intelligent XAI Synthesizer");
            } else {
                response.setSourceEngine("Google Gemini (" + AppConfig.getGeminiModel() + ") + SWI-Prolog Knowledge Base");
            }

            response.setExplanation(geminiExplanation);

            // 4. Generate structured Smart Action Recommendations
            if (items != null) {
                for (PrologAssessment a : items) {
                    if ("HIGH".equalsIgnoreCase(a.getRiskLevel())) {
                        String title = isMyanmar ?
                                "⚡ " + a.getFoodName() + " ထုတ်လုပ်မှုပမာဏ လျှော့ချမည်" :
                                "⚡ " + a.getRecommendation();
                        String badge = isMyanmar ? "အရေးပေါ်" : "URGENT";
                        response.addSmartAction(new SmartAction(
                                title,
                                "REDUCE_PRODUCTION",
                                badge,
                                "foodItemId=" + a.getFoodItemId()
                        ));
                    }
                    if (a.isRecommendRedistribution()) {
                        String title = isMyanmar ?
                                "🤝 " + a.getFoodName() + " ပိုလျှံမှု ပရဟိတသို့ လှူဒါန်းမည်" :
                                "🤝 Dispatch Surplus " + a.getFoodName() + " to Charity";
                        String badge = isMyanmar ? "ပြန်လည်လှူဒါန်းမှု" : "REDISTRIBUTION";
                        response.addSmartAction(new SmartAction(
                                title,
                                "SCHEDULE_DONATION",
                                badge,
                                "foodItemId=" + a.getFoodItemId()
                        ));
                    }
                }
            }

            if (response.getSmartRecommendations().isEmpty()) {
                String title = isMyanmar ? "📊 ကုန်ပစ္စည်းလက်ကျန်နှင့် ဝယ်လိုအား ကြည့်ရှုမည်" : "📊 View Inventory & Demand Forecast";
                response.addSmartAction(new SmartAction(
                        title,
                        "VIEW_INVENTORY",
                        "INFO",
                        "/inventory.html"
                ));
            }

        } catch (Exception e) {
            logger.error("Error in GeminiExplanationService: {}", e.getMessage(), e);
            if (isMyanmar) {
                response.setExplanation("ကျွန်ုပ်တို့၏ SWI-Prolog ယုတ္တိဗေဒစနစ်မှ မီးဖိုချောင် စာရင်းအင်းများကို ဆန်းစစ်ပေးပါသည်။ လက်ရှိတွင် စာရင်းသွင်းထားသော ကုန်ပစ္စည်း မရှိသေးပါက Inventory သို့ သွားရောက် ထည့်သွင်းပေးပါ။");
                response.setSourceEngine("FoodWaste AI Reasoner");
                response.addSmartAction(new SmartAction("ကုန်ပစ္စည်းလက်ကျန် ကြည့်ရှုမည်", "VIEW_INVENTORY", "အချက်အလက်", "/inventory.html"));
            } else {
                response.setExplanation("Our SWI-Prolog expert reasoning system evaluates live kitchen inventory. Please ensure food items are recorded in the Inventory section to generate waste predictions and mitigation directives.");
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
            contextBuilder.append("You are the Explainable AI Assistant for FoodWaste AI, an intelligent system for food waste prediction, prevention, and redistribution.\n\n");
            contextBuilder.append("CURRENT INVENTORY & DATABASE METRICS (MySQL):\n");
            for (FoodItem item : inventory) {
                contextBuilder.append(String.format("- %s (ID %d): Stock=%.1f %s, Price=%s MMK/unit, Expiry=%s, Status=%s\n",
                        item.getName(), item.getId(), item.getQuantity(), item.getUnit(), item.getPricePerUnit(), item.getExpiryDate(), item.getStatus()));
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
                contextBuilder.append("1. Answer the user's question in professional, elegant Business Myanmar (Burmese) language using Myanmar Unicode script.\n");
            } else {
                contextBuilder.append("1. Answer the user's question concisely, clearly, and politely using an articulate, professional English tone.\n");
            }
            contextBuilder.append("2. Strictly adhere to the SWI-Prolog logical conclusions and MySQL metrics above. Do not invent contradictory numbers.\n");
            contextBuilder.append("3. Format with clean bullet points, bold highlights, and actionable mitigation advice.\n");

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
     * Synthesizes a structured, highly articulate Explainable AI response based on real Prolog & MySQL data
     */
    private String generateRuleGroundedExplanation(String query, List<FoodItem> inventory,
                                                   List<PrologAssessment> items, List<RedistributionRecipient> recipients, String lang) {
        String lowerQuery = query.toLowerCase();
        boolean isMm = "mm".equalsIgnoreCase(lang);

        // Handle completely empty inventory
        if (inventory == null || inventory.isEmpty() || items == null || items.isEmpty()) {
            if (lowerQuery.contains("donat") || lowerQuery.contains("redistribut") || lowerQuery.contains("charit") || lowerQuery.contains("ngo") || lowerQuery.contains("လှူဒါန်း")) {
                StringBuilder recipientBlockMm = new StringBuilder();
                StringBuilder recipientBlockEn = new StringBuilder();

                if (recipients != null && !recipients.isEmpty()) {
                    int idx = 1;
                    for (RedistributionRecipient r : recipients) {
                        recipientBlockMm.append(String.format("%d. 🏢 **%s** (%s, ဖုန်း: %s)\n", idx, r.getName(), r.getContactPerson(), r.getPhone()));
                        recipientBlockEn.append(String.format("%d. 🏢 **%s** (%s, Contact: %s, Phone: %s)\n", idx, r.getName(), r.getOrganizationType(), r.getContactPerson(), r.getPhone()));
                        idx++;
                    }
                } else {
                    recipientBlockMm.append("*(လက်ရှိတွင် စနစ်အတွင်း မှတ်ပုံတင်ထားသော ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်း မရှိသေးပါ)*\n");
                    recipientBlockEn.append("*(No verified charity partners currently registered in database)*\n");
                }

                if (isMm) {
                    return "### 🤝 ပိုလျှံအစားအစာ ပြန်လည်လှူဒါန်းရေး အစီအစဉ်\n\n" +
                           "လက်ရှိတွင် လှူဒါန်းရန် ပိုလျှံအစားအစာ စာရင်း မရှိသေးပါ။\n\n" +
                           "**မှတ်ပုံတင်ထားသော ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်းများ:**\n" +
                           recipientBlockMm.toString() + "\n" +
                           "💡 *ပိုလျှံအစားအစာများ ရှိလာပါက Redistribution ကဏ္ဍတွင် အချိန်ဇယားဆွဲနိုင်ပါသည်။*";
                } else {
                    return "### 🤝 Surplus Food Redistribution Directory\n\n" +
                           "No surplus food items are currently flagged for donation in your inventory.\n\n" +
                           "**Verified Charity Partners Available for Pickup:**\n" +
                           recipientBlockEn.toString() + "\n" +
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

        // 1. Chicken specific query
        if (lowerQuery.contains("chicken") || lowerQuery.contains("poultry") || lowerQuery.contains("ကြက်သား")) {
            PrologAssessment chicken = items.stream().filter(i -> i.getFoodName().toLowerCase().contains("chicken") || i.getFoodName().contains("ကြက်သား")).findFirst().orElse(null);
            if (chicken != null) {
                String partnerNameMm = (recipients != null && !recipients.isEmpty()) ? "**" + recipients.get(0).getName() + "**" : "ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်း";
                String partnerNameEn = (recipients != null && !recipients.isEmpty()) ? "**" + recipients.get(0).getName() + "**" : "a verified charity partner";

                if (isMm) {
                    return String.format(
                            "### 🍗 ကြက်သား အလေအလွင့် အန္တရာယ် ဆန်းစစ်ချက်\n\n" +
                            "**အန္တရာယ် အဆင့်အတန်း:** %s (%d%%)\n\n" +
                            "**Prolog ယုတ္တိဗေဒ အကြောင်းရင်းများ:**\n" +
                            "%s\n\n" +
                            "**လက်ရှိ စာရင်းအင်း အချက်အလက်များ:**\n" +
                            "- **လက်ကျန်ပမာဏ:** %.1f kg (သက်တမ်းကုန်ရန် %d ရက်ကျန်ရှိ)\n" +
                            "- **ခန့်မှန်းလိုအပ်ချက်:** %.1f kg\n" +
                            "- **ပိုလျှံနေသော ပမာဏ:** %.1f kg\n\n" +
                            "**AI အကြံပြုချက်:**\n" +
                            "💡 %s။ ပိုလျှံမှုရှိပါက %s သို့ လှူဒါန်းနိုင်ပါသည်။",
                            chicken.getRiskLevel(),
                            Math.round(chicken.getRiskPercentage()),
                            chicken.getReasons().stream().map(r -> "- " + r).reduce((a, b) -> a + "\n" + b).orElse("- သက်တမ်းကုန်ဆုံးရက် နီးကပ်နေပါသည်"),
                            chicken.getStock(),
                            chicken.getExpiryDays(),
                            chicken.getExpectedDemand(),
                            Math.max(0, chicken.getStock() - chicken.getExpectedDemand()),
                            chicken.getRecommendation(),
                            partnerNameMm
                    );
                } else {
                    return String.format(
                            "### 🍗 Chicken Waste Risk Assessment\n\n" +
                            "**Risk Level:** %s (%d%% Probability)\n\n" +
                            "**Prolog Logical Reasons:**\n" +
                            "%s\n\n" +
                            "**Operational Metrics:**\n" +
                            "- **Current Stock:** %.1f kg (Expiry: %d day(s) remaining)\n" +
                            "- **Expected Demand:** %.1f kg\n" +
                            "- **Surplus Inventory:** %.1f kg\n\n" +
                            "**Smart AI Recommendation:**\n" +
                            "💡 %s. Surplus can be dispatched to %s.",
                            chicken.getRiskLevel(),
                            Math.round(chicken.getRiskPercentage()),
                            chicken.getReasons().stream().map(r -> "- " + r).reduce((a, b) -> a + "\n" + b).orElse("- Expiry approaching"),
                            chicken.getStock(),
                            chicken.getExpiryDays(),
                            chicken.getExpectedDemand(),
                            Math.max(0, chicken.getStock() - chicken.getExpectedDemand()),
                            chicken.getRecommendation(),
                            partnerNameEn
                    );
                }
            }
        }

        // 2. Donation / Redistribution query
        if (lowerQuery.contains("donat") || lowerQuery.contains("redistribut") || lowerQuery.contains("charit") || lowerQuery.contains("ngo") || lowerQuery.contains("လှူဒါန်း")) {
            StringBuilder recipientBlockMm = new StringBuilder();
            StringBuilder recipientBlockEn = new StringBuilder();

            if (recipients != null && !recipients.isEmpty()) {
                int idx = 1;
                for (RedistributionRecipient r : recipients) {
                    recipientBlockMm.append(String.format("%d. 🏢 **%s** (%s, ဖုန်း: %s)\n", idx, r.getName(), r.getContactPerson(), r.getPhone()));
                    recipientBlockEn.append(String.format("%d. 🏢 **%s** (%s, Contact: %s, Phone: %s)\n", idx, r.getName(), r.getOrganizationType(), r.getContactPerson(), r.getPhone()));
                    idx++;
                }
            } else {
                recipientBlockMm.append("*(လက်ရှိတွင် စနစ်အတွင်း မှတ်ပုံတင်ထားသော ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်း မရှိသေးပါ)*\n");
                recipientBlockEn.append("*(No verified charity partners currently registered in database)*\n");
            }

            long eligibleCount = items.stream().filter(PrologAssessment::isRecommendRedistribution).count();

            if (isMm) {
                return "### 🤝 ပိုလျှံအစားအစာ ပြန်လည်လှူဒါန်းရေး အစီအစဉ်\n\n" +
                       String.format("SWI-Prolog စည်းမျဉ်း `evaluate_redistribution/6` အရ လက်ရှိတွင် လှူဒါန်းရန် သင့်တော်သော ပစ္စည်း %d မျိုး ရှိပါသည်:\n\n", eligibleCount) +
                       "**လက်ခံမည့် ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်းများ:**\n" +
                       recipientBlockMm.toString() + "\n" +
                       "💡 *အောက်ပါ ခလုတ်ကို နှိပ်၍ လှူဒါန်းမှု အချိန်ဇယားဆွဲနိုင်ပါသည်။*";
            } else {
                return "### 🤝 Surplus Food Redistribution Plan\n\n" +
                       String.format("Based on SWI-Prolog evaluation rule `evaluate_redistribution/6`, %d surplus food item(s) are eligible for charity donation:\n\n", eligibleCount) +
                       "**Verified Charity Partners Available for Pickup:**\n" +
                       recipientBlockEn.toString() + "\n" +
                       "💡 *Click below or go to Redistribution tab to schedule courier dispatch.*";
            }
        }

        // 3. Default comprehensive overview
        long highCount = items.stream().filter(i -> "HIGH".equalsIgnoreCase(i.getRiskLevel())).count();
        double totalSurplus = items.stream().mapToDouble(i -> Math.max(0, i.getStock() - i.getExpectedDemand())).sum();
        String partnerSummaryMm = (recipients != null && !recipients.isEmpty()) ? recipients.get(0).getName() : "ပရဟိတ အဖွဲ့အစည်း";
        String partnerSummaryEn = (recipients != null && !recipients.isEmpty()) ? recipients.get(0).getName() : "a verified food bank";

        if (isMm) {
            return String.format(
                    "### 🍃 FoodWaste AI နေ့စဉ် မီးဖိုချောင် အနှစ်ချုပ် အစီရင်ခံစာ\n\n" +
                    "ကျွန်ုပ်တို့၏ **SWI-Prolog Expert Engine** မှ မီးဖိုချောင်ရှိ ကုန်ပစ္စည်း %d မျိုးကို ဆန်းစစ်တွက်ချက်ပြီး ဖြစ်ပါသည်:\n\n" +
                    "**အဓိက တွေ့ရှိချက်များ:**\n" +
                    "- **အန္တရာယ်မြင့် ကုန်ပစ္စည်းများ:** %d မျိုး\n" +
                    "- **ခန့်မှန်း ပိုလျှံအလေအလွင့်:** %.1f kg\n\n" +
                    "**မိတ်ဖက် အဖွဲ့အစည်းများ:** %s\n\n" +
                    "မည်သည့် အစားအစာ သို့မဟုတ် လုပ်ဆောင်ချက်ကို အသေးစိတ် ဆက်လက်စစ်ဆေးလိုပါသလဲ?",
                    inventory.size(), highCount, totalSurplus, partnerSummaryMm
            );
        } else {
            return String.format(
                    "### 🍃 FoodWaste AI Daily Intelligence Summary\n\n" +
                    "Our **SWI-Prolog Expert Engine** analyzed %d food items across your live MySQL inventory.\n\n" +
                    "**Key Findings:**\n" +
                    "- **High Waste Risk Items:** %d items\n" +
                    "- **Estimated Total Surplus:** %.1f kg\n\n" +
                    "**Partner Directory:** %s\n\n" +
                    "What specific item or operational action would you like to explore?",
                    inventory.size(), highCount, totalSurplus, partnerSummaryEn
            );
        }
    }
}
