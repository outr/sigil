# Sigil LoCoMo Benchmark Results

**Date:** 2026-05-04T13:22:48.200397671Z
**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)
**Retrieval:** vanilla cosine
**Score:** 958/1049 (91.3% R@10)

## Per-category accuracy

| Category | Correct | Total | Accuracy |
|---|---|---|---|
| basic_facts | 262 | 282 | 92.9% |
| temporal | 281 | 321 | 87.5% |
| abstention | 415 | 446 | 93.0% |

## Failures (91)

### [basic_facts] dataset_0.json — `What did Caroline research?`

- expected conv indices: 1
- top-10 ranked conv indices: 0, 5, 13, 8, 3, 9, 16, 11, 4, 14
- top-K snippets:
  1. Hey Mel! Good to see you! How have you been?
  2. Hey Mel! Long time no talk. Lots has been going on since then!
  3. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  4. Hey Caroline, hope all's good! I had a quiet weekend after we went camping with my fam two weekends ago. It was great to unplug and hang wit
  5. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  6. Hey Melanie! Just wanted to say hi!
  7. Hey Mel! Good to see you! How have you been?
  8. Hey Mel! Good to see you! How have you been?
  9. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  10. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth

### [basic_facts] dataset_0.json — `What are some changes Caroline has faced during her transition journey?`

- expected conv indices: 10, 15
- top-10 ranked conv indices: 13, 5, 2, 18, 4, 1, 16, 8, 12, 3
- top-K snippets:
  1. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  2. Hey Mel! Long time no talk. Lots has been going on since then!
  3. Hey Melanie! How's it going? I wanted to tell you about my school event last week. It was awesome! I talked about my transgender journey and
  4. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  5. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  6. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  7. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  8. Hey Caroline, hope all's good! I had a quiet weekend after we went camping with my fam two weekends ago. It was great to unplug and hang wit
  9. Hey Melanie! How's it going? I wanted to tell you about my school event last week. It was awesome! I talked about my transgender journey and
  10. Hi Melanie! Hope you're doing good. Guess what I did this week? I took the first step towards becoming a mom - I applied to adoption agencie

### [basic_facts] dataset_0.json — `When did Melanie go on a hike after the roadtrip?`

- expected conv indices: 17
- top-10 ranked conv indices: 5, 13, 14, 3, 11, 7, 9, 15, 16, 2
- top-K snippets:
  1. Hey Mel! Long time no talk. Lots has been going on since then!
  2. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  3. Hey Melanie, great to hear from you. What's been up since we talked?
  4. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  5. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  6. Hey Mel, what's up? Been a busy week since we talked.
  7. Hey Melanie! Just wanted to say hi!
  8. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  9. Hey Melanie! Just wanted to say hi!
  10. Hey Mel, what's up? Been a busy week since we talked.

### [basic_facts] dataset_0.json — `What items has Melanie bought?`

- expected conv indices: 6, 18
- top-10 ranked conv indices: 3, 14, 9, 5, 13, 12, 4, 0, 15, 7
- top-K snippets:
  1. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  2. Hey Melanie, great to hear from you. What's been up since we talked?
  3. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  4. Hey Melanie, great to hear from you. What's been up since we talked?
  5. Hey Melanie! Just wanted to say hi!
  6. Hey Mel! Long time no talk. Lots has been going on since then!
  7. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  8. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  9. Hey Melanie! Just wanted to say hi!
  10. Hi Melanie! Hope you're doing good. Guess what I did this week? I took the first step towards becoming a mom - I applied to adoption agencie

### [basic_facts] dataset_2.json — `What type of volunteering have John and Maria both done?`

- expected conv indices: 1, 2
- top-10 ranked conv indices: 30, 4, 14, 5, 27, 26, 15, 8, 3, 19
- top-K snippets:
  1. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  2. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  3. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar
  4. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  5. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  6. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  7. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  8. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b
  9. Hey John, great news - I'm now friends with one of my fellow volunteers! We both love helping others. How have you been since we last chatte
  10. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends

### [basic_facts] dataset_2.json — `What shelters does Maria volunteer at?`

- expected conv indices: 1, 10, 16
- top-10 ranked conv indices: 7, 30, 15, 5, 20, 4, 25, 28, 6, 11
- top-K snippets:
  1. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  2. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  3. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  4. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  5. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  6. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  7. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  8. Hey John, I'm doing ok - hope you are too. Some interesting stuff has been going on; last week I dropped off that stuff I baked at the homel
  9. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  10. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an

### [basic_facts] dataset_3.json — `What board games has Nate played?`

- expected conv indices: 15, 22
- top-10 ranked conv indices: 0, 9, 13, 27, 8, 11, 26, 14, 7, 25
- top-K snippets:
  1. Hey Joanna! Long time no see! What's up? Anything fun going on?
  2. Hey Joanna! Long time no see! What's up? Anything fun going on?
  3. Hey Nate, how's it going? I took your reccomendation and watched "The Lord of the Rings" Trilogy last night! It was awesome!
  4. Nate, after finishing my screenplay I got a rejection letter from a major company. It really bummed me out.
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Nate! Long time no talk! I wanted to tell ya I just joined a writers group. It's unbelievable--such inspirational people who really get 
  7. Hey Joanna! How've you been? Been a busy week since we talked.
  8. Hey Joanna! Long time no see! What's up? Anything fun going on?
  9. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  10. Nate, after finishing my screenplay I got a rejection letter from a major company. It really bummed me out.

### [basic_facts] dataset_3.json — `What animal do both Nate and Joanna like?`

- expected conv indices: 4, 25
- top-10 ranked conv indices: 24, 1, 28, 23, 19, 12, 18, 27, 14, 20
- top-K snippets:
  1. Hey Joanna, what's been up since we last chatted? How's it going?
  2. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  3. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  4. Hey Joanna, what's been up? Haven't seen you since we last talked.
  5. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  6. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni
  7. Woah Joanna, I won an international tournament yesterday! It was wild. Gaming has brought me so much success and now I'm able to make a livi
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  10. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!

### [basic_facts] dataset_3.json — `How many letters has Joanna recieved?`

- expected conv indices: 13, 17
- top-10 ranked conv indices: 24, 27, 19, 5, 7, 0, 1, 11, 23, 20
- top-K snippets:
  1. Hey Joanna, what's been up since we last chatted? How's it going?
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  4. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  5. Hey Joanna! Haven't talked with you in a while - how's it going?
  6. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  7. Hey Joanna! Long time no see! What's up? Anything fun going on?
  8. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  9. Hey Joanna! How've you been? Been a busy week since we talked.
  10. Hey Joanna, what's been up? Haven't seen you since we last talked.

### [basic_facts] dataset_3.json — `What pets does Nate have?`

- expected conv indices: 7, 11, 27
- top-10 ranked conv indices: 1, 12, 14, 22, 28, 18, 26, 0, 6, 19
- top-K snippets:
  1. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  2. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni
  3. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  4. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni
  5. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  6. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  7. Woah Joanna, I won an international tournament yesterday! It was wild. Gaming has brought me so much success and now I'm able to make a livi
  8. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  9. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni
  10. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w

### [basic_facts] dataset_4.json — `What sports does John like besides basketball?`

- expected conv indices: 0, 1, 2
- top-10 ranked conv indices: 23, 22, 11, 5, 10, 7, 28, 24, 18, 16
- top-K snippets:
  1. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  2. Hey Tim, great to see you! Any new success stories?
  3. Hey John! Awesome catchin' up with you! A lot's changed since last time.
  4. Hey Tim, sorry I missed you. Been a crazy few days. Took a trip to a new place - it's been amazing. Love the energy there.
  5. Hey Tim, been a while! How ya been?
  6. Hey Tim, been a while! How ya been?
  7. Hey Tim! Long time no talk. Hope you're doing great. Crazy things have been going on in my life. Just the other day, I found a new gym to st
  8. Hey John! How's it going? Hope all is good.
  9. Hey John, been a while since we chatted. How's it going?
  10. Hey John! Haven't talked in a bit, how ya been? Hope your injury is feeling better.

### [basic_facts] dataset_4.json — `What outdoor activities does John enjoy?`

- expected conv indices: 2, 11
- top-10 ranked conv indices: 23, 10, 26, 5, 13, 16, 7, 22, 28, 24
- top-K snippets:
  1. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  2. Hey Tim, been a while! How ya been?
  3. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  4. Hey Tim, sorry I missed you. Been a crazy few days. Took a trip to a new place - it's been amazing. Love the energy there.
  5. Hey Tim! Long time no talk - a lot has been going on since then!
  6. Hey Tim! Great to chat again. So much has happened!
  7. Hey Tim! Long time no talk. Hope you're doing great. Crazy things have been going on in my life. Just the other day, I found a new gym to st
  8. Hey Tim, great to see you! Any new success stories?
  9. Hey John! How's it going? Hope all is good.
  10. Hey John, been a while since we chatted. How's it going?

### [basic_facts] dataset_4.json — `which country has Tim visited most frequently in his travels?`

- expected conv indices: 0, 12, 17
- top-10 ranked conv indices: 26, 27, 14, 5, 10, 22, 13, 16, 8, 28
- top-K snippets:
  1. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  2. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  3. Hey John! Haven't talked to you in a bit but wanted to let you know I read this awesome book about castles in the UK. It was so interesting 
  4. Hey Tim, sorry I missed you. Been a crazy few days. Took a trip to a new place - it's been amazing. Love the energy there.
  5. Hey Tim, been a while! How ya been?
  6. Hey Tim, great to see you! Any new success stories?
  7. Hey Tim! Long time no talk - a lot has been going on since then!
  8. Hey Tim! Great to chat again. So much has happened!
  9. Hey John, this week's been really busy for me. Assignments and exams are overwhelming. I'm not giving up though! I'm trying to find a way to
  10. Hey John! How's it going? Hope all is good.

