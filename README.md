# Drop Luck Ledger

A RuneLite plugin that keeps a ledger of your boss kills and drops and tells you how lucky or dry you are, using the real drop tables. It is the RuneLite version of the Drop Luck Ledger web page: same maths, same ledger, in a side panel.

> \*\*Status: written and unit-tested, not yet run inside the game client.\*\* The maths, the capture rules, the history reader and the panel are covered by 232 automated tests, but the tests run against stand-ins for RuneLite, so the first `./gradlew run` is the first real test. See \[Not verified yet](#not-verified-yet).

## What it does

### Going forward

While the plugin is on and you are logged in to a normal (non-league, non-Deadman) world it:

* follows the game's own kill-count messages (`Your Zulrah kill count is: 1,501.`), so the ledger's kill count stays equal to the game's;
* records the drops RuneLite's **Loot Tracker plugin** reports for the 70 bosses in the plugin, each stamped with the kill number it came on. Only the items you track for that boss are written (the uniques and pets by default; you can change the list per boss). **Loot Tracker has to be switched on** (it is by default); with it off, kill counts still follow the game's messages but drops are not logged automatically;
* uses the collection log "New item added" message and the pet messages as a second source, because pets do not show up as loot;
* picks up the settings the game shows with a reward that change the drop table: Tombs of Amascut raid level, Doom of Mokhaiotl delve, and your Fishing level (Tempoross), Farming level (Hespori) and 200m Firemaking (Wintertodt);
* optionally prints a game message when a drop is added, such as `Logged Dragonbone necklace at Vorkath kill 1,234 (412 kills since the last one).`

Kills that happen while the plugin is off are not lost: the next kill-count message tells it how many were missed. It cannot know what dropped on them, so they count as kills with no drops; if something did drop, add it with *Log a drop* on the boss page.

PvP loot is ignored.

### The side panel

The Drop Luck Ledger button in RuneLite's toolbar opens the panel:

* **Overview**: the items you are driest and luckiest with, then "My bosses" (kill count and how lucky you have been with its uniques), the items several bosses share, and a searchable list of all 70 bosses. Everything is one compact line; hover a line for the figures and click it to open the boss. A "How to read the numbers" section explains them.
* **Boss page**: a kill count box (type it, or +10 / +100 / +1000) with *Log a drop* beside it, one compact card per tracked item (hover for the figures, click to open the details and chart), the drops you have logged (each can be edited or deleted), *Items I already own…* for copies you had before the plugin existed, the boss's own settings (game mode, raid level, team size and so on), and a link to its wiki page.
* **Settings**: the luck window (how many upcoming kills the "next kills" figures look at), *Wearing a ring of wealth (i)*, *Legends' Quest completed*, *Show item pictures*, game history, backup and *Erase everything…*.

Everything the web page does for a ledger is here except the simulator, which RuneLite's plugin rules do not allow ("no content simulation").

### How luck is shown

Each item has one percentage and one bar: **the chance, from all the kills you have made and the drop rate, that a player would have had the next copy by now.** While you have none, that is the chance of getting one: if you are dry on an item and the statistics say you should have had it 85% of the time, the bar sits at 85%, and it climbs as the kills pile up. When a drop lands, the bar **falls to the chance of one more copy, not to 0%**, because the kills you made beyond the drop rate still count towards the next one. Get one on rate and the bar drops low; get one after a huge drought and it drops, but stays high, since you were owed more than that. The bar turns gold from 50% and ember from 90%. Next to it, the **word** (Dry, On rate, Lucky…) says how your count of copies compares with players who made the same kills, because the same number means different things at different rates: none of something you only expect 0.2 of is normal, while 6 of 10 expected is dry. Nothing is ever reset.

* An item that several bosses drop (Virtus pieces at the four Desert Treasure II bosses, the dragon pickaxe, the Callisto cub and so on) shows the **pooled** figure on every one of those bosses' pages, joined over all your kills there, so it reads the same everywhere. Each boss still has its own per-kill rate (each Desert Treasure II boss has a different unique roll), which the tooltip shows.
* Copies that came in from game history have no kill number. They count fully towards the figures, but nobody knows how long ago they dropped, so no dry streak is shown for the item (the tooltip says so) until you edit the drop and add its kill number.
* The dry streak (kills since your last copy and the odds that a player would have had another by now), the luck percentile ("luckier than 8% of players") and the chart are in the card's details; the old "longest dry streaks" list is gone.

### Game history

**No source in the game tells you which kill an old drop came on.** The Boss Kill Log gives a kill count, the Collection Log says how many copies you own, and neither says when you got them. So the history import is deliberately limited to what the game does know:

* **Kill counts**, from three places, taking the largest: the counts RuneLite's Chat Commands plugin saved from your kill-count messages, the Loot Tracker's saved totals, and the Boss Kill Log when you open it.
* **Copies you own**, from the Loot Tracker's saved drops and from the Collection Log pages you open. They go into the ledger as "kill unknown". They count toward your luck figures, but they have no kill number, so they cannot place a dry streak, until you edit the drop and add one.
* **Stacks**: the game keeps running totals, so 3,750 bolt racks is not 3,750 drops. Items that drop several at a time are divided by the usual amount per drop from the drop table (a Barrows chest gives 35-40 bolt racks, so that is about 100 drops) and marked with `\~` in the review. If an earlier version of the importer counted a stack as many drops, the review offers to take those copies back (only copies that came from an import and have no kill number; anything you logged yourself is left alone).

How to use it:

1. In game, open the Adventure Log's **Boss Kill Log** once, and open the **Collection Log** pages of the bosses you care about. The plugin only reads what the game has put on screen; it does not click, scroll or type anything. A note appears at the top of the panel each time it reads a page, with an *Add to my ledger…* button. An Adventure Log that belongs to another player is ignored.
2. In the panel, use *Add to my ledger…* or **Settings → Add my game history…**. It shows exactly what it would add (kill counts and items, per boss) and adds nothing until you press *Add to ledger*.
3. When you log in and your ledger has no bosses in it yet, the panel also offers this on its own (*Look at it* / *Not now*). Turn that off with *Look for history on login* in the plugin's settings.

What it can only get if you had it switched on at the time:

* **Loot Tracker → "Remember loot"** must have been on. It saves running totals per boss (kills and how many of each item). RuneLite deletes a boss's saved totals once you have not killed it for 365 days, so very old history may already be gone.
* **Chat Commands** must have been on when you saw the kill-count messages.

Running the import twice does not add things twice.

## Run it

You need a JDK 11 or newer.

```
./gradlew run
```

That compiles the plugin and starts RuneLite's developer client with it loaded. To log in to the developer client with a Jagex account, follow [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

The plugin appears in the plugin list as **Drop Luck Ledger**. `./gradlew shadowJar` builds a single jar of the same thing.

## Where the data is

Files live in RuneLite's plugin data folder, `\~/.runelite/plugin-data/drop-luck-ledger/`:

|File|What it is|
|-|-|
|`ledger-<account>.json`|the ledger for one account (the number is RuneLite's account hash)|
|`ledger-<account>.json.bak`|a copy of the ledger as it was when the session started|
|`ledger-<account>.json.unreadable-<time>`|a ledger that could not be read; kept, never overwritten or deleted, and a fresh ledger is started|
|`item-ids.json`|which item id goes with which item name, for the pictures|

Backups made with *Settings → Backup → Export…* have the same format as the web page's, so a ledger can move between the two. The plugin makes **no network requests**: item pictures come from the game's own item sprites, and the only link out is the wiki button, which opens your browser.

## Settings in RuneLite's plugin panel

* **Chat message for logged drops** (on by default)
* **Look for history on login** (on by default)

The config group is `dropluckledger`.

## Putting it on the Plugin Hub

This is a normal RuneLite external plugin. To submit it: put the project in its own git repository, then open a pull request to [runelite/plugin-hub](https://github.com/runelite/plugin-hub) adding `plugins/drop-luck-ledger` with the repository URL and the commit to build. Before you do:

* change `author` in `runelite-plugin.properties` and the name in `LICENSE` to yours;
* add a 48×72 `icon.png` at the repository root if you want a card image;
* put `Generated-by: Claude Code` at the end of the pull request description (the guidelines in `AGENTS.md` ask for it on code Claude wrote);
* read the data note below.

The rules in `AGENTS.md` were checked against the code: no reflection, no processes, no Java serialization, no network requests, all file access through `Filepath`, and the game is only reached through the `Game` interface and its one adapter, `RuneLiteGame`.

## Data and licence

The code is BSD 2-Clause (`LICENSE`).

The drop rates, item lists and boss settings in `src/main/resources/com/dropluckledger/data.json` come from the [Old School RuneScape Wiki](https://oldschool.runescape.wiki/), whose content is available under [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/): credit the Wiki, keep it non-commercial, share alike. That file keeps those terms, whatever the code's licence. It is a snapshot taken when the plugin was made. It is not updated automatically and this project has no updater, so when Jagex changes a drop rate the file has to be regenerated by hand.

Old School RuneScape is a trademark of Jagex Ltd. This plugin is not affiliated with or endorsed by Jagex, RuneLite or the Wiki.

## Development

```
src/main/java/com/dropluckledger/
  engine/    drop-rate maths (compared with the web page's maths in about 80,000 checks)
  ledger/    the ledger, its file format, saving
  capture/   kill-count and drop messages -> ledger entries
  history/   merging game history into a ledger
  plugin/    the only package that knows about the game: Game (interface), RuneLiteGame (adapter), file store, readers
  ui/        the side panel (Swing)
  Tracker.java                 everything the plugin does once RuneLite reports an event
  DropLuckLedgerPlugin.java    RuneLite's entry point; hands events to Tracker
```

