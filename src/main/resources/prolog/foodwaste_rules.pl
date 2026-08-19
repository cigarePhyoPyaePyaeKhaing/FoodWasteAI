% =====================================================================
% FoodWaste AI - SWI-Prolog Expert System Knowledge Base
% Explainable AI Decision Engine for Food Waste Prediction, Prevention & Redistribution
% =====================================================================

:- module(foodwaste_rules, [
    assess_item/11,
    assess_waste_risk/6,
    recommend_production/6,
    evaluate_priority_use/3,
    evaluate_redistribution/6
]).

% ---------------------------------------------------------------------
% MAIN ASSESSMENT PREDICATE
% assess_item(+Stock, +ExpectedDemand, +ExpiryDays, +HistWasteRate, +CurrentProduction, -RiskLevel, -Reasons, -RecProduction, -RecAction, -Priority, -Redistribute)
% ---------------------------------------------------------------------
assess_item(Stock, ExpectedDemand, ExpiryDays, HistWasteRate, CurrentProduction, RiskLevel, Reasons, RecProduction, RecAction, Priority, Redistribute) :-
    assess_waste_risk(Stock, ExpectedDemand, ExpiryDays, HistWasteRate, RiskLevel, Reasons),
    recommend_production(Stock, ExpectedDemand, CurrentProduction, RiskLevel, RecProduction, RecAction),
    evaluate_priority_use(ExpiryDays, RiskLevel, Priority),
    Surplus is max(0, Stock - ExpectedDemand),
    evaluate_redistribution(Stock, ExpectedDemand, ExpiryDays, Surplus, Redistribute, _).

% ---------------------------------------------------------------------
% 1. WASTE RISK ASSESSMENT RULES
% Risk Levels: high, medium, low
% PRIORITY: Expiry date strictly prioritizes over quantity.
% ---------------------------------------------------------------------

% High Risk Rule 1: Item is already expired (<= 0 days)
assess_waste_risk(Stock, _, ExpiryDays, _, high, ['Item has reached or passed expiration date. Do not serve to customers.']) :-
    ExpiryDays =< 0,
    Stock > 0,
    !.

% High Risk Rule 2: Expiry is today or tomorrow (<= 1 day) - HIGHEST PRIORITY
assess_waste_risk(Stock, _, ExpiryDays, _, high, ['Product expires within 24 hours. Immediate action recommended.']) :-
    ExpiryDays =< 1,
    Stock > 0,
    !.

% High Risk Rule 3: Heavy Overstock (Stock >= 150% of demand) with Near Expiry (<= 3 days) or Elevated Waste (>= 20%)
assess_waste_risk(Stock, ExpectedDemand, ExpiryDays, HistWasteRate, high, Reasons) :-
    ExpectedDemand > 0,
    Ratio is Stock / ExpectedDemand,
    Ratio >= 1.50,
    (ExpiryDays =< 3 ; HistWasteRate >= 0.20),
    build_high_reasons(Ratio, ExpiryDays, HistWasteRate, Reasons),
    !.

% High Risk Rule 4: Moderate-to-Heavy Overstock (>= 130% of demand) with 2-day expiry or High Waste (>= 25%)
assess_waste_risk(Stock, ExpectedDemand, ExpiryDays, HistWasteRate, high, Reasons) :-
    ExpectedDemand > 0,
    Ratio is Stock / ExpectedDemand,
    Ratio >= 1.30,
    (ExpiryDays =< 2 ; HistWasteRate >= 0.25),
    build_high_reasons(Ratio, ExpiryDays, HistWasteRate, Reasons),
    !.

% High Risk Rule 5: Critical historical waste pattern (>= 30%) and stock exceeds demand
assess_waste_risk(Stock, ExpectedDemand, _, HistWasteRate, high, ['Historical waste rate is critical (>= 30%). Stock exceeds expected demand.']) :-
    HistWasteRate >= 0.30,
    Stock > ExpectedDemand,
    !.

% Medium Risk Rule 1: Expiry approaching within 2 to 3 days with positive stock
assess_waste_risk(Stock, _, ExpiryDays, _, medium, ['Product expires within 2-3 days. Monitor stock velocity closely.']) :-
    ExpiryDays > 1,
    ExpiryDays =< 3,
    Stock > 0,
    !.

% Medium Risk Rule 2: Quantity significantly higher than demand (>= 125% of demand)
assess_waste_risk(Stock, ExpectedDemand, _, HistWasteRate, medium, Reasons) :-
    ExpectedDemand > 0,
    Ratio is Stock / ExpectedDemand,
    Ratio >= 1.25,
    build_medium_reasons(Ratio, HistWasteRate, Reasons),
    !.

% Medium Risk Rule 3: Moderate historical waste rate (>= 15%) with stock >= demand
assess_waste_risk(Stock, ExpectedDemand, _, HistWasteRate, medium, ['Moderate historical waste rate recorded (>= 15%). Potential over-ordering pattern.']) :-
    HistWasteRate >= 0.15,
    Stock >= ExpectedDemand,
    !.

% Low Risk Rule 1: Safe shelf-life (> 3 days), balanced stock (< 125% of demand), low waste history (< 15%)
assess_waste_risk(Stock, ExpectedDemand, ExpiryDays, HistWasteRate, low, ['Safe shelf life remaining (> 3 days) and stock is balanced with demand.']) :-
    ExpiryDays > 3,
    HistWasteRate < 0.15,
    (ExpectedDemand =< 0 ; (Stock / ExpectedDemand) < 1.25),
    !.

