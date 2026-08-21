/**
 * FoodWaste AI - Internationalization (i18n) Engine
 * Supports Seamless English (EN) & Professional Myanmar (MM) Switching
 * Includes Unit-Aware Aggregation formatting across all operational modules.
 */
const I18n = {
  currentLang: 'en',

  init() {
    const saved = localStorage.getItem('foodwaste_lang');
    if (saved === 'mm' || saved === 'my' || saved === 'en') {
      this.currentLang = (saved === 'my') ? 'mm' : saved;
    } else {
      this.currentLang = 'en';
    }

    this.renderLanguageSwitcher();
    this.applyTranslations();
  },

  getLanguage() {
    return this.currentLang;
  },

  isMyanmar() {
    return this.currentLang === 'mm' || this.currentLang === 'my';
  },

  setLanguage(lang) {
    if (lang !== 'en' && lang !== 'mm' && lang !== 'my') return;
    const normalized = (lang === 'my') ? 'mm' : lang;
    this.currentLang = normalized;
    localStorage.setItem('foodwaste_lang', normalized);
    this.applyTranslations();
    this.updateSwitcherUI();

    // Trigger custom event for dynamic controllers to re-render immediately
    window.dispatchEvent(new CustomEvent('languageChanged', { detail: { language: normalized } }));
  },

  toggleLanguage() {
    this.setLanguage(this.isMyanmar() ? 'en' : 'mm');
  },

  /**
   * Unit-Aware Aggregate Formatting Helper
   * Safely groups quantities by unit.
   * - Single unit: "4.0 liter", "3.0 kg", "12 pieces"
   * - Mixed units: "5.0 liter • 3.0 kg • 12 pieces" (never adds incompatible numbers together!)
   * - Empty: "0.0" or "0.0 <defaultUnit>"
   */
  formatUnitAggregate(items, getQuantity, getUnit, defaultUnit = '') {
    if (!items || items.length === 0) {
      return defaultUnit ? `0.0 ${defaultUnit}` : '0.0';
    }

    const totalsByUnit = {};
    items.forEach(item => {
      let rawQty = 0;
      if (getQuantity) {
        rawQty = getQuantity(item);
      } else if (item.quantity !== undefined) {
        rawQty = item.quantity;
      } else if (item.quantitySold !== undefined) {
        rawQty = item.quantitySold;
      } else if (item.quantityWasted !== undefined) {
        rawQty = item.quantityWasted;
      } else if (item.predictedWasteQuantity !== undefined) {
        rawQty = item.predictedWasteQuantity;
      } else if (item.predictedWasteQty !== undefined) {
        rawQty = item.predictedWasteQty;
      } else if (item.stock !== undefined) {
        rawQty = item.stock;
      }
      const qty = Number(rawQty) || 0;

      let rawUnit = '';
      if (getUnit) {
        rawUnit = getUnit(item);
      } else if (item.unit) {
        rawUnit = item.unit;
      } else if (item.foodUnit) {
        rawUnit = item.foodUnit;
      } else {
        rawUnit = defaultUnit || 'units';
      }
      const unit = (rawUnit && String(rawUnit).trim()) ? String(rawUnit).trim() : (defaultUnit || 'units');

      if (qty > 0 || Object.keys(totalsByUnit).length === 0) {
        totalsByUnit[unit] = (totalsByUnit[unit] || 0) + qty;
      }
    });

    const units = Object.keys(totalsByUnit);
    if (units.length === 0) {
      return defaultUnit ? `0.0 ${defaultUnit}` : '0.0';
    }

    const formatVal = (val, u) => {
      const num = Number(val) || 0;
      const lower = String(u).toLowerCase();
      if ((lower === 'pieces' || lower === 'piece' || lower === 'units') && num % 1 === 0) {
        return Math.round(num).toString();
      }
      return num.toFixed(1);
    };

    if (units.length === 1) {
      const u = units[0];
      const val = totalsByUnit[u];
      return `${formatVal(val, u)} ${u}`;
    }

    // Mixed units: Render compact unit-specific totals e.g. "5.0 liter • 3.0 kg • 12 pieces"
    return units.map(u => `${formatVal(totalsByUnit[u], u)} ${u}`).join(' • ');
  },

  /**
   * Resolves dynamic bilingual field from database objects
   * e.g. I18n.getDynamic(recommendation, 'title')
   */
  getDynamic(obj, field) {
    if (!obj) return '';
    const enVal = obj[field + '_en'] || obj[field + 'En'] || obj[field] || '';
    if (this.isMyanmar()) {
      const myVal = obj[field + '_my'] || obj[field + 'My'];
      if (myVal && myVal.trim() && /[\u1000-\u109F]/.test(myVal)) {
        return myVal.trim();
      }
      // If Myanmar field is missing or contains untranslated English, run client-side synthesis fallback
      if (enVal) {
        const translated = this.translateDynamicText(enVal);
        if (translated && translated !== enVal && /[\u1000-\u109F]/.test(translated)) {
          return translated;
        }
      }
      // Fallback to English text
      return enVal;
    }
    return enVal;
  },

  /**
   * Client-side offline fallback synthesizer for dynamic AI strings
   */
  translateDynamicText(text) {
    if (!text || typeof text !== 'string') return '';
    const trimmed = text.trim();
    const lower = trimmed.toLowerCase();

    // 1. Prolog Rules - preserve predicates verbatim
    if (trimmed.includes('assess_waste_risk') && trimmed.includes('Expired')) {
      return 'Prolog စည်းမျဉ်း: assess_waste_risk/6 (အန္တရာယ်မြင့်: သက်တမ်းကုန်) -> evaluate_priority_use/3 (စွန့်ပစ် သို့မဟုတ် မြေဆွေးပြုလုပ်ရန်)';
    }
    if (trimmed.includes('assess_waste_risk') && trimmed.includes('High Risk') && trimmed.includes('recommend_production')) {
      return 'Prolog စည်းမျဉ်း: assess_waste_risk/6 (အန္တရာယ်မြင့်) -> recommend_production/6 (ထုတ်လုပ်မှု ၁၅-၂၅% လျှော့ချပါ)';
    }
    if (trimmed.includes('assess_waste_risk') && (trimmed.includes('Medium Risk') || trimmed.includes('medium')) && trimmed.includes('evaluate_priority_use')) {
      return 'Prolog စည်းမျဉ်း: assess_waste_risk/6 (အလယ်အလတ်အန္တရာယ်) -> evaluate_priority_use/3 (ဦးစားပေးအဆင့်မြင့်)';
    }
    if (trimmed.includes('assess_waste_risk') && (trimmed.includes('Low Risk') || trimmed.includes('low')) && trimmed.includes('recommend_production')) {
      return 'Prolog စည်းမျဉ်း: assess_waste_risk/6 (အန္တရာယ်နည်း) -> recommend_production/6 (ပုံမှန် သတ်မှတ်ထားသော ထုတ်လုပ်မှုအတိုင်း ဆက်လက်ဆောင်ရွက်ပါ)';
    }
    if (trimmed.includes('evaluate_priority_use') && trimmed.includes('recommend_production')) {
      return 'Prolog စည်းမျဉ်း: evaluate_priority_use/3 -> recommend_production/6 (ချက်ချင်း ဦးစားပေး သုံးစွဲပြီး ထုတ်လုပ်မှု ၂၀% လျှော့ချပါ)';
    }
    if (trimmed.includes('evaluate_redistribution') && trimmed.includes('assess_waste_risk')) {
      return 'Prolog စည်းမျဉ်း: assess_waste_risk/6 -> evaluate_redistribution/6 (ပရဟိတ လှူဒါန်းရန် ပိုလျှံပစ္စည်းအဖြစ် အတည်ပြုသည်)';
    }
    if (trimmed.includes('evaluate_redistribution')) {
      return 'Prolog စည်းမျဉ်း: evaluate_redistribution/6 -> ပရဟိတ လှူဒါန်းရန် သင့်တော်သော ပိုလျှံပစ္စည်းအဖြစ် အတည်ပြုသည်';
    }
    if (trimmed.includes('evaluate_priority_use') && (trimmed.includes('clear inventory within 3 days') || trimmed.includes('3 days'))) {
      return 'Prolog စည်းမျဉ်း: evaluate_priority_use/3 -> ၃ ရက်အတွင်း ကုန်စင်စေရန် ဦးစားပေးအဆင့်မြင့် သုံးစွဲပါ';
    }
    if (trimmed.includes('evaluate_priority_use')) {
      return 'Prolog စည်းမျဉ်း: evaluate_priority_use/3 -> သက်တမ်းကုန်ဆုံးရက် နီးကပ်နေသဖြင့် ချက်ချင်း ဦးစားပေး သုံးစွဲရန် လိုအပ်သည်';
    }
    if (trimmed.includes('recommend_production') && trimmed.includes('10-15%')) {
      return 'Prolog စည်းမျဉ်း: recommend_production/6 (ထုတ်လုပ်မှု ၁၀-၁၅% အနည်းငယ် လျှော့ချပါ)';
    }

    // 2. Recommendation Titles
    if (lower.includes('halt production and dispose of expired') || lower.includes('halt production and dispose')) {
      const match = trimmed.match(/(?:dispose of expired|dispose of)\s+([A-Za-z0-9\s_-]+)$/i);
      const item = match ? match[1].trim() : 'ကုန်ပစ္စည်း';
      return `${item} သက်တမ်းကုန်ဆုံးသွားသဖြင့် ထုတ်လုပ်မှုရပ်ဆိုင်းပြီး ဘေးကင်းစွာ စွန့်ပစ်ပါ`;
    }
    if (lower.startsWith('reduce next production batch for') || (lower.includes('reduce') && lower.includes('production batch for'))) {
      const match = trimmed.match(/for\s+([A-Za-z0-9\s_-]+?)(?:\s+by\s+(\d+%?)|$)/i);
      const item = match ? match[1].trim() : 'ကုန်ပစ္စည်း';
      const pct = (match && match[2]) ? match[2] : '';
      return pct ? `${item} အတွက် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို ${pct} လျှော့ချပါ` : `${item} အတွက် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို လျှော့ချပါ`;
    }
    if (lower.includes('surplus') && lower.includes('for ') && lower.includes('near expiry')) {
      const mItem = trimmed.match(/for\s+([A-Za-z0-9\s_-]+?)(?:\s+near|\s+with|\s+to|$)/i);
      const mQty = trimmed.match(/\(([\d.]+)\s*([A-Za-z]+)?\)/);
      if (mItem && mQty) {
        const item = mItem[1].trim();
        const qty = mQty[1];
        const unit = mQty[2] || 'kg';
        return `${item} အတွက် သက်တမ်းကုန်ခါနီး ပိုလျှံလက်ကျန် (${qty} ${unit}) တွေ့ရှိရသဖြင့် အလေအလွင့် ကာကွယ်ရန် လိုအပ်ပါသည်`;
      }
    }
    if (lower.includes('redistribute excess inventory for') || (lower.includes('redistribute') && lower.includes('for '))) {
      const match = trimmed.match(/for\s+([A-Za-z0-9\s_-]+)$/i);
      const item = match ? match[1].trim() : 'ကုန်ပစ္စည်း';
      return `${item} ၏ ပိုလျှံလက်ကျန်ကို ပရဟိတသို့ လှူဒါန်းပါ`;
    }
    if (lower.includes('prioritize usage today for') || lower.includes('prioritize usage for')) {
      const match = trimmed.match(/for\s+([A-Za-z0-9\s_-]+)$/i);
      const item = match ? match[1].trim() : 'ကုန်ပစ္စည်း';
      return `${item} ကို ယနေ့ မီးဖိုချောင်တွင် ဦးစားပေး သုံးစွဲပါ`;
    }
    if (lower.startsWith('monitor stock for') || lower.startsWith('monitor stock levels for')) {
      const match = trimmed.match(/for\s+([A-Za-z0-9\s_-]+)$/i);
      const item = match ? match[1].trim() : 'ကုန်ပစ္စည်း';
      return `${item} ၏ ကုန်ပစ္စည်းလက်ကျန် အခြေအနေကို စောင့်ကြည့်ပါ`;
    }
    if (lower.startsWith('adjust preparation quantity for') || lower.startsWith('adjust kitchen batch for')) {
      const match = trimmed.match(/for\s+([A-Za-z0-9\s_-]+)$/i);
      const item = match ? match[1].trim() : 'ကုန်ပစ္စည်း';
      return `${item} အတွက် ပြင်ဆင်ချက်ပြုတ်မှု ပမာဏကို ညှိနှိုင်းပါ`;
    }
    if (lower.startsWith('promote usage for')) {
      const match = trimmed.match(/for\s+([A-Za-z0-9\s_-]+)$/i);
      const item = match ? match[1].trim() : 'ကုန်ပစ္စည်း';
      return `${item} ကို နေ့စဉ် အထူးဟင်းလျာများတွင် ထည့်သွင်း ရောင်းချပါ`;
    }
    if (lower.startsWith('maintain normal operation for')) {
      const match = trimmed.match(/for\s+([A-Za-z0-9\s_-]+)$/i);
      const item = match ? match[1].trim() : 'ကုန်ပစ္စည်း';
      return `${item} အတွက် ပုံမှန် ထုတ်လုပ်မှု အစီအစဉ်အတိုင်း ဆက်လက်ဆောင်ရွက်ပါ`;
    }

    // 3. Recommendation Descriptions
    if (lower.contains ? lower.contains('passed expiration date') : lower.includes('passed expiration date')) {
      const mDays = trimmed.match(/(\d+)\s*day/i);
      const days = mDays ? mDays[1] : '၁';
      return `ကုန်ပစ္စည်းသည် သက်တမ်းကုန်ဆုံးသွားပါပြီ (လွန်ခဲ့သော ${days} ရက်က)။ ဧည့်သည်များထံ မကျွေးမွေးပါနှင့်။ ထုတ်လုပ်မှု ရပ်ဆိုင်းပြီး ဘေးကင်းစွာ စွန့်ပစ်ပါ သို့မဟုတ် မြေဆွေးပြုလုပ်ပါ။`;
    }
    if (lower.includes('stock is') && lower.includes('expected demand') && (lower.includes('reduce') || lower.includes('production'))) {
      const m = trimmed.match(/stock\s+is\s+([\d.]+)\s*([a-zA-Z]+)?\s+against\s+([\d.]+)\s*([a-zA-Z]+)?\s+expected\s+demand\s+with\s+(\d+)-day\s+expiry/i);
      if (m) {
        const stock = m[1];
        const unit1 = m[2] || 'kg';
        const demand = m[3];
        const unit2 = m[4] || unit1;
        const days = m[5];
        return `လက်ကျန် ${stock} ${unit1} ရှိပြီး ခန့်မှန်းဝယ်လိုအား ${demand} ${unit2} သာရှိကာ သက်တမ်းကုန်ဆုံးရန် ${days} ရက်သာ ကျန်ရှိပါသည်။ အလေအလွင့် ဆုံးရှုံးမှု ကာကွယ်ရန် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို ၁၅-၂၅% လျှော့ချပါ။`;
      }
      return 'လက်ကျန်ပမာဏသည် ခန့်မှန်းဝယ်လိုအားထက် ပိုလျှံနေပြီး သက်တမ်းကုန်ဆုံးရက် နီးကပ်နေသဖြင့် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို လျှော့ချပါ။';
    }
    if ((lower.includes('surplus stock') || lower.includes('surplus')) && (lower.includes('food bank') || lower.includes('charity partner') || lower.includes('dispatch'))) {
      const m = trimmed.match(/surplus\s+stock\s*\(?([\d.]+)\s*([a-zA-Z]+)?\)?/i);
      if (m) {
        const qty = m[1];
        const unit = m[2] || 'kg';
        return `သက်တမ်းကုန်ခါနီး ပိုလျှံလက်ကျန် (${qty} ${unit}) တွေ့ရှိရပါသည်။ သက်တမ်းမကုန်မီ မှတ်ပုံတင်ထားသော အစားအစာဘဏ် သို့မဟုတ် ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်းသို့ ပို့ဆောင်လှူဒါန်းပါ။`;
      }
      return 'သက်တမ်းမကုန်မီ ပိုလျှံနေသော အစားအစာများကို မှတ်ပုံတင်ထားသော ပရဟိတ အဖွဲ့အစည်း သို့မဟုတ် အစားအစာဘဏ်သို့ ပို့ဆောင်လှူဒါန်းပါ။';
    }
    if (lower.includes('prioritize in today\'s menu specials') || (lower.includes('prioritize') && lower.includes('today') && lower.includes('consumption'))) {
      const m = trimmed.match(/expires\s+(?:in\s+)?(\d+)\s*day/i);
      const days = m ? m[1] : '၀';
      return `ကုန်ပစ္စည်းသည် ${days} ရက်အတွင်း သက်တမ်းကုန်ဆုံးပါမည်။ ယနေ့ မီနူးအထူးဟင်းလျာများ၊ ချက်ပြုတ်ပြင်ဆင်မှုနှင့် မီးဖိုချောင်တွင် ချက်ချင်း ဦးစားပေး သုံးစွဲပါ။`;
    }
    if (lower.includes('monitor stock velocity and turnover') || (lower.includes('monitor stock') && lower.includes('accumulation'))) {
      const m = trimmed.match(/expires\s+in\s+(\d+)\s*days?/i);
      const days = m ? m[1] : '၂';
      return `ကုန်ပစ္စည်းသည် ${days} ရက်အတွင်း သက်တမ်းကုန်ဆုံးပါမည်။ ပိုလျှံမှု မဖြစ်ပေါ်စေရန် ကုန်ပစ္စည်းလက်ကျန်နှင့် သုံးစွဲမှုနှုန်းကို အနီးကပ် စောင့်ကြည့်ပါ။`;
    }
    if (lower.includes('adjust kitchen batch preparation down') || (lower.includes('moderate waste risk detected') && lower.includes('adjust'))) {
      const m = trimmed.match(/expected\s+demand\s*\(?([\d.]+)\s*([a-zA-Z]+)?\)?/i);
      if (m) {
        const demand = m[1];
        const unit = m[2] || 'kg';
        return `အလယ်အလတ် အလေအလွင့် ဖြစ်နိုင်ခြေ ရှိနေပါသည်။ ခန့်မှန်းဝယ်လိုအား (${demand} ${unit}) အရ မီးဖိုချောင် ပြင်ဆင်ချက်ပြုတ်မှု ပမာဏကို ၁၀-၁၅% လျှော့ချ ညှိနှိုင်းပါ။`;
      }
      return 'အလေအလွင့် ဖြစ်နိုင်ခြေ လျှော့ချရန် မီးဖိုချောင် ပြင်ဆင်ချက်ပြုတ်မှု ပမာဏကို ၁၀-၁၅% လျှော့ချ ညှိနှိုင်းပါ။';
    }
    if (lower.includes('feature in chef\'s daily side dish') || (lower.includes('lunch specials') && lower.includes('drawdown'))) {
      const m = trimmed.match(/within\s+(\d+)\s*days?/i);
      const days = m ? m[1] : '၂';
      return `ကုန်ပစ္စည်း ${days} ရက်အတွင်း လျင်မြန်စွာ ကုန်စင်စေရန် စားဖိုမှူး၏ နေ့စဉ် အထူးဟင်းလျာ၊ တွဲဖက်ရောင်းချမှု သို့မဟုတ် နေ့လယ်စာ ပရိုမိုးရှင်းများတွင် ထည့်သွင်း ရောင်းချပါ။`;
    }
    if (lower.includes('safe shelf-life remaining') || lower.includes('maintain standard scheduled production batch')) {
      const m = trimmed.match(/remaining\s*\(?(\d+)\s*days?\)?/i);
      const days = m ? m[1] : '၅';
      return `လုံလောက်သော သက်တမ်းကျန်ရှိပြီး (${days} ရက်) လက်ကျန်ပမာဏ မျှတနေပါသဖြင့် ပုံမှန် သတ်မှတ်ထားသော ထုတ်လုပ်မှုနှင့် ပစ္စည်းဖြည့်တင်းမှု အစီအစဉ်အတိုင်း ဆက်လက် ဆောင်ရွက်ပါ။`;
    }

    // 4. Prolog Reasons
    if (lower.includes('reached or passed expiration date') || lower.includes('passed expiration date') || lower.includes('do not serve')) {
      return 'ကုန်ပစ္စည်းသည် သက်တမ်းကုန်ဆုံးသွားပါပြီ။ ဧည့်သည်များထံ မကျွေးမွေးပါနှင့်။';
    }
    if (lower.includes('expires today') || lower.includes('product expires today')) {
      return 'ကုန်ပစ္စည်းသည် ယနေ့ သက်တမ်းကုန်ဆုံးပါမည်။ ချက်ချင်း စားသုံးရန် သို့မဟုတ် အရေးယူဆောင်ရွက်ရန် လိုအပ်ပါသည်။';
    }
    if (lower.includes('expires within 24 hours') || lower.includes('within 24 hours')) {
      return 'ကုန်ပစ္စည်းသည် ၂၄ နာရီအတွင်း သက်တမ်းကုန်ဆုံးပါမည်။ ချက်ချင်း အရေးယူဆောင်ရွက်ရန် လိုအပ်ပါသည်။';
    }
    if (lower.includes('expires within 2-3 days') || lower.includes('within 2-3 days')) {
      return 'ကုန်ပစ္စည်းသည် ၂-၃ ရက်အတွင်း သက်တမ်းကုန်ဆုံးပါမည်။ သုံးစွဲမှုနှုန်းကို အနီးကပ် စောင့်ကြည့်ပါ။';
    }
    if (lower.includes('significantly exceeds expected demand') || lower.includes('significantly exceeds')) {
      return 'ကုန်ပစ္စည်းလက်ကျန်သည် ခန့်မှန်းဝယ်လိုအားထက် သိသာစွာ ပိုလျှံနေပါသည်';
    }
    if (lower.includes('moderately exceeds')) {
      return 'လက်ရှိကုန်ပစ္စည်းလက်ကျန်သည် ခန့်မှန်းဝယ်လိုအားထက် အသင့်အတင့် ပိုလျှံနေပါသည်';
    }
    if (lower.includes('short remaining shelf life')) {
      return 'ကျန်ရှိသော သက်တမ်း နည်းပါးနေပါသည် (၃ ရက် သို့မဟုတ် ၃ ရက်အောက်)';
    }
    if (lower.includes('high historical waste rate')) {
      return 'အတိတ်ကာလ အလေအလွင့်ဖြစ်ပွားမှုနှုန်း မြင့်မားခဲ့ပါသည်';
    }
    if (lower.includes('critical (>=') || lower.includes('historical waste rate is critical')) {
      return 'အတိတ်ကာလ အလေအလွင့်ဖြစ်ပွားမှုနှုန်း အလွန်မြင့်မားပါသည် (၃၀% နှင့်အထက်)။ လက်ကျန်ပမာဏသည် ဝယ်လိုအားထက် ပိုလျှံနေပါသည်။';
    }
    if (lower.includes('safe shelf life remaining') || (lower.includes('safe shelf life') && lower.includes('balanced'))) {
      return 'လုံလောက်သော သက်တမ်းကျန်ရှိပြီး (> ၃ ရက်) လက်ကျန်ပမာဏနှင့် ဝယ်လိုအား မျှတနေပါသည်';
    }

    return trimmed;
  },

  translateRisk(risk) {
    if (!risk) return '';
    const upper = String(risk).toUpperCase();
    if (this.isMyanmar()) {
      if (upper === 'HIGH') return 'အန္တရာယ်မြင့်';
      if (upper === 'MEDIUM' || upper === 'MED') return 'အလယ်အလတ်အန္တရာယ်';
      if (upper === 'LOW') return 'အန္တရာယ်နည်း';
    }
    if (upper === 'HIGH') return 'HIGH RISK';
    if (upper === 'MEDIUM' || upper === 'MED') return 'MEDIUM RISK';
    if (upper === 'LOW') return 'LOW RISK';
    return upper;
  },

  translateCategory(cat) {
    if (!cat) return '';
    const upper = String(cat).toUpperCase();
    if (this.isMyanmar()) {
      if (upper === 'URGENT') return 'အရေးပေါ်';
      if (upper === 'IMPORTANT') return 'အရေးကြီး';
      if (upper === 'OPTIMIZATION') return 'စီမံညှိနှိုင်းမှု';
      if (upper === 'REDISTRIBUTION') return 'ပိုလျှံအစားအစာ ပြန်လည်ဖြန့်ဝေမှု';
    }
    if (upper === 'URGENT') return 'URGENT';
    if (upper === 'IMPORTANT') return 'IMPORTANT';
    if (upper === 'OPTIMIZATION') return 'OPTIMIZATION';
    if (upper === 'REDISTRIBUTION') return 'REDISTRIBUTION';
    return upper;
  },

  translateStatus(status) {
    if (!status) return '';
    const upper = String(status).toUpperCase();
    if (this.isMyanmar()) {
      if (upper === 'OK' || upper === 'SAFE') return 'ပုံမှန်ကောင်းမွန်';
      if (upper === 'NEAR_EXPIRY' || upper === 'NEAREXPIRY') return 'သက်တမ်းကုန်ရန်နီး';
      if (upper === 'SAME_DAY_EXPIRY' || upper === 'SAMEDAYEXPIRY') return 'ယနေ့သက်တမ်းကုန်';
      if (upper === 'EXPIRED') return 'သက်တမ်းကုန်ပြီး';
      if (upper === 'LOW_STOCK' || upper === 'LOWSTOCK') return 'လက်ကျန်နည်း';
      if (upper === 'PENDING') return 'စောင့်ဆိုင်းဆဲ';
      if (upper === 'CONFIRMED') return 'အတည်ပြုပြီး';
      if (upper === 'COLLECTED') return 'လက်ခံရယူပြီး';
      if (upper === 'COMPLETED') return 'ပြီးစီး';
      if (upper === 'ACCEPTED') return 'လက်ခံပြီး';
      if (upper === 'DISMISSED') return 'ပယ်ဖျက်ပြီး';
      if (upper === 'CANCELLED') return 'ပယ်ဖျက်ပြီး';
      if (upper === 'ACTIVE') return 'အသုံးပြုဆဲ';
      if (upper === 'INACTIVE') return 'ပိတ်ထားသည်';
    }
    if (upper === 'OK' || upper === 'SAFE') return 'OK';
    if (upper === 'NEAR_EXPIRY' || upper === 'NEAREXPIRY') return 'NEAR EXPIRY';
    if (upper === 'SAME_DAY_EXPIRY' || upper === 'SAMEDAYEXPIRY') return 'SAME DAY EXPIRY';
    if (upper === 'EXPIRED') return 'EXPIRED';
    if (upper === 'LOW_STOCK' || upper === 'LOWSTOCK') return 'LOW STOCK';
    if (upper === 'PENDING') return 'PENDING';
    if (upper === 'CONFIRMED') return 'CONFIRMED';
    if (upper === 'COLLECTED') return 'COLLECTED';
    if (upper === 'COMPLETED') return 'COMPLETED';
    if (upper === 'ACCEPTED') return 'ACCEPTED';
    if (upper === 'DISMISSED') return 'DISMISSED';
    if (upper === 'CANCELLED') return 'CANCELLED';
    if (upper === 'ACTIVE') return 'ACTIVE';
    if (upper === 'INACTIVE') return 'INACTIVE';
    return upper;
  },

  translatePriority(priority) {
    if (!priority) return '';
    const upper = String(priority).toUpperCase();
    if (this.isMyanmar()) {
      if (upper === 'IMMEDIATE_USE' || upper === 'IMMEDIATE') return 'ချက်ချင်းအသုံးပြုရန်';
      if (upper === 'HIGH_PRIORITY' || upper === 'HIGH') return 'ဦးစားပေးအဆင့်မြင့်';
      if (upper === 'MODERATE_PRIORITY' || upper === 'MODERATE' || upper === 'MEDIUM') return 'အလယ်အလတ် ဦးစားပေး';
      if (upper === 'STANDARD' || upper === 'NORMAL') return 'ပုံမှန်အဆင့်';
      if (upper === 'DISPOSE_OR_COMPOST' || upper === 'COMPOST') return 'စွန့်ပစ် သို့မဟုတ် မြေဆွေးပြုလုပ်ရန်';
    }
    if (upper === 'IMMEDIATE_USE' || upper === 'IMMEDIATE') return 'IMMEDIATE USE';
    if (upper === 'HIGH_PRIORITY' || upper === 'HIGH') return 'HIGH PRIORITY';
    if (upper === 'MODERATE_PRIORITY' || upper === 'MODERATE' || upper === 'MEDIUM') return 'MODERATE PRIORITY';
    if (upper === 'STANDARD' || upper === 'NORMAL') return 'STANDARD';
    if (upper === 'DISPOSE_OR_COMPOST' || upper === 'COMPOST') return 'DISPOSE / COMPOST';
    return upper;
  },

  translateWasteReason(reason) {
    if (!reason) return '';
    const upper = String(reason).toUpperCase();
    if (this.isMyanmar()) {
      if (upper === 'OVERPRODUCTION') return 'ပိုလျှံထုတ်လုပ်မှု';
      if (upper === 'EXPIRED') return 'သက်တမ်းကုန်ဆုံးခြင်း';
      if (upper === 'UNSOLD') return 'ညနေခင်း ရောင်းမကုန်သော ပစ္စည်း';
      if (upper === 'SPOILED') return 'သိုလှောင်မှု ချွတ်ယွင်းပျက်စီးခြင်း';
      if (upper === 'DAMAGED') return 'ကိုင်တွယ်စဉ် ထိခိုက်ပျက်စီးခြင်း';
      if (upper === 'PREPARATION_WASTE') return 'ချက်ပြုတ်ပြင်ဆင်မှု အလေအလွင့်';
      if (upper === 'OTHER') return 'အခြားအကြောင်းရင်း';
    }
    if (upper === 'OVERPRODUCTION') return 'Overproduction';
    if (upper === 'EXPIRED') return 'Expired Shelf Life';
    if (upper === 'UNSOLD') return 'Unsold Surplus';
    if (upper === 'SPOILED') return 'Spoiled Storage';
    if (upper === 'DAMAGED') return 'Handling Damage';
    if (upper === 'PREPARATION_WASTE') return 'Preparation Trimming';
    if (upper === 'OTHER') return 'Other Reason';
    return reason;
  },

  translateFoodCategory(category) {
    if (!category) return '';
    const upper = String(category).toUpperCase();
    if (this.isMyanmar()) {
      if (upper === 'POULTRY') return 'ကြက်/ဘဲ/ငှက် အသား';
      if (upper === 'PRODUCE') return 'ဟင်းသီးဟင်းရွက်နှင့် သစ်သီးဝလံ';
      if (upper === 'SEAFOOD') return 'ပင်လယ်စာ';
      if (upper === 'GRAINS') return 'ဂျုံနှင့် နှံစားသီးနှံ';
      if (upper === 'DAIRY') return 'နို့နှင့် နို့ထွက်ပစ္စည်း';
      if (upper === 'BAKERY') return 'မုန့်ဖုတ်ထုတ်ကုန်';
      if (upper === 'MEAT') return 'အသား';
    }
    return category;
  },

  translateDay(day) {
    if (!day) return '';
    const str = String(day).trim();
    if (this.isMyanmar()) {
      if (str === 'Monday' || str.startsWith('Mon')) return str.includes('Today') ? 'တနင်္လာ (ယနေ့)' : 'တနင်္လာ';
      if (str === 'Tuesday' || str.startsWith('Tue')) return str.includes('အင်္ဂါ') ? 'အင်္ဂါ (ယနေ့)' : 'အင်္ဂါ';
      if (str === 'Wednesday' || str.startsWith('Wed')) return str.includes('Today') ? 'ဗုဒ္ဓဟူး (ယနေ့)' : 'ဗုဒ္ဓဟူး';
      if (str === 'Thursday' || str.startsWith('Thu')) return str.includes('Today') ? 'ကြာသပတေး (ယနေ့)' : 'ကြာသပတေး';
      if (str === 'Friday' || str.startsWith('Fri')) return str.includes('Today') ? 'သောကြာ (ယနေ့)' : 'သောကြာ';
      if (str === 'Saturday' || str.startsWith('Sat')) return str.includes('Today') ? 'စနေ (ယနေ့)' : 'စနေ';
      if (str === 'Sunday' || str.startsWith('Sun')) return str.includes('Today') ? 'တနင်္ဂနွေ (ယနေ့)' : 'တနင်္ဂနွေ';
      if (str.toLowerCase().includes('tomorrow') || str.toLowerCase().includes('predict')) return 'မနက်ဖြန် (ခန့်မှန်း)';
    } else {
      if (str === 'Monday' || str.startsWith('Mon')) return str.includes('Today') ? 'Mon (Today)' : 'Mon';
      if (str === 'Tuesday' || str.startsWith('Tue')) return str.includes('Today') ? 'Tue (Today)' : 'Tue';
      if (str === 'Wednesday' || str.startsWith('Wed')) return str.includes('Today') ? 'Wed (Today)' : 'Wed';
      if (str === 'Thursday' || str.startsWith('Thu')) return str.includes('Today') ? 'Thu (Today)' : 'Thu';
      if (str === 'Friday' || str.startsWith('Fri')) return str.includes('Today') ? 'Fri (Today)' : 'Fri';
      if (str === 'Saturday' || str.startsWith('Sat')) return str.includes('Today') ? 'Sat (Today)' : 'Sat';
      if (str === 'Sunday' || str.startsWith('Sun')) return str.includes('Today') ? 'Sun (Today)' : 'Sun';
      if (str.toLowerCase().includes('tomorrow') || str.toLowerCase().includes('predict')) return 'Tomorrow (AI)';
    }
    return str;
  },

  translateError(err) {
    if (!err) return 'An error occurred';
    const msg = typeof err === 'string' ? err : (err.message || err.error || JSON.stringify(err));
    if (this.isMyanmar()) {
      if (msg.includes('Insufficient stock') || msg.includes('insufficient stock')) return 'ပစ္စည်းလက်ကျန် မလုံလောက်ပါ။ ' + msg;
      if (msg.includes('Cannot record sale for expired') || msg.includes('expired food item')) return 'သက်တမ်းကုန်ဆုံးသွားသော ကုန်ပစ္စည်းကို ရောင်းချ၍မရပါ';
      if (msg.includes('Recipient not found') || msg.includes('inactive')) return 'ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်းကို ရှာမတွေ့ပါ သို့မဟုတ် ပိတ်ထားပါသည်';
      if (msg.includes('Food item not found')) return 'ကုန်ပစ္စည်းကို ရှာမတွေ့ပါ';
      if (msg.includes('greater than zero') || msg.includes('greater than 0')) return 'ပမာဏသည် သုညထက် ကြီးရပါမည်';
      if (msg.includes('Invalid credentials')) return 'အသုံးပြုသူအမည် သို့မဟုတ် လျှို့ဝှက်နံပါတ် မှားယွင်းနေပါသည်';
      if (msg.includes('Unauthorized') || msg.includes('unauthorized')) return 'ဝင်ရောက်ခွင့် မရှိပါ';
      if (msg.includes('Recipient ID is required')) return 'ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်း ID လိုအပ်ပါသည်';
      if (msg.includes('Food item ID is required')) return 'ကုန်ပစ္စည်း ID လိုအပ်ပါသည်';
      if (msg.includes('Network') || msg.includes('Failed to fetch')) return 'ကွန်ရက် ချိတ်ဆက်မှု မအောင်မြင်ပါ';
    }
    return msg;
  },

  t(key, defaultText = '') {
    if (!key) return defaultText || '';
    const normKey = String(key).trim();
    const lowerKey = normKey.toLowerCase();
    const upperKey = normKey.toUpperCase();

    const dict = this.isMyanmar() ? (window.I18N_MM || {}) : (window.I18N_EN || {});
    const enDict = window.I18N_EN || {};

    // 1. Check current language dictionary (exact, lower, upper)
    if (dict[normKey] !== undefined) return dict[normKey];
    if (dict[lowerKey] !== undefined) return dict[lowerKey];
    if (dict[upperKey] !== undefined) return dict[upperKey];

    // 2. Fallback to English dictionary (exact, lower, upper)
    if (enDict[normKey] !== undefined) return enDict[normKey];
    if (enDict[lowerKey] !== undefined) return enDict[lowerKey];
    if (enDict[upperKey] !== undefined) return enDict[upperKey];

    // 3. Fallback to defaultText if provided and valid
    if (defaultText && defaultText !== normKey && defaultText !== lowerKey && defaultText !== upperKey) {
      return defaultText;
    }

    // 4. Safe humanized fallback - NEVER leak raw dot-separated or uppercase key names
    const lastPart = normKey.includes('.') ? normKey.split('.').pop() : normKey;
    const humanized = lastPart.replace(/([A-Z])/g, ' $1').replace(/[_-]/g, ' ').trim();
    return humanized.charAt(0).toUpperCase() + humanized.slice(1);
  },

  applyTranslations() {
    // 1. Translate elements with data-i18n attribute
    document.querySelectorAll('[data-i18n]').forEach(el => {
      const key = el.getAttribute('data-i18n');
      if (key) {
        if (!el.hasAttribute('data-i18n-default')) {
          const original = el.textContent ? el.textContent.trim() : '';
          if (original && original !== key) {
            el.setAttribute('data-i18n-default', original);
          }
        }
        const fallback = el.getAttribute('data-i18n-default') || '';
        const translation = this.t(key, fallback);
        if (translation) {
          el.textContent = translation;
        }
      }
    });

    // 2. Translate placeholders
    document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
      const key = el.getAttribute('data-i18n-placeholder');
      if (key) {
        if (!el.hasAttribute('data-i18n-placeholder-default')) {
          const original = el.getAttribute('placeholder') || '';
          if (original && original !== key) {
            el.setAttribute('data-i18n-placeholder-default', original);
          }
        }
        const fallback = el.getAttribute('data-i18n-placeholder-default') || '';
        const translation = this.t(key, fallback);
        if (translation) {
          el.setAttribute('placeholder', translation);
        }
      }
    });

    // 3. Translate tooltips / titles
    document.querySelectorAll('[data-i18n-title]').forEach(el => {
      const key = el.getAttribute('data-i18n-title');
      if (key) {
        if (!el.hasAttribute('data-i18n-title-default')) {
          const original = el.getAttribute('title') || '';
          if (original && original !== key) {
            el.setAttribute('data-i18n-title-default', original);
          }
        }
        const fallback = el.getAttribute('data-i18n-title-default') || '';
        const translation = this.t(key, fallback);
        if (translation) {
          el.setAttribute('title', translation);
        }
      }
    });

    // 4. Update dynamic document title if specified
    const pageTitleKey = document.body ? document.body.getAttribute('data-i18n-page-title') : null;
    if (pageTitleKey) {
      document.title = "FoodWaste AI - " + this.t(pageTitleKey);
    }
  },

  renderLanguageSwitcher() {
    // Look for dedicated container or inject into topbar-right / login-box
    let container = document.getElementById('language-switcher-container');

    if (!container) {
      const topbarRight = document.querySelector('.topbar-right');
      if (topbarRight) {
        container = document.createElement('div');
        container.id = 'language-switcher-container';
        topbarRight.insertBefore(container, topbarRight.firstChild);
      } else {
        const loginCard = document.getElementById('login-lang-container') || document.querySelector('.brand-hero-box');
        if (loginCard) {
          container = document.createElement('div');
          container.id = 'language-switcher-container';
          container.style.display = 'flex';
          container.style.justifyContent = 'center';
          container.style.marginBottom = '1rem';
          loginCard.appendChild(container);
        }
      }
    }

    if (container) {
      const isEn = this.currentLang === 'en';
      const isMm = this.isMyanmar();
      container.innerHTML = `
        <div class="lang-switch-bubble">
          <button type="button" id="lang-btn-en" onclick="I18n.setLanguage('en')" class="lang-btn ${isEn ? 'active' : ''}">
            🌐 English
          </button>
          <button type="button" id="lang-btn-mm" onclick="I18n.setLanguage('mm')" class="lang-btn ${isMm ? 'active' : ''}">
            မြန်မာ
          </button>
        </div>
      `;
    }
  },

  updateSwitcherUI() {
    const btnEn = document.getElementById('lang-btn-en');
    const btnMm = document.getElementById('lang-btn-mm');

    if (btnEn && btnMm) {
      if (this.currentLang === 'en') {
        btnEn.classList.add('active');
        btnMm.classList.remove('active');
      } else {
        btnMm.classList.add('active');
        btnEn.classList.remove('active');
      }
    }
  }
};

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => {
    I18n.init();
  });
} else {
  I18n.init();
}
