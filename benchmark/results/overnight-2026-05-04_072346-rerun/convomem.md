# Sigil ConvoMem Benchmark Results

**Date:** 2026-05-04T14:09:21.266988522Z
**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)
**Retrieval:** vanilla cosine
**Score:** 1046/1078 (97.0% R@1)

## Per-category accuracy

| Category | Correct | Total | Accuracy |
|---|---|---|---|
| changing_evidence | 759 | 759 | 100.0% |
| implicit_connection_evidence | 287 | 319 | 90.0% |

## Failures (32)

### [implicit_connection_evidence / 1_evidence / batched_000.json] `I'm in a bit of a cooking rut and need some inspiration. Do you have any suggestions for a simple but satisfying dinner I could make this week?`

- expected conv indices: 0
- top-1 ranked: 1
- top-K snippets:
  1. Ugh, what a day. I feel like I've been running a marathon but haven't moved from my chair.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `A close friend is visiting me in Chicago for the weekend. Could you help me brainstorm a list of fun activities and places to go?`

- expected conv indices: 0
- top-1 ranked: 2
- top-K snippets:
  1. Hey, I'm trying to settle into a good routine at my new job and in the new city. It feels a bit overwhelming. Do you have any tips for manag

### [implicit_connection_evidence / 1_evidence / batched_000.json] `I'm feeling a bit restless lately and am looking to pick up a new hobby. Do you have any interesting suggestions?`

- expected conv indices: 1
- top-1 ranked: 2
- top-K snippets:
  1. Hey, I'm trying to settle into a good routine at my new job and in the new city. It feels a bit overwhelming. Do you have any tips for manag

### [implicit_connection_evidence / 1_evidence / batched_000.json] `What key accomplishments should I highlight in my self-assessment this quarter?`

- expected conv indices: 1
- top-1 ranked: 0
- top-K snippets:
  1. Alright, it's that time of the quarter again. Can you pull the pipeline generation and revenue attribution reports for my top five partners 

### [implicit_connection_evidence / 1_evidence / batched_000.json] `I'm wrapping up work now. Any ideas for what I should make for dinner tonight?`

- expected conv indices: 2
- top-1 ranked: 0
- top-K snippets:
  1. Ugh, what a morning. I've been staring at the same renewal contract for three hours straight. The legal jargon is starting to blur together.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `My colleague just sent an 'urgent' request to pull some performance data for a report. It's after hours now. Can you help me write a quick reply?`

- expected conv indices: 1
- top-1 ranked: 2
- top-K snippets:
  1. Hey, can you help me brainstorm some key talking points for my Q3 review with the leadership team?

### [implicit_connection_evidence / 1_evidence / batched_000.json] `Okay team, I'm starting to build out the marketing plan for next quarter. What are your initial thoughts on the main theme for our Q3 campaign?`

- expected conv indices: 2
- top-1 ranked: 1
- top-K snippets:
  1. Hey, I just wrapped up a massive discovery call with the team at DataCorp. My head is spinning with all the details.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `On a different note, I'm looking to build a more structured evening routine. Do you have any suggestions?`

- expected conv indices: 1
- top-1 ranked: 2
- top-K snippets:
  1. Hey, I need to get my thoughts in order for the big Q3 brand strategy presentation. My brain feels like a browser with too many tabs open—lo

### [implicit_connection_evidence / 1_evidence / batched_000.json] `I've been browsing job boards and feel a bit stuck. Based on our conversations, what kind of career change do you think would actually make me happier?`

- expected conv indices: 0
- top-1 ranked: 1
- top-K snippets:
  1. Hey, I need some advice. My new office in SF is an open-plan layout and it's... lively. Can you recommend some really good noise-cancelling 

### [implicit_connection_evidence / 1_evidence / batched_000.json] `Can you review this ad copy I wrote? It highlights our product's API and integration capabilities.`

- expected conv indices: 1
- top-1 ranked: 0
- top-K snippets:
  1. Hey, can you pull up the Q3 sales performance report for the Pacific Northwest territory? I want to see the breakdown by account executive.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `What are some professional development topics I could explore?`

- expected conv indices: 2
- top-1 ranked: 1
- top-K snippets:
  1. Finally Friday. This week felt like it was about a month long.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `I need to schedule a critical planning session for the Q3 training curriculum with my team. What time on Wednesday would be good for us to meet?`

- expected conv indices: 1
- top-1 ranked: 0
- top-K snippets:
  1. Okay, let's get started. I need to iron out the content plan for Q3, and my brain is a bit fried from back-to-back meetings. Can you help me

### [implicit_connection_evidence / 1_evidence / batched_000.json] `Hey team, that all-day offsite is coming up. I have my laptop and a notebook on my list. Are there any other essentials I should be sure to pack in my work bag?`