### [basic_facts] dataset_4.json — `What instruments does Tim play?`

- expected conv indices: 7, 20
- top-10 ranked conv indices: 10, 0, 13, 22, 16, 28, 2, 3, 18, 25
- top-K snippets:
  1. Hey Tim, been a while! How ya been?
  2. Hey Tim, nice to meet you! What's up? Anything new happening?
  3. Hey Tim! Long time no talk - a lot has been going on since then!
  4. Hey Tim, great to see you! Any new success stories?
  5. Hey Tim! Great to chat again. So much has happened!
  6. Hey John! How's it going? Hope all is good.
  7. Hey Tim! Good to see you again. So much has happened in the last month - on and off the court. Last week I scored 40 points, my highest ever
  8. Hey John! How've you been? Something awesome happened - I'm writing articles about fantasy novels for an online mag. It's so rewarding!
  9. Hey John! Haven't talked in a bit, how ya been? Hope your injury is feeling better.
  10. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a

### [basic_facts] dataset_5.json — `What is a shared frustration regarding dog ownership for Audrey and Andrew?`

- expected conv indices: 6, 9
- top-10 ranked conv indices: 25, 26, 27, 17, 18, 19, 23, 12, 13, 20
- top-K snippets:
  1. Hey Andrew, I wanted to let you know about something going on with my dogs. I noticed they weren't acting normally, so I made an appointment
  2. Hey Audrey, had a great weekend! My girlfriend and I went on a bike ride and stumbled upon a cool park outside of town. It was awesome to ge
  3. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  4. Hey Audrey, how's it going? Since we last talked, a few new things have come up in my life. Work's been tough and stressful, so my outdoor a
  5. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  6. Hey Audrey! Long time no talk! How have you been?
  7. Hey wassup? Got some great news - the gf and I are hitting the beach next month with Toby!
  8. Hey Andrew, hope you're doing ok. I recently had a good week - I went to a pet store last Monday to buy toys for my dogs and it was great se
  9. Hey Audrey! How are you? My GF and I just had a great experience volunteering at a pet shelter on Monday - it was so rewarding! We loved spe
  10. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz

### [basic_facts] dataset_5.json — `What does Audrey view her pets as?`

- expected conv indices: 14, 22
- top-10 ranked conv indices: 12, 18, 23, 27, 0, 11, 16, 8, 20, 26
- top-K snippets:
  1. Hey Audrey! How are you? My GF and I just had a great experience volunteering at a pet shelter on Monday - it was so rewarding! We loved spe
  2. Hey Audrey! Long time no talk! How have you been?
  3. Hey Andrew, hope you're doing ok. I recently had a good week - I went to a pet store last Monday to buy toys for my dogs and it was great se
  4. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  5. Hey Audrey! Long time no talk! How have you been?
  6. Hey Audrey! How are you? My GF and I just had a great experience volunteering at a pet shelter on Monday - it was so rewarding! We loved spe
  7. Hey Audrey! Long time no talk! How have you been?
  8. Hey Andrew! Good to see ya! What's been up since we last talked?
  9. Hey Audrey! How are you? My GF and I just had a great experience volunteering at a pet shelter on Monday - it was so rewarding! We loved spe
  10. Hey Audrey! How are you? My GF and I just had a great experience volunteering at a pet shelter on Monday - it was so rewarding! We loved spe

### [basic_facts] dataset_7.json — `What activities does Deborah pursue besides practicing and teaching yoga?`

- expected conv indices: 11, 14, 27, 28
- top-10 ranked conv indices: 6, 22, 12, 10, 19, 0, 1, 23, 4, 18
- top-K snippets:
  1. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  2. Hey Deborah, how's it going? Guess what? Yesterday my partner and I got back from an awesome trip to Rio de Janeiro- we checked out some coo
  3. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  4. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  5. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  6.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  7. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?
  8. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  9. Hey Jolene, just catching up. I went to a cool event last week with the aim to support each other - pretty inspiring. Have you been connecti
  10. Hey Deborah! Been a few days since we last talked so I wanted to fill you in on something cool. Last Wednesday I did a mini retreat to asses

### [basic_facts] dataset_8.json — `What motivates Evan to take care of his health?`

- expected conv indices: 4
- top-10 ranked conv indices: 3, 14, 7, 15, 10, 13, 9, 11, 6, 2
- top-K snippets:
  1. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.
  2. Morning, Evan. I've been trying to keep up with my new health routine, but it's tough. My family's really pushing for it, and I feel so pres
  3. Hey Evan, some big news: I'm on a diet and living healthier! Been tough, but I'm determined.
  4. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.
  5. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen
  6. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?
  7. Hey Evan! I've been missing our chats. I had quite the health scare last weekend - ended up in the ER with a severe stomachache. Turns out, 
  8. Hey Sam! Long time no talk! Hope all is good. What have I been doing these past few weeks?
  9. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.
  10. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?

### [basic_facts] dataset_9.json — `What is Dave's favorite activity?`

- expected conv indices: 18, 20, 21
- top-10 ranked conv indices: 3, 4, 11, 27, 24, 26, 5, 10, 14, 17
- top-K snippets:
  1. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  2. Hey Calvin! Long time no talk. How's it going? Crazy news - I'm teaming up with a local garage. Take a look at what we working on together!
  3. Hey Dave, long time no see! I just took my Ferrari for a service and it was so stressful. I'm kinda attached to it. Can you relate? What kin
  4. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  5. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  6. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  7. Hey Dave, long time no see! I just took my Ferrari for a service and it was so stressful. I'm kinda attached to it. Can you relate? What kin
  8. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  9. Hey Dave! Long time no chat! Lots has gone down since we last caught up.
  10. Hey Cal, been ages since we spoke! Guess what? I just got back from a road trip with my friends - we saw some stunning countryside. It was s

### [basic_facts] dataset_9.json — `Which cities did Dave travel to in 2023?`

- expected conv indices: 13, 25
- top-10 ranked conv indices: 2, 17, 23, 14, 5, 6, 29, 16, 7, 10
- top-K snippets:
  1. Hey Dave! Long time no see. I just went to an awesome music thingy in Tokyo - so cool!
  2. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  3. Hey Dave! Been a few days since we talked, but lots happened. Started touring with Frank Ocean and it's been amazing - so much energy from t
  4. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  5. Hey Dave! Long time no chat! Lots has gone down since we last caught up.
  6. Hey Dave! Been ages since we chatted. So much has gone down. Touring with Frank Ocean last week was wild. Tokyo was unreal -- the crowd was 
  7. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  8. Hey Calvin! Been a while, what's up? I'm tied up with car stuff lately, yesterday I came back from San Francsico with some great insights an
  9. Hey Calvin! Been a while, what's up? I'm tied up with car stuff lately, yesterday I came back from San Francsico with some great insights an
  10. Hey Dave! Met with the creative team for my album yesterday. It was a long session, but awesome to see everything coming together. 

### [temporal] dataset_0.json — `When did Caroline meet up with her friends, family, and mentors?`

- expected conv indices: 2
- top-10 ranked conv indices: 8, 6, 7, 0, 9, 3, 13, 5, 14, 16
- top-K snippets:
  1. Hey Caroline, hope all's good! I had a quiet weekend after we went camping with my fam two weekends ago. It was great to unplug and hang wit
  2. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  3. Hey Mel, what's up? Been a busy week since we talked.
  4. Hey Mel! Good to see you! How have you been?
  5. Hey Melanie! Just wanted to say hi!
  6. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  7. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  8. Hey Mel! Long time no talk. Lots has been going on since then!
  9. Hey Caroline, hope all's good! I had a quiet weekend after we went camping with my fam two weekends ago. It was great to unplug and hang wit
  10. Hey Mel, what's up? Been a busy week since we talked.

### [temporal] dataset_0.json — `When did Caroline have a picnic?`

- expected conv indices: 5
- top-10 ranked conv indices: 14, 11, 13, 10, 7, 15, 3, 9, 8, 17
- top-K snippets:
  1. Hey Melanie, great to hear from you. What's been up since we talked?
  2. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  3. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  4. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  5. Hey Caroline! Last night was amazing! We celebrated my daughter's birthday with a concert surrounded by music, joy and the warm summer breez
  6. Hey Mel, what's up? Been a busy week since we talked.
  7. Hey Mel, what's up? Been a busy week since we talked.
  8. Hey Mel, long time no chat! I had a wicked day out with the gang last weekend - we went biking and saw some pretty cool stuff. It was so ref
  9. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  10. Hey Melanie! Just wanted to say hi!

### [temporal] dataset_0.json — `When did Melanie go camping in July?`

- expected conv indices: 8
- top-10 ranked conv indices: 9, 5, 7, 14, 3, 11, 1, 15, 13, 17
- top-K snippets:
  1. Hey Melanie! Just wanted to say hi!
  2. Hey Mel! Long time no talk. Lots has been going on since then!
  3. Hey Mel, what's up? Been a busy week since we talked.
  4. Hey Melanie, great to hear from you. What's been up since we talked?
  5. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  6. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  7. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  8. Hey Melanie! Just wanted to say hi!
  9. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  10. Hey Melanie! Just wanted to say hi!

### [temporal] dataset_0.json — `When is Melanie's daughter's birthday?`

- expected conv indices: 10
- top-10 ranked conv indices: 2, 9, 3, 5, 0, 13, 14, 12, 1, 18
- top-K snippets:
  1. Hey Melanie! How's it going? I wanted to tell you about my school event last week. It was awesome! I talked about my transgender journey and
  2. Hey Melanie! Just wanted to say hi!
  3. Hey Melanie! Just wanted to say hi!
  4. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  5. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  6. Hey Mel! Long time no talk. Lots has been going on since then!
  7. Hey Mel! Good to see you! How have you been?
  8. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  9. Hey Melanie, great to hear from you. What's been up since we talked?
  10. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.

