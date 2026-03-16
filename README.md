# DonutAH — Auction House Market Tracker

**DonutAH** is a Fabric client mod for **DonutSMP** that automatically collects Auction House price data and shows you live market stats — right in your item tooltips and in a web dashboard.

---

## How It Works

Every time you open `/ah` on page 1 (sorted by lowest price), DonutAH silently reads the listings and contributes them to a shared market database. No extra steps. No commands needed. Just play normally and the data builds up automatically.

The more players use it, the more accurate the data becomes.

---

## Features

### In-game

- **Auto-scanning** — open `/ah` on page 1 and it scrapes automatically
- **Smart deduplication** — only submits new or changed listings, so no spam
- **Per-unit pricing** — handles stacked items correctly (64x cobblestone → price per 1)
- **Enchantment-aware** — enchanted items are priced by their exact enchant combo, not blended with other variants
- **Hover tooltips** — see the avg price on any item you hover (enchant-specific)
- **Shulker box valuation** — hovering a shulker box shows the total market value of everything inside it
- **Custom item labels** — items can have a short note attached that shows up in the tooltip (e.g. "In my shop", "Buying", "Overpriced", "Good deal")
- **Shop prices** — items sold in `/shop` at fixed prices show **Shop: $X** in the tooltip instead of a market price
- **Auto price sync** — price data and tooltips sync automatically every 3 minutes after joining
- **Server failover** — seamlessly switches to the backup server if the primary is unreachable
- **`/donutah <item>` command** — get detailed stats in chat (avg, min/max, 7-day avg, trend, volume; per-enchant breakdown)
- **`/donutah top`** — top flip opportunities sorted by price spread
- **`/donutah settings`** — customize tooltips, price display, stack value, shulker totals, and more
- **`/donutah reload`** — clears caches and re-syncs everything from the server

### Dashboard

Live web dashboard at **[donutah.efm.lol](https://donutah.efm.lol)** with:
- Full item table with prices, scan counts, and trend badges
- Advanced item view: price-by-hour chart, price-by-day-of-week chart, 30-day history, top sellers
- Admin panel: manual price entry, item deletion, blacklist management, and more

---

## Requirements

- Minecraft **1.21.11**
- [Fabric Loader](https://fabricmc.net/use/installer/)
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Cloth Config](https://modrinth.com/mod/cloth-config) (required for settings screen)
- Must be playing on **donutsmp.net**

---

## Building from Source

```bash
./gradlew.bat clean build
```

The JAR will be in `build/libs/`.

> **Note:** This is the redacted open-source release. The `API_KEY` and server IPs in `ApiClient.java` are replaced with placeholders — you'll need to set up your own backend to run a functional build.

---

## Changelog

See [releases/](https://github.com/BKHornYT/donutah/releases) for version history.

---

*Data is submitted anonymously. No personal information is collected.*
