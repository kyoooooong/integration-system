Read `AGENTS.md` first.
Follow the architecture, dependency rules, implementation rules, and error rules defined there.

Do not introduce Redis, a queue, or a rate/inventory cache. Those were evaluated and rejected —
reject them again by naming the failure mode they create, not by calling them complex.
The reasons are in `README.md` under cache, duplicate merging, and omitted decisions.

Do not claim anything in the documentation that is not in the code.
`README.md` and `k6/search-load.js` are the measurement reference; when a document disagrees
with a measurement, fix the document.

Never write MEASURED for a number that has not been measured.
