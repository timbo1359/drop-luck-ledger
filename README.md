# Drop Luck Ledger

A RuneLite plugin that keeps a ledger of your boss kills and drops and tells you how lucky or dry you are, using the real drop tables. It is the RuneLite version of the Drop Luck Ledger web page: same maths, same ledger, in a side panel.

> **Status: written and unit-tested, not yet run inside the game client.** The maths, the capture rules, the history reader and the panel are covered by 294 automated tests, but the tests run against stand-ins for RuneLite, so the first `./gradlew run` is the first real test. See [Not verified yet](#not-verified-yet).

## What it does

### Going forward

While the plugin is on and you are logged in to a normal (non-league, non-Deadman) world it:

- follows the game's own kill-count messages (`Your Zulrah kill count is: 1,501.`), so the ledger's kill count stays equal to the game's;
- records the drops RuneLite's **Loot Tracker plugin** reports for the 64 bosses in the plugin (the raids, the Royal Titans and Wintertodt are left out: see [What the plugin leaves out](#what-the-plugin-leaves-out)), each stamped with the kill number it came on. Only the items you track for that boss are written (the uniques and pets by default; you can change the list per boss). **Loot Tracker has to be switched on** (it is by default); with it off, kill counts still follow the game's messages but drops are not logged automatically;
- uses the collection log "New item added" message and the pet messages as a second source, because pets do not show up as loot;
- picks up, for every kill it sees, the settings the game reveals that change the drop table (see [Teams and rewards](#teams-and-rewards));
- warns you once if RuneLite's Loot Tracker plugin is switched off, because then no drops can be recorded;
- optionally prints a game message when a drop is added, such as `Logged Dragonbone necklace at Vorkath kill 1,234 (412 kills since the last one).`

Kills that happen while the plugin is off are not lost: the next kill-count message tells it how many were missed. It cannot know what dropped on them, so they count as kills with no drops; if something did drop, add it with *Log a drop* on the boss page.

PvP loot is ignored.

### The side panel

The Drop Luck Ledger button in RuneLite's toolbar opens the panel:

- **Overview**: **Biggest Spoons**, the items you have been luckiest with, luckiest first (each line says how many copies you got against how many your kills would give on average; the first ten show, the rest under *Show all*), then "My bosses" (kill count and how lucky you have been with its uniques), the items several bosses share, and a searchable list of the bosses. Everything is one compact line; hover a line for the figures and click it to open the boss. A "How to read the numbers" section explains them.
- **Boss page**: a kill count box (type it, or +10 / +100 / +1000) with *Log a drop* beside it, one compact card per tracked item (hover for the figures, click to open the details and chart), the drops you have logged (each can be edited or deleted), *Items I already own…* for copies you had before the plugin existed, the boss's own settings (awakened or not, team size, your share of the damage and so on), and a link to its wiki page.
- **Settings** (the link at the top right of the panel): the luck window (how many upcoming kills the "next kills" figures look at), *Wearing a ring of wealth (i)*, *Legends' Quest completed*, *Show item pictures*, **Bosses to show**, game history, backup and *Erase everything…*.

**Bosses to show** has a tick box for every boss, in a fold per category, with *Tick all* / *Untick all* and *Tick these* / *Untick these* for each category. The overview then lists only the ticked bosses: their items in Biggest Spoons, My bosses and Shared drops, and the boss list and search. To see only the Desert Treasure II bosses, press *Untick all*, open *Desert Treasure II* and press *Tick these*. It only changes what is shown: kills and drops at the other bosses are still recorded, and an item several bosses drop (Virtus) keeps the luck of all your kills at them, so it reads the same as on the boss pages. The choice is kept with each account's ledger, and a boss added to the plugin later starts ticked.

Everything the web page does for a ledger is here except the simulator, which RuneLite's plugin rules do not allow ("no content simulation").

### How luck is shown

Each item has one percentage and one bar: **the chance, from all the kills you have made and the drop rate, that a player would have had the next copy by now.** While you have none, that is the chance of getting one: if you are dry on an item and the statistics say you should have had it 85% of the time, the bar sits at 85%, and it climbs as the kills pile up. When a drop lands, the bar **falls to the chance of one more copy, not to 0%**, because the kills you made beyond the drop rate still count towards the next one. Get one on rate and the bar drops low; get one after a huge drought and it drops, but stays high, since you were owed more than that. The bar turns gold from 50% and ember from 90%. Next to it, the **word** (Dry, On rate, Lucky…) says how your count of copies compares with players who made the same kills, because the same number means different things at different rates: none of something you only expect 0.2 of is normal, while 6 of 10 expected is dry. Nothing is ever reset.

- An item that several bosses drop (Virtus pieces at the four Desert Treasure II bosses, the dragon pickaxe, the Callisto cub and so on) shows the **pooled** figure on every one of those bosses' pages, joined over all your kills there, so it reads the same everywhere. Each boss still has its own per-kill rate (each Desert Treasure II boss has a different unique roll), which the tooltip shows.
- Copies that came in from game history have no kill number. They count fully towards the figures, but nobody knows how long ago they dropped, so no dry streak is shown for the item (the tooltip says so) until you edit the drop and add its kill number.
- The dry streak (kills since your last copy and the odds that a player would have had another by now), the luck percentile ("luckier than 8% of players") and the chart are in the card's details; the old "longest dry streaks" list is gone.

### Teams and rewards

Many bosses do not pay every player the same. The plugin reads what the game reveals about each kill it sees and gives that kill its own setting, so the figures follow how you actually played:

| Boss | What the plugin reads for each kill |
| --- | --- |
| God Wars generals, Corporeal Beast, Dagannoth Kings, Kalphite Queen, Giant Mole, Sarachnis, Chaos Elemental, Scorpia, Thermonuclear smoke devil, Abyssal Sire, King Black Dragon | whether the loot was yours: only the top damage dealer gets it, so a kill with no loot (another player got it) is left out of the figures. This needs the Loot Tracker on; with it off nothing is decided. |
| Barrows / Moons of Peril | the brothers / moons you defeated before opening the chest |
| Nex, The Nightmare, Scurrius | whether you were the MVP (big bones or rat meat in your loot) |
| Araxxor, Maggot King | whether you destroyed the corpse / took the eggs (no loot) |
| Brutus, Bryophyta | members or free-to-play world |
| Desert Treasure II bosses | normal or awakened |
| Doom of Mokhaiotl | the delve you claimed at; each claimed pile is one run, rolling once per level |
| Fortis Colosseum | the wave you claimed at (from the game's "Wave N completed!" message); each claimed chest is one run, early claims included |
| Revenant maledictus | it has no kill count, so each loot is one kill; loot of only blighted supplies means another player dealt the most damage, and that kill is left out |
| Abyssal Sire | what an Unsired gives at the Font of Consumption is added to the Sire without counting a kill |
| Tempoross | reward-pool loot is added to your games without counting as games |
| Tempoross, Hespori | your Fishing or Farming level (the pet chance) |

For these settings, what you set under **Boss settings** is for the kills the plugin did not see (your older kills); the boss page says which kills that is. The kills it saw keep what the game said. For the Doom delve and the Fishing and Farming levels, the first value it reads also becomes the starting guess for your older kills, unless you set them yourself.

What the game does not reveal is yours to set, and the boss page tells you what is assumed: your share of the damage at Nex, The Nightmare, Callisto, Venenatis, Vet'ion and The Hueycoatl, and the team size at Nex and The Nightmare. A setting can apply to all your kills or to new kills only.

Other things the data now models: the God Wars bodyguards' drops (their godsword shards count for the general); duplicate protection (the moon sets, the halberd pieces, the bludgeon pieces and the Sunfire armour) is followed through your whole history, so an early piece does not make you look lucky and a piece you cannot get right now still has a chance over the next kills; and clue scrolls that drop as scroll boxes.

### Game history

**No source in the game tells you which kill an old drop came on.** The Boss Kill Log gives a kill count, the Collection Log says how many copies you own, and neither says when you got them. So the history import is deliberately limited to what the game does know:

- **Kill counts**, from three places, taking the largest: the counts RuneLite's Chat Commands plugin saved from your kill-count messages, the Loot Tracker's saved totals, and the Boss Kill Log when you open it.
- **Copies you own**, from the Loot Tracker's saved drops and from the Collection Log pages you open. They go into the ledger as "kill unknown". They count toward your luck figures, but they have no kill number, so they cannot place a dry streak, until you edit the drop and add one.
- **Stacks**: the game keeps running totals, so 75,000 dragon thrownaxes is not 75,000 drops. Items that drop several at a time are divided by the usual amount per drop from the drop table (the Alchemical Hydra gives 500-1000 at a time, so that is about 100 drops) and marked with `~` in the review. If an earlier version of the importer counted a stack as many drops, the review offers to take those copies back (only copies that came from an import and have no kill number; anything you logged yourself is left alone).

How to use it:

1. In game, open the Adventure Log's **Boss Kill Log** once, and open the **Collection Log** pages of the bosses you care about. The plugin only reads what the game has put on screen; it does not click, scroll or type anything. A note appears at the top of the panel each time it reads a page, with an *Add to my ledger…* button. An Adventure Log that belongs to another player is ignored.
2. In the panel, use *Add to my ledger…* or **Settings → Add my game history…**. It shows exactly what it would add (kill counts and items, per boss) and adds nothing until you press *Add to ledger*.
3. When you log in and your ledger has no bosses in it yet, the panel also offers this on its own (*Look at it* / *Not now*). Turn that off with *Look for history on login* in the plugin's settings.

What it can only get if you had it switched on at the time:

- **Loot Tracker → "Remember loot"** must have been on. It saves running totals per boss (kills and how many of each item). RuneLite deletes a boss's saved totals once you have not killed it for 365 days, so very old history may already be gone.
- **Chat Commands** must have been on when you saw the kill-count messages.

Running the import twice does not add things twice.

### What the plugin leaves out

- **Bosses**: the Chambers of Xeric, the Theatre of Blood, the Tombs of Amascut, the Royal Titans and Wintertodt are not in the plugin (the web page still has them). Their kill-count messages and loot are ignored, and they are not listed anywhere. A ledger that already has them (an older ledger, or a backup from the web page) keeps them: the plugin writes them back untouched when it saves and puts them in backups, so nothing is lost. *Erase everything…* removes them too.
- **Items**: frozen tears, bolt racks, the loop and tooth halves of the crystal key (Barrows) and the tooth half of the moon key are never tracked, listed, offered in *Log a drop* or counted as collection-log items; the Giantsoul amulet went with the Royal Titans. They stay in the drop tables, so the chances of the other items are still right. Drops of them already in a ledger stay in the file but are not shown.

The lists live in the web project's `src/plugin-exclude.txt`; see [Updating the boss data](#updating-the-boss-data).

## Run it

You need a JDK 11 or newer.

```
./gradlew run
```

That compiles the plugin and starts RuneLite's developer client with it loaded. To log in to the developer client with a Jagex account, follow [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

The plugin appears in the plugin list as **Drop Luck Ledger**. `./gradlew shadowJar` builds a single jar of the same thing.

### First-run checklist

Things only a real client can show:

1. The panel opens from the toolbar, says "Log in to see and record your ledger." while you are logged out, and shows your ledger after you log in.
2. A boss kill: the kill count in the panel goes up by one, once (not twice), and a drop you tracked appears with the right kill number and a game message.
3. A Doom delve, a Colosseum claim or a Barrows chest: the boss settings show the delve, wave or brothers the game reported, and the note above them says which older kills your own values are for.
4. A team kill at a God Wars general where someone else gets the loot: the boss page says one kill was left out; a Maggot King kill where you take the eggs: that kill is set to *Eggs*.
5. A pet or collection-log drop is logged once.
6. Open the Boss Kill Log, then a Collection Log page, then use *Add my game history…* and check the numbers against what the game shows.
7. Log out and in again, and switch to another account: each account has its own ledger.
8. Go to a Leagues or Deadman world: the panel says "Paused: this kind of world has its own save…" and nothing is recorded.
9. Quit RuneLite and start it again: everything is still there.
10. The overview shows **Biggest Spoons** (no "Driest so far"); with more than ten spoons, *Show all* lists the rest.
11. **Settings → Bosses to show**: untick everything, tick the Desert Treasure II group, go back: the overview lists only those bosses and their items, and kills elsewhere are still counted.
12. The raids, the Royal Titans and Wintertodt are not in the boss list, and Barrows, Amoxliatl, The Hueycoatl and Doom of Mokhaiotl show no bolt racks, frozen tears or key halves.

## Where the data is

Files live in RuneLite's plugin data folder, `~/.runelite/plugin-data/drop-luck-ledger/`:

| File | What it is |
| --- | --- |
| `ledger-<account>.json` | the ledger for one account (the number is RuneLite's account hash) |
| `ledger-<account>.json.bak` | a copy of the ledger as it was when the session started |
| `ledger-<account>.json.unreadable-<time>` | a ledger that could not be read; kept, never overwritten or deleted, and a fresh ledger is started |
| `item-ids.json` | which item id goes with which item name, for the pictures |

Backups made with *Settings → Backup → Export…* have the same format as the web page's, so a ledger can move between the two. The plugin makes **no network requests**: item pictures come from the game's own item sprites, and the only link out is the wiki button, which opens your browser.

## Settings in RuneLite's plugin panel

- **Chat message for logged drops** (on by default)
- **Look for history on login** (on by default)

The config group is `dropluckledger`.

## Not verified yet

These need a real client or the live game to confirm; the plugin degrades quietly where it cannot read something (it logs less, it does not guess):

- The whole RuneLite wiring, the first time it runs.
- The exact wording of some kill-count messages: Desert Treasure II awakened variants, Colosseum, Doom of Mokhaiotl. Unrecognised wording means that kill is picked up from the Loot Tracker event only.
- What is read per kill: the Barrows brothers and Moons defeated (varbits at the chest), the MVP items, the Colosseum wave, and kills without loot at the top-damage bosses and the Maggot King (decided 50 ticks after the kill count, or at the next kill).
- Pet messages are matched on a best-effort basis.
- Item pictures come from RuneLite's item manager and have only been tested with stand-ins.
- Whether the Boss Kill Log and Collection Log pages read the same on every client layout.

## Putting it on the Plugin Hub

This is a normal RuneLite external plugin. To submit it: put the project in its own git repository, then open a pull request to [runelite/plugin-hub](https://github.com/runelite/plugin-hub) adding `plugins/drop-luck-ledger` with the repository URL and the commit to build. Before you do:

- change `author` in `runelite-plugin.properties` and the name in `LICENSE` to yours;
- add a 48×72 `icon.png` at the repository root if you want a card image;
- put `Generated-by: Claude Code` at the end of the pull request description (the guidelines in `AGENTS.md` ask for it on code Claude wrote);
- read the data note below.

The rules in `AGENTS.md` were checked against the code: no reflection, no processes, no Java serialization, no network requests, all file access through `Filepath`, and the game is only reached through the `Game` interface and its one adapter, `RuneLiteGame`.

## Data and licence

The code is BSD 2-Clause (`LICENSE`).

The drop rates, item lists and boss settings in `src/main/resources/com/dropluckledger/data.json` come from the [Old School RuneScape Wiki](https://oldschool.runescape.wiki/), whose content is available under [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/): credit the Wiki, keep it non-commercial, share alike. That file keeps those terms, whatever the code's licence. It is a snapshot taken when the plugin was made. It is not updated automatically and this project has no updater, so when Jagex changes a drop rate the file has to be regenerated by hand.

### Updating the boss data

`data.json` is built from the web project's boss files (`src/bosses/*.ds`):

```
python3 compile.py             # all bosses, for the web page: build/data.json
python3 tools/plugin_data.py   # minus what src/plugin-exclude.txt leaves out: build/plugin-data.json
cp build/plugin-data.json <this project>/src/main/resources/com/dropluckledger/data.json
```

`src/plugin-exclude.txt` has one `boss: <id>` or `item: <name>` per line. A boss listed there is not in the plugin at all; an item stays in the drop tables but is hidden. To bring something back, delete its line and run the two commands again.

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

RuneLite ships older libraries than the ones you might expect (Gson 2.8.5, for one: no `JsonParser.parseString`), so the code and the tests are compiled against those.

Tests are plain JUnit 4 in `src/test/java`. Everything except `RuneLiteGame` and the plugin class is tested against `FakeGame`, so no test needs a game client. `DropLuckLedgerPluginTest` is the launcher that `./gradlew run` uses (it has no tests in it, like the template's).
