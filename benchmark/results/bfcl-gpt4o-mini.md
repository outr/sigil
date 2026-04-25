# Sigil BFCL Benchmark Results

**Date:** 2026-04-25T04:14:07.552562017Z
**Pipeline:** Sigil (Provider abstraction + BFCLScorer port of `ast_checker.py`)
**Model:** openai/gpt-4o-mini
**Category:** simple_python
**Score:** 345/400 (86.3% accuracy, 0 no-tool-call)

## Failures (55)

### `simple_python_40` — wrong-args

- observed: `calculate_magnetic_field({"current":5,"radius":4,"permeability":125663706.14359173})`
- ground_truth: `[{"calculate_magnetic_field":{"current":[5],"radius":[4],"permeability":["",1.257E+11]}}]`

### `simple_python_51` — wrong-args

- observed: `calculate_entropy_change({"initial_temp":300,"final_temp":400,"heat_capacity":5,"isothermal":false})`
- ground_truth: `[{"calculate_entropy_change":{"initial_temp":[300],"final_temp":[400],"heat_capacity":[5],"isothermal":["",true]}}]`

### `simple_python_66` — wrong-args

- observed: `ecology_data_precipitation_stats({"location":"Amazon rainforest","time_frame":"last six months"})`
- ground_truth: `[{"ecology_data.precipitation_stats":{"location":["Amazon rainforest"],"time_frame":["six_months"]}}]`

### `simple_python_81` — wrong-args

- observed: `map_routing_fastest_route({"start_location":"San Francisco, CA","end_location":"Los Angeles, CA","avoid_tolls":true})`
- ground_truth: `[{"map_routing.fastest_route":{"start_location":["San Francisco","SF"],"end_location":["Los Angeles","LA"],"avoid_tolls":[true]}}]`

### `simple_python_94` — wrong-args

- observed: `update_user_info({"user_id":43523,"update_info":{"name":"John Doe","email":"johndoe@email.com"},"database":"customer_database"})`
- ground_truth: `[{"update_user_info":{"user_id":[43523],"update_info":[{"name":["John Doe"],"email":["johndoe@email.com"]}],"database":["CustomerInfo",""]}}]`

### `simple_python_112` — wrong-args

- observed: `calculate_probability({"total_outcomes":52,"favorable_outcomes":4,"round_to":4})`
- ground_truth: `[{"calculate_probability":{"total_outcomes":[52],"favorable_outcomes":[4],"round_to":["",2]}}]`

### `simple_python_127` — wrong-args

- observed: `calculate_NPV({"cash_flows":[200,300,400,500],"discount_rate":10,"initial_investment":2000})`
- ground_truth: `[{"calculate_NPV":{"cash_flows":[[200,300,400,500]],"discount_rate":[0.1],"initial_investment":[2000]}}]`

### `simple_python_129` — wrong-args

- observed: `calculate_discounted_cash_flow({"coupon_payment":100,"period":5,"discount_rate":4,"face_value":1000})`
- ground_truth: `[{"calculate_discounted_cash_flow":{"coupon_payment":[100],"period":[5],"discount_rate":[0.04],"face_value":["",1000]}}]`

### `simple_python_130` — wrong-args

- observed: `finance_calculator_npv({"cash_flows":[-50000,10000,15000,20000,25000,30000],"discount_rate":8,"years":[0,1,2,3,4,5]})`
- ground_truth: `[{"finance_calculator.npv":{"cash_flows":[[-50000,10000,15000,20000,25000,30000]],"discount_rate":[0.08],"years":["",[]]}}]`

### `simple_python_133` — wrong-args

- observed: `finance_predict_future_value({"present_value":5000,"annual_interest_rate":5,"compounding_periods_per_year":12,"time_years":3})`
- ground_truth: `[{"finance.predict_future_value":{"present_value":[5000],"annual_interest_rate":[0.05],"compounding_periods_per_year":[12],"time_years":[3]}}]`

### `simple_python_134` — wrong-args

- observed: `investment_predictProfit({"investment_amount":5000,"annual_return":7,"years":5})`
- ground_truth: `[{"investment.predictProfit":{"investment_amount":[5000],"annual_return":[0.07],"years":[5]}}]`

### `simple_python_137` — wrong-args

