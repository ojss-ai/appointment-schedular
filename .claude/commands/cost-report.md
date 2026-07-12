# /cost-report

Print a token usage and cost summary for the current Claude session.

## Steps

1. **Read cost log**
   - Open `docs/COST-LOG.md` and filter rows matching today's date.

2. **Aggregate by agent**
   - Sum input tokens and output tokens per agent name.
   - Compute estimated cost: (input_tokens / 1_000_000 * 3.00) + (output_tokens / 1_000_000 * 15.00).

3. **Print summary table**

```
Agent              | Input Tokens | Output Tokens | Est. Cost (USD)
-------------------|-------------|---------------|----------------
orchestrator       | 12,450      | 3,200         | $0.085
coder              | 48,200      | 18,400        | $0.421
testgen            | 22,100      | 9,800         | $0.214
security           | 8,900       | 2,100         | $0.058
adr-docs           | 5,400       | 1,800         | $0.043
migrations         | 6,200       | 2,400         | $0.055
observability      | 3,100       | 900           | $0.023
-------------------|-------------|---------------|----------------
TOTAL              | 106,350     | 38,600        | $0.899
```

4. **Append to log**
   - If new activity occurred since last log entry, append updated rows to `docs/COST-LOG.md`.
