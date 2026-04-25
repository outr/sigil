# Sigil ConvoMem Benchmark Results

**Date:** 2026-04-25T19:10:41.255502028Z
**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)
**Retrieval:** vanilla cosine
**Score:** 6941/7000 (99.2% R@1)

## Per-category accuracy

| Category | Correct | Total | Accuracy |
|---|---|---|---|
| abstention_evidence | 2659 | 2710 | 98.1% |
| assistant_facts_evidence | 2700 | 2705 | 99.8% |
| changing_evidence | 759 | 759 | 100.0% |
| user_evidence | 823 | 826 | 99.6% |

## Failures (59)

### [user_evidence / 1_evidence / batched_020.json] `I'm filling out a form for a new utility service and it's asking for my move-in date. I can't remember exactly what I told you before. Can you remind me how long ago I said I moved into my current house?`

- expected conv indices: 0
- top-1 ranked: 1
- top-K snippets:
  1. Hey, good morning. I've got my coffee and I'm ready to dive into some major planning. Let's start mapping out this year's Innovate Summit.

### [user_evidence / 1_evidence / batched_020.json] `I'm filling out this online form for car insurance and it's asking for my vehicle details. Could you remind me of the year and model of my car?`

- expected conv indices: 0
- top-1 ranked: 1
- top-K snippets:
  1. Hey, I've been thinking about updating my LinkedIn profile. It's been a while since I last did that.

### [user_evidence / 1_evidence / batched_020.json] `A friend is offering to grab me coffee, what should I tell them my usual order is?`

- expected conv indices: 0
- top-1 ranked: 1
- top-K snippets:
  1. Okay, can you help me structure my week? I have three high-priority accounts: Globex Corp, Initech, and Vandelay Industries. I need to make 

### [assistant_facts_evidence / 1_evidence / batched_040.json] `Can you remind me of the art project you suggested I try?`

- expected conv indices: 2
- top-1 ranked: 1
- top-K snippets:
  1. Hey, can you help me brainstorm for a bit? I have a new project kicking off next week and I'm putting together my initial project plan and r

### [assistant_facts_evidence / 1_evidence / batched_040.json] `Can you remind me of the DIY project you suggested for this weekend?`

- expected conv indices: 1
- top-1 ranked: 2
- top-K snippets:
  1. Hi, I've had such a long week. I need your help brainstorming some ideas for the weekend.

### [assistant_facts_evidence / 1_evidence / batched_040.json] `A while back I asked for CRM recommendations. Which specific system did you suggest for me, and what was the key reason you gave for that recommendation?`

- expected conv indices: 2
- top-1 ranked: 4
- top-K snippets:
  1. Hey, I've been thinking about how to better manage my client relationships. Do you have any suggestions?

### [assistant_facts_evidence / 1_evidence / batched_040.json] `You previously gave me a recommendation for bringing on new team members to ensure a consistent experience. What was the specific name of the onboarding plan you suggested I create?`

- expected conv indices: 1
- top-1 ranked: 4
- top-K snippets:
  1. Hey, can I pick your brain for a minute? I'm feeling a bit swamped with a task my manager just gave me.

### [assistant_facts_evidence / 1_evidence / batched_040.json] `You previously mentioned a meal delivery service that could help me on my busy weeks. What was its name again?`

- expected conv indices: 2
- top-1 ranked: 4
- top-K snippets:
  1. Hey, I've been thinking about how to eat healthier during my busy work weeks. Do you have any simple meal prep ideas?

### [abstention_evidence / 1_evidence / batched_040.json] `What is the exact date of the concert Sarah is attending?`

- expected conv indices: 0
- top-1 ranked: 4
- top-K snippets:
  1. Hi, I've been diving into the new compliance initiative at the bank. It's quite a comprehensive framework.

### [abstention_evidence / 1_evidence / batched_040.json] `For a genealogy search I'm working on, can you tell me my mother's maiden name?`