- observed: `calculate_stock_return({"investment_amount":5000,"annual_growth_rate":6,"holding_period":5,"dividends":false})`
- ground_truth: `[{"calculate_stock_return":{"investment_amount":[5000],"annual_growth_rate":[0.06],"holding_period":[5],"dividends":["",false]}}]`

### `simple_python_145` — wrong-args

- observed: `calculate_compounded_interest({"principal":5000,"interest_rate":5,"period":10,"compounding_frequency":"Annually"})`
- ground_truth: `[{"calculate_compounded_interest":{"principal":[5000],"interest_rate":[0.05],"period":[10],"compounding_frequency":["Annually",""]}}]`

### `simple_python_148` — wrong-args

- observed: `finance_calculate_future_value({"initial_investment":20000,"rate_of_return":8,"years":5,"contribution":0})`
- ground_truth: `[{"finance.calculate_future_value":{"initial_investment":[20000],"rate_of_return":[0.08],"years":[5],"contribution":["",0]}}]`

### `simple_python_150` — wrong-args

- observed: `calculate_roi({"deposit":1000,"annual_interest_rate":3,"years":1})`
- ground_truth: `[{"calculate_roi":{"deposit":[1000],"annual_interest_rate":[0.03],"years":[1]}}]`

### `simple_python_152` — wrong-args

- observed: `calculate_mutual_fund_balance({"investment_amount":50000,"annual_yield":5,"years":3})`
- ground_truth: `[{"calculate_mutual_fund_balance":{"investment_amount":[50000],"annual_yield":[0.05],"years":[3]}}]`

### `simple_python_153` — wrong-args

- observed: `calculate_compounded_interest({"principal":5000,"rate":3,"time":5,"n":4})`
- ground_truth: `[{"calculate_compounded_interest":{"principal":[5000],"rate":[0.03],"time":[5],"n":[4]}}]`

### `simple_python_178` — wrong-args

- observed: `get_lawsuit_details({"case_number":"LAX2019080202","court_location":"Los Angeles","additional_details":["parties","status","filing_date","case_type"]})`
- ground_truth: `[{"get_lawsuit_details":{"case_number":["LAX2019080202"],"court_location":["Los Angeles"],"additional_details":["",["attorneys","plaintiffs","defendants","charges","court_updates"]]}}]`

### `simple_python_184` — wrong-args

- observed: `lawsuit_check_case({"case_id":1234,"closed_status":false})`
- ground_truth: `[{"lawsuit.check_case":{"case_id":[1234],"closed_status":[true]}}]`

### `simple_python_193` — wrong-args

- observed: `local_nursery_find({"location":"Toronto","plant_types":["annual plants"]})`
- ground_truth: `[{"local_nursery.find":{"location":["Toronto"],"plant_types":[["Annual"]]}}]`

### `simple_python_197` — wrong-args

- observed: `get_air_quality_index({"location":"San Diego, CA","time":"2023-10-06T12:00:00"})`
- ground_truth: `[{"get_air_quality_index":{"location":["San Diego"],"time":["12pm","12:00"]}}]`

### `simple_python_200` — wrong-args

- observed: `calculate_emissions({"distance":12000,"fuel_type":"gasoline","fuel_efficiency":25,"efficiency_reduction":0})`
- ground_truth: `[{"calculate_emissions":{"distance":[12000],"fuel_type":["gas"],"fuel_efficiency":["",25.0],"efficiency_reduction":[0,""]}}]`

### `simple_python_201` — wrong-args

- observed: `estimate_population({"species":"panda","country":"China","year":2023})`
- ground_truth: `[{"estimate_population":{"species":["panda","pandas"],"country":["China","CN"],"year":["",2024]}}]`

### `simple_python_207` — wrong-args

- observed: `calculate_shortest_distance({"start_location":"New York, USA","end_location":"Miami, USA","route_preference":"fastest"})`
- ground_truth: `[{"calculate_shortest_distance":{"start_location":["New York, USA","New York City","New York City, NY","NYC","NY"],"end_location":["Miami, USA","Miami","Miami, FL","FL"],"route_preference":["Shortest"]}}]`

### `simple_python_208` — wrong-args

- observed: `map_service_get_directions({"start":"New York","end":"Los Angeles","avoid":["highways","ferries"]})`
- ground_truth: `[{"map_service.get_directions":{"start":["New York","NYC"],"end":["Los Angeles","LA"],"avoid":[["highways","tolls"],["tolls","highways"]]}}]`

