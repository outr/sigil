# Sigil MemBench Benchmark Results

**Date:** 2026-05-04T16:39:40.790635808Z
**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)
**Agent:** FirstAgent
**Retrieval:** vanilla cosine
**Score:** 3293/3500 (94.1% R@5)

## Per-category accuracy

| Category | Correct | Total | Accuracy |
|---|---|---|---|
| aggregative | 499 | 500 | 99.8% |
| comparative | 500 | 500 | 100.0% |
| conditional | 462 | 500 | 92.4% |
| knowledge_update | 495 | 500 | 99.0% |
| noisy | 401 | 500 | 80.2% |
| post_processing | 442 | 500 | 88.4% |
| simple | 494 | 500 | 98.8% |

## Failures (207)

### [simple tid=54] `What is the work location of the coworker?`

- expected sids: 118
- top-5 sids: 113, 132, 48, 101, 112

### [simple tid=106] `What is the hometown of that coworker?`

- expected sids: 30
- top-5 sids: 63, 21, 128, 31, 65

### [simple tid=177] `What hobbies does my female cousin have?`

- expected sids: 158
- top-5 sids: 73, 146, 111, 92, 164

### [simple tid=373] `What hobby does the coworker enjoy?`

- expected sids: 123
- top-5 sids: 88, 49, 154, 95, 11

### [simple tid=419] `What hobbies does my male cousin enjoy?`

- expected sids: 62
- top-5 sids: 89, 47, 61, 132, 28

### [simple tid=443] `What hometown does that coworker come from?`

- expected sids: 40
- top-5 sids: 10, 65, 147, 48, 93

### [aggregative tid=183] `How many people live in California?`

- expected sids: 5, 27, 55, 76, 93, 107, 133, 147
- top-5 sids: 50, 64, 103, 158, 90

### [knowledge_update tid=94] `What is the hobby of my aunt?`

- expected sids: 90
- top-5 sids: 184, 74, 33, 149, 106

### [knowledge_update tid=104] `What is my female cousin's position?`

- expected sids: 173
- top-5 sids: 160, 163, 69, 102, 0

### [knowledge_update tid=428] `What hobby does my subordinate have?`

- expected sids: 82
- top-5 sids: 72, 102, 148, 60, 57

### [knowledge_update tid=447] `What is the name of my cousin's company?`

- expected sids: 132
- top-5 sids: 100, 170, 164, 16, 120

### [knowledge_update tid=498] `What does my cousin do for a living?`

- expected sids: 20
- top-5 sids: 86, 69, 0, 12, 72

### [conditional tid=8] `What is the height of someone from Austin, TX?`

- expected sids: 75, 79
- top-5 sids: 87, 150, 26, 19, 108

### [conditional tid=88] `What is the height of the person who is 31 years old?`

- expected sids: 21, 40
- top-5 sids: 63, 86, 17, 148, 89

### [conditional tid=93] `What is the email address for the person with the contact number 30506814045?`

- expected sids: 46, 50
- top-5 sids: 164, 141, 15, 142, 165

### [conditional tid=99] `What is the company name of the person who is 164 cm tall?`

- expected sids: 24, 36
- top-5 sids: 87, 133, 150, 71, 1

### [conditional tid=115] `What is the height of someone from Columbus, OH?`

- expected sids: 27, 33
- top-5 sids: 67, 45, 92, 108, 128

### [conditional tid=123] `What is the name of someone with a high school education?`

- expected sids: 21, 24
- top-5 sids: 120, 70, 136, 169, 158

### [conditional tid=169] `What is the work location for someone whose occupation is a musician?`

- expected sids: 162, 169
- top-5 sids: 47, 4, 163, 159, 11

### [conditional tid=170] `What is the hobby of the person with the contact number 41506565783?`

- expected sids: 67, 76
- top-5 sids: 144, 133, 162, 52, 57

### [conditional tid=179] `What is the contact number for someone with a Bachelor’s degree?`

- expected sids: 6, 20
- top-5 sids: 92, 117, 57, 168, 103

### [conditional tid=208] `For someone whose hometown is Boston, MA, what is their work location?`

- expected sids: 131, 140
- top-5 sids: 6, 93, 51, 95, 25

### [conditional tid=218] `What is the contact number for someone with a high school education?`

- expected sids: 71, 79
- top-5 sids: 160, 29, 10, 103, 102

### [conditional tid=219] `What is the occupation of the person with the contact number 31002799100?`

- expected sids: 26, 34
- top-5 sids: 14, 106, 127, 163, 63

### [conditional tid=221] `What is the birthday of the person who is 37 years old?`

- expected sids: 3, 19
- top-5 sids: 108, 66, 85, 126, 150

### [conditional tid=241] `For someone whose work location is Austin, TX, what is their hometown?`

- expected sids: 122, 125
- top-5 sids: 135, 138, 3, 68, 31

### [conditional tid=250] `What is the work location for someone who is 166 cm tall?`

- expected sids: 141, 143
- top-5 sids: 82, 46, 23, 157, 1

### [conditional tid=266] `What is the contact number for someone whose birthday is July 10th?`

- expected sids: 93, 104
- top-5 sids: 129, 162, 15, 139, 54

### [conditional tid=278] `What is the hometown of the person who is 27 years old?`

- expected sids: 19, 21
- top-5 sids: 129, 87, 22, 64, 155

### [conditional tid=303] `What is the hobby of someone who is 34 years old?`

- expected sids: 48, 64
- top-5 sids: 160, 143, 0, 74, 22

### [conditional tid=313] `What is the contact number for the person whose birthday is on 10/05?`

- expected sids: 112, 120
- top-5 sids: 101, 3, 85, 34, 55

### [conditional tid=318] `What is the contact number for the person who is 38 years old?`

- expected sids: 90, 94
- top-5 sids: 61, 37, 66, 137, 44

### [conditional tid=326] `What is the education level of someone who is 166cm tall?`

- expected sids: 115, 118
- top-5 sids: 155, 3, 89, 32, 67

### [conditional tid=343] `What is the email address of someone whose hometown is Denver, CO?`

- expected sids: 154, 164
- top-5 sids: 54, 32, 56, 148, 21

### [conditional tid=344] `What is the contact number for the person who is 32 years old?`

- expected sids: 10, 14
- top-5 sids: 121, 34, 129, 105, 81

### [conditional tid=367] `What is the education level of someone whose occupation is Chef?`

- expected sids: 71, 78
- top-5 sids: 150, 134, 149, 151, 50

### [conditional tid=374] `What position does someone who is 168 cm tall hold?`

- expected sids: 116, 126
- top-5 sids: 28, 2, 65, 88, 128

### [conditional tid=377] `What is the education of the person who is 32 years old?`

- expected sids: 63, 78
- top-5 sids: 7, 46, 106, 0, 130

### [conditional tid=396] `What is the work location of the person who is 37 years old?`

