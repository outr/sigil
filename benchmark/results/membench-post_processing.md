# Sigil MemBench Benchmark Results

**Date:** 2026-04-25T04:17:56.045396792Z
**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)
**Agent:** FirstAgent
**Retrieval:** vanilla cosine
**Score:** 84/100 (84.0% R@5)

## Per-category accuracy

| Category | Correct | Total | Accuracy |
|---|---|---|---|
| post_processing | 84 | 100 | 84.0% |

## Failures (16)

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