### `simple_python_209` — wrong-args

- observed: `public_library_find_nearby({"location":"Boston, MA","facilities":["English fiction","Wi-Fi"]})`
- ground_truth: `[{"public_library.find_nearby":{"location":["Boston, MA","Boston, Massachusetts"],"facilities":[["Fiction","Wi-Fi"],["Wi-Fi","Fiction"]]}}]`

### `simple_python_215` — wrong-args

- observed: `movie_details_brief({"title":"Interstellar","extra_info":true})`
- ground_truth: `[{"movie_details.brief":{"title":["Interstellar"],"extra_info":["",false]}}]`

### `simple_python_219` — wrong-args

- observed: `get_neuron_coordinates({"neuron_type":"GABA","brain_region":"whole brain"})`
- ground_truth: `[{"get_neuron_coordinates":{"neuron_type":["GABA"],"brain_region":["All","all part of the brain","entire brain"]}}]`

### `simple_python_225` — wrong-args

- observed: `psych_research_get_preference({"category":"reading","option_one":"digital reading","option_two":"physical books","demographic":"general population"})`
- ground_truth: `[{"psych_research.get_preference":{"category":["reading"],"option_one":["digital reading","digital"],"option_two":["physical book","physical","physical books"],"demographic":["","all"]}}]`

### `simple_python_234` — wrong-args

- observed: `history_eu_fetch_events({"century":19,"region":"Europe","category":"Wars"})`
- ground_truth: `[{"history_eu.fetch_events":{"century":[19],"region":["Northern","Southern","Eastern","Western"],"category":["Wars"]}}]`

### `simple_python_244` — wrong-args

- observed: `publication_year_find({"author":"Isaac Newton","work_title":"Philosophi\u00E6 Naturalis Principia Mathematica","location":"all"})`
- ground_truth: `[{"publication_year.find":{"author":["Isaac Newton"],"work_title":["Law of Universal Gravitation","Universal Law of Gravitation","The law of universal gravitation"],"location":["","all"]}}]`

### `simple_python_249` — wrong-args

- observed: `religion_history_info({"religion":"Christianity","till_century":14,"include_people":true})`
- ground_truth: `[{"religion.history_info":{"religion":["Christianity"],"till_century":[14],"include_people":[false,""]}}]`

### `simple_python_251` — wrong-args

- observed: `get_earliest_reference({"name":"Jesus Christ","source":"historical"})`
- ground_truth: `[{"get_earliest_reference":{"name":["Jesus Christ"],"source":["historical records"]}}]`

### `simple_python_267` — wrong-args

- observed: `find_exhibition({"location":"New York City, NY","art_form":"sculpture","month":"November","user_ratings":"high"})`
- ground_truth: `[{"find_exhibition":{"location":["New York City, NY"],"art_form":["sculpture","modern sculpture"],"month":[""],"user_ratings":["high"]}}]`

### `simple_python_269` — wrong-args

- observed: `calculate_compound_interest({"principle":10000,"interest_rate":5,"time":10,"compounds_per_year":1})`
- ground_truth: `[{"calculate_compound_interest":{"principle":[10000],"interest_rate":[0.05],"time":[10],"compounds_per_year":[1,""]}}]`

### `simple_python_277` — wrong-args

- observed: `museum_info({"museum":"The British Museum","date":"2023-06-20","information":["opening hours","ticket prices"]})`
- ground_truth: `[{"museum_info":{"museum":["The British Museum"],"date":["2023-06-20"],"information":[["opening_hours","ticket_price"],["ticket_price","opening_hours"]]}}]`

### `simple_python_278` — wrong-args

- observed: `get_instrument_details({"instrument":"piano","manufacturer":"Yamaha","features":["price","ratings"]})`
- ground_truth: `[{"get_instrument_details":{"instrument":["piano"],"manufacturer":["Yamaha"],"features":[["price","rating"]]}}]`

### `simple_python_284` — wrong-args

- observed: `concert_info_get({"location":"New York","date":"2023-11","genre":"pop"})`
- ground_truth: `[{"concert_info.get":{"location":["New York City, NY","New York"],"date":["next month","2023-06-01","06\/01\/2023","Jun.1,2023","June 2023"],"genre":["Pop"]}}]`

### `simple_python_290` — wrong-args