- expected sids: 94, 97
- top-5 sids: 128, 72, 108, 130, 22

### [conditional tid=408] `What is the email address of the person whose birthday is on 11/25?`

- expected sids: 138, 146
- top-5 sids: 152, 39, 46, 70, 101

### [conditional tid=411] `What is the contact number for the person with a PhD in education?`

- expected sids: 15, 18
- top-5 sids: 120, 98, 48, 22, 110

### [conditional tid=413] `What's the contact number for the person whose height is 161 cm?`

- expected sids: 163, 167
- top-5 sids: 18, 37, 45, 131, 101

### [conditional tid=426] `What is the occupation of someone whose work location is Los Angeles, CA?`

- expected sids: 157, 162
- top-5 sids: 133, 6, 94, 49, 97

### [conditional tid=447] `What is the company name of the person who is 26 years old?`

- expected sids: 1, 8
- top-5 sids: 151, 43, 86, 74, 93

### [conditional tid=452] `What is the work location for someone whose occupation is a doctor?`

- expected sids: 150, 160
- top-5 sids: 25, 87, 157, 36, 27

### [conditional tid=454] `What is the occupation of the person who is 29 years old?`

- expected sids: 63, 64
- top-5 sids: 149, 0, 42, 83, 105

### [conditional tid=461] `What is the hobby of the person who is 37 years old?`

- expected sids: 10, 13
- top-5 sids: 117, 137, 22, 151, 29

### [conditional tid=470] `What is the education of someone whose birthday is on 05/28?`

- expected sids: 72, 83
- top-5 sids: 153, 90, 129, 44, 109

### [conditional tid=474] `What is the company name of the person who is 164 cm tall?`

- expected sids: 77, 80
- top-5 sids: 21, 44, 95, 1, 106

### [conditional tid=481] `What's the name of the person with a high school education?`

- expected sids: 21, 32
- top-5 sids: 133, 76, 77, 111, 4

### [noisy tid=2] `I spoke to someone about a project, but I can't recall the details now. How do I even keep track of these things? Did I leave the oven on?Oops, actually what I wanted to ask was: What is the occupation of the person with the contact number 31009417649?`

- expected sids: 85, 97
- top-5 sids: 58, 138, 117, 65, 35

### [noisy tid=3] `I was thinking about that book I read last summer, the plot was quite intriguing. Have you heard about the new café that opened downtown? I wonder if they're hiring anyone lately.Wait a minute,What is the contact number for the person with a PhD in education?`

- expected sids: 29, 32
- top-5 sids: 85, 62, 161, 151, 66

### [noisy tid=4] `I was thinking about that one project we discussed last week, but I can't recall the exact details. Did I leave the oven on? You know, sometimes I forget where I put my keys.Hold on, what I actually wanted to understand is: What is the email address for the person with the contact number 70703316548?`

- expected sids: 43, 46
- top-5 sids: 98, 81, 97, 41, 164

### [noisy tid=7] `I was thinking about that book I started last month, did I ever finish it? It’s so hard to keep track of everything. Oh, and I really need to plan that trip we talked about last week. What was the name of that restaurant?Uh, hold on,What hobbies do people who are professors have?`

- expected sids: 156, 159
- top-5 sids: 51, 43, 76, 64, 53

### [noisy tid=10] `I was thinking about that charity event last year, it reminded me of my friend's graduation. Does my sister's cake need more frosting? Oh, and I need to check the calendar for the meeting.I got it wrong, what I really meant to ask is: What is the birthday of the person with a Bachelor's degree?`

- expected sids: 114, 117
- top-5 sids: 69, 6, 28, 99, 70

### [noisy tid=16] `I was at the coffee shop yesterday, and I overheard someone talking about their job, but I couldn't catch the name of the company. Did I forget to call the dentist? I really need to schedule that appointment.Sorry, what I truly wanted to ask is: What is the company name of the person who is 38 years old?`

- expected sids: 50, 63
- top-5 sids: 86, 23, 107, 108, 90

### [noisy tid=20] `I saw a beautiful painting at the gallery yesterday, it reminded me of summer vacations. I wonder if I should start a new book this weekend. Did you hear about that new café opening downtown?What I really meant was,What is the education level of someone whose hobby is attending concerts?`

- expected sids: 24, 41
- top-5 sids: 138, 114, 139, 60, 121

### [noisy tid=29] `I just finished reading an interesting article about the history of jazz music. I wonder if there’s a good coffee shop nearby. Did I remember to send that email?Oh right, I wanted to ask,What is the position of the person whose work location is Boston, MA?`

- expected sids: 140, 147
- top-5 sids: 14, 166, 101, 120, 164

### [noisy tid=30] `The weather has been nice lately, I really should plan a trip soon. Did I forget to water the plants? I wonder if that movie is still showing in theaters.Hmm, actually my question was this: What is the work location for the person with the contact number 20207061186?`

- expected sids: 25, 40
- top-5 sids: 15, 10, 99, 14, 12

### [noisy tid=33] `I saw someone the other day, I think he had a blue jacket on, but I can't recall his name. Did I mention I need to pick up groceries? It's been such a long time since we last met.Hmm, actually my question was this: What is the height of the person with the contact number 31009325688?`

- expected sids: 137, 148
- top-5 sids: 100, 55, 122, 107, 86

### [noisy tid=36] `I was thinking about that new coffee place that just opened, it looked really nice. Did you hear about the concert next month? I wonder if I'll be able to find time to go. There's so much to do today, I can't seem to remember what was on my list.Wait, what I really want to know is: What is the work location for someone with a high school education?`

- expected sids: 131, 141
- top-5 sids: 27, 148, 89, 67, 107

### [noisy tid=37] `I think I saw a movie recently about a small town. What was the name of that restaurant we liked? I wonder if it’s still open after all this time.I got it wrong, what I really meant to ask is: What is the hometown of the person who is 151 cm tall?`

- expected sids: 114, 119
- top-5 sids: 6, 131, 49, 152, 71

### [noisy tid=42] `I was thinking about that time we went hiking, the weather was perfect for it. Have you seen my favorite book? It’s been missing for days. I wonder if I left it at the café last week.Oh no, I actually wanted to figure out,What is the email address of the person with a Bachelor's degree?`

- expected sids: 2, 13
- top-5 sids: 160, 157, 159, 55, 118

### [noisy tid=43] `I was thinking about that project we discussed last week, but I can’t recall exactly what the deadline was. Did I ever send you that report? Oh, and didn't you mention something about your favorite restaurant?Oh no, I actually wanted to figure out,What is the email address of the person with a Bachelor's degree?`

- expected sids: 115, 120
- top-5 sids: 150, 80, 56, 41, 59

