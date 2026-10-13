# Drop Luck Ledger

Keeps a ledger of your boss kills and drops, and shows how lucky or dry you are on every item, worked out from the real drop rates.

<p>
<img src="https://raw.githubusercontent.com/timbo1359/drop-luck-ledger/master/docs/overview.png" alt="The overview, with Biggest Spoons" width="242" align="top">
<img src="https://raw.githubusercontent.com/timbo1359/drop-luck-ledger/master/docs/boss-page.png" alt="A boss page with one item opened" width="242" align="top">
<img src="https://raw.githubusercontent.com/timbo1359/drop-luck-ledger/master/docs/bosses-to-show.png" alt="Settings, Bosses to show" width="242" align="top">
<br><sub>In game, each lettered square is the item's picture.</sub>
</p>

## Getting started

1. Make sure RuneLite's **Loot Tracker** plugin is switched on (it is by default). Drops are read from it.
2. Log in and open **Drop Luck Ledger** from the RuneLite sidebar.
3. Kill bosses. The kill count follows the game's "Your … kill count is" messages, and drops of the items you track are logged with the kill they came on.
4. To add what you did before installing the plugin, open your **Boss Kill Log** (in the Adventure Log) and the **Collection Log** pages of your bosses in game, then use **Settings → Add my game history…**. It shows exactly what it would add, and adds nothing until you say so.

## The overview

- **Biggest Spoons**: the items you have been luckiest with, luckiest first. Each line shows the boss, and the copies you got against the copies your kills would give on average. The first ten show; *Show all* lists the rest.
- **My bosses**: your kill count at each boss, how lucky you have been with its uniques, and your collection log progress.
- **Shared drops**: items that several bosses drop (Virtus, the dragon pickaxe…), with the luck of all those kills together.
- **All bosses** and the search box: find any boss, or find a boss by an item it drops.

Hover a line for the figures; click it to open the boss.

## A boss page

- Type or adjust your kill count, and **Log a drop** by hand.
- One card for each item you track. Click a card for the details: the chance per kill, copies received against expected, your dry streak, the chance over your next kills, and a chart.
- **Items I already own…** for copies you had before you started, **Also track** to add any item from the drop table, and your drop history (any drop can be edited or deleted).
- **Boss settings** for what changes the rates that the game does not tell the plugin, such as a slayer task, Combat Achievements, the team size or your share of the damage.
- **Drop table** lists every drop with its chance at your settings. **About** explains how the boss's drops work and how sure the data is.

## Reading the numbers

Each item has a percentage and a bar: **the chance, from all the kills you have made, that a player would have the next copy by now.** With none of an item, that is the chance of having had one: 85% means you are overdue, and the bar fills as you go dry. When the drop comes, the bar falls to the chance of one more copy, not to zero, because the kills you made beyond the drop rate still count towards the next one.

The word beside it (Very dry, Dry, On rate, Lucky, Very lucky…) compares your copies with players who made the same number of kills.

## Settings

Open **Settings** at the top right of the panel:

- **Luck window**: how many upcoming kills the "next kills" figures look at.
- **Wearing a ring of wealth (i)** and **Legends' Quest completed**: both change some drop tables.
- **Show item pictures**.
- **Bosses to show**: tick the bosses you want the overview to list. To see only the Desert Treasure II bosses, press *Untick all*, open *Desert Treasure II* and press *Tick these*. Kills and drops at the other bosses are still recorded; this only changes what is shown.
- **Add my game history…**, **Export…** and **Import…** for backups, and **Erase everything…**.

In RuneLite's own settings for the plugin (the cog next to it in the plugin list):

- **Chat message for logged drops**: a game message when a drop is added, such as *Logged Dragonbone necklace at Vorkath kill 1,234 (412 kills since the last one).*
- **Look for history on login**: offers to add your game history while your ledger is empty.

## Good to know

- Pets are picked up from the collection log and pet messages, as they don't show up as loot.
- Kills made while the plugin is off are counted from the next kill-count message, but what dropped on them can't be known: add any drop with *Log a drop*.
- The game doesn't record which kill an old drop came on, so copies added from your game history count towards your luck but show no dry streak until you edit the drop and add its kill. Items that drop in stacks are turned into an estimated number of drops.
- At bosses where only the top damage dealer gets the loot (the God Wars generals, the Corporeal Beast, the Kalphite Queen…), a kill where someone else got the loot is left out.
- For every kill it sees, the plugin reads what the game reveals about it: awakened or not, the brothers or moons defeated, the Doom delve, the Colosseum wave, the MVP. For older kills, it uses your values under *Boss settings*.
- Not tracked: the raids (Chambers of Xeric, Theatre of Blood, Tombs of Amascut), the Royal Titans and Wintertodt. Frozen tears, bolt racks and key halves are not shown.
- Nothing is recorded on Leagues or Deadman worlds, and PvP loot is ignored.
- Each account has its own ledger, saved in `.runelite/plugin-data/drop-luck-ledger/`. The plugin makes no network requests.

## Credits

Drop rates and item data come from the [Old School RuneScape Wiki](https://oldschool.runescape.wiki/), under [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/). Not affiliated with Jagex.