- expected conv indices: 2
- top-1 ranked: 1
- top-K snippets:
  1. Hey, just wanted to chat about my move. It's been a bit overwhelming.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `My co-founder and I are finally getting some traction and need to get organized. What's a good CRM to start tracking our first customer interactions?`

- expected conv indices: 2
- top-1 ranked: 1
- top-K snippets:
  1. Okay, let's get started. I need to prep for the Q1 partner business reviews. Pull up the performance dashboard for my 'Tier 1' strategic par

### [implicit_connection_evidence / 1_evidence / batched_000.json] `What would be an engaging topic for me to present at the next 'lunch and learn' session?`

- expected conv indices: 0
- top-1 ranked: 1
- top-K snippets:
  1. Hey, can you pull up the team's performance dashboard from last week? I want to review the key metrics before our weekly sync.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `What should I focus on this weekend?`

- expected conv indices: 1
- top-1 ranked: 0
- top-K snippets:
  1. What a day. My brain feels like it's been run through a data processor on the fritz.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `Can you help me request a detailed breakdown of churn by region for Q2?`

- expected conv indices: 2
- top-1 ranked: 0
- top-K snippets:
  1. Good afternoon. Can you help me organize some thoughts for a project kickoff meeting tomorrow?

### [implicit_connection_evidence / 1_evidence / batched_000.json] `How should I handle this new issue that's come up?`

- expected conv indices: 2
- top-1 ranked: 1
- top-K snippets:
  1. Okay, deep breaths. I have that massive training session for the Q3 product update coming up in two weeks and I'm already starting to feel t

### [implicit_connection_evidence / 1_evidence / batched_000.json] `I'm looking to get back into a regular fitness routine. Any suggestions for a good workout class to try?`

- expected conv indices: 2
- top-1 ranked: 1
- top-K snippets:
  1. Hi there, I wanted to discuss the recent performance review outcomes with you.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `What are the main advantages of implementing a CRM system for my business?`

- expected conv indices: 1
- top-1 ranked: 2
- top-K snippets:
  1. Hey, I'm trying to optimize my workflow. I feel like I'm spending too much time on password reset tickets for our CRM. Any thoughts on strea

### [implicit_connection_evidence / 1_evidence / batched_000.json] `I'm finally getting a chance to catch up with my friend Sarah this weekend. Do you have any suggestions for something we could do?`

- expected conv indices: 0
- top-1 ranked: 1
- top-K snippets:
  1. Okay, that was an intense day. Can you help me debrief and organize my thoughts before I sign off?

### [implicit_connection_evidence / 1_evidence / batched_000.json] `This competitor analysis report is falling pretty far behind schedule, and the product strategy briefing is looming. I'm starting to feel the pressure. What are some effective strategies to get this back on track?`

- expected conv indices: 1
- top-1 ranked: 2
- top-K snippets:
  1. Morning. Can you pull up the performance dashboard for our top 10 blog posts from the last quarter? I want to see the traffic vs. newsletter

### [implicit_connection_evidence / 1_evidence / batched_000.json] `My sister just texted asking what I want for my birthday this year. I'm drawing a total blank. Got any suggestions for what I should tell her?`

- expected conv indices: 0
- top-1 ranked: 2
- top-K snippets:
  1. Hey, I've been updating some account notes today. It's been a bit of a task, but I think I'm getting through it.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `What can I do to make sure the upcoming client call is successful?`

- expected conv indices: 0
- top-1 ranked: 2
- top-K snippets:
  1. Hey, I need to get organized for my quarterly business review next week. Can you help me prep?

### [implicit_connection_evidence / 1_evidence / batched_000.json] `Can you suggest some exercise ideas for me?`

- expected conv indices: 0
- top-1 ranked: 2
- top-K snippets:
  1. Hey, can you help me brainstorm some ideas?

### [implicit_connection_evidence / 1_evidence / batched_000.json] `What are some ways to make guests feel comfortable at a gathering?`

- expected conv indices: 2
- top-1 ranked: 0
- top-K snippets:
  1. Hey, I've been thinking about how to make our virtual meetings more engaging. It's been a bit challenging lately.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `What movie should I watch tonight?`

- expected conv indices: 0
- top-1 ranked: 1
- top-K snippets:
  1. Okay, the launch event for NexusCore is officially wrapped. What a whirlwind. Now the real fun begins.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `My dad's birthday is coming up next month and I'm completely drawing a blank. Any good gift ideas for him?`

- expected conv indices: 1
- top-1 ranked: 0
- top-K snippets:
  1. Hey, I have to give a talk to some junior marketers next month. I want to brainstorm some ideas for the topic and structure.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `Feeling pretty burnt out from this quarter's sales targets. I'm thinking of taking a weekend road trip from Seattle soon to recharge. Any ideas for good destinations?`

- expected conv indices: 2
- top-1 ranked: 1
- top-K snippets:
  1. Hey, I need to get some planning done for a weekend trip coming up in a few weeks. Can you help me brainstorm some ideas?

### [implicit_connection_evidence / 1_evidence / batched_000.json] `What are some activities I can do in the evening to unwind?`

- expected conv indices: 0
- top-1 ranked: 2
- top-K snippets:
  1. Hey, I've been thinking about ways to unwind after work. My evenings have been so hectic lately.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `I have three friends coming over this Friday, so there will be four of us total. Any suggestions for a fun group activity we could do at my place?`

- expected conv indices: 0
- top-1 ranked: 1
- top-K snippets:
  1. Hey, I've been asked to present a data quality report to the executives next week. It's a bit daunting.

### [implicit_connection_evidence / 1_evidence / batched_000.json] `I'm looking for some advice on boosting my support metrics. Any suggestions?`

- expected conv indices: 1
- top-1 ranked: 2
- top-K snippets:
  1. Hey, Assistant. I feel like I'm drowning in notifications today.