- expected conv indices: 0
- top-1 ranked: 3
- top-K snippets:
  1. Morning. It's the start of a new week and I want to get my head around Q4. Can you pull up my main objectives for the quarter?

### [abstention_evidence / 1_evidence / batched_040.json] `What is the specific deadline for submitting new articles for this month's review cycle at the CRM company?`

- expected conv indices: 2
- top-1 ranked: 0
- top-K snippets:
  1. Hey, I have my quarterly performance review with my manager, Maria, next week. Can you help me organize my thoughts and key achievements fro

### [abstention_evidence / 1_evidence / batched_040.json] `What was the name of my direct manager at the first tech startup where I worked as a customer support associate?`

- expected conv indices: 0
- top-1 ranked: 4
- top-K snippets:
  1. Hey, Assistant. I was just reviewing the Q3 retention numbers, and while they're solid, I can't shake this feeling that we could be even mor

### [abstention_evidence / 1_evidence / batched_040.json] `What is the name of the restaurant Emma considers her favorite in her Chicago neighborhood?`

- expected conv indices: 2
- top-1 ranked: 1
- top-K snippets:
  1. Hi there, Assistant. Hope you're having a good week. I was just reviewing some of our Q2 campaign performance data and had a thought I wante

### [abstention_evidence / 1_evidence / batched_040.json] `Based on our previous conversations, what is my exact birth date, including the month and day?`

- expected conv indices: 4
- top-1 ranked: 3
- top-K snippets:
  1. Hey, can you pull up the latest activity log for the Northwood Manufacturing account?

### [abstention_evidence / 1_evidence / batched_040.json] `On which specific date is the new team member scheduled to start working with Emma's Returns & Exchanges team?`

- expected conv indices: 1
- top-1 ranked: 0
- top-K snippets:
  1. Good morning. Can you pull up my project board for the Aperture Labs implementation?

### [abstention_evidence / 1_evidence / batched_040.json] `What specific feedback did the client give about the CRM's performance during their onboarding process?`

- expected conv indices: 2
- top-1 ranked: 0
- top-K snippets:
  1. Good morning. Can you pull up my project board for the Aperture Labs implementation?

### [abstention_evidence / 1_evidence / batched_040.json] `What is the name of the sibling Alex spoke to last night?`

- expected conv indices: 4
- top-1 ranked: 3
- top-K snippets:
  1. Hey Alex, how's the week shaping up?

### [abstention_evidence / 1_evidence / batched_040.json] `What is my username for my Qobuz account?`

- expected conv indices: 4
- top-1 ranked: 2
- top-K snippets:
  1. Hey, can you pull up my current Q3 regional sales forecast? I want to see the latest numbers from the CRM.

### [abstention_evidence / 1_evidence / batched_040.json] `I'm filling out the new parking permit form for the office, and I've blanked on my license plate number. What is it?`

- expected conv indices: 0
- top-1 ranked: 1
- top-K snippets:
  1. Hey, can you get my schedule for tomorrow? I feel like I'm forgetting something.

### [abstention_evidence / 1_evidence / batched_040.json] `Based on my chat history, on what specific date did I move into my current apartment in the Rosemont neighborhood?`

- expected conv indices: 4
- top-1 ranked: 0
- top-K snippets:
  1. This week has been relentless. I'm already looking at my weekend to-do list and it feels like a second job.

### [abstention_evidence / 1_evidence / batched_040.json] `I'm drafting an update for my director and need to include the key stakeholders. Can you remind me, who was mentioned as the executive sponsor for 'Project Phoenix'?`

- expected conv indices: 0
- top-1 ranked: 3
- top-K snippets:
  1. Okay, I need to think out loud for a minute. I'm trying to scope out a new project and my brain is just hitting a wall.

### [abstention_evidence / 1_evidence / batched_040.json] `What specific reason did Sarah give for switching her major from pre-med to business during her college years?`