% Fallback Low Risk
assess_waste_risk(_, _, _, _, low, ['Standard operational parameters. Safe shelf life and normal consumption expected.']).

% ---------------------------------------------------------------------
% HELPER REASON BUILDERS (Explainable AI clauses)
% ---------------------------------------------------------------------
build_high_reasons(Ratio, ExpiryDays, HistWasteRate, Reasons) :-
    findall(R, high_reason_item(Ratio, ExpiryDays, HistWasteRate, R), Reasons).

high_reason_item(Ratio, _, _, 'Stock significantly exceeds expected demand') :- Ratio >= 1.30.
high_reason_item(_, ExpiryDays, _, 'Short remaining shelf life (<= 3 days)') :- ExpiryDays =< 3.
high_reason_item(_, _, HistWasteRate, 'High historical waste rate recorded') :- HistWasteRate >= 0.20.

build_medium_reasons(Ratio, HistWasteRate, Reasons) :-
    findall(R, medium_reason_item(Ratio, HistWasteRate, R), Reasons).

medium_reason_item(Ratio, _, 'Current stock moderately exceeds forecasted demand') :- Ratio >= 1.25.
medium_reason_item(_, HistWasteRate, 'Historical waste rate indicates slight overproduction') :- HistWasteRate >= 0.15.

% ---------------------------------------------------------------------
% 2. PRODUCTION REDUCTION & MITIGATION RECOMMENDATION RULES
% ---------------------------------------------------------------------
% Expired item -> Halt production
recommend_production(Stock, _, CurrentProduction, high, 0, 'Halt production and dispose of expired inventory safely') :-
    Stock > 0,
    CurrentProduction =< 0,
    !.

% High Risk & Excess Stock -> Reduce production or redistribute immediately
recommend_production(Stock, ExpectedDemand, CurrentProduction, high, RecProduction, 'Reduce production or redistribute immediately') :-
    Stock > ExpectedDemand,
    CurrentProduction > 0,
    RecProduction is max(0, round(CurrentProduction * 0.70)),
    !.

recommend_production(Stock, ExpectedDemand, CurrentProduction, high, RecProduction, 'Reduce production or redistribute immediately') :-
    Stock >= ExpectedDemand,
    RecProduction is max(0, round(CurrentProduction * 0.50)),
    !.

recommend_production(_, _, CurrentProduction, high, RecProduction, 'Prioritize remaining stock in specials and reduce batch prep') :-
    RecProduction is max(0, round(CurrentProduction * 0.75)),
    !.

% Medium Risk -> Slightly reduce by 10-15%
recommend_production(Stock, ExpectedDemand, CurrentProduction, medium, RecProduction, 'Slightly reduce production by 10-15% and monitor inventory turnover') :-
    Stock > ExpectedDemand,
    CurrentProduction > 0,
    RecProduction is max(0, round(CurrentProduction * 0.85)),
    !.

recommend_production(_, _, CurrentProduction, medium, RecProduction, 'Feature in daily specials to accelerate turnover') :-
    RecProduction is max(0, round(CurrentProduction * 0.90)),
    !.

% Low Risk & Demand exceeds stock -> Maintain or scale up to match demand
recommend_production(Stock, ExpectedDemand, CurrentProduction, low, RecProduction, 'Maintain optimal production aligned with customer demand') :-
    Stock < ExpectedDemand,
    Deficit is ExpectedDemand - Stock,
    RecProduction is max(CurrentProduction, Deficit),
    !.

recommend_production(_, _, CurrentProduction, low, CurrentProduction, 'Maintain standard scheduled production batch').

% ---------------------------------------------------------------------
% 3. PRIORITY USAGE RECOMMENDATION RULES
% ---------------------------------------------------------------------
evaluate_priority_use(ExpiryDays, _, 'DISPOSE_OR_COMPOST') :-
    ExpiryDays =< 0,
    !.
evaluate_priority_use(ExpiryDays, _, 'IMMEDIATE_USE') :-
    ExpiryDays =< 1,
    !.
evaluate_priority_use(ExpiryDays, high, 'IMMEDIATE_USE') :-
    ExpiryDays =< 2,
    !.
evaluate_priority_use(ExpiryDays, _, 'HIGH_PRIORITY') :-
    ExpiryDays =< 3,
    !.
evaluate_priority_use(_, high, 'HIGH_PRIORITY') :- !.
evaluate_priority_use(_, medium, 'MODERATE_PRIORITY') :- !.
evaluate_priority_use(_, low, 'STANDARD').

% ---------------------------------------------------------------------
% 4. REDISTRIBUTION RECOMMENDATION RULES
% ---------------------------------------------------------------------
evaluate_redistribution(_, _, ExpiryDays, _, false, 'Expired or unsafe for donation') :-
    ExpiryDays =< 0,
    !.

evaluate_redistribution(Stock, ExpectedDemand, ExpiryDays, Surplus, true, 'Surplus exceeds daily demand and is within safe redistribution window') :-
    Surplus >= 5,
    ExpiryDays >= 1,
    ExpiryDays =< 4,
    Stock > ExpectedDemand,
    !.

evaluate_redistribution(_, _, _, Surplus, false, 'No actionable surplus for external redistribution') :-
    Surplus < 5.