- observed: `concert_find_details({"artist":"The Weeknd","month":"December","year":2023})`
- ground_truth: `[{"concert.find_details":{"artist":["The Weeknd"],"month":["December"],"year":["",2022]}}]`

### `simple_python_305` — wrong-args

- observed: `sports_stats_get_performance({"player_name":"Lionel Messi","tournament":"La Liga","season":"2020-2021","performance_indicator":["goals","assists"]})`
- ground_truth: `[{"sports_stats.get_performance":{"player_name":["Messi","Lionel Messi"],"tournament":["La Liga"],"season":["2020-2021"],"performance_indicator":[["Goals Scored","Assists Made"]]}}]`

### `simple_python_307` — wrong-args

- observed: `game_result_get_winner({"teams":["Lakers","Clippers"],"date":"2021-01-28","venue":"home"})`
- ground_truth: `[{"game_result.get_winner":{"teams":[["Lakers","Clippers"],["Clippers","Lakers"]],"date":["2021-01-28","01\/28\/2021","Jan.28,2021"],"venue":["",true]}}]`

### `simple_python_323` — wrong-args

- observed: `sports_ranking_get_top_player({"sport":"tennis","gender":"female"})`
- ground_truth: `[{"sports_ranking.get_top_player":{"sport":["tennis"],"gender":["women"]}}]`

### `simple_python_325` — wrong-args

- observed: `sports_match_results({"team1":"Chicago Bulls","team2":"Los Angeles Lakers","season":"2023"})`
- ground_truth: `[{"sports.match_results":{"team1":["Chicago Bulls"],"team2":["Los Angeles Lakers"],"season":[""]}}]`

### `simple_python_333` — wrong-args

- observed: `detailed_weather_forecast({"location":"London, United Kingdom","days":3,"details":["high","low","humidity","precipitation"]})`
- ground_truth: `[{"detailed_weather_forecast":{"location":["London, United Kingdom","London"],"days":[3],"details":[["high_low_temperature","humidity","precipitation"]]}}]`

### `simple_python_335` — wrong-args

- observed: `find_card_in_deck({"rank":"Queen","suit":"Hearts","deck":[{"rank":"Ace","suit":"Hearts"},{"rank":"Two","suit":"Hearts"},{"rank":"Three","suit":"Hearts"},{"rank":"Four","suit":"Hearts"},{"rank":"Five","suit":"Hearts"},{"rank":"Six","suit":"Hearts"},{"rank":"Seven","suit":"Hearts"},{"rank":"Eight","suit":"Hearts"},{"rank":"Nine","suit":"Hearts"},{"rank":"Ten","suit":"Hearts"},{"rank":"Jack","suit":"Hearts"},{"rank":"Queen","suit":"Hearts"},{"rank":"King","suit":"Hearts"},{"rank":"Ace","suit":"Diamonds"},{"rank":"Two","suit":"Diamonds"},{"rank":"Three","suit":"Diamonds"},{"rank":"Four","suit":"Diamonds"},{"rank":"Five","suit":"Diamonds"},{"rank":"Six","suit":"Diamonds"},{"rank":"Seven","suit":"Diamonds"},{"rank":"Eight","suit":"Diamonds"},{"rank":"Nine","suit":"Diamonds"},{"rank":"Ten","suit":"Diamonds"},{"rank":"Jack","suit":"Diamonds"},{"rank":"Queen","suit":"Diamonds"},{"rank":"King","suit":"Diamonds"},{"rank":"Ace","suit":"Clubs"},{"rank":"Two","suit":"Clubs"},{"rank":"Three","suit":"Clubs"},{"rank":"Four","suit":"Clubs"},{"rank":"Five","suit":"Clubs"},{"rank":"Six","suit":"Clubs"},{"rank":"Seven","suit":"Clubs"},{"rank":"Eight","suit":"Clubs"},{"rank":"Nine","suit":"Clubs"},{"rank":"Ten","suit":"Clubs"},{"rank":"Jack","suit":"Clubs"},{"rank":"Queen","suit":"Clubs"},{"rank":"King","suit":"Clubs"},{"rank":"Ace","suit":"Spades"},{"rank":"Two","suit":"Spades"},{"rank":"Three","suit":"Spades"},{"rank":"Four","suit":"Spades"},{"rank":"Five","suit":"Spades"},{"rank":"Six","suit":"Spades"},{"rank":"Seven","suit":"Spades"},{"rank":"Eight","suit":"Spades"},{"rank":"Nine","suit":"Spades"},{"rank":"Ten","suit":"Spades"},{"rank":"Jack","suit":"Spades"},{"rank":"Queen","suit":"Spades"},{"rank":"King","suit":"Spades"}]})`
- ground_truth: `[{"find_card_in_deck":{"rank":["Queen"],"suit":["Hearts"],"deck":[""]}}]`