- expected conv indices: 0
- top-1 ranked: 2
- top-K snippets:
  1. Hey, can you help me brainstorm something? I have a career development one-on-one with one of my best CSMs tomorrow and I feel a bit stuck.

### [abstention_evidence / 1_evidence / batched_040.json] `What is Alex's current savings account balance?`

- expected conv indices: 0
- top-1 ranked: 1
- top-K snippets:
  1. Ugh, this ticket is going to be the end of me. I've been staring at this error log for hours.

### [abstention_evidence / 1_evidence / batched_040.json] `Based on our previous conversations, what was the official start date for the team member's Performance Improvement Plan (PIP)?`

- expected conv indices: 2
- top-1 ranked: 1
- top-K snippets:
  1. Hey, can you pull up my main Q3 performance dashboard for the sales team?

### [abstention_evidence / 1_evidence / batched_040.json] `What is my manager's last name?`

- expected conv indices: 1
- top-1 ranked: 2
- top-K snippets:
  1. Assistant, let's do a final check before we kick off the big Q2 review meeting in 15 minutes.

### [abstention_evidence / 1_evidence / batched_040.json] `What is the name of the city Emma recently moved to?`

- expected conv indices: 2
- top-1 ranked: 3
- top-K snippets:
  1. Hey, I've been thinking about how we can improve our customer interactions. Have you noticed any recurring requests from customers lately?

### [abstention_evidence / 1_evidence / batched_040.json] `What is the specific SLA target for response times in our current service level agreements?`

- expected conv indices: 0
- top-1 ranked: 3
- top-K snippets:
  1. Good afternoon. Can you pull up my active project file for the new CRM implementation?

### [abstention_evidence / 1_evidence / batched_040.json] `What is the exact time Emma's presentation to senior leadership is scheduled to begin?`

- expected conv indices: 1
- top-1 ranked: 3
- top-K snippets:
  1. Good afternoon. Can you pull up my active project file for the new CRM implementation?

### [abstention_evidence / 1_evidence / batched_040.json] `My manager is asking for developer referrals and I mentioned my cousin who is in tech. Can you remind me, what's the name of the company he works for?`

- expected conv indices: 3
- top-1 ranked: 1
- top-K snippets:
  1. Hey, can you help me structure a document? I need to write a post-mortem for a pretty nasty escalation that we just closed.

### [abstention_evidence / 1_evidence / batched_040.json] `Based on our conversations, can you tell me the specific make and model of my car?`

- expected conv indices: 1
- top-1 ranked: 2
- top-K snippets:
  1. Morning. Can you pull up the project details for the 'Catalyst Analytics Implementation' project? I need to prep the final close-out report.

### [abstention_evidence / 1_evidence / batched_040.json] `What is the name of the dog sitter Alex used the last time he went away?`

- expected conv indices: 4
- top-1 ranked: 1
- top-K snippets:
  1. Hey, I need to strategize for the end of the quarter. Can you help me think through some ways to boost my numbers?

### [abstention_evidence / 1_evidence / batched_040.json] `Based on our previous conversations, what was the exact calendar date, including month, day, and year, that I officially moved into my new house?`

- expected conv indices: 4
- top-1 ranked: 3
- top-K snippets:
  1. Hey, I've been thinking about how my career has evolved over the years.

### [abstention_evidence / 1_evidence / batched_040.json] `Based on our previous conversations, what was the name of the high school Elena Vargas graduated from?`

- expected conv indices: 1
- top-1 ranked: 4
- top-K snippets:
  1. Good morning. Can you help me organize my thoughts for the Q3 strategic HR planning deck I need to present to leadership next week?

### [abstention_evidence / 1_evidence / batched_040.json] `Based on our previous conversations, what is the exact distance of my usual running route along the waterfront?`

- expected conv indices: 1
- top-1 ranked: 4
- top-K snippets:
  1. Hey there. It's been one of those weeks. I feel like I'm drowning in data.