### [temporal] dataset_1.json — `When did Jon start to go to the gym?`

- expected conv indices: 5
- top-10 ranked conv indices: 16, 13, 8, 2, 10, 18, 3, 0, 15, 12
- top-K snippets:
  1. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  2. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to
  3. Hey Gina! I'm turning my loves of dance into a business. I'm sunk tons of time into the studio lately, and look at my students - they're alr
  4. Hey Gina, hope you're doing ok! Still following my passion for dance. It's been bumpy, but I'm determined to make it work. I'm still searchi
  5. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  6. Hey Gina! We haven't talked in a few days. Been rehearsing hard and working on business plans. It's been stressful, but dancing has kept me 
  7. Hey Gina! What's up? How's the store going? I gotta tell you about this thing with my biz.
  8. Hey Jon! Good to see you. What's up? Anything new?
  9. Hey Jon, what's been up? Some pretty cool stuff happened since we talked. I have acquired some new unique pieces for my store.
  10. Hey Gina, thanks for being there for me and believing in me. It means a lot.

### [temporal] dataset_1.json — `When was Jon in Rome?`

- expected conv indices: 14
- top-10 ranked conv indices: 1, 18, 16, 7, 12, 0, 3, 11, 5, 13
- top-K snippets:
  1. Hey Jon! Long time no see! Things have been hectic lately. I just launched an ad campaign for my clothing store in hopes of growing the busi
  2. Hey Gina! We haven't talked in a few days. Been rehearsing hard and working on business plans. It's been stressful, but dancing has kept me 
  3. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  4. Hey Gina, I had to shut down my bank account. It was tough, but I needed to do it for my biz.
  5. Hey Gina, thanks for being there for me and believing in me. It means a lot.
  6. Hey Jon! Good to see you. What's up? Anything new?
  7. Hey Gina! What's up? How's the store going? I gotta tell you about this thing with my biz.
  8. Hey Gina, I had to shut down my bank account. It was tough, but I needed to do it for my biz.
  9. Hey Jon! Long time no talk! A lot's happened - I just got accepted for a fashion internship!
  10. Hey Gina, thanks for being there for me and believing in me. It means a lot.

### [temporal] dataset_2.json — `When did Maria donate her car?`

- expected conv indices: 1
- top-10 ranked conv indices: 15, 3, 28, 10, 31, 6, 4, 19, 14, 16
- top-K snippets:
  1. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  2. Hey John, great news - I'm now friends with one of my fellow volunteers! We both love helping others. How have you been since we last chatte
  3. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  4. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  5. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  6. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  7. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  8. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends
  9. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar
  10. Hey Maria, long time no talk! Life's been pretty wild lately. The toughest thing to deal with is that we had to say goodbye to Max. He was s

### [temporal] dataset_2.json — `When did Maria go to the beach?`

- expected conv indices: 2
- top-10 ranked conv indices: 12, 15, 18, 0, 21, 26, 6, 3, 29, 22
- top-K snippets:
  1. Hey Maria! Long time no see! Tons has gone down since then!
  2. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  3. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  4. Hey John! Long time no see! What's up?
  5. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  6. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  7. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  8. Hey John, great news - I'm now friends with one of my fellow volunteers! We both love helping others. How have you been since we last chatte
  9. Hey John! Long time no talk! Guess what - I got a puppy two weeks ago! Her name's Coco and she's adorable.
  10. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 

### [temporal] dataset_2.json — `When did Maria get in a car accident?`

- expected conv indices: 20
- top-10 ranked conv indices: 3, 10, 6, 12, 0, 22, 30, 13, 21, 27
- top-K snippets:
  1. Hey John, great news - I'm now friends with one of my fellow volunteers! We both love helping others. How have you been since we last chatte
  2. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  3. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  4. Hey Maria! Long time no see! Tons has gone down since then!
  5. Hey John! Long time no see! What's up?
  6. Maria, since we talked, it's been tough. My old area was hit by a nasty flood last week. The infrastructure wasn't great so lots of homes we
  7. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  8. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  9. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  10. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th

### [temporal] dataset_2.json — `When did Maria take up community work with her church friends?`

- expected conv indices: 27
- top-10 ranked conv indices: 13, 4, 26, 23, 30, 7, 15, 5, 28, 31
- top-K snippets:
  1. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  2. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  3. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  4. Hey Maria, last week was really eye-opening. I visited a veteran's hospital and met some amazing people. It made me appreciate what we have 
  5. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  6. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  7. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  8. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  9. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  10. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!

### [temporal] dataset_3.json — `What movie did Joanna watch on 1 May, 2022?`

- expected conv indices: 9
- top-10 ranked conv indices: 24, 28, 2, 12, 0, 25, 3, 5, 27, 23
- top-K snippets:
  1. Hey Joanna, what's been up since we last chatted? How's it going?
  2. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  3. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  4. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni
  5. Hey Joanna, what's been up since we last chatted? How's it going?
  6. Hey Joanna! Long time no see! What's up? Anything fun going on?
  7. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  8. Hey Joanna! Sorry I haven't been around. I made my friend some ice cream and they loved it!
  9. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  10. Hey Joanna! Long time no see! What's up? Anything fun going on?

### [temporal] dataset_3.json — `Which outdoor spot did Joanna visit in May?`

- expected conv indices: 10
- top-10 ranked conv indices: 16, 27, 0, 24, 28, 5, 25, 14, 19, 1
- top-K snippets:
  1. Hey Joanna, check this out! I won my fourth video game tournament on Friday! It was awesome competing and showing off my skills - and the vi
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna! Long time no see! What's up? Anything fun going on?
  4. Hey Joanna, what's been up since we last chatted? How's it going?
  5. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  6. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  7. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  8. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  9. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  10. Hey Nate! Haven't talked in a few days. Crazy things happened to me!

### [temporal] dataset_3.json — `Who was the new addition to Nate's family in May 2022?`

- expected conv indices: 11
- top-10 ranked conv indices: 14, 22, 12, 2, 1, 6, 17, 19, 20, 5
- top-K snippets:
  1. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  2. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  3. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni
  4. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  5. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  6. Hey Jo, guess what I did? Dyed my hair last week - come see!
  7. Hey Nate, long time no talk! I've been busy with writing projects and really going all out with it. It's been the best thing ever - a mix of
  8. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  9. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  10. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.

### [temporal] dataset_3.json — `When did Joanna hike with her buddies?`

- expected conv indices: 13
- top-10 ranked conv indices: 0, 24, 8, 23, 7, 27, 1, 5, 16, 11
- top-K snippets:
  1. Hey Joanna! Long time no see! What's up? Anything fun going on?
  2. Hey Joanna, what's been up since we last chatted? How's it going?
  3. Hey Nate! Long time no talk! I wanted to tell ya I just joined a writers group. It's unbelievable--such inspirational people who really get 
  4. Hey Joanna, what's been up? Haven't seen you since we last talked.
  5. Hey Joanna! Haven't talked with you in a while - how's it going?
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  8. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  9. Hey Joanna, check this out! I won my fourth video game tournament on Friday! It was awesome competing and showing off my skills - and the vi
  10. Hey Nate! Haven't talked in a few days. Crazy things happened to me!

### [temporal] dataset_3.json — `When did someone write Joanna a touching letter?`

- expected conv indices: 17
- top-10 ranked conv indices: 3, 24, 27, 5, 7, 26, 23, 11, 19, 0
- top-K snippets:
  1. Hey Joanna! Sorry I haven't been around. I made my friend some ice cream and they loved it!
  2. Hey Joanna, what's been up since we last chatted? How's it going?
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  5. Hey Joanna! Haven't talked with you in a while - how's it going?
  6. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  7. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  8. Hey Joanna, what's been up? Haven't seen you since we last talked.
  9. Hey Joanna! How've you been? Been a busy week since we talked.
  10. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe

### [temporal] dataset_3.json — `When did Joanna finish up the writing for her book?`

- expected conv indices: 21
- top-10 ranked conv indices: 24, 27, 19, 3, 4, 26, 5, 16, 25, 20
- top-K snippets:
  1. Hey Joanna, what's been up since we last chatted? How's it going?
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  4. Hey Joanna! Sorry I haven't been around. I made my friend some ice cream and they loved it!
  5. Hey Nate, it's been a minute! I wrapped up my second script, and the feels have been wild. Sometimes I'm so relieved, but other times I just
  6. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  7. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  8. Hey Joanna, check this out! I won my fourth video game tournament on Friday! It was awesome competing and showing off my skills - and the vi
  9. Hey Joanna, what's been up since we last chatted? How's it going?
  10. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!

### [temporal] dataset_3.json — `When did Nate win his second tournament?`

- expected conv indices: 9
- top-10 ranked conv indices: 13, 18, 21, 0, 26, 27, 22, 14, 19, 20
- top-K snippets:
  1. Nate, after finishing my screenplay I got a rejection letter from a major company. It really bummed me out.
  2. Woah Joanna, I won an international tournament yesterday! It was wild. Gaming has brought me so much success and now I'm able to make a livi
  3. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  4. Hey Joanna! Long time no see! What's up? Anything fun going on?
  5. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  6. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  9. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  10. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe

### [temporal] dataset_4.json — `Which country was Tim visiting in the second week of November?`

- expected conv indices: 17
- top-10 ranked conv indices: 26, 5, 27, 10, 13, 6, 14, 16, 22, 9
- top-K snippets:
  1. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  2. Hey Tim, sorry I missed you. Been a crazy few days. Took a trip to a new place - it's been amazing. Love the energy there.
  3. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  4. Hey Tim, been a while! How ya been?
  5. Hey Tim! Long time no talk - a lot has been going on since then!
  6. Hey Tim! We had a wild few days since we talked. I met back up with my teammates on the 15th after my trip and it was amazing! Everyone miss
  7. Hey John! Haven't talked to you in a bit but wanted to let you know I read this awesome book about castles in the UK. It was so interesting 
  8. Hey Tim! Great to chat again. So much has happened!
  9. Hey Tim, great to see you! Any new success stories?
  10. Hey John, it's been a few days! I got a no for a summer job I wanted which wasn't great but I'm staying positive. On your NYC trip, did you 

