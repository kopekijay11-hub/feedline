base: 810e9cc079094465e82e3bd725923da8d0965781 == ls-remote 810e9cc079094465e82e3bd725923da8d0965781

# Trading View Expansion Report

## Adds
ECB Press Releases, Bank of England News, Bank of Japan News, SNB News, ForexLive, MarketPulse, RBA Media Releases (low-frequency-official), Benzinga (probation), Statistics Canada Prices/CPI, Statistics Canada Labour, Statistics Canada Economic Accounts.

## Rejections
Seeking Alpha 7/7 stub; CFTC enforcement feed; StatCan International Trade; full StatCan Daily.

## Category composition
Business & Markets removed; Bloomberg Markets, CNBC Top News, Investing.com News, FXStreet News moved to Trading.

## Cadence class design
daily default 7 days; low-frequency-official 35 days. Health reads per source.

## Standing notes
1. published_at vs fetched_at distinction.
2. category and tags resolved from sourceId via config.
3. headline + summary both stored, unchanged.