### [noisy tid=47] `I was thinking about that concert next week, it should be a lot of fun. Did I remember to send that report? I wonder if the café on Main Street still has that pumpkin spice latte. It's been a while since I last checked the schedule for the movie night, has anyone said anything about it?Wait a minute, what I wanted to ask is,What’s the email address of the person whose birthday is on 07/26?`

- expected sids: 44, 64
- top-5 sids: 72, 107, 4, 83, 149

### [noisy tid=62] `I was thinking about that great restaurant we visited last month, you remember the one with the amazing pasta? It's been ages since I called my friend from college; I wonder what she's up to now. Did you notice that new coffee shop opened down the street?Hold on, what I actually wanted to understand is: What is the company name of the person whose hobby is volunteering?`

- expected sids: 45, 62
- top-5 sids: 11, 92, 161, 131, 84

### [noisy tid=63] `I saw a friend at the cafe yesterday, we talked about our plans for the weekend. I can't recall if I sent that email or not. What was the name of that new restaurant again?Oh right, I wanted to ask,What's the name of the person whose birthday is on 08/03?`

- expected sids: 14, 15
- top-5 sids: 90, 116, 155, 130, 51

### [noisy tid=64] `I had that meeting last week, but the details are a bit fuzzy now. What was I supposed to do with the report? It feels like I just saw him yesterday. Did I get the appointment time right?Oh, what I truly wanted to clarify is,What is the work location for the person with the contact number 81801826839?`

- expected sids: 47, 60
- top-5 sids: 143, 87, 21, 144, 145

### [noisy tid=70] `You know, I was thinking about that movie last week. It's amazing how time flies, isn't it? Oh, and did you hear about the new café opening downtown? I wonder if they will have my favorite pastry. What was I saying again?Hmm, actually my question was this: What is the company name for someone who has a high school education?`

- expected sids: 92, 99
- top-5 sids: 136, 124, 142, 107, 68

### [noisy tid=80] `I saw a friend at the coffee shop yesterday, she mentioned something about a new project she's working on. Have you heard about the new restaurant opening downtown? It's been such a long time since we caught up. Did I leave my keys in the car?I got it wrong, what I really meant to ask is: What is the hometown of someone with a Bachelor's degree?`

- expected sids: 129, 145
- top-5 sids: 34, 108, 111, 8, 68

### [noisy tid=87] `It's interesting how time flies, isn't it? Just the other day, I was thinking about some old friends and their ages. I wonder what their plans are for this year. By the way, did you ever ask Sarah when she celebrates her birthday?Oops, actually what I wanted to ask was: What is the birthday of someone who is 41 years old?`

- expected sids: 77, 84
- top-5 sids: 154, 109, 23, 152, 47

### [noisy tid=92] `I was thinking about my friend's birthday coming up; it feels like just yesterday we celebrated. Also, have you seen the new cafe that opened on Main Street? I wonder what their coffee is like. And did we ever get that report completed on time?Oops, actually what I wanted to ask was: What is the contact number for the person who is 26 years old?`

- expected sids: 112, 122
- top-5 sids: 144, 72, 127, 130, 140

### [noisy tid=97] `I went to the park yesterday, the weather was nice. I wonder what's for dinner tonight? Did I leave the lights on in my car?Wait a minute, what I wanted to ask is,What is the name of the person who is 24 years old?`

- expected sids: 55, 61
- top-5 sids: 133, 86, 68, 25, 0

### [noisy tid=102] `I heard something interesting about a new restaurant opening downtown. Have you seen the latest art exhibit at the gallery? What's the weather supposed to be like this weekend?Oh right, I wanted to ask,What is the education of someone who works in Austin, TX?`

- expected sids: 25, 33
- top-5 sids: 48, 112, 111, 153, 47

### [noisy tid=119] `You know, I was thinking about last week's meeting, and that guy had an interesting perspective on teamwork. Isn't it funny how everyone has their own view on deadlines? Sometimes I wonder if I should start a new hobby… What was I saying? Oh right, that project is due soon, isn’t it?Wait a minute,What is the age of the person with the contact number 51007718988?`

- expected sids: 44, 46
- top-5 sids: 102, 37, 17, 136, 129

### [noisy tid=121] `I saw a beautiful sunset last night, the colors were so vivid, I wonder if it will rain tomorrow. Have I fed the cat? I really should get back to that book I started last week.I got it wrong, what I really meant to ask is: What is the hobby of someone who is 164 cm tall?`

- expected sids: 47, 60
- top-5 sids: 90, 22, 120, 45, 128

### [noisy tid=124] `I was at the café earlier, and there was a group discussing weekend plans. Someone mentioned a concert that sounded interesting, but I can't recall the name of the artist. Also, did you hear about the new exhibit downtown?Hold on, what I actually wanted to understand is: What is the work location for someone who is 24 years old?`

- expected sids: 75, 76
- top-5 sids: 160, 150, 159, 43, 108

### [noisy tid=133] `It was an interesting day at the office, and I couldn't help but wonder about the plans for the weekend. Do I need to pick up groceries later? It's always something different. I remember chatting with someone about their favorite movie, but I can't quite recall the title. Time flies, doesn't it?Oh no, I actually wanted to figure out,What is the contact number for someone whose birthday is on 02/16?`

- expected sids: 114, 120
- top-5 sids: 150, 77, 164, 140, 50

### [noisy tid=140] `Did you hear about that program last weekend? I think it was really interesting, but I can't remember the details. By the way, have you seen my notebook? I thought I left it on the table earlier. It's funny how time flies, isn't it?Oh right, I wanted to ask,What is the contact number for someone with an Associate Degree in education?`

- expected sids: 156, 161
- top-5 sids: 22, 27, 40, 56, 57

### [noisy tid=141] `I wonder if I should try that new recipe this weekend, or maybe just order takeout. Did I finish my book? Oh, and I think I need to water the plants soon.Oh, what I truly wanted to clarify is,What is the hobby of the person with the contact number 20204910051?`

- expected sids: 118, 130
- top-5 sids: 77, 12, 79, 97, 21

### [noisy tid=144] `It's been such a long week; I can’t even recall the last time I had a quiet moment. Did I leave my keys on the counter? Sometimes I wonder about the old days, feels like yesterday we were just starting out. What should I make for dinner?Oops, actually what I wanted to ask was: What is the education level of the person who is 37 years old?`

- expected sids: 111, 115
- top-5 sids: 43, 22, 65, 131, 0

### [noisy tid=149] `Did you see that movie last week? I think it had a great soundtrack. I wonder what time the meeting is tomorrow. I really should clean my room this weekend.Wait a minute,What is the position of someone who is 30 years old?`

- expected sids: 130, 142
- top-5 sids: 106, 0, 39, 21, 32

### [noisy tid=155] `I was thinking about the weather last week, it was surprisingly mild for this time of year. Did you see that new café opening downtown? I wonder how their coffee is. It’s funny how time flies, isn't it? What's for dinner tonight?What I really meant was,What is the contact number for the person whose hobby is climbing?`