### [temporal] dataset_4.json — `Where was Tim in the week before 16 November 2023?`

- expected conv indices: 17
- top-10 ranked conv indices: 27, 13, 10, 0, 16, 2, 28, 5, 18, 8
- top-K snippets:
  1. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  2. Hey Tim! Long time no talk - a lot has been going on since then!
  3. Hey Tim, been a while! How ya been?
  4. Hey Tim, nice to meet you! What's up? Anything new happening?
  5. Hey Tim! Great to chat again. So much has happened!
  6. Hey Tim! Good to see you again. So much has happened in the last month - on and off the court. Last week I scored 40 points, my highest ever
  7. Hey John! How's it going? Hope all is good.
  8. Hey Tim, sorry I missed you. Been a crazy few days. Took a trip to a new place - it's been amazing. Love the energy there.
  9. Hey John! Haven't talked in a bit, how ya been? Hope your injury is feeling better.
  10. Hey John, this week's been really busy for me. Assignments and exams are overwhelming. I'm not giving up though! I'm trying to find a way to

### [temporal] dataset_5.json — `When did Audrey move to a new place?`

- expected conv indices: 8
- top-10 ranked conv indices: 20, 17, 18, 0, 22, 26, 2, 16, 15, 13
- top-K snippets:
  1. Hi Audrey! Been a while since I hear from you. How's it been?
  2. Hey Audrey, how's it going? Since we last talked, a few new things have come up in my life. Work's been tough and stressful, so my outdoor a
  3. Hey Audrey! Long time no talk! How have you been?
  4. Hey Andrew! Good to see ya! What's been up since we last talked?
  5. Hey Audrey, it's been a busy week for me. Last Tuesday, my gf, Toby, and I had a really awesome night playing board games. It was really nic
  6. Hey Audrey, had a great weekend! My girlfriend and I went on a bike ride and stumbled upon a cool park outside of town. It was awesome to ge
  7. Hey Audrey! What's up? Missed chatting with ya! Check it out, my girl & I tried out that new cafe scene in the city last weekend! Super fun 
  8. Hey Audrey! What's up? Last weekend my girlfriend and I went fishing in one of the nearby lakes. It was so nice. We got a few fish and had a
  9. Hey Audrey, hope you're doing good! So I've decided to take a break from work yesterday and check out a new cafe. It was a nice change and r
  10. Hey, Audrey! I can't wait for the weekend. My girlfriend, Toby and I are going camping. It's been forever since I've been in nature.

### [temporal] dataset_5.json — `Where did Andrew go during the first weekend of August 2023?`

- expected conv indices: 13
- top-10 ranked conv indices: 19, 20, 0, 5, 23, 14, 15, 27, 12, 25
- top-K snippets:
  1. Hey wassup? Got some great news - the gf and I are hitting the beach next month with Toby!
  2. Hi Audrey! Been a while since I hear from you. How's it been?
  3. Hey Andrew! Good to see ya! What's been up since we last talked?
  4. Hi Audrey! I had a great hike last weekend with some friends and my girlfriend at the spot we found recently. Nature was so peaceful – it wa
  5. Hey Andrew, hope you're doing ok. I recently had a good week - I went to a pet store last Monday to buy toys for my dogs and it was great se
  6. Hey Andrew, since we last spoke I got another tattoo of my four dogs on my arm! They really mean a lot to me so I thought it'd be nice to ha
  7. Hey wassup? Got some great news - the gf and I are hitting the beach next month with Toby!
  8. Hey Audrey, hope you're doing good! So I've decided to take a break from work yesterday and check out a new cafe. It was a nice change and r
  9. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  10. Hey Andrew, hope you're doing ok. I recently had a good week - I went to a pet store last Monday to buy toys for my dogs and it was great se

### [temporal] dataset_5.json — `When did Audrey get into an accident in the park?`

- expected conv indices: 24
- top-10 ranked conv indices: 26, 17, 2, 20, 18, 16, 13, 5, 27, 22
- top-K snippets:
  1. Hey Audrey, had a great weekend! My girlfriend and I went on a bike ride and stumbled upon a cool park outside of town. It was awesome to ge
  2. Hey Audrey, how's it going? Since we last talked, a few new things have come up in my life. Work's been tough and stressful, so my outdoor a
  3. Hey Audrey! What's up? Missed chatting with ya! Check it out, my girl & I tried out that new cafe scene in the city last weekend! Super fun 
  4. Hi Audrey! Been a while since I hear from you. How's it been?
  5. Hey Audrey! Long time no talk! How have you been?
  6. Hey Audrey! What's up? Last weekend my girlfriend and I went fishing in one of the nearby lakes. It was so nice. We got a few fish and had a
  7. Hey, Audrey! I can't wait for the weekend. My girlfriend, Toby and I are going camping. It's been forever since I've been in nature.
  8. Hi Audrey! I had a great hike last weekend with some friends and my girlfriend at the spot we found recently. Nature was so peaceful – it wa
  9. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  10. Hey Audrey, it's been a busy week for me. Last Tuesday, my gf, Toby, and I had a really awesome night playing board games. It was really nic

### [temporal] dataset_5.json — `How long has it been since Andrew adopted his first pet, as of November 2023?`

- expected conv indices: 11
- top-10 ranked conv indices: 1, 27, 12, 23, 20, 14, 25, 5, 15, 13
- top-K snippets:
  1. Hey Andrew, I got a surprise for you! We adopted another puppy called Pixie. She's SO cute! Isn't she just the cutest?
  2. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  3. Hey Audrey! How are you? My GF and I just had a great experience volunteering at a pet shelter on Monday - it was so rewarding! We loved spe
  4. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  5. Hey Andrew, hope you're doing ok. I recently had a good week - I went to a pet store last Monday to buy toys for my dogs and it was great se
  6. Hi Audrey! Been a while since I hear from you. How's it been?
  7. Hey Andrew, since we last spoke I got another tattoo of my four dogs on my arm! They really mean a lot to me so I thought it'd be nice to ha
  8. Hey Andrew, I wanted to let you know about something going on with my dogs. I noticed they weren't acting normally, so I made an appointment
  9. Hi Audrey! I had a great hike last weekend with some friends and my girlfriend at the spot we found recently. Nature was so peaceful – it wa
  10. Hey Andrew, I got a surprise for you! We adopted another puppy called Pixie. She's SO cute! Isn't she just the cutest?

### [temporal] dataset_6.json — `How was John feeling on April 10, 2022?`

- expected conv indices: 5
- top-10 ranked conv indices: 24, 17, 7, 27, 12, 13, 29, 15, 25, 6
- top-K snippets:
  1. Hey James, been a few days since we chatted. Lots of stuff goin' on in my life!
  2. Hey James, good catching up! Been a while huh? I made a huge call - recently left my IT job after 3 years. It was tough but I wanted somethi
  3. Hey John! What's up? Anything fun going on?
  4. Hey James, been a few days since we chatted. Lots of stuff goin' on in my life!
  5. Hey John, long time no talk! So much has happened!
  6. Hey James, long time no talk! A lot has happened during this time. Let me fill you in.
  7. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  8. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  9. Hey James, long time no talk! A lot has happened during this time. Let me fill you in.
  10. Hey John! Long time no talk - hope you're doing well. Guess what? Last week I actually won an online gaming tournament! It was such an excit

### [temporal] dataset_6.json — `Where was James at on July 12, 2022?`

- expected conv indices: 15
- top-10 ranked conv indices: 18, 6, 27, 29, 24, 12, 8, 0, 5, 17
- top-K snippets:
  1. Hey James, been a few days. The convo got me thinking about my passions and goals. Thanks for encouraging me to try new game genres.
  2. Hey James! How's it going?
  3. Hey John, long time no talk! So much has happened!
  4. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  5. Hey James, been a few days since we chatted. Lots of stuff goin' on in my life!
  6. Hey James, long time no talk! A lot has happened during this time. Let me fill you in.
  7. Hey James! How've you been? Had an eventful week since our last chat.
  8. Hey! Glad to finally talk to you. I want to ask you, what motivates you?
  9. Hey James! Long time no see! I have great news! Last Tuesday I met three cool new friends in my programming course, they share the same pass
  10. Hey James, good catching up! Been a while huh? I made a huge call - recently left my IT job after 3 years. It was tough but I wanted somethi

### [temporal] dataset_6.json — `When did John work with a game developer on a project?`

- expected conv indices: 30
- top-10 ranked conv indices: 24, 0, 5, 25, 26, 21, 19, 9, 18, 20
- top-K snippets:
  1. Hey James, been a few days since we chatted. Lots of stuff goin' on in my life!
  2. Hey James, been a few days since we chatted. Lots of stuff goin' on in my life!
  3. Hey! Glad to finally talk to you. I want to ask you, what motivates you?
  4. Hey James! Long time no see! I have great news! Last Tuesday I met three cool new friends in my programming course, they share the same pass
  5. Hey James! Busy few weeks for sure, but I'm pushing through. Got an email about a volunteer gig at a game dev non-profit. It's something I'v
  6. Hey James! Busy few weeks for sure, but I'm pushing through. Got an email about a volunteer gig at a game dev non-profit. It's something I'v
  7. Hey James! How's it going? I had a blast last week when my programmer friends and I organized an online comp. It was awesome to see everyone
  8. Hey John! Been a while, but hope you're doing well. My Unity strategy game is finally finished—it took loads of time and effort, but I'm rea
  9. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  10. Hey John! Been a while since we chatted. Sorry 'bout not getting back sooner. How's it going? Any new games you're into?

