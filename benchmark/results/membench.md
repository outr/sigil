# Sigil MemBench Benchmark Results

**Date:** 2026-04-25T17:17:52.917356972Z
**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)
**Agent:** FirstAgent
**Retrieval:** vanilla cosine
**Score:** 656/700 (93.7% R@5)

## Per-category accuracy

| Category | Correct | Total | Accuracy |
|---|---|---|---|
| aggregative | 100 | 100 | 100.0% |
| comparative | 100 | 100 | 100.0% |
| conditional | 97 | 100 | 97.0% |
| knowledge_update | 99 | 100 | 99.0% |
| noisy | 77 | 100 | 77.0% |
| post_processing | 84 | 100 | 84.0% |
| simple | 99 | 100 | 99.0% |

## Failures (44)

### [simple tid=54] `What is the work location of the coworker?`

- expected sids: 118
- top-5 sids: 113, 132, 48, 101, 112

### [knowledge_update tid=94] `What is the hobby of my aunt?`

- expected sids: 90
- top-5 sids: 184, 74, 33, 149, 106

### [conditional tid=8] `What is the height of someone from Austin, TX?`

- expected sids: 75, 79
- top-5 sids: 87, 150, 26, 19, 108

### [conditional tid=88] `What is the height of the person who is 31 years old?`

- expected sids: 21, 40
- top-5 sids: 63, 86, 17, 148, 89

### [conditional tid=93] `What is the email address for the person with the contact number 30506814045?`

- expected sids: 46, 50
- top-5 sids: 164, 141, 15, 142, 165

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
- top-5 sids: 69, 6, 28, 70, 99

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