- expected sids: 52, 58
- top-5 sids: 126, 17, 164, 35, 136

### [noisy tid=159] `I was thinking about the last time we discussed that project, but I can't seem to recall the details. Have you seen my notebook? It's somewhere around here. I really need to find that paper I started last month.Sorry about that, what I truly wanted to ask is,What position does someone with the occupation of researcher hold?`

- expected sids: 3, 15
- top-5 sids: 92, 22, 38, 90, 153

### [noisy tid=169] `I wonder if we'll see any updates on the project this week. Did I forget to send that email? By the way, do you remember when we last had that team outing?Uh, hold on,What is the work location of the person whose birthday is on 02/27?`

- expected sids: 0, 6
- top-5 sids: 27, 128, 63, 140, 45

### [noisy tid=174] `I saw someone wearing a really cool jacket today, and I thought about when I last went hiking. I wonder if anyone remembers that party last year. Did I mention how much I love garlic bread?Oh right, I wanted to ask,What is the birthday of the person whose work location is Portland, OR?`

- expected sids: 115, 122
- top-5 sids: 152, 68, 28, 46, 25

### [noisy tid=179] `I had a conversation with someone yesterday, they mentioned a trip somewhere but I can't recall the destination. Did I remember to send that email? It's been such a busy week, and I still need to pick up groceries later.Sorry, what I truly wanted to ask is: What is the hometown of the person with the contact number 71809676196?`

- expected sids: 46, 61
- top-5 sids: 140, 34, 153, 141, 121

### [noisy tid=183] `I was thinking about that new cafe that opened downtown, it’s so vibrant. Do you remember the last time we went hiking? I really need to organize my closet this weekend.Wait, what I really want to know is: What is the hometown of the person with the contact number 31008000505?`

- expected sids: 86, 104
- top-5 sids: 52, 35, 128, 21, 84

### [noisy tid=186] `I was thinking about the last meeting we had, and it reminded me of that time we went to the conference. Do you remember what we discussed about the project timelines? It's been so hectic lately, I can’t keep track of all the deadlines. By the way, have you seen the latest updates on the report?Oh right, I wanted to ask,What is the email address of the person whose birthday is on 10/04?`

- expected sids: 26, 36
- top-5 sids: 162, 13, 140, 165, 66

### [noisy tid=194] `I saw a beautiful painting the other day, it reminded me of summer, I can't recall where I put my keys. Wasn't there an event this weekend?Oh no, I actually wanted to figure out,What is the birthday of someone named Camille Donovan?`

- expected sids: 56, 57
- top-5 sids: 43, 52, 58, 131, 71

### [noisy tid=205] `Did you see the weather last week? I think I need to buy some new shoes soon. That movie we talked about seemed interesting, didn't it?Hmm, actually my question was this: What is the education of someone who is 22 years old?`

- expected sids: 1, 14
- top-5 sids: 64, 52, 127, 53, 31

### [noisy tid=221] `I was browsing through some old photos, and I came across that trip we took last summer. I can't recall when exactly it was, though. It feels like just yesterday. What was the name of that café we visited?Hold on, what I actually wanted to understand is: What’s the birthday of someone whose hometown is Los Angeles, CA?`

- expected sids: 22, 41
- top-5 sids: 153, 147, 112, 136, 70

### [noisy tid=236] `She always talked about her childhood, there were so many trees in her backyard. Did you see the sky last night? It was so clear. I wonder if we’ll get to travel next year.Wait, what I really want to know is: What is the hometown of someone whose occupation is a scientist?`

- expected sids: 92, 93
- top-5 sids: 132, 136, 45, 14, 6

### [noisy tid=237] `I was thinking about that new café that opened up downtown, I can't remember the last time I went there. Did I leave my keys on the table? It's always something! What were we talking about? Ah, never mind, it’ll come back to me.Wait a minute, what I wanted to ask is,What is the name of the person with the contact number 41509289919?`

- expected sids: 150, 166
- top-5 sids: 113, 78, 62, 142, 18

### [noisy tid=238] `I heard that someone celebrated their birthday last week, it makes me think about the upcoming holidays. Have you ever wondered how time flies? I saw a great cake in the bakery today that reminded me of my last birthday party. What’s the best gift you’ve ever received?Oh right, I wanted to ask,What is the birthday of someone who is 33 years old?`

- expected sids: 53, 63
- top-5 sids: 132, 111, 146, 88, 25

### [noisy tid=242] `Did you see that new café on Main Street? I think it opens at 9. What was the name of that movie we watched last week? It's so hard to keep track of everything sometimes. I wonder if it will rain later.Oops, actually what I wanted to ask was: What is the work location for someone who is 161 cm tall?`

- expected sids: 115, 129
- top-5 sids: 66, 32, 90, 166, 131

### [noisy tid=245] `Did you see that new cafe that opened downtown? I wonder if they have good coffee. By the way, have you heard from Sarah lately? I can't recall if her birthday is this month or next.Oh no, I actually wanted to figure out,What is the birthday of someone with a high school education?`

- expected sids: 24, 38
- top-5 sids: 43, 152, 57, 22, 44

### [noisy tid=247] `I think I saw someone around the park, or was it near the coffee shop? It's so hard to keep track of everyone's details. Have you heard about that new café opening up? I need to check my calendar for next week’s meetings.Sorry about that, what I truly wanted to ask is,What is the company name of the person who is 156 cm tall?`

- expected sids: 115, 116
- top-5 sids: 33, 151, 43, 150, 129

### [noisy tid=249] `I was thinking about going for a hike sometime soon. Did you see that new coffee shop opened nearby? It reminds me of the time I visited that city; the food was incredible. What a lovely day for a stroll! I need to catch up on that book series I started last summer.I got it wrong, what I really meant to ask is: What hobbies does the person who is 33 years old have?`

- expected sids: 89, 106
- top-5 sids: 138, 126, 139, 140, 56

### [noisy tid=255] `I ran into an old friend at the coffee shop, they seemed really happy. Isn't it funny how time flies? Speaking of which, what should I get for my sister's celebration?Oh no, I actually wanted to figure out,What would be the birthday of someone who is 25 years old?`

- expected sids: 126, 127
- top-5 sids: 129, 25, 131, 65, 45

### [noisy tid=259] `I was thinking about that trip we took last summer, it feels like ages ago. Did you hear about the new cafe downtown? What’s the weather like tomorrow?Hold on, what I actually wanted to understand is: What is the education of the person who is 32 years old?`

- expected sids: 139, 149
- top-5 sids: 88, 22, 66, 44, 150

### [noisy tid=268] `I think I saw a movie last week, but the title escapes me. What a strange coincidence, I was just thinking about the weather. Did I leave my keys on the table?Actually, my real question is: What are the hobbies of someone who has an Associate Degree?`