### [temporal] dataset_7.json — `What kind of project was Jolene working on in the beginning of January 2023?`

- expected conv indices: 0
- top-10 ranked conv indices: 24, 15, 13, 2, 12, 3, 22, 23, 21, 9
- top-K snippets:
  1. Woohoo! I signed up for a meditation course at a retreat near a lake. Can't wait to share this experience with my partner and learn some new
  2. Hey Jolene! Great news - I just started a project for a cleanup in our community and have been trying to raise funds for it. It's been amazi
  3. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  4. Hi Deb! How're you? I've been busy. My engineering professor gave us a huge robotics project. It's tough but fun, it's making me get creativ
  5. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  6. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  7. Hey Deborah, how's it going? Guess what? Yesterday my partner and I got back from an awesome trip to Rio de Janeiro- we checked out some coo
  8. Hey Jolene, just catching up. I went to a cool event last week with the aim to support each other - pretty inspiring. Have you been connecti
  9. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  10. Hey Jolene, it's been a while. Hope you're doing okay with all your exams and deadlines. I know it's difficult for you right now.

### [temporal] dataset_7.json — `When did Deborah receive an appreciation letter from her community?`

- expected conv indices: 1
- top-10 ranked conv indices: 28, 20, 4, 19, 15, 13, 14, 16, 18, 29
- top-K snippets:
  1. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  2. Hey Jolene! Good to hear from you! A lot's happened since we talked - last week I got to go to this yoga retreat near my mom's place. It was
  3. Hey Deborah! Been a few days since we last talked so I wanted to fill you in on something cool. Last Wednesday I did a mini retreat to asses
  4.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  5. Hey Deborah! Been a few days since we last talked so I wanted to fill you in on something cool. Last Wednesday I did a mini retreat to asses
  6. Hey Jolene! Great news - I just started a project for a cleanup in our community and have been trying to raise funds for it. It's been amazi
  7. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  8. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  9. Since we last spoke, I made a meditation guide for my yoga retreat. How about you?
  10. Hey Jolene! Hope you're having a good one. Last Friday I told Anna the story of my life and they were super kind about it. It was so nice to

### [temporal] dataset_7.json — `When did Deborah meet Anna?`

- expected conv indices: 2
- top-10 ranked conv indices: 28, 4, 15, 3, 12, 5, 22, 18, 6, 24
- top-K snippets:
  1. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  2. Hey Deborah! Been a few days since we last talked so I wanted to fill you in on something cool. Last Wednesday I did a mini retreat to asses
  3. Hey Jolene! Great news - I just started a project for a cleanup in our community and have been trying to raise funds for it. It's been amazi
  4. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  5. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  6. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  7. Hey Deborah, totally buzzing! Had a great night out last night - dinner, and drinks with my friends. So glad I got to let my hair down. You?
  8. Hey Deborah, how's it going? Guess what? Yesterday my partner and I got back from an awesome trip to Rio de Janeiro- we checked out some coo
  9. Hey Jolene! Hope you're having a good one. Last Friday I told Anna the story of my life and they were super kind about it. It was so nice to
  10. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui

### [temporal] dataset_7.json — `When did Jolene have a dinner and drinks with her friends?`

- expected conv indices: 5
- top-10 ranked conv indices: 25, 23, 1, 0, 6, 8, 3, 13, 19, 9
- top-K snippets:
  1. Hey Jolene, had a tough week. Storm forced us to cancel our yoga getaway.
  2. Hey Jolene, just catching up. I went to a cool event last week with the aim to support each other - pretty inspiring. Have you been connecti
  3. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  4. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?
  5. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  6. Hi Jolene! We haven't corresponded for a long time!
  7. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  8. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  9.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  10.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?

### [temporal] dataset_7.json — `Which country was Jolene located in during the last week of August 2023?`

- expected conv indices: 22
- top-10 ranked conv indices: 0, 3, 8, 25, 9, 20, 23, 1, 6, 19
- top-K snippets:
  1. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?
  2. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  3. Hi Jolene! We haven't corresponded for a long time!
  4. Hey Jolene, had a tough week. Storm forced us to cancel our yoga getaway.
  5. Hey Jolene, it's been a while. Hope you're doing okay with all your exams and deadlines. I know it's difficult for you right now.
  6. Hey Jolene, had a tough week. Storm forced us to cancel our yoga getaway.
  7. Hey Jolene! Good to hear from you! A lot's happened since we talked - last week I got to go to this yoga retreat near my mom's place. It was
  8. Hey Jolene, just catching up. I went to a cool event last week with the aim to support each other - pretty inspiring. Have you been connecti
  9. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  10. Hi Jolene! We haven't corresponded for a long time!

### [temporal] dataset_8.json — `What significant event happened in Sam's life towards the end of summer 2023?`

- expected conv indices: 4
- top-10 ranked conv indices: 19, 18, 9, 2, 23, 22, 12, 16, 5, 20
- top-K snippets:
  1. Hey Sam, what's up? Long time no see, huh? Lots has happened.
  2. Hey Sam, hope you're doing good. Wanted to share some amazing news - my partner is pregnant! We're so excited! It's been a while since we ha
  3. Hey Sam! Long time no talk! Hope all is good. What have I been doing these past few weeks?
  4. Hey Sam! Long time no talk! How're you doing? Life's been quite the rollercoaster lately. I had a health scare last week – a sudden heart pa
  5. Hey Sam, hope you're doing good. Something funny happened last night.
  6. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  7. Hey Sam, how's it going? Been a while since we talked. Hope all is good.
  8. Hey Ev! Long time no chat. How's it going? Hope all is well.
  9. Hey Sam, long time no talk! Hope you're doing great. I just got back from a rad vacay with my new SO in Canada. Tried some awesome activitie
  10. Hey Evan! Long time no see, how's it going?

### [temporal] dataset_8.json — `Which year did Evan start taking care of his health seriously?`

- expected conv indices: 4
- top-10 ranked conv indices: 3, 7, 10, 8, 13, 9, 21, 2, 15, 14
- top-K snippets:
  1. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.
  2. Hey Evan, some big news: I'm on a diet and living healthier! Been tough, but I'm determined.
  3. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?
  4. Hey Evan! Exciting news: I started a new diet and exercise routine last Monday and it's made a huge difference. I feel great! What about you
  5. Hey Evan! I've been missing our chats. I had quite the health scare last weekend - ended up in the ER with a severe stomachache. Turns out, 
  6. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.
  7. Hey Sam! Long time no talk! Hope all is good. What have I been doing these past few weeks?
  8. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!
  9. Hey Sam! Long time no talk! How're you doing? Life's been quite the rollercoaster lately. I had a health scare last week – a sudden heart pa
  10. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen

### [temporal] dataset_8.json — `When Evan get back from a vacation with his SO?`

- expected conv indices: 5
- top-10 ranked conv indices: 20, 22, 0, 18, 23, 12, 19, 11, 10, 8
- top-K snippets:
  1. Hey Evan! Long time no see, how's it going?
  2. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  3. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  4. Hey Sam, hope you're doing good. Wanted to share some amazing news - my partner is pregnant! We're so excited! It's been a while since we ha
  5. Hey Sam, hope you're doing good. Something funny happened last night.
  6. Hey Sam, how's it going? Been a while since we talked. Hope all is good.
  7. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  8. Hey Sam, how's it going? Been a while since we talked. Hope all is good.
  9. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  10. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed

### [temporal] dataset_8.json — `When was Sam in the ER?`

- expected conv indices: 13
- top-10 ranked conv indices: 16, 6, 2, 23, 19, 12, 24, 9, 1, 14
- top-K snippets:
  1. Hey Ev! Long time no chat. How's it going? Hope all is well.
  2. Hey Sam, what's up? It's been a few days since we talked. How have you been? Life's been tough lately - my son had a soccer accident last Sa
  3. Hey Sam! Long time no talk! How're you doing? Life's been quite the rollercoaster lately. I had a health scare last week – a sudden heart pa
  4. Hey Sam, hope you're doing good. Something funny happened last night.
  5. Hey Sam, what's up? It's been a few days since we talked. How have you been? Life's been tough lately - my son had a soccer accident last Sa
  6. Hey Sam, what's up? Long time no see, huh? Lots has happened.
  7. Hey Sam, how's it going? Been a while since we talked. Hope all is good.
  8. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  9. Hey Sam! Long time no talk! Hope all is good. What have I been doing these past few weeks?
  10. Hey Sam, good to hear from you! Since we last talked, lots has been happening! Last weekend, I took my family on a road trip to Jasper. It w

### [temporal] dataset_9.json — `When did Calvin's place get flooded in Tokyo?`

- expected conv indices: 5
- top-10 ranked conv indices: 23, 13, 2, 0, 6, 15, 1, 17, 29, 14
- top-K snippets:
  1. Hey Dave! Been a few days since we talked, but lots happened. Started touring with Frank Ocean and it's been amazing - so much energy from t
  2. Hey Dave! Been a few days since we talked, but lots happened. Started touring with Frank Ocean and it's been amazing - so much energy from t
  3. Hey Dave! Been a few days since we talked, but lots happened. Started touring with Frank Ocean and it's been amazing - so much energy from t
  4. Hey Cal, how's it going? Something cool happened since last we talked - I got to go to a car workshop in San Francisco! So cool to dive into
  5. Hey Dave! Long time no see. I just went to an awesome music thingy in Tokyo - so cool!
  6. Hey Dave! Nice to meet you! How's it going since we talked?
  7. Hey Dave! Been ages since we chatted. So much has gone down. Touring with Frank Ocean last week was wild. Tokyo was unreal -- the crowd was 
  8. Hey Dave! Been a few days since we talked, but lots happened. Started touring with Frank Ocean and it's been amazing - so much energy from t
  9. Hey Cal, how's it going? Something cool happened since last we talked - I got to go to a car workshop in San Francisco! So cool to dive into
  10. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!

