package com.foodwasteai;

import com.foodwasteai.model.PredictionItem;
import com.foodwasteai.model.Recommendation;
import com.foodwasteai.model.Redistribution;
import com.foodwasteai.service.RecommendationService;
import com.foodwasteai.service.TranslationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for bilingual field storage, retrieval, and localized getter resolution
 * across PredictionItem, Recommendation, and Redistribution models.
 */
public class BilingualPersistenceTest {

    @Test
    @DisplayName("PredictionItem localized reasoning resolution")
    void testPredictionItemLocalization() {
        PredictionItem item = new PredictionItem();
        item.setReasoningText("Default reasoning");
        item.setReasoningTextEn("High waste probability due to short expiry");
        item.setReasoningTextMy("သက်တမ်းကုန်ဆုံးရန် နီးကပ်နေသောကြောင့် အလေအလွင့် ဖြစ်နိုင်ခြေ မြင့်မားပါသည်");

        assertEquals("High waste probability due to short expiry", item.getReasoningText("en"));
        assertEquals("သက်တမ်းကုန်ဆုံးရန် နီးကပ်နေသောကြောင့် အလေအလွင့် ဖြစ်နိုင်ခြေ မြင့်မားပါသည်", item.getReasoningText("mm"));
        assertEquals("သက်တမ်းကုန်ဆုံးရန် နီးကပ်နေသောကြောင့် အလေအလွင့် ဖြစ်နိုင်ခြေ မြင့်မားပါသည်", item.getReasoningText("my"));
        assertEquals("High waste probability due to short expiry", item.getReasoningText("fr")); // Fallback to EN
    }

    @Test
    @DisplayName("Recommendation localized getters and automatic fallback")
    void testRecommendationLocalization() {
        Recommendation rec = new Recommendation();
        rec.setTitle("Reduce production");
        rec.setTitleEn("Reduce next production batch for Fresh Chicken Breast");
        rec.setTitleMy("Fresh Chicken Breast အတွက် နောက်တစ်ကြိမ် ထုတ်လုပ်မှု ပမာဏကို လျှော့ချပါ");

        rec.setDescription("Reduce batch by 20%");
        rec.setDescriptionEn("Reduce batch by 20% to prevent spoilage");
        rec.setDescriptionMy("ပုပ်သိုးဆုံးရှုံးမှုကို ကာကွယ်ရန် ထုတ်လုပ်မှု ပမာဏကို ၂၀% လျှော့ချပါ");

        rec.setReasoningDetails("Prolog assess_waste_risk/6");
        rec.setReasoningDetailsEn("Prolog Rule: assess_waste_risk/6 (High Risk)");
        rec.setReasoningDetailsMy("Prolog စည်းမျဉ်း: assess_waste_risk/6 (အန္တရာယ်မြင့်)");

        // English getters
        assertEquals("Reduce next production batch for Fresh Chicken Breast", rec.getTitle("en"));
        assertEquals("Reduce batch by 20% to prevent spoilage", rec.getDescription("en"));
        assertEquals("Prolog Rule: assess_waste_risk/6 (High Risk)", rec.getReasoningDetails("en"));

        // Myanmar getters
        assertEquals("Fresh Chicken Breast အတွက် နောက်တစ်ကြိမ် ထုတ်လုပ်မှု ပမာဏကို လျှော့ချပါ", rec.getTitle("mm"));
        assertEquals("ပုပ်သိုးဆုံးရှုံးမှုကို ကာကွယ်ရန် ထုတ်လုပ်မှု ပမာဏကို ၂၀% လျှော့ချပါ", rec.getDescription("mm"));
        assertEquals("Prolog စည်းမျဉ်း: assess_waste_risk/6 (အန္တရာယ်မြင့်)", rec.getReasoningDetails("my"));

        // Fallback when myanmar is missing
        Recommendation incompleteRec = new Recommendation();
        incompleteRec.setTitle("Maintain operations");
        incompleteRec.setTitleEn("Maintain normal operations");
        assertEquals("Maintain normal operations", incompleteRec.getTitle("mm"));
    }

    @Test
    @DisplayName("Redistribution localized notes getter and fallback")
    void testRedistributionLocalization() {
        Redistribution red = new Redistribution();
        red.setNotes("Scheduled donation");
        red.setNotesEn("Surplus food donation to Yangon Food Bank");
        red.setNotesMy("Yangon Food Bank သို့ ပိုလျှံအစားအစာ လှူဒါန်းမှု အချိန်ဇယား");

        assertEquals("Surplus food donation to Yangon Food Bank", red.getNotes("en"));
        assertEquals("Yangon Food Bank သို့ ပိုလျှံအစားအစာ လှူဒါန်းမှု အချိန်ဇယား", red.getNotes("mm"));
        assertEquals("Yangon Food Bank သို့ ပိုလျှံအစားအစာ လှူဒါန်းမှု အချိန်ဇယား", red.getNotes("my"));

        // Fallback test
        Redistribution fallbackRed = new Redistribution();
        fallbackRed.setNotes("Donation notes");
        assertEquals("Donation notes", fallbackRed.getNotes("mm"));
    }
}