- expected sids: 127, 128
- top-5 sids: 123, 49, 121, 48, 113

### [noisy tid=271] `I wonder how many years it takes to become a tenured professor. Did I ever ask Sarah about her teaching experience? I think I need to check my schedule for next week. What was I planning for dinner tonight?Oops, actually what I wanted to ask was: What is the age of someone whose occupation is Professor?`

- expected sids: 149, 163
- top-5 sids: 153, 164, 133, 139, 154

### [noisy tid=276] `I was just thinking about all the different birthdays coming up this month. It's funny how some people seem to remember every detail, while others just forget. Do you think it's important to celebrate them? What was that conversation we had about memorable parties?Wait a minute, what I wanted to ask is,What is the birthday of the person with the email address harper.sinclair@pinnaclewealth.com?`

- expected sids: 46, 48
- top-5 sids: 89, 153, 132, 66, 112

### [noisy tid=279] `I thought I saw her at the gym last week, but I can't recall if she's into yoga or weightlifting. What did you think of that new smoothie place? I wonder if they have protein options.What I really meant was,What position does someone with a hobby in fitness hold?`

- expected sids: 5, 12
- top-5 sids: 62, 46, 71, 57, 52

### [noisy tid=285] `I was thinking about the project deadline, but I'm not sure if I remembered to send that email. Have I checked my schedule for next week? There was something about the meeting time that seemed off. Did I leave the oven on?Oops, actually what I wanted to ask was: What is the company name of the person with the contact number 51002995394?`

- expected sids: 87, 105
- top-5 sids: 171, 32, 82, 81, 57

### [noisy tid=287] `Did you hear about that new café on the corner? I think they have a great selection of pastries. Oh, and what was that movie we watched last week? I loved the soundtrack. We should definitely plan a get-together soon. I can't believe how fast time flies!Actually, my real question is: What is the age of someone whose birthday is on 10/03?`

- expected sids: 168, 170
- top-5 sids: 47, 91, 68, 2, 148

### [noisy tid=294] `I saw someone painting in the park the other day, it looked quite interesting. Have you ever tried that new café downtown? I can't remember if I left my keys on the table or in my bag.Sorry about that, what I truly wanted to ask is,What hobby does a person who is 150 cm tall have?`

- expected sids: 97, 100
- top-5 sids: 7, 67, 130, 98, 45

### [noisy tid=296] `I was just thinking about the weather lately, it feels like winter is approaching faster than usual. Did you see that movie that everyone is talking about? I really need to check my calendar for next week.Sorry, what I truly wanted to ask is: What is the height of someone who is 32 years old?`

- expected sids: 130, 145
- top-5 sids: 64, 148, 149, 42, 87

### [noisy tid=298] `Did you see the weather this morning? I wonder if the store has any new products. It's been such a busy week, I can hardly keep track of everything! What was that movie everyone was talking about?Hold on, what I actually wanted to understand is: What is the contact number for the person who is 144 cm tall?`

- expected sids: 160, 168
- top-5 sids: 142, 80, 104, 4, 86

### [noisy tid=299] `Have you seen that new café downtown? I wonder if they have good coffee. I was thinking about going for a walk later; do you think it will rain?Wait a minute,What is the name of the person whose education is at the High School level?`

- expected sids: 50, 55
- top-5 sids: 16, 112, 52, 57, 94

### [noisy tid=305] `I heard someone talking about their favorite restaurant, but I can't recall the name. Did you see that movie that came out last summer? I wonder what I should have for dinner tonight.Hold on, what I actually wanted to understand is: What is the height of someone who is 32 years old?`

- expected sids: 154, 168
- top-5 sids: 0, 109, 22, 87, 67

### [noisy tid=308] `I was thinking about that new project we discussed last week, but I can't recall the details. Oh, and I really need to check when my friend’s birthday is... wasn't it sometime in the summer?Oh no, I actually wanted to figure out,What is the company name of the person whose birthday is on 07/31?`

- expected sids: 46, 58
- top-5 sids: 87, 150, 88, 145, 130

### [noisy tid=313] `I was thinking about that presentation last week, and I'm not sure if I sent the follow-up email. Do you remember when the next meeting is scheduled? It feels like an eternity since we last caught up with everyone.Sorry, what I truly wanted to ask is: What is the email address for the person with the contact number 81800676656?`

- expected sids: 71, 79
- top-5 sids: 141, 164, 42, 163, 120

### [noisy tid=317] `I was thinking about that trip to the mountains last year, you know, the one where we tried that new trail. It feels like ages ago, but the memories are still fresh. I should really check my schedule for the weekend. Did I mention I found an old book about gardening? It’s fascinating how time flies. What was I planning to cook for dinner?What I really meant was,What hobby does the person who is 35 years old have?`

- expected sids: 27, 32
- top-5 sids: 114, 97, 62, 119, 61

### [noisy tid=320] `I was thinking about that project we discussed last week, but I can't recall the deadline. Did we confirm the meeting time? Oh, what was that restaurant's name?Wait, what I really want to know is: What is the work location of the person whose birthday is on 02/04?`

- expected sids: 3, 14
- top-5 sids: 109, 67, 80, 141, 22

### [noisy tid=321] `I was thinking about that meeting we had last week, you know, the one with all the paperwork. It got me wondering when I need to send out those invitations. Does anyone remember when the project deadlines are?I got it wrong, what I really meant to ask is: What’s the birthday of the person whose occupation is a programmer?`

- expected sids: 138, 139
- top-5 sids: 92, 2, 86, 162, 4

### [noisy tid=323] `I was thinking about the last time we had a gathering, it feels like ages ago. Did I leave the lights on at home? Sometimes I forget the smallest details, like what I had for breakfast. Why is it so hard to remember names?Uh, hold on,What is the contact number for the person who is 34 years old?`

- expected sids: 158, 161
- top-5 sids: 136, 1, 12, 109, 43

### [noisy tid=329] `Have you seen that new café downtown? I heard they have the best pastries. I can't remember where I put my keys. It's so hard to keep track of everything these days. Did you watch that game last night?Uh, hold on,What is the contact number for someone with a High School education?`

- expected sids: 96, 97
- top-5 sids: 168, 15, 106, 120, 82

### [noisy tid=337] `I was thinking about taking a short trip soon, maybe somewhere warm? Did you see the new restaurant downtown? I forgot to pick up the groceries, didn't I? What time does the show start tonight?Oh right, I wanted to ask,What is the hobby of someone whose work location is Las Vegas, NV?`

- expected sids: 21, 41
- top-5 sids: 8, 66, 61, 161, 76

### [noisy tid=346] `You know, I saw a movie yesterday, and it really made me think. It's been a while since I've had a good cup of coffee, hasn’t it? I should probably call my friend; we haven't talked in ages.Sorry about that, what I truly wanted to ask is,What is the education level of someone who is 157 cm tall?`