### [temporal] dataset_9.json — `Which city was Calvin at on October 3, 2023?`

- expected conv indices: 20
- top-10 ranked conv indices: 17, 15, 7, 14, 19, 12, 23, 1, 6, 13
- top-K snippets:
  1. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  2. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!
  3. Hey Dave! Met with the creative team for my album yesterday. It was a long session, but awesome to see everything coming together. 
  4. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  5. Hey Calvin, good to catch up again! Had a tough time with my car project. Worked on the engine of the vintage Mustang, thought I'd fixed it,
  6. Hey Calvin, been ages! Guess what? I got picked for a car mod workshop. Gonna get better at it and learn something new! Look at the cars I'm
  7. Hey Dave! Been a few days since we talked, but lots happened. Started touring with Frank Ocean and it's been amazing - so much energy from t
  8. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  9. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  10. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f

### [temporal] dataset_9.json — `What was Dave doing in the first weekend of October 2023?`

- expected conv indices: 21
- top-10 ranked conv indices: 17, 29, 5, 14, 7, 19, 3, 26, 27, 23
- top-K snippets:
  1. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  2. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  3. Hey Dave! Long time no chat! Lots has gone down since we last caught up.
  4. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  5. Hey Dave! Met with the creative team for my album yesterday. It was a long session, but awesome to see everything coming together. 
  6. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  7. Hey Dave! Long time no chat! Lots has gone down since we last caught up.
  8. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  9. Hey Calvin, good to catch up again! Had a tough time with my car project. Worked on the engine of the vintage Mustang, thought I'd fixed it,
  10. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 

### [temporal] dataset_9.json — `When did Calvin buy his second Ferrari?`

- expected conv indices: 22
- top-10 ranked conv indices: 1, 21, 24, 3, 16, 0, 20, 14, 25, 12
- top-K snippets:
  1. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  2. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  3. Hey Calvin! What’s up? Last Friday I went to the car show. I saw some awesome cars and got to mess with car mods! There were so many cool ma
  4. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  5. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  6. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  7. Hey Calvin! Been a while, what's up? I'm tied up with car stuff lately, yesterday I came back from San Francsico with some great insights an
  8. Hey Dave! Nice to meet you! How's it going since we talked?
  9. Hey Dave! Yesterday I met with some incredible artists in Boston and we talked about working together. It was such an inspiring and exciting
  10. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 

### [temporal] dataset_9.json — `Where was Calvin located in the last week of October 2023?`

- expected conv indices: 27
- top-10 ranked conv indices: 17, 15, 14, 19, 7, 23, 13, 12, 1, 5
- top-K snippets:
  1. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  2. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!
  3. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  4. Hey Calvin, good to catch up again! Had a tough time with my car project. Worked on the engine of the vintage Mustang, thought I'd fixed it,
  5. Hey Dave! Met with the creative team for my album yesterday. It was a long session, but awesome to see everything coming together. 
  6. Hey Dave! Been a few days since we talked, but lots happened. Started touring with Frank Ocean and it's been amazing - so much energy from t
  7. Hey Cal, how's it going? Something cool happened since last we talked - I got to go to a car workshop in San Francisco! So cool to dive into
  8. Hey Calvin, been ages! Guess what? I got picked for a car mod workshop. Gonna get better at it and learn something new! Look at the cars I'm
  9. Hey Dave! Met with the creative team for my album yesterday. It was a long session, but awesome to see everything coming together. 
  10. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!

### [abstention] dataset_0.json — `What setback did Caroline face recently?`

- expected conv indices: 16
- top-10 ranked conv indices: 17, 5, 9, 8, 0, 18, 3, 1, 11, 4
- top-K snippets:
  1. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  2. Hey Mel! Long time no talk. Lots has been going on since then!
  3. Hey Melanie! Just wanted to say hi!
  4. Hey Caroline, hope all's good! I had a quiet weekend after we went camping with my fam two weekends ago. It was great to unplug and hang wit
  5. Hey Mel! Good to see you! How have you been?
  6. Hey Mel! Good to see you! How have you been?
  7. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  8. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  9. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  10. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth

### [abstention] dataset_1.json — `What is Gina's attitude towards participating in the dance festival?`

- expected conv indices: 0
- top-10 ranked conv indices: 8, 4, 18, 16, 2, 17, 12, 7, 10, 14
- top-K snippets:
  1. Hey Gina! I'm turning my loves of dance into a business. I'm sunk tons of time into the studio lately, and look at my students - they're alr
  2. Hey Jon! Great hearing from you again. How have you been? BTW, I found a cool new fashion piece for my store. Can't wait to share with my cu
  3. Hey Gina! We haven't talked in a few days. Been rehearsing hard and working on business plans. It's been stressful, but dancing has kept me 
  4. Hey Gina! I'm turning my loves of dance into a business. I'm sunk tons of time into the studio lately, and look at my students - they're alr
  5. Hey Gina! I'm turning my loves of dance into a business. I'm sunk tons of time into the studio lately, and look at my students - they're alr
  6. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  7. Hey Gina, hope you're doing ok! Still following my passion for dance. It's been bumpy, but I'm determined to make it work. I'm still searchi
  8. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  9. Hey Gina, thanks for being there for me and believing in me. It means a lot.
  10. Hey Gina, I had to shut down my bank account. It was tough, but I needed to do it for my biz.

### [abstention] dataset_1.json — `What did Jon design for his store?`

- expected conv indices: 2
- top-10 ranked conv indices: 15, 1, 4, 3, 6, 13, 9, 0, 8, 5
- top-K snippets:
  1. Hey Jon, what's been up? Some pretty cool stuff happened since we talked. I have acquired some new unique pieces for my store.
  2. Hey Jon! Long time no see! Things have been hectic lately. I just launched an ad campaign for my clothing store in hopes of growing the busi
  3. Hey Jon! Great hearing from you again. How have you been? BTW, I found a cool new fashion piece for my store. Can't wait to share with my cu
  4. Hey Gina! What's up? How's the store going? I gotta tell you about this thing with my biz.
  5. Hey Gina, how's it going?
  6. Hey Jon! Great hearing from you again. How have you been? BTW, I found a cool new fashion piece for my store. Can't wait to share with my cu
  7. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to
  8. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to
  9. Hi Gina! I just wanted to fill you in on my business. Yesterday, I went to a fair to show off my studio, it was both stressful and great! I 
  10. Hey Jon! Good to see you. What's up? Anything new?

### [abstention] dataset_1.json — `What did Jon take a trip to Barcelona for?`

- expected conv indices: 14
- top-10 ranked conv indices: 1, 16, 11, 18, 17, 0, 7, 3, 15, 4
- top-K snippets:
  1. Hey Jon! Long time no see! Things have been hectic lately. I just launched an ad campaign for my clothing store in hopes of growing the busi
  2. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  3. Hey Jon! Long time no talk! A lot's happened - I just got accepted for a fashion internship!
  4. Hey Gina! We haven't talked in a few days. Been rehearsing hard and working on business plans. It's been stressful, but dancing has kept me 
  5. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  6. Hey Jon! Good to see you. What's up? Anything new?
  7. Hey Gina, I had to shut down my bank account. It was tough, but I needed to do it for my biz.
  8. Hey Jon! Good to see you. What's up? Anything new?
  9. Hey Gina! What's up? How's the store going? I gotta tell you about this thing with my biz.
  10. Hey Jon, what's been up? Some pretty cool stuff happened since we talked. I have acquired some new unique pieces for my store.

### [abstention] dataset_2.json — `What did Maria donate to a luxury store in December 2023?`

- expected conv indices: 1
- top-10 ranked conv indices: 15, 3, 26, 19, 28, 6, 4, 0, 20, 12
- top-K snippets:
  1. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  2. Hey John, great news - I'm now friends with one of my fellow volunteers! We both love helping others. How have you been since we last chatte
  3. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  4. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends
  5. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  6. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  7. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  8. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  9. Hey John! Long time no see! What's up?
  10. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!

### [abstention] dataset_2.json — `Who inspired John to start volunteering?`

- expected conv indices: 4
- top-10 ranked conv indices: 20, 5, 6, 27, 11, 25, 31, 26, 8, 14
- top-K snippets:
  1. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  2. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  3. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  4. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  5. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin
  6. Hey John, I'm doing ok - hope you are too. Some interesting stuff has been going on; last week I dropped off that stuff I baked at the homel
  7. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  8. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  9. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  10. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b

### [abstention] dataset_3.json — `What is Joanna's favorite movie trilogy?`

- expected conv indices: 8
- top-10 ranked conv indices: 11, 28, 2, 0, 27, 20, 24, 3, 16, 12
- top-K snippets:
  1. Hey Joanna! How've you been? Been a busy week since we talked.
  2. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  3. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  4. Hey Joanna! Long time no see! What's up? Anything fun going on?
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  7. Hey Joanna, what's been up since we last chatted? How's it going?
  8. Hey Joanna! Sorry I haven't been around. I made my friend some ice cream and they loved it!
  9. Hey Joanna, what's been up since we last chatted? How's it going?
  10. Hey Joanna, check this out! I won my fourth video game tournament on Friday! It was awesome competing and showing off my skills - and the vi

### [abstention] dataset_3.json — `What is Nate's third screenplay about?`

- expected conv indices: 11
- top-10 ranked conv indices: 28, 4, 2, 3, 15, 14, 24, 27, 13, 16
- top-K snippets:
  1. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  2. Hey Nate, it's been a minute! I wrapped up my second script, and the feels have been wild. Sometimes I'm so relieved, but other times I just
  3. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  4. Hey Joanna! Sorry I haven't been around. I made my friend some ice cream and they loved it!
  5. Hey Nate, long time no see! How have you been? I just got done submitting my recent screenplay to a film contest just to see how others migh
  6. Hey Joanna! Sorry I haven't been around. I made my friend some ice cream and they loved it!
  7. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  8. Hey Joanna, what's been up since we last chatted? How's it going?
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  10. Nate, after finishing my screenplay I got a rejection letter from a major company. It really bummed me out.