### [abstention_evidence / 1_evidence / batched_040.json] `In which city do my parents live, based on what I've told you about my weekend plans?`

- expected conv indices: 0
- top-1 ranked: 2
- top-K snippets:
  1. Hey, I've been thinking about optimizing our CRM workflows. Do you have any suggestions on where to start?

### [abstention_evidence / 1_evidence / batched_040.json] `Based on what I've told you about my education, can you remind me of the name of the high school I attended before going to UC Berkeley?`

- expected conv indices: 3
- top-1 ranked: 1
- top-K snippets:
  1. Hey, I've been thinking about how much time I spend on perfecting reports. It's a bit of a habit from my days at TechFlow.

### [abstention_evidence / 1_evidence / batched_040.json] `What is the exact monthly rent I should budget for my new apartment?`

- expected conv indices: 1
- top-1 ranked: 2
- top-K snippets:
  1. Ugh, what a day. I think my brain is completely fried.

### [abstention_evidence / 1_evidence / batched_040.json] `What was the monthly rent for Liam's old downtown apartment?`

- expected conv indices: 0
- top-1 ranked: 3
- top-K snippets:
  1. Hey, I need some help organizing my finances. I feel like I'm just winging it every month.

### [abstention_evidence / 1_evidence / batched_040.json] `Where did Sarah travel for her budget-friendly trip after her customer service role?`

- expected conv indices: 2
- top-1 ranked: 0
- top-K snippets:
  1. Assistant, I'm trying to optimize our lead nurturing sequences for the upcoming quarter. I'm thinking of segmenting based on engagement leve

### [abstention_evidence / 1_evidence / batched_040.json] `What are the first names of Emma's parents?`

- expected conv indices: 2
- top-1 ranked: 4
- top-K snippets:
  1. Hey, it's been a really long week. I need to get out of the city this weekend.

### [abstention_evidence / 1_evidence / batched_040.json] `What is Sarah's password for the streaming service she recommended?`

- expected conv indices: 1
- top-1 ranked: 0
- top-K snippets:
  1. I feel like I'm drowning in technical specs and security reports. Can you help me find something interesting to read that's completely unrel

### [abstention_evidence / 1_evidence / batched_040.json] `Based on our previous conversations, what profession was my ex-wife in?`

- expected conv indices: 0
- top-1 ranked: 2
- top-K snippets:
  1. Hey, I've been thinking about how technology has evolved since my college days. It's fascinating how much has changed.

### [abstention_evidence / 1_evidence / batched_040.json] `What was the codename of the project I approved against the data a few months ago?`

- expected conv indices: 2
- top-1 ranked: 0
- top-K snippets:
  1. Good morning. Can you pull up the executive summary of our Q2 operational performance metrics?

### [abstention_evidence / 1_evidence / batched_040.json] `What is the make and model of Alex's car?`

- expected conv indices: 4
- top-1 ranked: 2
- top-K snippets:
  1. Hey, I'm thinking about something and could use some information. Now that I'm in my new place in Boulder, I finally have a yard. It got me 

### [abstention_evidence / 1_evidence / batched_040.json] `Who is my home internet provider?`

- expected conv indices: 1
- top-1 ranked: 3
- top-K snippets:
  1. Hey, can you help me get my thoughts organized? I'm trying to track all my recent major expenses since the move.

### [abstention_evidence / 1_evidence / batched_040.json] `What is the exact distance, in miles, of Sarah's new morning running loop through the park near her home?`

- expected conv indices: 3
- top-1 ranked: 4
- top-K snippets:
  1. Good morning. Can you help me structure my talking points for the weekly executive leadership meeting this afternoon?

### [abstention_evidence / 1_evidence / batched_040.json] `What was the most exciting part of our weekend adventure?`