- expected sids: 117, 119
- top-5 sids: 128, 1, 86, 150, 79

### [noisy tid=351] `I was thinking about that movie from last year, the one with the surprising twist at the end. Do you remember the name of the actress in it? It's such a beautiful day outside, maybe we should go for a walk later. I really need to organize my bookshelf; it's looking quite messy. Did I leave my keys on the kitchen counter?Hold on, what I actually wanted to understand is: What is the name of the person who is 22 years old?`

- expected sids: 112, 121
- top-5 sids: 62, 42, 23, 148, 21

### [noisy tid=358] `I was thinking about trying that new coffee place downtown, but I can't recall if they have a good ambiance. Did I leave my keys on the counter? It's been a while since I caught up with Sarah; I wonder how she's doing.What I really meant was,What hobbies does a 25-year-old have?`

- expected sids: 118, 119
- top-5 sids: 150, 6, 29, 5, 25

### [noisy tid=361] `I wonder if I should start that new book soon, I was thinking about the last time we went camping. Did I water the plants today?Wait, what I really want to know is: What is the hobby of someone who is 174cm tall?`

- expected sids: 39, 41
- top-5 sids: 7, 143, 111, 13, 67

### [noisy tid=364] `I saw someone today who really loved hiking, I wonder if that trail is still open. Did you hear about that new café downtown? It's been ages since I've seen that old movie.Oh no, I actually wanted to figure out,What is the hometown of someone who is 153cm tall?`

- expected sids: 0, 5
- top-5 sids: 171, 22, 157, 148, 28

### [noisy tid=367] `I was thinking about the weekend plans, but I can't remember who was in charge of the movie choice. Did we decide on pizza or something else? Oh, and what time does the meeting start again?Wait, what I really want to know is: What is the hometown of the person holding the position of Head Chef?`

- expected sids: 91, 94
- top-5 sids: 113, 116, 164, 92, 117

### [noisy tid=368] `Did you see that new cafe downtown? I wonder if they have good coffee. By the way, do you think it's going to rain later? I have to pick up groceries after work.Sorry about that, what I truly wanted to ask is,What is the education level of someone who is 24 years old?`

- expected sids: 127, 134
- top-5 sids: 118, 113, 25, 5, 117

### [noisy tid=369] `Did you see the news yesterday? I was thinking about that time we went hiking. I wonder if they'll have that new coffee flavor at the café. What was the name of that book we talked about?Sorry, what I truly wanted to ask is: What is the hometown of the person who is 39 years old?`

- expected sids: 111, 125
- top-5 sids: 44, 150, 66, 129, 88

### [noisy tid=378] `I was thinking about that new restaurant that opened last week, but I can't recall if it was Italian or Mexican. I wonder if it will rain tomorrow, my plants could really use some water. Did I leave the oven on?Wait, what I really want to know is: What is the email address of the person whose hobby is watching movies?`

- expected sids: 155, 160
- top-5 sids: 16, 107, 125, 63, 139

### [noisy tid=383] `I just saw a movie that was really interesting, but it slipped my mind what the title was. Oh, and there was something about a birthday party next week, wasn’t there? Can't believe how fast the year is flying by!Uh, hold on,What is the birthday of the person whose hobby is playing video games?`

- expected sids: 3, 16
- top-5 sids: 25, 88, 108, 150, 70

### [noisy tid=408] `I heard something interesting the other day about the education system. Did you catch that lecture last week? It's funny how much things can change over time. I wonder what the weather will be like tomorrow.Actually, my real question is: What's the occupation of someone with a PhD in education?`

- expected sids: 155, 160
- top-5 sids: 113, 137, 120, 121, 52

### [noisy tid=414] `I was thinking about that restaurant we used to go to, but I can't recall the exact location. Do you remember when we last visited? It feels like it was ages ago. And what about that movie we watched? I keep mixing up the titles.Hold on, what I actually wanted to understand is: What is the hometown of someone who is 33 years old?`

- expected sids: 164, 168
- top-5 sids: 28, 86, 124, 125, 65

### [noisy tid=419] `I saw a movie last night, it had such a captivating storyline. By the way, have you heard from Julia recently? She was talking about her plans for the weekend.Oh right, I wanted to ask,What is the birthday of someone who is 35 years old?`

- expected sids: 153, 163
- top-5 sids: 111, 0, 128, 15, 16

### [noisy tid=431] `I had a conversation with someone the other day, and they mentioned something about a project, but I can't recall the details. Do you remember where we put that document? It feels like ages since we last met, doesn't it?Sorry, what I truly wanted to ask is: What is the position of the person with the contact number 20202057981?`

- expected sids: 67, 69
- top-5 sids: 100, 126, 56, 148, 21

### [noisy tid=438] `Oh, I wonder what I should have for dinner tonight. It's been ages since I caught up with that friend. Speaking of which, when did I last check my calendar? I really need to organize my schedule.Oh no, I actually wanted to figure out,What is the occupation of someone whose birthday is on 08/03?`

- expected sids: 53, 56
- top-5 sids: 130, 2, 161, 23, 155

### [noisy tid=439] `I was thinking about the weather in different cities, and it reminded me of that trip I took last summer. You know, everyone has their own little quirks, like how some people like their coffee. What was that movie we were discussing last week?Actually, my real question is: What is the height of the person whose work location is Boston, MA?`

- expected sids: 113, 125
- top-5 sids: 4, 3, 108, 66, 17

### [noisy tid=440] `You know, I was thinking about that movie we watched last week, it really had some interesting plot twists. Did you ever try that restaurant on Main Street? I can't remember their hours, though. Also, did you hear about that concert coming up next month? I wonder if we can get tickets.Oh, what I truly wanted to clarify is,What hobbies do people with a high school education have?`

- expected sids: 76, 82
- top-5 sids: 110, 35, 27, 112, 22

### [noisy tid=447] `I was thinking about that new coffee place downtown, it was so crowded last time. Did you see the latest episode of that show? I need to check my schedule for next week. Is it just me, or is this weather so unpredictable lately?What I really meant was,For someone with a Bachelor's degree, what is their work location?`

- expected sids: 110, 118
- top-5 sids: 165, 47, 113, 157, 78

### [noisy tid=460] `It's interesting how people find their passions outside of work. I wonder if they like to explore nature on weekends. I should really plan a trip soon, maybe a hiking adventure. Did I lock the door before leaving?Hmm, actually my question was this: What is the hobby of someone whose occupation is a teacher?`

- expected sids: 94, 101
- top-5 sids: 74, 141, 11, 140, 157

### [noisy tid=462] `I was talking to a colleague the other day, and they mentioned a conference coming up soon. I wonder if it's near my friend's anniversary. I keep forgetting the date of that event; it feels like just yesterday. Have you heard about the new semester starting?I got it wrong, what I really meant to ask is: What’s the birthday of the person whose occupation is Professor?`