### [abstention] dataset_3.json — `What did Nate think of the caramel ice cream he made?`

- expected conv indices: 2
- top-10 ranked conv indices: 17, 3, 28, 21, 20, 9, 23, 18, 27, 15
- top-K snippets:
  1. Hey Nate, long time no talk! I've been busy with writing projects and really going all out with it. It's been the best thing ever - a mix of
  2. Hey Joanna! Sorry I haven't been around. I made my friend some ice cream and they loved it!
  3. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  4. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  5. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  6. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  7. Hey Nate, how's it going? I took your reccomendation and watched "The Lord of the Rings" Trilogy last night! It was awesome!
  8. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  9. Hey Joanna, what's been up? Haven't seen you since we last talked.
  10. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m

### [abstention] dataset_3.json — `Which activity helps Nate escape and numbs his mind?`

- expected conv indices: 8
- top-10 ranked conv indices: 6, 4, 0, 27, 14, 11, 25, 10, 7, 1
- top-K snippets:
  1. Hey Jo, guess what I did? Dyed my hair last week - come see!
  2. Hey Nate, it's been a minute! I wrapped up my second script, and the feels have been wild. Sometimes I'm so relieved, but other times I just
  3. Hey Joanna! Long time no see! What's up? Anything fun going on?
  4. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  5. Hey Joanna! Long time no see! What's up? Anything fun going on?
  6. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  7. Hey Joanna! How've you been? Been a busy week since we talked.
  8. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  9. Hey Nate, it's been a minute! I wrapped up my second script, and the feels have been wild. Sometimes I'm so relieved, but other times I just
  10. Hey Nate! Great to hear from you! Quite a week since we last talked - something awesome happened to me!

### [abstention] dataset_3.json — `What did Nate share with his writers group in August 2022?`

- expected conv indices: 18
- top-10 ranked conv indices: 8, 17, 27, 25, 4, 5, 1, 15, 26, 14
- top-K snippets:
  1. Hey Nate! Long time no talk! I wanted to tell ya I just joined a writers group. It's unbelievable--such inspirational people who really get 
  2. Hey Nate, long time no talk! I've been busy with writing projects and really going all out with it. It's been the best thing ever - a mix of
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Nate, long time no talk! I've been busy with writing projects and really going all out with it. It's been the best thing ever - a mix of
  5. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  6. Hey Nate, it's been a minute! I wrapped up my second script, and the feels have been wild. Sometimes I'm so relieved, but other times I just
  7. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  8. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  9. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  10. Hey Nate, long time no see! How have you been? I just got done submitting my recent screenplay to a film contest just to see how others migh

### [abstention] dataset_3.json — `What does Nate do while he writes?`

- expected conv indices: 23
- top-10 ranked conv indices: 0, 17, 1, 27, 3, 25, 5, 18, 4, 26
- top-K snippets:
  1. Hey Joanna! Long time no see! What's up? Anything fun going on?
  2. Hey Nate, long time no talk! I've been busy with writing projects and really going all out with it. It's been the best thing ever - a mix of
  3. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  4. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna! Sorry I haven't been around. I made my friend some ice cream and they loved it!
  8. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  9. Hey Nate, long time no talk! I've been busy with writing projects and really going all out with it. It's been the best thing ever - a mix of
  10. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.

### [abstention] dataset_4.json — `What cult did Tim join recently?`

- expected conv indices: 1
- top-10 ranked conv indices: 0, 22, 7, 5, 10, 28, 24, 13, 26, 20
- top-K snippets:
  1. Hey Tim, nice to meet you! What's up? Anything new happening?
  2. Hey Tim, great to see you! Any new success stories?
  3. Hey Tim! Long time no talk. Hope you're doing great. Crazy things have been going on in my life. Just the other day, I found a new gym to st
  4. Hey Tim, sorry I missed you. Been a crazy few days. Took a trip to a new place - it's been amazing. Love the energy there.
  5. Hey Tim, been a while! How ya been?
  6. Hey John! How's it going? Hope all is good.
  7. Hey John, been a while since we chatted. How's it going?
  8. Hey Tim! Long time no talk - a lot has been going on since then!
  9. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  10. Hey John! Haven't talked in a few days, wanted to let you know I joined a travel club! Always been interested in different cultures and coun

### [abstention] dataset_4.json — `What new activity has John started learning in August 2023?`

- expected conv indices: 7
- top-10 ranked conv indices: 25, 20, 23, 26, 16, 10, 27, 14, 0, 8
- top-K snippets:
  1. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  2. Hey John! Haven't talked in a few days, wanted to let you know I joined a travel club! Always been interested in different cultures and coun
  3. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  4. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  5. Hey Tim! Great to chat again. So much has happened!
  6. Hey Tim, been a while! How ya been?
  7. Hey John! Haven't talked in a few days, wanted to let you know I joined a travel club! Always been interested in different cultures and coun
  8. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  9. Hey John! Haven't talked to you in a bit but wanted to let you know I read this awesome book about castles in the UK. It was so interesting 
  10. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a

### [abstention] dataset_4.json — `What is Tim excited to see at Disneyland?`

- expected conv indices: 9
- top-10 ranked conv indices: 14, 27, 22, 21, 7, 0, 28, 8, 26, 5
- top-K snippets:
  1. Hey John! Haven't talked to you in a bit but wanted to let you know I read this awesome book about castles in the UK. It was so interesting 
  2. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  3. Hey Tim, great to see you! Any new success stories?
  4. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 
  5. Hey Tim! Long time no talk. Hope you're doing great. Crazy things have been going on in my life. Just the other day, I found a new gym to st
  6. Hey Tim, nice to meet you! What's up? Anything new happening?
  7. Hey John! How's it going? Hope all is good.
  8. Hey John, this week's been really busy for me. Assignments and exams are overwhelming. I'm not giving up though! I'm trying to find a way to
  9. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  10. Hey Tim, sorry I missed you. Been a crazy few days. Took a trip to a new place - it's been amazing. Love the energy there.

### [abstention] dataset_4.json — `What movie did Tim just finish watching on 8th December, 2023?`

- expected conv indices: 21
- top-10 ranked conv indices: 7, 25, 13, 28, 16, 27, 0, 10, 26, 11
- top-K snippets:
  1. Hey Tim! Long time no talk. Hope you're doing great. Crazy things have been going on in my life. Just the other day, I found a new gym to st
  2. Hey Tim! Long time no talk. Hope you're doing great. Crazy things have been going on in my life. Just the other day, I found a new gym to st
  3. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  4. Hey Tim! Long time no talk - a lot has been going on since then!
  5. Hey John! How's it going? Hope all is good.
  6. Hey Tim! Great to chat again. So much has happened!
  7. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  8. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  9. Hey Tim, nice to meet you! What's up? Anything new happening?
  10. Hey Tim! Long time no talk. Hope you're doing great. Crazy things have been going on in my life. Just the other day, I found a new gym to st

### [abstention] dataset_5.json — `What are some of the personalities of Andrew's four fur babies?`

- expected conv indices: 12
- top-10 ranked conv indices: 27, 14, 1, 20, 25, 5, 23, 19, 13, 15
- top-K snippets:
  1. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  2. Hey Andrew, since we last spoke I got another tattoo of my four dogs on my arm! They really mean a lot to me so I thought it'd be nice to ha
  3. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  4. Hey Andrew, I got a surprise for you! We adopted another puppy called Pixie. She's SO cute! Isn't she just the cutest?
  5. Hi Audrey! Been a while since I hear from you. How's it been?
  6. Hey Andrew, since we last spoke I got another tattoo of my four dogs on my arm! They really mean a lot to me so I thought it'd be nice to ha
  7. Hey Andrew, I wanted to let you know about something going on with my dogs. I noticed they weren't acting normally, so I made an appointment
  8. Hi Audrey! I had a great hike last weekend with some friends and my girlfriend at the spot we found recently. Nature was so peaceful – it wa
  9. Hey Andrew, hope you're doing ok. I recently had a good week - I went to a pet store last Monday to buy toys for my dogs and it was great se
  10. Hey wassup? Got some great news - the gf and I are hitting the beach next month with Toby!

### [abstention] dataset_6.json — `How did James relax in his free time on 9 July, 2022?`

- expected conv indices: 15
- top-10 ranked conv indices: 29, 27, 26, 0, 9, 18, 6, 14, 2, 24
- top-K snippets:
  1. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  2. Hey John, long time no talk! So much has happened!
  3. Hey James! How's it going? I had a blast last week when my programmer friends and I organized an online comp. It was awesome to see everyone
  4. Hey! Glad to finally talk to you. I want to ask you, what motivates you?
  5. Hey John! Been a while since we chatted. Sorry 'bout not getting back sooner. How's it going? Any new games you're into?
  6. Hey James, been a few days. The convo got me thinking about my passions and goals. Thanks for encouraging me to try new game genres.
  7. Hey James! How's it going?
  8. Hey John, since our last chat, something awesome happened. Last Friday, I started introducing Max, Daisy and the new pup Ned. It was hard at
  9. Hey James, long time no see! I had a big win in my game last week - finally advanced to the next level! It was a huge confidence booster and
  10. Hey James, been a few days since we chatted. Lots of stuff goin' on in my life!

### [abstention] dataset_6.json — `What new hobby did John become interested in on 9 July, 2022?`