### `simple_python_337` — wrong-args

- observed: `poker_game_winner({"players":["Alex","Sam","Robert","Steve"],"cards":{},"type":"Texas Holdem"})`
- ground_truth: `[{"poker_game_winner":{"players":[["Alex","Sam","Robert","Steve"]],"cards":[{"Alex":[["A of spades","K of spades"]],"Sam":[["2 of diamonds","3 of clubs"]],"Robert":[["Q of hearts","10 of hearts"]],"Steve":[["4 of spades","5 of spades"]]}],"type":["Texas Holdem",""]}}]`

### `simple_python_353` — wrong-args

- observed: `find_recipes({"diet":"gluten-free","meal_type":"dinner","ingredients":[]})`
- ground_truth: `[{"find_recipes":{"diet":["gluten-free"],"meal_type":["dinner"],"ingredients":[""]}}]`

### `simple_python_354` — wrong-args

- observed: `get_vegan_recipe({"dish_type":"soup","cooking_time":30,"ingredient_preference":[]})`
- ground_truth: `[{"get_vegan_recipe":{"dish_type":["soup"],"cooking_time":[30],"ingredient_preference":[""]}}]`

### `simple_python_366` — wrong-args

- observed: `recipe_unit_conversion({"value":2,"from_unit":"tablespoon","to_unit":"teaspoon","precision":0})`
- ground_truth: `[{"recipe.unit_conversion":{"value":[2],"from_unit":["tablespoon","tbsp"],"to_unit":["teaspoon","tsp"],"precision":[1,""]}}]`

### `simple_python_369` — wrong-args

- observed: `grocery_store_find_nearby({"location":"Houston, TX","categories":["organic fruits","organic vegetables"]})`
- ground_truth: `[{"grocery_store.find_nearby":{"location":["Houston","Houston, TX"],"categories":[["Organic","Vegetables","Fruits"],["Organic","Fruits","Vegetables"],["Vegetables","Fruits","Organic"],["Fruits","Vegetables","Organic"],["Fruits","Organic","Vegetables"],["Vegetables","Organic","Fruits"]]}}]`

### `simple_python_373` — wrong-args

- observed: `walmart_purchase({"loc":"San Jose","product_list":["apples","rice","bottled water"],"pack_size":[0,0,12]})`
- ground_truth: `[{"walmart.purchase":{"loc":["San Jose","San Jose, CA"],"product_list":[["apples","rice","bottled water"],["apples","rice","water"]],"pack_size":[[1,1,12]]}}]`

### `simple_python_374` — wrong-args

- observed: `grocery_info_nutritional_info({"store":"Walmart","food":"avocado","information":["protein","calories","carbs"]})`
- ground_truth: `[{"grocery_info.nutritional_info":{"store":["Walmart"],"food":["avocado","Avocado"],"information":[["Protein","Calories","Carbohydrates"]]}}]`

### `simple_python_375` — wrong-args

- observed: `walmart_check_price({"items":["pumpkin","dozen eggs"],"quantities":[3,2],"store_location":"all"})`
- ground_truth: `[{"walmart.check_price":{"items":[["pumpkins","eggs"],["pumpkin","dozen eggs"]],"quantities":[[3,24],[3,2]],"store_location":[""]}}]`

### `simple_python_379` — wrong-args

- observed: `get_current_time({"location":"Sydney","country":"Australia","timezone":"Australia\/Sydney"})`
- ground_truth: `[{"get_current_time":{"location":["Sydney"],"country":["Australia","Australia\/Sydney"],"timezone":[""]}}]`

### `simple_python_394` — wrong-args

- observed: `maps_get_distance_duration({"start_location":"Eiffel Tower, Paris, France","end_location":"Louvre Museum, Paris, France","traffic":false})`
- ground_truth: `[{"maps.get_distance_duration":{"start_location":["Eiffel Tower"],"end_location":["Louvre Museum"],"traffic":["",false]}}]`