- expected sids: 132, 139
- top-5 sids: 44, 65, 90, 88, 151

### [noisy tid=467] `You know, I was thinking about that movie we watched last week. It's funny how birthdays can sneak up on you. I really enjoyed that restaurant we went to as well. By the way, do you remember when we used to play video games all night?Hold on, what I actually wanted to understand is: What is the birthday of the person whose hobby is playing video games?`

- expected sids: 69, 76
- top-5 sids: 45, 90, 107, 89, 49

### [noisy tid=468] `I heard there was a discussion about the new project design, but I don't recall the details. Did you catch the last meeting on the budget? Oh, what's for lunch today?I got it wrong, what I really meant to ask is: What is the name of the person whose occupation is Engineer?`

- expected sids: 42, 55
- top-5 sids: 84, 49, 121, 87, 70

### [noisy tid=469] `I heard someone talking about their weekend plans, but they seemed a bit vague. Did you catch that new coffee shop that opened? I wonder what the weather will be like next week. There's a movie I've been meaning to watch, but I can't remember the title.Wait a minute,What is the hometown of someone whose work location is Orlando, FL?`

- expected sids: 13, 22
- top-5 sids: 170, 52, 28, 97, 164

### [noisy tid=472] `I was thinking about that new coffee shop downtown, have you tried their pastries? It’s been a while since I caught up with Sarah, I wonder how her project is going. By the way, did you see that movie everyone’s talking about?What I really meant was,What is the contact number for someone whose work location is Portland, OR?`

- expected sids: 84, 86
- top-5 sids: 54, 164, 34, 165, 36

### [noisy tid=474] `I saw a new project proposal floating around, but I can't recall the details. I wonder if the meeting about that was last week or the week before. Oh, and did I send the birthday wishes? I need to check on that.Sorry about that, what I truly wanted to ask is,What is the company name for someone whose birthday is on 05/23?`

- expected sids: 118, 120
- top-5 sids: 149, 85, 21, 19, 139

### [noisy tid=480] `I was thinking about that restaurant we visited last summer, the one near the park. I can't seem to recall the name of the dish I ordered. Did I leave my keys on the kitchen counter?Wait a minute, what I wanted to ask is,What is the company name of the person who is 170cm tall?`

- expected sids: 138, 143
- top-5 sids: 101, 167, 48, 22, 164

### [noisy tid=481] `I saw a beautiful painting at the gallery yesterday, the colors were so vibrant. Speaking of colors, what's your favorite season? I wonder if they still make that dessert I used to love.I got it wrong, what I really meant to ask is: What is the age of someone whose birthday is on 04/11?`

- expected sids: 72, 76
- top-5 sids: 137, 145, 87, 144, 45

### [noisy tid=485] `I was thinking about that movie we watched last week, it had that great soundtrack. You know, the weather has been quite unpredictable lately. Did you remember to bring that book I lent you?Uh, hold on,What is the email address of the person who has camping as a hobby?`

- expected sids: 56, 65
- top-5 sids: 154, 155, 133, 153, 168

### [noisy tid=493] `It's strange, I thought I had the report right here. Did you see my glasses? I wonder if we need to reschedule that meeting. What time does the café close again?Actually, my real question is: What is the occupation of the person with the contact number 51007332765?`

- expected sids: 42, 47
- top-5 sids: 138, 37, 15, 122, 151

### [post_processing tid=25] `What are the last three digits of the contact number for the person who works as a salesperson, and what is their sum?`

- expected sids: 117, 120
- top-5 sids: 37, 166, 99, 47, 112

### [post_processing tid=26] `What are the main responsibilities of a 31-year-old in their profession?`

- expected sids: 120, 122
- top-5 sids: 0, 9, 49, 22, 121

### [post_processing tid=28] `What is the primary responsibility of a 32-year-old in their job?`

- expected sids: 51, 62
- top-5 sids: 119, 65, 136, 133, 69

### [post_processing tid=32] `In which season does the birthday of a person who works in Las Vegas, NV fall?`

- expected sids: 169, 171
- top-5 sids: 75, 132, 11, 49, 122

### [post_processing tid=34] `What is the email address suffix for people working in Portland, OR?`

- expected sids: 66, 69
- top-5 sids: 37, 53, 17, 27, 8

### [post_processing tid=40] `What are the main interests and hobbies of people living and working in Portland, OR?`

- expected sids: 43, 55
- top-5 sids: 157, 13, 76, 151, 35

### [post_processing tid=48] `What are some common interests and hobbies for a 24-year-old?`

- expected sids: 121, 127
- top-5 sids: 149, 136, 101, 161, 94

### [post_processing tid=51] `In which season does someone with a high school education have their birthday?`

- expected sids: 2, 17
- top-5 sids: 97, 53, 132, 139, 87

### [post_processing tid=65] `What are the main interests and hobbies of a person who is 163 cm tall?`

- expected sids: 23, 38
- top-5 sids: 110, 87, 4, 46, 129

### [post_processing tid=66] `What are the main responsibilities of a person who is 168cm tall?`

- expected sids: 8, 19
- top-5 sids: 90, 67, 164, 131, 39

### [post_processing tid=76] `What are the main responsibilities of the person with the contact number 65003215995 in their job?`

- expected sids: 48, 52
- top-5 sids: 98, 147, 71, 121, 149

### [post_processing tid=79] `What does the work location look like for someone based in Chicago, IL?`

- expected sids: 67, 71
- top-5 sids: 129, 45, 28, 86, 158

### [post_processing tid=86] `For someone who works in New York, NY, which of the following options would best describe their workplace?`

- expected sids: 51, 56
- top-5 sids: 27, 42, 78, 69, 4

### [post_processing tid=87] `During which season does the person with the contact number 31004592259 celebrate their birthday?`

- expected sids: 129, 130
- top-5 sids: 23, 79, 159, 38, 67

### [post_processing tid=89] `What is the sum of the last three digits of the contact number for the person who is 35 years old?`

- expected sids: 1, 4
- top-5 sids: 54, 66, 122, 139, 100

### [post_processing tid=95] `For a teacher, in which season does their birthday fall?`

- expected sids: 130, 141
- top-5 sids: 109, 135, 2, 144, 147

### [post_processing tid=124] `What are the main interests and hobbies of the individual with the contact number 85801168355?`

- expected sids: 112, 128
- top-5 sids: 166, 83, 143, 162, 161

### [post_processing tid=147] `What are the key responsibilities of someone with a high school education in their job?`

- expected sids: 87, 97
- top-5 sids: 112, 28, 77, 153, 168

### [post_processing tid=155] `What is the total of the last six digits of the contact number for the person who is 26 years old?`

- expected sids: 88, 90
- top-5 sids: 164, 54, 80, 148, 11

### [post_processing tid=167] `What are the main interests and hobbies of a 39-year-old person?`