- expected conv indices: 15
- top-10 ranked conv indices: 1, 24, 27, 7, 22, 29, 19, 8, 21, 3
- top-K snippets:
  1. Hey John, something awesome happened since we talked. I made a game avatar and joined a new platform. It's so fun exploring and chatting wit
  2. Hey James, been a few days since we chatted. Lots of stuff goin' on in my life!
  3. Hey John, something awesome happened since we talked. I made a game avatar and joined a new platform. It's so fun exploring and chatting wit
  4. Hey John, long time no talk! So much has happened!
  5. Hey John! What's up? Anything fun going on?
  6. Hey John, something awesome happened since we talked. I made a game avatar and joined a new platform. It's so fun exploring and chatting wit
  7. Hey John, it's been a few days since we talked. So much has gone on, both good and bad. Yesterday, when we were at the theater, Samantha lov
  8. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  9. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  10. Hey James! How've you been? Had an eventful week since our last chat.

### [abstention] dataset_7.json — `For how long has Jolene had Lucifer as a pet?`

- expected conv indices: 13
- top-10 ranked conv indices: 27, 1, 21, 15, 14, 8, 0, 12, 6, 3
- top-K snippets:
  1. Since speaking last, I reconnected with my mom's old friends. Their stories made me tear up and reminded me how lucky I am to have had her.
  2. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  3. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  4. Hey Jolene! Great news - I just started a project for a cleanup in our community and have been trying to raise funds for it. It's been amazi
  5. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  6. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  7. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  8. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  9. Hi Jolene! We haven't corresponded for a long time!
  10. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.

### [abstention] dataset_7.json — `What kind of event did Deborah present at recently?`

- expected conv indices: 20
- top-10 ranked conv indices: 28, 4, 12, 5, 22, 3, 13, 11, 15, 6
- top-K snippets:
  1. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  2. Hey Deborah! Been a few days since we last talked so I wanted to fill you in on something cool. Last Wednesday I did a mini retreat to asses
  3. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  4. Hey Deborah, totally buzzing! Had a great night out last night - dinner, and drinks with my friends. So glad I got to let my hair down. You?
  5. Hey Deborah, how's it going? Guess what? Yesterday my partner and I got back from an awesome trip to Rio de Janeiro- we checked out some coo
  6. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  7. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  8. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  9. Hey Deborah! Been a few days since we last talked so I wanted to fill you in on something cool. Last Wednesday I did a mini retreat to asses
  10. Hey Jolene! Great news - I just started a project for a cleanup in our community and have been trying to raise funds for it. It's been amazi

### [abstention] dataset_7.json — `Where did Jolene get married?`

- expected conv indices: 27
- top-10 ranked conv indices: 6, 3, 12, 1, 19, 0, 21, 13, 22, 8
- top-K snippets:
  1. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  2. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  3. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  4. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  5.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  6. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?
  7. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  8. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  9. Hey Deborah, how's it going? Guess what? Yesterday my partner and I got back from an awesome trip to Rio de Janeiro- we checked out some coo
  10. Hi Jolene! We haven't corresponded for a long time!

### [abstention] dataset_8.json — `What electronics issue has been frustrating Evan lately?`

- expected conv indices: 10
- top-10 ranked conv indices: 17, 0, 23, 20, 13, 15, 19, 12, 24, 8
- top-K snippets:
  1. Hey Sam, good to hear from you. I've hit a bit of a snag - my new Prius, the one I just bought, broke down. It's a bit of a stressor since I
  2. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  3. Hey Sam, hope you're doing good. Something funny happened last night.
  4. Hey Evan! Long time no see, how's it going?
  5. Hey Evan! I've been missing our chats. I had quite the health scare last weekend - ended up in the ER with a severe stomachache. Turns out, 
  6. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen
  7. Hey Sam, what's up? Long time no see, huh? Lots has happened.
  8. Hey Sam, how's it going? Been a while since we talked. Hope all is good.
  9. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  10. Hey Evan! Exciting news: I started a new diet and exercise routine last Monday and it's made a huge difference. I feel great! What about you

### [abstention] dataset_8.json — `What activity did Evan quit one year ago?`

- expected conv indices: 11
- top-10 ranked conv indices: 10, 8, 7, 21, 0, 20, 14, 15, 3, 19
- top-K snippets:
  1. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?
  2. Hey Evan! Exciting news: I started a new diet and exercise routine last Monday and it's made a huge difference. I feel great! What about you
  3. Hey Evan, some big news: I'm on a diet and living healthier! Been tough, but I'm determined.
  4. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!
  5. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  6. Hey Evan! Long time no see, how's it going?
  7. Morning, Evan. I've been trying to keep up with my new health routine, but it's tough. My family's really pushing for it, and I feel so pres
  8. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen
  9. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.
  10. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.

### [abstention] dataset_8.json — `What dance activity did Evan and his partner try in a recent weekend?`

- expected conv indices: 23
- top-10 ranked conv indices: 7, 0, 8, 21, 10, 3, 9, 20, 14, 24
- top-K snippets:
  1. Hey Evan, some big news: I'm on a diet and living healthier! Been tough, but I'm determined.
  2. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  3. Hey Evan! Exciting news: I started a new diet and exercise routine last Monday and it's made a huge difference. I feel great! What about you
  4. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!
  5. Hey Evan! Exciting news: I started a new diet and exercise routine last Monday and it's made a huge difference. I feel great! What about you
  6. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?
  7. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  8. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.
  9. Hey Sam! Long time no talk! Hope all is good. What have I been doing these past few weeks?
  10. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?

### [abstention] dataset_8.json — `What activity hindered Evan's stress and flexibility?`

- expected conv indices: 23
- top-10 ranked conv indices: 8, 10, 16, 3, 17, 24, 13, 14, 15, 12
- top-K snippets:
  1. Hey Evan! Exciting news: I started a new diet and exercise routine last Monday and it's made a huge difference. I feel great! What about you
  2. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?
  3. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?
  4. Hey Ev! Long time no chat. How's it going? Hope all is well.
  5. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.
  6. Hey Sam, good to hear from you. I've hit a bit of a snag - my new Prius, the one I just bought, broke down. It's a bit of a stressor since I
  7. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  8. Hey Evan! I've been missing our chats. I had quite the health scare last weekend - ended up in the ER with a severe stomachache. Turns out, 
  9. Morning, Evan. I've been trying to keep up with my new health routine, but it's tough. My family's really pushing for it, and I feel so pres
  10. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen

### [abstention] dataset_9.json — `What did Calvin open in May 2023?`

- expected conv indices: 5
- top-10 ranked conv indices: 17, 3, 14, 15, 29, 13, 27, 1, 19, 4
- top-K snippets:
  1. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  2. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  3. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  4. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  5. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!
  6. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  7. Hey Cal, how's it going? Something cool happened since last we talked - I got to go to a car workshop in San Francisco! So cool to dive into
  8. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  9. Hey Cal, how's it going? Something cool happened since last we talked - I got to go to a car workshop in San Francisco! So cool to dive into
  10. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f

### [abstention] dataset_9.json — `Which horror movie did Dave mention as one of his favorites?`

- expected conv indices: 18
- top-10 ranked conv indices: 1, 3, 14, 27, 23, 24, 17, 5, 15, 29
- top-K snippets:
  1. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  2. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  3. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  4. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  5. Hey Dave! Been a few days since we talked, but lots happened. Started touring with Frank Ocean and it's been amazing - so much energy from t
  6. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  7. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  8. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  9. Hey Dave! Long time no chat! Lots has gone down since we last caught up.
  10. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!

### [abstention] dataset_9.json — `What does Dave find satisfying about destroying old cars?`

- expected conv indices: 20
- top-10 ranked conv indices: 21, 3, 16, 11, 12, 22, 25, 18, 13, 19
- top-K snippets:
  1. Hey Calvin! What’s up? Last Friday I went to the car show. I saw some awesome cars and got to mess with car mods! There were so many cool ma
  2. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  3. Hey Calvin! Been a while, what's up? I'm tied up with car stuff lately, yesterday I came back from San Francsico with some great insights an
  4. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  5. Hey Dave, long time no see! I just took my Ferrari for a service and it was so stressful. I'm kinda attached to it. Can you relate? What kin
  6. Hey Calvin, been ages! Guess what? I got picked for a car mod workshop. Gonna get better at it and learn something new! Look at the cars I'm
  7. Hey Cal, miss ya! Crazy rollercoaster week. A competing car maintenance shop snagged a deal we were trying to secure for months and it made 
  8. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  9. Hey Calvin! What’s up? Last Friday I went to the car show. I saw some awesome cars and got to mess with car mods! There were so many cool ma
  10. Hey Calvin! Long time no talk! Got some cool news to share - last night was a blast! My band and I were jamming and the music just kept flow

### [abstention] dataset_9.json — `What did Calvin recently get that is a "masterpiece on canvas"?`

- expected conv indices: 22
- top-10 ranked conv indices: 24, 19, 27, 12, 28, 3, 15, 23, 1, 10
- top-K snippets:
  1. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  2. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  3. Hey Calvin, good to catch up again! Had a tough time with my car project. Worked on the engine of the vintage Mustang, thought I'd fixed it,
  4. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  5. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  6. Hey Calvin, been ages! Guess what? I got picked for a car mod workshop. Gonna get better at it and learn something new! Look at the cars I'm
  7. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  8. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  9. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!
  10. Hey Dave! Been a few days since we talked, but lots happened. Started touring with Frank Ocean and it's been amazing - so much energy from t

### [abstention] dataset_9.json — `What new item did Calvin buy recently?`

- expected conv indices: 29
- top-10 ranked conv indices: 1, 2, 3, 16, 24, 14, 17, 4, 27, 5
- top-K snippets:
  1. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  2. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  3. Hey Dave! Long time no see. I just went to an awesome music thingy in Tokyo - so cool!
  4. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  5. Hey Calvin! Been a while, what's up? I'm tied up with car stuff lately, yesterday I came back from San Francsico with some great insights an
  6. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  7. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  8. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  9. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  10. Hey Calvin! Long time no talk. How's it going? Crazy news - I'm teaming up with a local garage. Take a look at what we working on together!