- expected conv indices: 4
- top-1 ranked: 2
- top-K snippets:
  1. Hello, I'm looking for some ideas for the upcoming weekend. I'm still new to San Francisco and want to do some exploring.

### [abstention_evidence / 1_evidence / batched_040.json] `What is the official name of the new product feature launching soon?`

- expected conv indices: 4
- top-1 ranked: 3
- top-K snippets:
  1. Ugh, I am so over this quarter. I feel like I've been staring at my CRM dashboard for a month straight.

### [abstention_evidence / 1_evidence / batched_040.json] `Considering what I've mentioned before, who did my favorite team, the Golden State Warriors, play against in their most recent match?`

- expected conv indices: 1
- top-1 ranked: 2
- top-K snippets:
  1. Ugh, I've got a big project status meeting with senior leadership tomorrow and my anxiety is already kicking in. I hate these things.

### [abstention_evidence / 1_evidence / batched_040.json] `Ugh, I just got my first San Francisco parking ticket. I'm trying to pay it online, but I can't for the life of me remember my car's license plate number. Can you look it up for me?`

- expected conv indices: 1
- top-1 ranked: 0
- top-K snippets:
  1. Morning. I need to pick your brain about something. I'm hitting a wall with an integration.

### [abstention_evidence / 1_evidence / batched_040.json] `What make and model is my car?`

- expected conv indices: 0
- top-1 ranked: 1
- top-K snippets:
  1. Hey, I've been having a rough time with my phone lately.

### [abstention_evidence / 1_evidence / batched_040.json] `What is the name of the moving company Alex used for his recent move into the city?`

- expected conv indices: 3
- top-1 ranked: 0
- top-K snippets:
  1. Good morning, Alex. Hope you had a good start to the week. How are things looking on the CRM knowledge base update for the new enterprise fe

### [abstention_evidence / 1_evidence / batched_040.json] `I'm updating my car insurance information and the form is asking for my vehicle's details. Can you remind me what the model of my car is?`

- expected conv indices: 3
- top-1 ranked: 4
- top-K snippets:
  1. Hey, can we go over my schedule for the week? I have a few important calls lined up.

### [abstention_evidence / 1_evidence / batched_040.json] `What were the main findings in the sales report on market trends?`

- expected conv indices: 0
- top-1 ranked: 3
- top-K snippets:
  1. Hi! Do you have a moment? I wanted to bounce some ideas off you regarding our next big fundraising push, especially after what we learned fr

### [abstention_evidence / 1_evidence / batched_040.json] `What is the make and model of Alex's car?`

- expected conv indices: 0
- top-1 ranked: 4
- top-K snippets:
  1. Hey, how's it going today?

### [abstention_evidence / 1_evidence / batched_040.json] `What are the names of Emma's two younger siblings?`

- expected conv indices: 3
- top-1 ranked: 2
- top-K snippets:
  1. Good morning! Just got into the office, already deep diving into the Q3 support metrics. There's a slight uptick in ticket resolution time f

### [abstention_evidence / 1_evidence / batched_040.json] `I'm filling out the parking permit application for my building and need my car's license plate number. Can you look that up for me?`

- expected conv indices: 3
- top-1 ranked: 1
- top-K snippets:
  1. Hey, can you help me with something? I just finished that required training on data privacy, but I can't remember the exact title. It was qu

### [abstention_evidence / 1_evidence / batched_040.json] `What specific item of furniture is Sarah planning to buy for her apartment?`

- expected conv indices: 1
- top-1 ranked: 2
- top-K snippets:
  1. Hey, I need some help organizing my finances. I feel like my spending has gotten a bit out of control since I moved.

### [abstention_evidence / 1_evidence / batched_040.json] `Based on our last conversation, what specific cycling trail or park did I mention I was planning to visit this weekend?`

- expected conv indices: 2
- top-1 ranked: 3
- top-K snippets:
  1. Hey, what a long day. Just got back from a client site way out in the suburbs.