- expected sids: 156, 158
- top-5 sids: 127, 115, 0, 82, 142

### [post_processing tid=170] `What are the main responsibilities of a 35-year-old in their job?`

- expected sids: 50, 59
- top-5 sids: 72, 87, 130, 116, 71

### [post_processing tid=186] `How many letters are in the names of people who work in Austin, TX?`

- expected sids: 1, 2
- top-5 sids: 79, 158, 55, 139, 150

### [post_processing tid=199] `What are Finn Caldwell's main interests and hobbies?`

- expected sids: 92, 97
- top-5 sids: 86, 103, 87, 94, 98

### [post_processing tid=202] `What is the total of the last three digits of the contact number for the individual whose hobby is watching movies?`

- expected sids: 23, 33
- top-5 sids: 93, 97, 76, 143, 94

### [post_processing tid=213] `What is the total of the last six digits of the contact number for individuals whose work location is in Chicago, IL?`

- expected sids: 66, 73
- top-5 sids: 107, 96, 142, 159, 18

### [post_processing tid=220] `What are the main interests and hobbies of a 32-year-old?`

- expected sids: 29, 30
- top-5 sids: 72, 44, 156, 108, 74

### [post_processing tid=226] `What is the total of the last five digits of Savannah Cole's contact number?`

- expected sids: 112, 127
- top-5 sids: 130, 110, 103, 14, 41

### [post_processing tid=235] `What email address suffix would someone from Miami, FL have?`

- expected sids: 154, 169
- top-5 sids: 133, 121, 89, 1, 62

### [post_processing tid=239] `What are the main interests and hobbies of a person with a high school education?`

- expected sids: 93, 100
- top-5 sids: 8, 9, 70, 25, 10

### [post_processing tid=246] `What is the email address suffix for someone who is 29 years old?`

- expected sids: 43, 59
- top-5 sids: 71, 107, 41, 0, 129

### [post_processing tid=247] `What are the main responsibilities of someone born on May 30th?`

- expected sids: 96, 100
- top-5 sids: 44, 2, 82, 23, 131

### [post_processing tid=255] `How many letters are in the name of a person whose job is Doctor?`

- expected sids: 22, 24
- top-5 sids: 6, 111, 114, 88, 150

### [post_processing tid=263] `What is the email address suffix for someone who is 147 cm tall?`

- expected sids: 107, 120
- top-5 sids: 27, 148, 42, 64, 165

### [post_processing tid=267] `What season does a doctor celebrate their birthday in?`

- expected sids: 23, 31
- top-5 sids: 133, 140, 66, 154, 136

### [post_processing tid=289] `In which season does someone whose hobby is fitness celebrate their birthday?`

- expected sids: 48, 60
- top-5 sids: 131, 68, 113, 155, 91

### [post_processing tid=290] `What are the main responsibilities for someone working in Denver, CO?`

- expected sids: 21, 36
- top-5 sids: 47, 6, 29, 93, 133

### [post_processing tid=292] `During which season does a musician celebrate their birthday?`

- expected sids: 33, 38
- top-5 sids: 127, 66, 122, 152, 86

### [post_processing tid=297] `Which of these descriptions would apply to someone who works in Denver, CO?`

- expected sids: 7, 9
- top-5 sids: 27, 94, 156, 127, 75

### [post_processing tid=314] `What are the typical interests and hobbies of a person with a Bachelor's degree?`

- expected sids: 23, 27
- top-5 sids: 144, 8, 73, 70, 92

### [post_processing tid=316] `In which season does a person who is 170 cm tall celebrate their birthday?`

- expected sids: 11, 18
- top-5 sids: 153, 131, 24, 132, 54

### [post_processing tid=335] `What is the email address suffix for someone who has a High School education?`

- expected sids: 161, 163
- top-5 sids: 110, 48, 87, 70, 96

### [post_processing tid=346] `What are the main responsibilities of a 27-year-old in their profession?`

- expected sids: 145, 149
- top-5 sids: 114, 22, 28, 113, 44

### [post_processing tid=348] `What are the key responsibilities of a 23-year-old in their profession?`

- expected sids: 79, 81
- top-5 sids: 133, 161, 151, 117, 43

### [post_processing tid=350] `What are the typical interests and hobbies of a 25-year-old?`

- expected sids: 118, 125
- top-5 sids: 17, 43, 94, 137, 95

### [post_processing tid=360] `What are the main interests and hobbies of a person who is 169 cm tall?`

- expected sids: 125, 126
- top-5 sids: 152, 72, 1, 45, 88

### [post_processing tid=383] `What are the main interests and hobbies of someone born on August 14th?`

- expected sids: 51, 56
- top-5 sids: 108, 2, 86, 74, 161

### [post_processing tid=389] `What are the main interests and hobbies of a person who is 162 cm tall?`

- expected sids: 160, 167
- top-5 sids: 106, 66, 1, 128, 86

### [post_processing tid=399] `What is the sum of the last three digits of Maya Sullivan's contact number?`

- expected sids: 31, 42
- top-5 sids: 36, 121, 163, 22, 164

### [post_processing tid=410] `What season does someone who is 140cm tall have their birthday in?`

- expected sids: 32, 37
- top-5 sids: 107, 44, 85, 109, 2

### [post_processing tid=419] `For someone who works in Atlanta, GA, which of the following descriptions best fits their workplace?`

- expected sids: 47, 56
- top-5 sids: 11, 162, 152, 135, 167

### [post_processing tid=422] `What are the main interests and hobbies of the person with the contact number 65002084084?`

- expected sids: 64, 72
- top-5 sids: 8, 50, 55, 37, 13

### [post_processing tid=429] `What are the main job responsibilities for someone with a high school education?`

- expected sids: 156, 169
- top-5 sids: 51, 4, 33, 114, 116

### [post_processing tid=436] `How many letters are in the names of people who work in Austin, TX?`

- expected sids: 66, 68
- top-5 sids: 106, 115, 62, 14, 63

### [post_processing tid=448] `What are the key responsibilities of a 30-year-old person in their job?`

- expected sids: 111, 116
- top-5 sids: 126, 25, 132, 44, 67

### [post_processing tid=454] `What are the main responsibilities of a 28-year-old in their profession?`

- expected sids: 88, 91
- top-5 sids: 19, 41, 50, 23, 95

### [post_processing tid=457] `What is the email address domain for a Police Officer?`

- expected sids: 108, 120
- top-5 sids: 41, 77, 37, 73, 65

### [post_processing tid=485] `How many letters are there in the names of individuals who work in Austin, TX?`

- expected sids: 95, 104
- top-5 sids: 101, 31, 76, 85, 125

### [post_processing tid=494] `What is the total of the last five digits of the contact number for a person whose birthday falls on February 15th?`

- expected sids: 0, 16
- top-5 sids: 77, 143, 57, 89, 167

